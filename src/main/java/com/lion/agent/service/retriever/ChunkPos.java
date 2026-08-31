package com.lion.agent.service.retriever;

/**
 * 分片位置：文档号 + 分片序号。
 * <p>
 * 用于 small-to-big 窗口扩容时定位命中块及其前后相邻分片，
 * 对应入库时 metadata 中的 documentId 与 chunkIndex。
 * </p>
 *
 * @param documentId 文档 ID
 * @param index      分片序号（从 0 开始）
 */
public record ChunkPos(long documentId, int index) {
}
