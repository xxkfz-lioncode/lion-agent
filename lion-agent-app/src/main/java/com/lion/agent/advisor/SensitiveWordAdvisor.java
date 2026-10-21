package com.lion.agent.advisor;

import com.lion.agent.service.SensitiveWordService;
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

import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * 输入侧敏感词拦截器（词库由页面维护，动态生效）
 *
 * <p>与官方 {@code SafeGuardAdvisor} 的区别：官方版本的敏感词在构造时以 {@code List} 传入，
 * 后续无法变更（字段 final），改词必须改代码重启；本 Advisor 每次调用前从
 * {@link SensitiveWordService} 读取当前启用词表（服务内带缓存），页面改词即时生效。</p>
 *
 * <p>命中时短路：不调用大模型，直接伪造一条「助手消息」返回拒绝话术，
 * 与 {@link QaCacheAdvisor} 缓存命中的短路写法一致，对上层调用方完全透明。</p>
 *
 * <p>检测范围由 {@code lion.sensitive.check-scope} 控制：
 * <ul>
 *   <li>{@code user}（默认）：只检测本轮用户输入——避免系统提示词、历史消息、
 *       知识库检索内容里恰好含敏感词导致全量误杀；</li>
 *   <li>{@code all}：检测整个 prompt（与官方 SafeGuardAdvisor 行为一致）。</li>
 * </ul>
 */
@Slf4j
public class SensitiveWordAdvisor implements CallAdvisor, StreamAdvisor {

    /** 检测范围：整个 prompt（系统提示词 + 历史 + 知识库 + 用户输入），与官方 SafeGuardAdvisor 行为一致 */
    public static final String SCOPE_ALL = "all";

    /** 检测范围：只检测本轮最后一条用户输入，避免系统提示词/历史/知识库内容误杀 */
    public static final String SCOPE_USER = "user";

    private final SensitiveWordService sensitiveWordService;

    /** 命中后返回给用户的拒绝话术 */
    private final String rejectMessage;

    /** 检测范围：{@code all} = 整个 prompt；其余（含 {@code user}、空值、非法值）= 只检测本轮用户输入 */
    private final String checkScope;

    /** 是否检测整个 prompt（false = 只检测本轮用户输入） */
    private final boolean checkAllContent;

    /**
     * 调用链顺序（order 越小越靠外层）。默认 {@link Ordered#HIGHEST_PRECEDENCE} + 100：
     * 早于记忆注入与工具调用循环，命中时直接短路，不消耗 token、不写入会话记忆。
     */
    private final int order;

    public SensitiveWordAdvisor(SensitiveWordService sensitiveWordService, String rejectMessage) {
        this(sensitiveWordService, rejectMessage, SCOPE_USER, Ordered.HIGHEST_PRECEDENCE + 100);
    }

    /**
     * @param checkScope 检测范围：{@code all} 检测整个 prompt；{@code user}（默认）只检测本轮用户输入。
     *                   取值非法或为空时按 {@code user} 处理（更保守，避免系统提示词/知识库内容误杀）。
     */
    public SensitiveWordAdvisor(SensitiveWordService sensitiveWordService, String rejectMessage,
                                String checkScope, int order) {
        this.sensitiveWordService = sensitiveWordService;
        this.rejectMessage = StringUtils.hasText(rejectMessage)
                ? rejectMessage
                : "您的问题包含敏感内容，我无法回答。";
        this.checkScope = StringUtils.hasText(checkScope) ? checkScope.trim().toLowerCase(Locale.ROOT) : SCOPE_USER;
        this.checkAllContent = SCOPE_ALL.equals(this.checkScope);
        this.order = order;
        if (!SCOPE_ALL.equals(this.checkScope) && !SCOPE_USER.equals(this.checkScope)) {
            log.warn("[SensitiveWord] 未知的检测范围 checkScope={}，已按 {} 处理（只检测本轮用户输入）",
                    checkScope, SCOPE_USER);
        }
    }

    @NotNull
    @Override
    public String getName() {
        return "SensitiveWordAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    @NotNull
    @Override
    public ChatClientResponse adviseCall(@NotNull ChatClientRequest request, @NotNull CallAdvisorChain chain) {
        List<String> hits = matchHits(request);
        if (!hits.isEmpty()) {
            log.error("[SensitiveWord] 命中敏感词 {}，已拦截本次提问", hits);
            return buildRejectResponse(request);
        }
        return chain.nextCall(request);
    }

    @NotNull
    @Override
    public Flux<ChatClientResponse> adviseStream(@NotNull ChatClientRequest request,
                                                 @NotNull StreamAdvisorChain chain) {
        List<String> hits = matchHits(request);
        if (!hits.isEmpty()) {
            log.error("[SensitiveWord] 命中敏感词 {}，已拦截本次提问（流式）", hits);
            return Flux.just(buildRejectResponse(request));
        }
        return chain.nextStream(request);
    }

    /**
     * 按配置的检测范围取待检测文本并匹配词表
     */
    private List<String> matchHits(ChatClientRequest request) {
        String text = resolveText(request);
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        return sensitiveWordService.match(text);
    }

    private String resolveText(ChatClientRequest request) {
        if (checkAllContent) {
            return request.prompt().getContents();
        }
        // 只检测本轮用户输入：从后往前找最后一条 USER 消息
        List<Message> instructions = request.prompt().getInstructions();
        if (instructions == null) {
            return null;
        }
        for (int i = instructions.size() - 1; i >= 0; i--) {
            Message message = instructions.get(i);
            if (MessageType.USER.equals(message.getMessageType()) && StringUtils.hasText(message.getText())) {
                return message.getText();
            }
        }
        return null;
    }

    /**
     * 构造拦截响应：伪装成模型回答，保证上层无需感知拦截发生
     */
    private ChatClientResponse buildRejectResponse(ChatClientRequest request) {
        Generation generation = new Generation(new AssistantMessage(rejectMessage));
        ChatResponse chatResponse = new ChatResponse(List.of(generation), ChatResponseMetadata.builder().build());
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(new HashMap<>(request.context()))
                .build();
    }
}
