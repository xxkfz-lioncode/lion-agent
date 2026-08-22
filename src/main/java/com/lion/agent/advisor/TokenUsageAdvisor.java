package com.lion.agent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Token 用量统计 Advisor
 *
 * <p>同时实现 {@link CallAdvisor}（同步）与 {@link StreamAdvisor}（流式）两种调用链，
 * 在调用完成后统一记录：用户 ID、会话 ID、模型名、输入 / 输出 / 总 token 数与调用耗时。</p>
 *
 * <p>上下文 Key 使用自定义常量（取值与 Spring AI 内置常量一致），避免直接依赖
 * {@code UserMemoryAdvisor.USER_ID} 等内部类常量（本项目中并未注册 UserMemoryAdvisor，
 * 直接引用属于隐性耦合，且版本升级时可能编译失败）。业务层发起调用时通过
 * {@code .advisors(spec -> spec.param(...))} 注入 userId / conversationId 即可。</p>
 */
@Slf4j
public class TokenUsageAdvisor implements CallAdvisor, StreamAdvisor {

    /** 上下文 Key：用户 ID（与 Spring AI UserMemoryAdvisor.USER_ID 取值一致：user_id） */
    public static final String USER_ID_KEY = "user_id";
    /** 上下文 Key：会话 ID（与 Spring AI ChatMemory.CONVERSATION_ID 取值一致） */
    public static final String CONVERSATION_ID_KEY = "chat_memory_conversation_id";

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Instant start = Instant.now();
        ChatClientResponse response = chain.nextCall(request);
        logUsage("同步", request, response, Duration.between(start, Instant.now()));
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Instant start = Instant.now();
        // 流式场景：每个分片都可能携带 Usage，保留最后一个有效的用于统计
        AtomicReference<ChatClientResponse> lastWithUsage = new AtomicReference<>();
        return chain.nextStream(request)
                .doOnNext(response -> {
                    if (containsUsage(response)) {
                        lastWithUsage.set(response);
                    }
                })
                .doOnComplete(() -> logUsage("流式", request, lastWithUsage.get(), Duration.between(start, Instant.now())))
                .doOnError(error -> log.warn("[Token] 流式调用异常 | userId={} conversationId={} error={}",
                        resolveUserId(request), resolveConversationId(request), error.getMessage()));
    }

    /**
     * 从响应中提取 token 用量并记录日志（同步 / 流式共用）
     */
    private void logUsage(String type, ChatClientRequest request, ChatClientResponse response, Duration cost) {
        if (response == null || response.chatResponse() == null) {
            log.warn("[Token] {} | userId={} conversationId={} 调用无响应体",
                    type, resolveUserId(request), resolveConversationId(request));
            return;
        }
        var metadata = response.chatResponse().getMetadata();
        if (metadata == null) {
            log.warn("[Token] {} | userId={} conversationId={} 响应缺少元数据",
                    type, resolveUserId(request), resolveConversationId(request));
            return;
        }
        Usage usage = metadata.getUsage();
        if (usage == null) {
            log.info("[Token] {} | userId={} conversationId={} 模型={} 耗时={}ms 无用量信息",
                    type, resolveUserId(request), resolveConversationId(request),
                    metadata.getModel(), cost.toMillis());
            return;
        }
        log.info("[Token] {} | userId={} conversationId={} 模型={} 输入={} 输出={} 总计={} 耗时={}ms",
                type,
                resolveUserId(request),
                resolveConversationId(request),
                metadata.getModel(),
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getTotalTokens(),
                cost.toMillis());
    }

    private boolean containsUsage(ChatClientResponse response) {
        return response != null
                && response.chatResponse() != null
                && response.chatResponse().getMetadata() != null
                && response.chatResponse().getMetadata().getUsage() != null;
    }

    /**
     * 从上下文读取用户 ID，支持 Long / Number / String 三种形态，解析失败返回 null
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
     * 从上下文读取会话 ID，读取不到时返回占位符 "-"
     */
    private String resolveConversationId(ChatClientRequest request) {
        Object value = request.context().get(CONVERSATION_ID_KEY);
        return value != null ? value.toString() : "-";
    }

    @Override
    public String getName() {
        return "TokenUsageAdvisor";
    }

    @Override
    public int getOrder() {
        // order 越小越靠外层（最高优先级最先处理请求、最后处理响应），
        // 置于所有内置 Advisor（默认 order=0）之前，确保拿到经过完整调用链后的最终响应
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
