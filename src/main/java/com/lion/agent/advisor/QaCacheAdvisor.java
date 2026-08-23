package com.lion.agent.advisor;

import com.lion.agent.common.constants.AdvisorConstants;
import com.lion.agent.service.QaCacheService;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;

import static com.lion.agent.common.constants.AdvisorConstants.CONVERSATION_ID_KEY;
import static com.lion.agent.common.constants.AdvisorConstants.USER_ID_KEY;

/**
 * 语义缓存 Advisor（Spring AI 2.0）
 * <p>
 * 职责：
 * 1. 调用前：按 userId + 用户问题检索 {@link QaCacheService} 语义缓存，相似度达到阈值即视为
 *    重复问题，直接构造模拟 {@link ChatClientResponse} 返回历史回答（短路，跳过模型调用）——
 *    省 token、秒回、答案与历史一致。
 * 2. 调用后：把本次「用户问题 + 助手回复」写入语义缓存，供后续相似问题直接复用。
 * <p>
 * 说明：消息落库（user / assistant）统一由 ChatServiceImpl 负责（user 在调用前、assistant 在
 * 拿到 reply 后），缓存命中时 reply 即缓存答案，同样会走 ChatServiceImpl 落库，本 Advisor
 * 不直接写 chat_message 表，避免重复插入。
 * <p>
 * 依赖的上下文参数（由业务层通过 {@code .advisors(spec -> spec.param(...))} 注入）：
 * <ul>
 *   <li>{@link AdvisorConstants#USER_ID_KEY}（user_id）</li>
 *   <li>{@link AdvisorConstants#CONVERSATION_ID_KEY}（chat_memory_conversation_id）</li>
 * </ul>
 * 取 prompt 中最后一条 USER 消息作为本次问题（本 Advisor 在记忆注入前执行，因此即原始输入）。
 */
@Slf4j
public class QaCacheAdvisor implements CallAdvisor, StreamAdvisor {

    private final QaCacheService qaCacheService;

    /**
     * 调用链顺序（order 越小越靠外层）。默认 {@link Ordered#HIGHEST_PRECEDENCE} + 1：
     * 在会话记忆（ConversationSummaryAdvisor 默认 order=100）之前执行，缓存命中时短路跳过记忆注入与模型调用，
     * 仅晚于最外层的 TokenUsageAdvisor（统计）；实际值由 {@code AiConfig} 从配置
     * {@code lion.advisor.qa-cache-order} 读取后构造注入，可在不改代码的情况下调整。
     */
    private final int order;

    public QaCacheAdvisor(QaCacheService qaCacheService) {
        this(qaCacheService, Ordered.HIGHEST_PRECEDENCE + 1);
    }

    public QaCacheAdvisor(QaCacheService qaCacheService, int order) {
        this.qaCacheService = qaCacheService;
        this.order = order;
    }

