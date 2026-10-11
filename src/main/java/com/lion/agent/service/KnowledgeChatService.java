package com.lion.agent.service;

import com.lion.agent.pojo.dto.KnowledgeChatRequest;
import com.lion.agent.pojo.vo.ChatResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 知识库对话服务
 *
 * <p>与普通对话 {@link ChatService} 分离的独立链路：固定执行知识库检索（RAG），
 * 基于检索到的资料作答，并在结果中回带引用来源 {@code referencedChunks}。</p>
 *
 * <p>接口入口见 {@code KnowledgeChatController}（/api/knowledge/chat）；
 * 普通对话不走这里，见 {@code /api/chat}。</p>
 */
public interface KnowledgeChatService {

    /**
     * 知识库问答：检索 → 渲染 KB 提示词 → 模型作答（非流式）
     */
    ChatResult send(KnowledgeChatRequest request);

    /**
     * 知识库问答流式接口（SSE），事件约定与普通对话一致：start / message / done / error
     */
    SseEmitter stream(KnowledgeChatRequest request);
}
