package com.lion.agent.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.lion.agent.common.enums.ChatType;
import com.lion.agent.common.utils.SseEmitterUtils;
import com.lion.agent.config.PromptConfig;
import com.lion.agent.pojo.dto.KnowledgeChatRequest;
import com.lion.agent.pojo.vo.ChunkSource;
import com.lion.agent.service.KnowledgeChatService;
import com.lion.agent.service.KnowledgeRetrievalService;
import com.lion.agent.service.support.ChatLlmSupport;
import com.lion.agent.service.support.ChatPersistenceSupport;
import com.lion.agent.pojo.vo.ChatResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 知识库对话服务实现（RAG 链路）
 *
 * <p>与普通对话 {@code ChatServiceImpl} 完全分离的两点差异：</p>
 * <ol>
 *   <li>固定执行知识库检索（改写 / 多路召回 / RRF / Rerank / 门控），不做意图识别；</li>
 *   <li>命中后用 {@code kb-answer} 模板渲染上下文作答，并在结果中回带引用来源。</li>
 * </ol>
 *
 * <p>会话定位、消息落库、模型调用等公共能力复用 {@link ChatPersistenceSupport} 与
 * {@link ChatLlmSupport}，与普通对话保持一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeChatServiceImpl implements KnowledgeChatService {

    private final ChatPersistenceSupport persistence;
    private final ChatLlmSupport llmSupport;
    /** 知识库检索服务（高级 RAG 流水线：改写/多路召回/RRF/Rerank/门控） */
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    /** 提示词模板（kb-answer 上下文渲染） */
    private final PromptConfig promptConfig;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatResult send(KnowledgeChatRequest request) {
        long userId = StpUtil.getLoginIdAsLong();

        // 1. 确定会话（为空则自动创建新会话，chat_type=kb 与普通对话隔离）
        Long conversationId = persistence.resolveConversation(userId, request.getConversationId(),
                request.getMessage(), ChatType.KB.getValue());

        // 2. 检索 + 生成回复
        ChatReply answer = generateReply(userId, request, conversationId);

        // 3. 用户消息 + AI 回复一次性落库（单条 multi-values SQL）
        ChatPersistenceSupport.MessagePair messages =
                persistence.saveMessages(conversationId, request.getMessage(), answer.content());

        // 4. 刷新会话（更新时间 + 首轮标题，单条 SQL）
        persistence.refreshConversation(conversationId);

        return ChatResult.builder()
                .conversationId(conversationId)
                .userMessageId(messages.user().getId())
                .assistantMessageId(messages.assistant().getId())
                .reply(answer.content())
                .referencedChunks(answer.chunks())
                .build();
    }

    @Override
    public SseEmitter stream(KnowledgeChatRequest request) {
        long userId = StpUtil.getLoginIdAsLong();

        // 1. 确定会话（为空则自动创建新会话，chat_type=kb 与普通对话隔离）
        Long conversationId = persistence.resolveConversation(userId, request.getConversationId(),
                request.getMessage(), ChatType.KB.getValue());

        // 2. 创建 SSE 发射器（0L 表示不自动超时）
        SseEmitter emitter = new SseEmitter(0L);

        // 3. 先推送会话信息，便于前端拿到新会话 ID
        if (!SseEmitterUtils.sendStart(emitter, conversationId)) {
            return emitter;
        }

        // 4. 检索 + 生成回复
        ChatReply answer;
        try {
            answer = generateReply(userId, request, conversationId);
        } catch (Exception e) {
            log.error("[KnowledgeChat] 生成回复失败 conversationId={} knowledgeId={}",
                    conversationId, request.getKnowledgeId(), e);
            // 失败也要把用户消息落库，避免前端刷新后这条提问凭空消失
            persistence.saveMessages(conversationId, request.getMessage(), null);
            persistence.refreshConversation(conversationId);
            SseEmitterUtils.error(emitter, "AI 服务调用失败，请稍后重试");
            return emitter;
        }

        // 5. 用户消息 + AI 回复一次性落库
        persistence.saveMessages(conversationId, request.getMessage(), answer.content());
        persistence.refreshConversation(conversationId);

        // 6. 推送完整回复 + 引用来源 + done，并关闭连接
        SseEmitterUtils.sendMessage(emitter, answer.content());
        SseEmitterUtils.sendDone(emitter, answer.content(), answer.chunks());
        SseEmitterUtils.complete(emitter);

        return emitter;
    }

    // ==================== 私有方法 ====================

    /** 一轮知识库问答的模型输出：回复文本 + 引用来源 */
    private record ChatReply(String content, List<ChunkSource> chunks) {
    }

    /**
     * 生成回复：高级 RAG 检索 →（门控未通过时降级为模型兜底，不阻断提问）
     */
    private ChatReply generateReply(Long userId, KnowledgeChatRequest request, Long conversationId) {
        KnowledgeRetrievalService.RetrievalResult result =
                knowledgeRetrievalService.retrieve(userId, request.getMessage(), request.getKnowledgeId());
        if (!result.qualified()) {
            // 门控拦截：资料不足以从知识库回答，降级为由主模型直接作答
            log.info("[KnowledgeChat] 知识库检索未命中，降级为模型兜底：{}", result.reason());
            return new ChatReply(llmSupport.call(request.getMessage(), conversationId, ChatType.KB.getValue(),
                    request.getMessage()), List.of());
        }
        // 检索通过：kb-answer 模板渲染（上下文 + 问题），chatType=kb
        String prompt = promptConfig.renderKbAnswer(result.context(), request.getMessage());
        return new ChatReply(llmSupport.call(prompt, conversationId, ChatType.KB.getValue(), request.getMessage()),
                result.chunks());
    }
}
