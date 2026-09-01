package com.lion.agent.service;

import com.lion.agent.common.enums.VectorType;
import io.milvus.client.MilvusServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 语义缓存（方案 A：跨会话重复问题复用）
 *
 * <p>核心思路：每次问答完成后把「问题 + 回答」写入向量库（独立 Milvus collection
 * lion_agent_qa_cache，与知识库 lion_agent_knowledge 物理隔离）；新问题进来先做向量检索，
 * 相似度达到阈值即视为与历史问题重复，直接复用历史回答，跳过模型调用——省 token、秒回、
 * 答案与历史一致。</p>
 *
 * <p>重要：这里不把 VectorStore 注册为 Spring Bean，而是内部直接持有独立的
 * {@link MilvusVectorStore} 实例（懒加载：首次使用时才构建 + 建表）。否则会顶掉 Spring AI
 * 自动配置的默认 VectorStore（指向知识库 collection），导致知识库 RAG / 工具索引全部错位。</p>
 *
 * <p>降级策略：Milvus 不可用 / 检索异常时仅记录日志并返回 null，主流程不受影响；
 * 初始化失败会在下次使用时自动重试。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaCacheService {

    /** Milvus 建表时 doc_id 为 VarChar 主键，max_length=36，因此 doc id 必须 ≤ 36 字符 */
    private static final int MAX_DOC_ID_LENGTH = 36;

    private final MilvusServiceClient milvusClient;
    private final EmbeddingModel embeddingModel;

    /** 缓存开关 */
    @Value("${lion.qa-cache.enabled:false}")
    private boolean enabled;

    /** 语义缓存专用 collection 名（与知识库隔离） */
    @Value("${lion.qa-cache.collection-name:lion_agent_qa_cache}")
    private String collectionName;

    /** 向量维度，必须与 embedding 模型输出一致（text-embedding-v3 默认 1024） */
    @Value("${lion.qa-cache.embedding-dimension:1024}")
    private int embeddingDimension;

    /**
     * 命中相似度阈值：text-embedding-v3 向量未归一化，短句相似度偏低（完全相同文本实测约 0.83），
     * 阈值 0.75-0.80 较合理；过高（0.9+）会导致永远命中不了缓存
     */
    @Value("${lion.qa-cache.threshold:0.78}")
    private double threshold;

    /** 每次检索取 Top-N 条候选（取最高相似度一条） */
    @Value("${lion.qa-cache.top-k:1}")
    private int topK;

    /** 语义缓存专用向量存储（懒加载，独立 collection，非 Spring Bean） */
    private volatile MilvusVectorStore cacheStore;

    /** 向量库是否已就绪（collection 已建好并加载）。操作失败会置 false，下次自动重建 */
    private volatile boolean storeReady = false;

    /**
     * 懒加载获取缓存向量库：首次调用（或上次失败后）才构建并建表。
     *
     * <p>为什么需要手动建表：Spring AI 2.x 的 {@link MilvusVectorStore} 建表
     * （createCollection + createIndex + loadCollection）发生在 {@code afterPropertiesSet()}
     * 中，这是 Spring bean 生命周期回调，只有容器管理的 bean 才会被自动调用；而本类刻意不把
     * 它注册成 Bean（否则会顶掉自动配置的默认 VectorStore、破坏知识库 RAG），所以必须手动触发。
     * 封装成懒加载 getter 后，业务方法无需任何前置初始化步骤，失败也在下次调用时自动自愈。</p>
     */
    private MilvusVectorStore store() {
        if (!storeReady) {
            synchronized (this) {
                if (!storeReady) {
                    try {
                        MilvusVectorStore store = MilvusVectorStore.builder(milvusClient, embeddingModel)
                                .collectionName(collectionName)
                                .embeddingDimension(embeddingDimension)
                                .initializeSchema(true)
                                .build();
                        store.afterPropertiesSet();
                        this.cacheStore = store;
                        this.storeReady = true;
                        log.info("语义缓存向量库已就绪 collection={} dim={} threshold={}",
                                collectionName, embeddingDimension, threshold);
                    } catch (Exception e) {
                        log.warn("语义缓存向量库初始化失败 collection={}（下次使用自动重试建表）", collectionName, e);
                    }
                }
            }
        }
        return cacheStore;
    }

    /** 命中结果：answer 为历史回答全文，askedAt 为提问时间描述（如"3个月前"） */
    public record Hit(String answer, String askedAt) {
    }

    /**
     * 检索语义缓存：query 与历史问题相似度达到阈值则返回命中结果，否则返回 null。
     * 按 userId 隔离，不同用户的缓存互不可见。
     */
    public Hit search(Long userId, String query) {
        if (!enabled || !StringUtils.hasText(query)) {
            return null;
        }
        String filter = "type == '" + VectorType.QA_CACHE.getValue() + "' && userId == '" + userId + "'";
        try {
            List<Document> docs = store().similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(threshold)
                    .filterExpression(filter)
                    .build());
            log.info("语义缓存检索 query={} filter={} 召回 {} 条", truncate(query, 30), filter, docs.size());
            if (docs.isEmpty()) {
                return null;
            }
            // 打印召回结果，方便观察相似度分布
            for (Document doc : docs) {
                log.info("语义缓存召回候选 id={} question={} metadataKeys={}", doc.getId(),
                        truncate((String) doc.getMetadata().get("question"), 30),
                        doc.getMetadata().keySet());
            }
            Map<String, Object> metadata = docs.get(0).getMetadata();
            String answer = (String) metadata.get("answer");
            if (!StringUtils.hasText(answer)) {
                return null;
            }
            Object epoch = metadata.get("createdAtEpoch");
            long askedAtEpoch = epoch instanceof Number ? ((Number) epoch).longValue() : 0L;
            return new Hit(answer, formatAskedAt(askedAtEpoch));
        } catch (Exception e) {
            // 降级：Milvus 不可用时跳过缓存，走正常模型调用
            storeReady = false; // 检索失败大概率是 collection 丢失 / Milvus 重启，下次自动重建
            log.warn("语义缓存检索失败（跳过缓存）", e);
            return null;
        }
    }


    /**
     * 写入一条问答缓存（Milvus 异常仅告警，不影响主流程）
     *
     * <p>doc id 用去横线的 UUID（32 字符），必须 ≤36（Milvus VarChar 主键 max_length）。
     * userId / conversationId 等业务信息全部放 metadata，检索与清理靠 filterExpression
     * （type + userId）定位，不依赖 doc id 可读性。</p>
     */
    public void cache(Long userId, Long conversationId, String question, String answer) {
        if (!enabled || !StringUtils.hasText(question) || !StringUtils.hasText(answer)) {
            return;
        }
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("type", VectorType.QA_CACHE.getValue());
            // userId 存字符串，与 filterExpression 中的字符串比较保持一致
            metadata.put("userId", String.valueOf(userId));
            metadata.put("conversationId", conversationId);
            metadata.put("question", question);
            metadata.put("answer", answer);
            metadata.put("createdAtEpoch", System.currentTimeMillis());
            String docId = UUID.randomUUID().toString().replace("-", "");
            if (docId.length() > MAX_DOC_ID_LENGTH) {
                docId = docId.substring(0, MAX_DOC_ID_LENGTH);
            }
            Document doc = Document.builder()
                    .id(docId)
                    .text(question)
                    .metadata(metadata)
                    .build();
            store().add(List.of(doc));
            log.info("已写入语义缓存 userId={} docId={} question={}", userId, docId, truncate(question, 30));
        } catch (Exception e) {
            // 打完整堆栈，方便定位（doc_id 超长 / 维度不匹配 / embedding 失败等）
            storeReady = false; // 例如 collection 被删 / Milvus 重启，下次写入时重新建表
            log.error("写入语义缓存失败 userId={} question={}", userId, truncate(question, 30), e);
        }
    }

    /**
     * 清理某个用户的所有语义缓存（如用户注销 / 数据重置时调用）
     */
    public void clear(Long userId) {
        try {
            store().delete("type == '" + VectorType.QA_CACHE.getValue() + "' && userId == '" + userId + "'");
            log.info("已清理语义缓存 userId={}", userId);
        } catch (Exception e) {
            storeReady = false;
            log.warn("清理语义缓存失败", e);
        }
    }

    /** epoch 毫秒 -> "今天 / N天前 / N个月前 / N年前" */
    private String formatAskedAt(long epoch) {
        long days = (System.currentTimeMillis() - epoch) / (24 * 3600 * 1000L);
        if (days <= 0) {
            return "今天";
        }
        if (days < 30) {
            return days + "天前";
        }
        if (days < 365) {
            return (days / 30) + "个月前";
        }
        return (days / 365) + "年前";
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
