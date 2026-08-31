package com.lion.agent.service.retriever;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分片原文读取器（本地内存版）：从 {@link InMemoryChunkStore} 按 (documentId, chunkIndex)
 * 批量取回分片内容，供「窗口扩容 + 临近拼接」阶段获取命中块前后相邻分片的完整原文。
 *
 * <p>不依赖 Milvus 客户端，与 BM25 共用同一份本地分片副本（入库时同步、重启后种子加载）。
 * 本地分片为空时返回空 Map，由调用方降级为「命中块自成一段」，不伤主链路。</p>
 */
@Slf4j
@Component
public class MilvusChunkReader {

    private final InMemoryChunkStore chunkStore;

    public MilvusChunkReader(InMemoryChunkStore chunkStore) {
        this.chunkStore = chunkStore;
    }

    /**
     * 按位置批量取回分片原文
     *
     * @param positions 需要的位置集合（含命中块与其前后邻居）
     * @return documentId → chunkIndex → 原文；本地分片为空时返回空 Map
     */
    public Map<ChunkPos, String> selectByPositions(Collection<ChunkPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return Map.of();
        }
        List<Document> all = chunkStore.all();
        if (all.isEmpty()) {
            log.warn("[MilvusChunkReader] 本地分片为空，窗口扩容降级");
            return Map.of();
        }
        // 建索引：documentId -> (chunkIndex -> 原文)
        Map<Long, Map<Integer, String>> byDoc = new HashMap<>();
        for (Document doc : all) {
            Map<String, Object> meta = doc.getMetadata();
            if (meta == null) {
                continue;
            }
            Object docIdObj = meta.get("documentId");
            Object idxObj = meta.get("chunkIndex");
            if (!(docIdObj instanceof Number) || !(idxObj instanceof Number)) {
                continue;
            }
            byDoc.computeIfAbsent(((Number) docIdObj).longValue(), k -> new HashMap<>())
                    .put(((Number) idxObj).intValue(), doc.getText());
        }
        Map<ChunkPos, String> result = new HashMap<>();
        for (ChunkPos pos : positions) {
            Map<Integer, String> chunks = byDoc.get(pos.documentId());
            if (chunks == null) {
                continue;
            }
            String text = chunks.get(pos.index());
            if (text != null) {
                result.put(pos, text);
            }
        }
        return result;
    }
}
