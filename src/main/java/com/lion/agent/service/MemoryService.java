package com.lion.agent.service;

import com.lion.agent.model.entity.AiMemory;

import java.util.List;

/**
 * 用户长期记忆服务（跨会话语义记忆）
 *
 * <p>写入链路：对话/知识库问答完成后，异步调用 LLM 从本轮对话抽取用户的持久性事实与偏好
 * （{@link com.lion.agent.service.MemoryExtractor}），去重合并后落库：MySQL ai_memory 存原文与元数据，
 * Milvus collection lion_agent_memory 存向量副本，供后续检索。</p>
 *
 * <p>读取链路：{@code LongTermMemoryAdvisor} 在每次模型调用前，用当前用户消息在 Milvus 按
 * userId 隔离检索 Top-K 相关记忆，以 SystemMessage 注入 prompt，实现跨会话记忆。</p>
 */
public interface MemoryService {

    /**
     * 一条长期记忆：content-记忆内容、importance-重要性 1-5
     */
    record MemoryItem(String content, int importance) {
    }

    /**
     * 异步抽取并落库长期记忆（不阻塞主调用链路；抽取/落库失败仅告警）
     *
     * @param userId            归属用户 ID
     * @param conversationId    来源会话 ID（知识库问答为 null）
     * @param userContent       本轮用户消息
     * @param assistantContent  本轮 AI 回复
     */
    void extractAndStoreAsync(Long userId, Long conversationId, String userContent, String assistantContent);

    /**
     * 检索用户长期记忆（按 userId 隔离，相似度达到阈值才返回）
     *
     * @param userId 用户 ID
     * @param query  检索查询（通常为当前用户消息）
     * @param topK   返回条数（<=0 时用默认配置）
     */
    List<MemoryItem> search(Long userId, String query, int topK);

    /**
     * 查询用户全部长期记忆画像（按更新时间倒序，用于管理页面展示）
     *
     * @param userId 用户 ID
     * @return 该用户全部记忆画像记录
     */
    List<AiMemory> listByUser(Long userId);
}
