package com.lion.agent.common.util;

import io.milvus.client.MilvusServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;

/**
 * 独立 Milvus collection 的懒加载 {@link MilvusVectorStore} 持有器（线程安全、失败可降级/自愈）。
 *
 * <p>背景：Spring AI 2.x 的 {@link MilvusVectorStore} 建表（createCollection + createIndex +
 * loadCollection）发生在 {@code afterPropertiesSet()} 中，而该方法属于 Spring Bean 生命周期回调，
 * 只有容器管理的 Bean 才会被自动调用。业务上常有需要「独立于自动配置默认 VectorStore」的额外
 * collection（如语义缓存、长期记忆），这些 store 刻意不注册成 Bean（否则会顶掉默认 VectorStore、
 * 破坏知识库 RAG / 工具索引），因此必须手动触发初始化。</p>
 *
 * <p>本类把「懒初始化 + 双重检查锁 + 失败自愈」收敛为一行调用，两个业务方的差异只体现在方法选择上：</p>
 * <ul>
 *   <li>{@link #get()}：初始化失败直接抛出，适合「失败应当暴露、由上层统一兜底」的场景（如记忆服务）；</li>
 *   <li>{@link #getOrNull()}：初始化失败仅告警并返回 null、下次调用自动重试，适合「降级不影响主链路」的场景（如语义缓存）；</li>
 *   <li>{@link #reset()}：业务操作（检索/写入）发现 collection 丢失或 Milvus 重启后调用，使下次访问重新建库自愈。</li>
 * </ul>
 */
@Slf4j
public final class LazyMilvusVectorStore {

    private final MilvusServiceClient milvusClient;
    private final EmbeddingModel embeddingModel;
    private final String collectionName;
    private final int embeddingDimension;
    /** 日志业务标识，便于区分同容器内的多个实例（如「语义缓存」「长期记忆」） */
    private final String logTag;

    private volatile MilvusVectorStore store;
    private volatile boolean ready;

    /**
     * @param milvusClient      Milvus 客户端（通常是 Spring Bean）
     * @param embeddingModel    Embedding 模型（维度必须与 embeddingDimension 一致）
     * @param collectionName    目标 collection 名
     * @param embeddingDimension 向量维度
     * @param logTag            日志前缀，如「语义缓存」「长期记忆」
     */
    public LazyMilvusVectorStore(MilvusServiceClient milvusClient, EmbeddingModel embeddingModel,
                                 String collectionName, int embeddingDimension, String logTag) {
        this.milvusClient = milvusClient;
        this.embeddingModel = embeddingModel;
        this.collectionName = collectionName;
        this.embeddingDimension = embeddingDimension;
        this.logTag = logTag;
    }

    /**
     * 获取（必要时懒初始化并建表）向量库。
     *
     * @return 已就绪的 {@link MilvusVectorStore}
     * @throws IllegalStateException 初始化失败时抛出（ready 保持 false，下次调用会自动重试）
     */
    public MilvusVectorStore get() {
        if (!ready) {
            synchronized (this) {
                if (!ready) {
                    try {
                        MilvusVectorStore built = MilvusVectorStore.builder(milvusClient, embeddingModel)
                                .collectionName(collectionName)
                                .embeddingDimension(embeddingDimension)
                                .initializeSchema(true)
                                .build();
                        built.afterPropertiesSet();
                        this.store = built;
                        this.ready = true;
                        log.info("[{}] Milvus 向量库已就绪 collection={} dim={}", logTag, collectionName, embeddingDimension);
                    } catch (Exception e) {
                        throw new IllegalStateException(
                                "[" + logTag + "] Milvus 向量库初始化失败 collection=" + collectionName, e);
                    }
                }
            }
        }
        return store;
    }

    /**
     * 降级获取：与 {@link #get()} 相同，但初始化失败不抛出，仅告警并返回 {@code null}。
     *
     * <p>适合「向量库不可用时跳过该功能、不影响主链路」的场景；返回 null 后由调用方判空处理，
     * 下次访问会自动重试建表。</p>
     */
    public MilvusVectorStore getOrNull() {
        try {
            return get();
        } catch (Exception e) {
            log.warn("[{}] Milvus 向量库初始化失败 collection={}（降级处理，下次使用自动重试建表）",
                    logTag, collectionName, e);
            return null;
        }
    }

    /**
     * 置为未就绪：业务操作（如检索/写入）失败大概率是 collection 丢失或 Milvus 重启，
     * 调用本方法后，下次访问会重新执行建表自愈。
     */
    public void reset() {
        this.ready = false;
    }
}
