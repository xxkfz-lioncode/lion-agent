package com.lion.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lion.agent.common.enums.MemoryType;
import com.lion.agent.common.enums.VectorType;
import com.lion.agent.model.entity.AiMemory;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 用户长期记忆服务实现（Phase 1）
 *
 * <p>写入：{@link #extractAndStoreAsync} 在独立线程池 {@code memoryExecutor} 中异步执行——
 * LLM 抽取 → MySQL ai_memory 存原文 + Milvus lion_agent_memory 存向量副本。
 * 每个用户最多只保留一条画像（profile）：落库时不依赖向量相似度判断重复，
 * 直接按 userId 查询已有画像并强制合并（内容去重拼接、重要性取高、重建向量），
 * 不存在才新增。</p>
 *
 * <p>读取：{@link #search} 按 userId 过滤（多租户隔离），返回相似度达标（默认 0.55）的 Top-K 记忆，
 * 供 {@code LongTermMemoryAdvisor} 注入 prompt。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryServiceImpl implements MemoryService {

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

    /** 懒加载的 Milvus 向量存储（首次使用时初始化） */
    private volatile MilvusVectorStore memoryStore;
    private volatile boolean storeReady = false;

    /**
     * 懒加载获取 Milvus 向量存储（仿 QaCacheService）
     */
    private MilvusVectorStore store() {
        if (!storeReady) {
            synchronized (this) {
                if (!storeReady) {
                    MilvusVectorStore store = MilvusVectorStore.builder(milvusClient, embeddingModel)
                            .collectionName(collectionName)
                            .embeddingDimension(embeddingDimension)
                            .initializeSchema(true)
                            .build();
                    try {
                        store.afterPropertiesSet();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    this.memoryStore = store;
                    this.storeReady = true;
                    log.info("[Memory] Milvus collection {} 初始化完成", collectionName);
                }
            }
        }
        return memoryStore;
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
        // 同一轮抽取出的多条事实/偏好合并为一条用户画像，避免数据库/向量库碎片化
        MemoryItem profile = mergeItemsToProfile(items);
        try {
            storeItem(userId, conversationId, profile);
            log.info("[Memory] 用户画像落库完成 userId={} conversationId={} 合并 {} 条原始记忆", userId, conversationId, items.size());
        } catch (Exception e) {
            log.warn("[Memory] 用户画像落库失败 userId={} error={}", userId, e.getMessage());
        }
    }

    /**
     * 单条记忆落库（不依赖向量相似度去重）：
     * 直接按 userId 查询 MySQL 中该用户的画像（profile）记录——
     * 已存在则强制合并为同一条（内容去重拼接、重要性取高、重建向量），
     * 不存在则 MySQL 插入 + Milvus 写入。保证每个用户最多一条画像。
     */
    private void storeItem(Long userId, Long conversationId, MemoryItem item) {
        // 查询该用户已有的全部画像记录（含历史遗留的多条，合并后收敛为一条）
        List<AiMemory> existingProfiles = memoryMapper.selectList(new LambdaQueryWrapper<AiMemory>()
                .eq(AiMemory::getUserId, userId)
                .eq(AiMemory::getMemoryType, MemoryType.PROFILE.getValue())
                .orderByAsc(AiMemory::getId));

        if (!existingProfiles.isEmpty()) {
            mergeProfiles(userId, conversationId, item, existingProfiles);
            return;
        }

        AiMemory memory = new AiMemory();
        memory.setUserId(userId);
        memory.setMemoryType(MemoryType.PROFILE.getValue());
        memory.setContent(item.content());
        memory.setImportance(item.importance());
        memory.setSourceConversationId(conversationId);
        memory.setCreatedAt(LocalDateTime.now());
        memory.setUpdatedAt(LocalDateTime.now());
        memoryMapper.insert(memory);

        addDocument(userId, conversationId, memory.getId(), item);
        log.info("[Memory] 新增用户画像 userId={} memoryId={} importance={}",
                userId, memory.getId(), item.importance());
    }

    /**
     * 合并用户已有画像：以最早一条为基底，将新内容与其余历史画像内容合并，
     * 重要性取最高；多余的历史画像记录及向量一并清理，保证每用户仅一条画像。
     */
    private void mergeProfiles(Long userId, Long conversationId, MemoryItem item, List<AiMemory> existingProfiles) {
        AiMemory base = existingProfiles.get(0);
        String merged = base.getContent();
        int maxImportance = base.getImportance() == null ? 0 : base.getImportance();
        List<Long> redundantIds = new ArrayList<>();
        for (int i = 1; i < existingProfiles.size(); i++) {
            AiMemory extra = existingProfiles.get(i);
            merged = mergeContents(merged, extra.getContent());
            if (extra.getImportance() != null && extra.getImportance() > maxImportance) {
                maxImportance = extra.getImportance();
            }
            redundantIds.add(extra.getId());
        }
        merged = mergeContents(merged, item.content());
        if (item.importance() > maxImportance) {
            maxImportance = item.importance();
        }

        base.setContent(merged);
        base.setImportance(maxImportance);
        base.setUpdatedAt(LocalDateTime.now());
        memoryMapper.updateById(base);

        // 清理多余的历史画像记录及其向量
        for (Long id : redundantIds) {
            deleteVectorByMemoryId(userId, id);
        }
        if (!redundantIds.isEmpty()) {
            memoryMapper.deleteBatchIds(redundantIds);
        }

        // 重建主画像向量：先删旧 doc 再写入
        deleteVectorByMemoryId(userId, base.getId());
        addDocument(userId, conversationId, base.getId(), new MemoryItem(merged, maxImportance));
        log.info("[Memory] 合并用户画像 userId={} memoryId={} 内容长度={} 重要性={}",
                userId, base.getId(), merged.length(), maxImportance);
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
     * 把同一轮抽取出的多条记忆合并为一条用户画像，避免数据库/向量库碎片化。
     * 内容去重拼接，重要性取最高。
     */
    private MemoryItem mergeItemsToProfile(List<MemoryItem> items) {
        if (items.size() == 1) {
            return new MemoryItem(items.get(0).content(), items.get(0).importance());
        }
        StringBuilder sb = new StringBuilder();
        int maxImportance = 0;
        for (MemoryItem item : items) {
            if (StringUtils.hasText(item.content())) {
                if (!sb.isEmpty()) {
                    sb.append("；");
                }
                sb.append(item.content());
            }
            if (item.importance() > maxImportance) {
                maxImportance = item.importance();
            }
        }
        // 限制单条画像长度，避免超出 ai_memory.content VARCHAR(1024) 上限
        String content = sb.toString();
        if (content.length() > 900) {
            content = content.substring(0, 900);
        }
        return new MemoryItem(content, Math.max(maxImportance, 3));
    }

    /**
     * 合并已有画像与新画像内容，按"；"拆分后去重拼接，保留信息完整度。
     * 合并后长度超过 1000 时截断，避免越界。
     */
    private String mergeContents(String existingContent, String newContent) {
        Set<String> segments = new LinkedHashSet<>();
        if (StringUtils.hasText(existingContent)) {
            for (String s : existingContent.split("[；;]")) {
                if (StringUtils.hasText(s.trim())) {
                    segments.add(s.trim());
                }
            }
        }
        if (StringUtils.hasText(newContent)) {
            for (String s : newContent.split("[；;]")) {
                if (StringUtils.hasText(s.trim())) {
                    segments.add(s.trim());
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String segment : segments) {
            if (!sb.isEmpty()) {
                sb.append("；");
            }
            sb.append(segment);
            if (sb.length() > 1000) {
                break;
            }
        }
        return sb.toString();
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
            storeReady = false;
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