    @NotNull
    @Override
    public String getName() {
        return "QaCacheAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    @NotNull
    @Override
    public ChatClientResponse adviseCall(@NotNull ChatClientRequest request, @NotNull CallAdvisorChain chain) {
        String query = extractUserQuery(request);
        Long userId = resolveUserId(request);
        String conversationId = resolveConversationId(request);

        // 1. 调用前：检索语义缓存，命中则直接返回历史回答（短路，跳过模型调用）
        if (userId != null && StringUtils.hasText(query)) {
            QaCacheService.Hit hit = qaCacheService.search(userId, query);
            if (hit != null) {
                String cachedReply = buildCachedReply(hit);
                // 消息落库由 ChatServiceImpl 统一负责（reply 即缓存答案，调用方拿到后落库），此处不重复插入
                log.info("[QaCache] 缓存命中 userId={} conversationId={} query={}",
                        userId, conversationId, truncate(query, 30));
                return buildCachedResponse(cachedReply);
            }
        }

        // 2. 调用下游（大模型 / 其它 Advisor）
        ChatClientResponse response = chain.nextCall(request);

        // 3. 调用后：把本次「问题 + 回答」写入缓存，供后续相似问题直接复用
        String reply = extractOutput(response);
        if (userId != null && StringUtils.hasText(reply)) {
            try {
                qaCacheService.cache(userId, toLong(conversationId), query, reply);
            } catch (Exception e) {
                log.warn("[QaCache] 写入语义缓存失败 userId={}", userId, e);
            }
        }
        return response;
    }

    @NotNull
    @Override
    public Flux<ChatClientResponse> adviseStream(@NotNull ChatClientRequest request, @NotNull StreamAdvisorChain chain) {
        String query = extractUserQuery(request);
        Long userId = resolveUserId(request);
        String conversationId = resolveConversationId(request);

        // 调用前：缓存命中则直接下发历史回答，跳过模型调用
        if (userId != null && StringUtils.hasText(query)) {
            QaCacheService.Hit hit = qaCacheService.search(userId, query);
            if (hit != null) {
                String cachedReply = buildCachedReply(hit);
                log.info("[QaCache] 缓存命中(流式) userId={} conversationId={} query={}",
                        userId, conversationId, truncate(query, 30));
                return Flux.just(buildCachedResponse(cachedReply));
            }
        }

        // 流式调用：聚合输出，完成后写入缓存
        StringBuilder output = new StringBuilder();
        return chain.nextStream(request)
                .doOnNext(resp -> {
                    String text = extractOutput(resp);
                    if (text != null) {
                        output.append(text);
                    }
                })
                .doOnComplete(() -> {
                    String reply = output.toString();
                    if (userId != null && StringUtils.hasText(reply)) {
                        try {
                            qaCacheService.cache(userId, toLong(conversationId), query, reply);
                        } catch (Exception e) {
                            log.warn("[QaCache] 写入语义缓存失败(流式) userId={}", userId, e);
                        }
                    }
                })
                .doOnError(e -> log.warn("[QaCache] 流式调用异常 userId={} error={}", userId, e.getMessage()));
    }

    // ==================== 私有方法 ====================

    /**
     * 缓存命中时组装回复：历史回答 + 来源时间提示（提醒用户内容可能过时）
     */
    private String buildCachedReply(QaCacheService.Hit hit) {
        return hit.answer() + "\n\n---\n（该回答来自你 " + hit.askedAt() + " 提出的相似问题，内容可能已过时，如需最新信息请追问）";
    }

    /**
     * 从响应中提取助手文本（缓存命中构造的响应同样适用）
     */
    private String extractOutput(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null || response.chatResponse().getResult() == null) {
            return null;
        }
        AssistantMessage output = response.chatResponse().getResult().getOutput();
        return output == null ? null : output.getText();
    }

    /**
     * 取 prompt 中最后一条 USER 消息作为本次问题
     */
    private String extractUserQuery(ChatClientRequest request) {
        List<Message> instructions = request.prompt().getInstructions();
        for (int i = instructions.size() - 1; i >= 0; i--) {
            Message m = instructions.get(i);
            if (MessageType.USER.equals(m.getMessageType()) && StringUtils.hasText(m.getText())) {
                return m.getText();
            }
        }
        return null;
    }

    /**
     * 从上下文读取用户 ID（与 TokenUsageAdvisor 一致），解析失败返回 null
     */
    private Long resolveUserId(ChatClientRequest request) {
        Object value = request.context().get(USER_ID_KEY);
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 从上下文读取会话 ID，读取不到时返回 null
     */
    private String resolveConversationId(ChatClientRequest request) {
        Object value = request.context().get(CONVERSATION_ID_KEY);
        return value != null ? value.toString() : null;
    }

    private Long toLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /**
     * 构造缓存命中时的模拟响应（短路返回，跳过模型调用）
     */
    private ChatClientResponse buildCachedResponse(String reply) {
        Generation generation = new Generation(new AssistantMessage(reply));
        ChatResponse chatResponse = new ChatResponse(List.of(generation), ChatResponseMetadata.builder().build());
        return ChatClientResponse.builder().chatResponse(chatResponse).build();
    }
}
