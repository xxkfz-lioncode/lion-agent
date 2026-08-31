package com.lion.agent.service.retriever;

import com.lion.agent.utils.MilvusQueryUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 内存分片存储：知识库全量分片（原文 + metadata）的本地副本，
 * 供 BM25 关键词召回、窗口扩容等检索链路直接读取，检索时不再依赖 Milvus 客户端。
 *
 * <p>数据来源：</p>
 * <ul>
 *   <li>运行时：文档入库成功后由 {@code KnowledgeDocumentServiceImpl} 同步 {@link #addAll}，
 *       删除文档时同步 {@link #removeByDocumentId}，与向量库保持一致；</li>
 *   <li>启动恢复：首次读取时若内存为空（服务重启场景），从 Milvus 全量种子加载一次，
 *       客户端不可用则跳过，不影响主流程。</li>
 * </ul>
 *
 * <p>返回给调用方的都是深拷贝（含 metadata 副本），调用方改写 metadata
 * （如打来源标记）不会污染共享数据。</p>
 */
@Slf4j
@Component
public class InMemoryChunkStore {

    private final MilvusQueryUtils milvusQueryUtils;
    private final String collectionName;

    /** 全量分片：写少读多，CopyOnWriteArrayList 保证并发安全 */
    private final List<Document> documents = new CopyOnWriteArrayList<>();

    /** 种子加载只允许尝试一次（无论成败），避免每次空读都打一次 Milvus */
    private final AtomicBoolean seedAttempted = new AtomicBoolean(false);

    public InMemoryChunkStore(MilvusQueryUtils milvusQueryUtils,
                              @Value("${spring.ai.vectorstore.milvus.collection-name:lion_agent_knowledge}") String collectionName) {
        this.milvusQueryUtils = milvusQueryUtils;
        this.collectionName = collectionName;
    }

    /** 入库成功后同步本地副本 */
    public void addAll(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        documents.addAll(docs);
        log.info("[InMemoryChunkStore] 本地分片 +{} 条，当前共 {} 条", docs.size(), documents.size());
    }

    /** 删除文档后同步本地副本 */
    public void removeByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        int before = documents.size();
        documents.removeIf(doc -> {
            Object v = doc.getMetadata() == null ? null : doc.getMetadata().get("documentId");
            return v instanceof Number && ((Number) v).longValue() == documentId;
        });
        if (documents.size() != before) {
            log.info("[InMemoryChunkStore] 移除文档 documentId={} 分片 {} 条，当前共 {} 条",
                    documentId, before - documents.size(), documents.size());
        }
    }

    /**
     * 全量分片快照（深拷贝，含 metadata 副本）。
     * 防御兜底：内存完全为空时触发一次全量种子加载，保证重启后检索有数据。
     */
    public List<Document> all() {
        ensureSeeded();
        List<Document> copy = new ArrayList<>(documents.size());
        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata() == null ? new HashMap<>() : new HashMap<>(doc.getMetadata());
            copy.add(new Document(doc.getId(), doc.getText(), meta));
        }
        return copy;
    }

    /** 启动恢复：首次读取且内存为空时，从 Milvus 全量种子加载一次 */
    private void ensureSeeded() {
        if (!documents.isEmpty() || !seedAttempted.compareAndSet(false, true)) {
            return;
        }
        List<Document> loaded = milvusQueryUtils.toDocuments(milvusQueryUtils.queryAll(
                collectionName, List.of("doc_id", "content", "metadata")));
        addFresh(loaded);
        log.info("[InMemoryChunkStore] 种子加载 {} 条分片到内存", loaded.size());
    }

    /** 加载进内存前去重（避免种子加载与运行时同步在极端并发下重复） */
    private void addFresh(List<Document> loaded) {
        if (loaded == null || loaded.isEmpty()) {
            return;
        }
        Set<String> existing = documents.stream().map(Document::getId).collect(Collectors.toSet());
        List<Document> fresh = loaded.stream().filter(d -> !existing.contains(d.getId())).toList();
        if (!fresh.isEmpty()) {
            documents.addAll(fresh);
        }
    }
}
