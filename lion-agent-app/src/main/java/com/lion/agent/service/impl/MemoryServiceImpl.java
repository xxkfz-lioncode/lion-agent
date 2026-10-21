package com.lion.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lion.agent.common.enums.MemoryType;
import com.lion.agent.common.enums.VectorType;
import com.lion.agent.common.utils.LazyMilvusVectorStoreUtils;
import com.lion.agent.pojo.entity.AiMemory;
import com.lion.agent.mapper.AiMemoryMapper;
import com.lion.agent.service.MemoryExtractor;
import com.lion.agent.service.MemoryService;
import io.milvus.client.MilvusServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 用户长期记忆服务实现（Phase 1）
 *
 * <p>写入：{@link #extractAndStoreAsync} 在独立线程池 {@code memoryExecutor} 中异步执行——
 * LLM 抽取 → 与已有画像去重整合 → 整体覆盖写回 MySQL ai_memory，并重建 Milvus 向量副本。
 * 每个用户最多保留一条画像（profile），历史遗留的多条记录随首次写入收敛清理。</p>
 *
 * <p>读取：{@link #search} 按 userId 过滤（多租户隔离），返回相似度达标（默认 0.55）的 Top-K 记忆，
 * 供 {@code LongTermMemoryAdvisor} 注入 prompt。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryServiceImpl implements MemoryService {

    /** 画像内容长度上限（ai_memory.content 为 VARCHAR，超限会被截断报错） */
    private static final int MAX_PROFILE_LENGTH = 900;

    /** importance 兜底值 */
    private static final int DEFAULT_IMPORTANCE = 3;

    /** Milvus doc id 长度上限（<= 36） */
    private static final int MAX_DOC_ID_LENGTH = 36;

    private final AiMemoryMapper memoryMapper;
    private final MemoryExtractor memoryExtractor;
    private final MilvusServiceClient milvusClient;
    private final EmbeddingModel embeddingModel;

    @Value("${lion.memory.enabled:true}")
    private boolean enabled;

    @Value("${lion.memory.extract-enabled:true}")
    private boolean extractEnabled;

    @Value("${lion.memory.collection-name:lion_agent_memory}")
    private String collectionName;

    @Value("${lion.memory.embedding-dimension:1024}")
    private int embeddingDimension;

    @Value("${lion.memory.search-threshold:0.70}")
    private double searchThreshold;

    @Value("${lion.memory.search-top-k:5}")
    private int searchTopK;

    /** 懒加载的 Milvus 向量存储持有器（首次使用时初始化建库；初始化失败即抛，由上层兜底） */
    private volatile LazyMilvusVectorStoreUtils memoryStore;

    /**
     * 懒加载获取 Milvus 向量存储（建表逻辑统一收敛于 {@link LazyMilvusVectorStoreUtils}，
     * 此处仅在首次访问时组装参数，@Value 注入完成后才可用）
     */
    private MilvusVectorStore store() {
        LazyMilvusVectorStoreUtils holder = memoryStore;
        if (holder == null) {
            synchronized (this) {
                holder = memoryStore;
                if (holder == null) {
                    holder = new LazyMilvusVectorStoreUtils(milvusClient, embeddingModel,
                            collectionName, embeddingDimension, "长期记忆");
                    memoryStore = holder;
                }
            }
        }
        return holder.get();
    }

    // ==================== 写入链路 ====================

    /**
     * 异步抽取并落库（@Async 走 memoryExecutor 线程池，不阻塞主调用链路；
     * 抽取/落库异常仅告警，绝不影响主链路）
     */
    @Async("memoryExecutor")
    @Override
    public void extractAndStoreAsync(Long userId, Long conversationId, String userContent, String assistantContent) {
        if (!enabled || !extractEnabled || userId == null || !StringUtils.hasText(userContent)) {
            return;
        }
        log.info("[Memory] 开始异步抽取 userId={} conversationId={}", userId, conversationId);
        List<MemoryItem> items = memoryExtractor.extract(userContent, assistantContent);
        if (items.isEmpty()) {
            return;
        }
        try {
            storeProfile(userId, conversationId, items);
            log.info("[Memory] 用户画像落库完成 userId={} conversationId={} 本轮抽取 {} 条",
                    userId, conversationId, items.size());
        } catch (Exception e) {
            log.warn("[Memory] 用户画像落库失败 userId={} error={}", userId, e.getMessage());
        }
    }

    /**
     * 画像落库主流程（每个用户最多保留一条 PROFILE）：
     * 加载已有画像 → 与本轮抽取条目去重整合 → 整体覆盖写回 → 重建向量副本。
     */
    private void storeProfile(Long userId, Long conversationId, List<MemoryItem> items) {
        List<AiMemory> profiles = queryProfiles(userId);
        AiMemory base = profiles.isEmpty() ? null : profiles.getFirst();
        String existingContent = profiles.stream().map(AiMemory::getContent).reduce("", this::mergeContents);

        MemoryItem profile = consolidate(base, existingContent, items);
        AiMemory saved = base == null ? insertProfile(userId, conversationId, profile) : updateProfile(base, profile);

        // 清理历史遗留的多余画像行及其向量
        List<Long> redundantIds = profiles.size() > 1
                ? profiles.subList(1, profiles.size()).stream().map(AiMemory::getId).toList()
                : List.of();
        for (Long id : redundantIds) {
            deleteVectorByMemoryId(userId, id);
        }
        if (!redundantIds.isEmpty()) {
            memoryMapper.deleteByIds(redundantIds);
            log.info("[Memory] 清理多余画像 userId={} 条数={}", userId, redundantIds.size());
        }

        // 内容已变化，向量副本整体重建
        deleteVectorByMemoryId(userId, saved.getId());
        addDocument(userId, conversationId, saved.getId(), profile);
        log.info("[Memory] 画像已保存 userId={} memoryId={} 内容长度={} 重要性={}",
                userId, saved.getId(), profile.content().length(), profile.importance());
    }

    /** 查询用户已有画像（每个用户只应有一条，多条时为历史遗留数据） */
    private List<AiMemory> queryProfiles(Long userId) {
        return memoryMapper.selectList(new LambdaQueryWrapper<AiMemory>()
                .eq(AiMemory::getUserId, userId)
                .eq(AiMemory::getMemoryType, MemoryType.PROFILE.getValue())
                .orderByAsc(AiMemory::getId));
    }

    /**
     * 去重整合：优先交给 LLM 做语义合并（去重、矛盾以最新陈述为准，结果已包含需保留的旧记忆）；
     * 整合失败或返回空时，回退为字符串级去重拼接。
     */
    private MemoryItem consolidate(AiMemory base, String existingContent, List<MemoryItem> items) {
        List<MemoryItem> consolidated = memoryExtractor.merge(existingContent, items);
        if (!consolidated.isEmpty()) {
            return toProfile(consolidated);
        }
        log.warn("[Memory] 整合结果为空，回退字符串合并 userId={}", base == null ? null : base.getUserId());
        MemoryItem incoming = toProfile(items);
        return new MemoryItem(mergeContents(existingContent, incoming.content()),
                Math.max(incoming.importance(), importanceOf(base)));
    }

    private AiMemory insertProfile(Long userId, Long conversationId, MemoryItem profile) {
        AiMemory memory = new AiMemory();
        memory.setUserId(userId);
        memory.setMemoryType(MemoryType.PROFILE.getValue());
        memory.setContent(profile.content());
        memory.setImportance(profile.importance());
        memory.setSourceConversationId(conversationId);
        memory.setCreatedAt(LocalDateTime.now());
        memory.setUpdatedAt(LocalDateTime.now());
        memoryMapper.insert(memory);
        return memory;
    }

    private AiMemory updateProfile(AiMemory base, MemoryItem profile) {
        base.setContent(profile.content());
        base.setImportance(profile.importance());
        base.setUpdatedAt(LocalDateTime.now());
        memoryMapper.updateById(base);
        return base;
    }

    private int importanceOf(AiMemory profile) {
        return profile == null || profile.getImportance() == null ? DEFAULT_IMPORTANCE : profile.getImportance();
    }

    /**
     * 按 memoryId 删除 Milvus 中的向量（含全部旧 doc，防止多轮重复）
     */
    private void deleteVectorByMemoryId(Long userId, Long memoryId) {
        String delFilter = "type == '" + VectorType.LONG_TERM_MEMORY.getValue() + "' && userId == '" + userId
                + "' && memoryId == '" + memoryId + "'";
        try {
            store().delete(delFilter);
        } catch (Exception e) {
            log.warn("[Memory] 删除旧向量失败（忽略，继续重建）memoryId={} error={}", memoryId, e.getMessage());
        }
    }

    /**
     * 把多条记忆收敛为一条画像，避免数据库/向量库碎片化：去重后以「；」拼接，重要性取最高。
     */
    private MemoryItem toProfile(List<MemoryItem> items) {
        String content = items.stream()
                .map(MemoryItem::content)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .collect(Collectors.joining("；"));
        int importance = items.stream().mapToInt(MemoryItem::importance).max().orElse(DEFAULT_IMPORTANCE);
        return new MemoryItem(clamp(content, MAX_PROFILE_LENGTH), Math.max(importance, DEFAULT_IMPORTANCE));
    }

    /**
     * 两段画像内容去重拼接：按「；」拆分后去重再拼回（保留顺序），超限截断。
     */
    private String mergeContents(String existingContent, String newContent) {
        List<String> segments = new ArrayList<>();
        for (String source : Arrays.asList(existingContent, newContent)) {
            if (!StringUtils.hasText(source)) {
                continue;
            }
            for (String segment : source.split("[；;]")) {
                String text = segment.trim();
                if (StringUtils.hasText(text) && !segments.contains(text)) {
                    segments.add(text);
                }
            }
        }
        return clamp(String.join("；", segments), MAX_PROFILE_LENGTH);
    }

    private String clamp(String text, int max) {
        return text == null ? "" : (text.length() <= max ? text : text.substring(0, max));
    }

    /**
     * 写入 Milvus 向量副本（metadata 挂 userId/memoryId/memoryType 等，供过滤与回连 MySQL）
     */
    private void addDocument(Long userId, Long conversationId, Long memoryId, MemoryItem item) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", VectorType.LONG_TERM_MEMORY.getValue());
        metadata.put("userId", String.valueOf(userId));
        metadata.put("memoryId", String.valueOf(memoryId));
        metadata.put("memoryType", MemoryType.PROFILE.getValue());
        metadata.put("importance", item.importance());
        if (conversationId != null) {
            metadata.put("conversationId", String.valueOf(conversationId));
        }
        metadata.put("createdAtEpoch", System.currentTimeMillis());

        String docId = UUID.randomUUID().toString().replace("-", "");
        if (docId.length() > MAX_DOC_ID_LENGTH) {
            docId = docId.substring(0, MAX_DOC_ID_LENGTH);
        }
        store().add(List.of(Document.builder()
                .id(docId)
                .text(item.content())
                .metadata(metadata)
                .build()));
    }

    // ==================== 读取链路 ====================

    @Override
    public List<MemoryItem> search(Long userId, String query, int topK) {
        if (!enabled || userId == null || !StringUtils.hasText(query)) {
            return List.of();
        }
        String filter = "type == '" + VectorType.LONG_TERM_MEMORY.getValue() + "' && userId == '" + userId + "'";
        try {
            List<Document> docs = store().similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(topK <= 0 ? searchTopK : topK)
                    .similarityThreshold(searchThreshold)
                    .filterExpression(filter)
                    .build());
            if (docs.isEmpty()) {
                return List.of();
            }
            List<MemoryItem> items = new ArrayList<>(docs.size());
            for (Document doc : docs) {
                int importance = 3;
                Object imp = doc.getMetadata().get("importance");
                if (imp instanceof Number number) {
                    importance = number.intValue();
                }
                items.add(new MemoryItem(doc.getText(), importance));
            }
            log.debug("[Memory] 检索命中 userId={} 条数={}", userId, items.size());
            return items;
        } catch (Exception e) {
            // 检索失败（如 collection 不存在/连接异常）：降级跳过注入，不影响主链路
            memoryStore.reset();
            log.warn("[Memory] 检索失败，跳过长期记忆注入 userId={} error={}", userId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<AiMemory> listByUser(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return memoryMapper.selectList(new LambdaQueryWrapper<AiMemory>()
                .eq(AiMemory::getUserId, userId)
                .orderByDesc(AiMemory::getUpdatedAt));
    }
}
