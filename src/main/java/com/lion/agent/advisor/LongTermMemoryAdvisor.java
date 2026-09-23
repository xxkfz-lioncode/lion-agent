package com.lion.agent.advisor;

import com.lion.agent.common.constants.AdvisorConstants;
import com.lion.agent.config.PromptConfig;
import com.lion.agent.service.MemoryService;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户长期记忆 Advisor（Spring AI 2.0，跨会话记忆）
 * <p>
 * 以 {@code chain.nextCall(...)} / {@code chain.nextStream(...)} 为分界线，本 Advisor 承担两件事：
 * <ol>
 *   <li><b>请求阶段（分界线之前）</b>：取 context 的 {@code USER_ID_KEY} 与原始提问，
 *       在 Milvus 检索该用户的长期记忆（事实/偏好），命中则作为 SystemMessage 注入 prompt
 *       （放在系统提示词之后、会话摘要之前），实现"新会话也能记得老用户"。</li>
 *   <li><b>响应阶段（分界线之后）</b>：拿到本轮「用户原话 + AI 回复」提交异步抽取，
 *       由 {@link MemoryService} 落库画像（原来散落在 ChatServiceImpl 的两处调用已收敛到这里）。</li>
 * </ol>
 * <p>
 * 抽取的三个前置约束（缺一不可，否则会误抽）：
 * <ul>
 *   <li>userId 非空 —— 摘要压缩等内部调用不带 user_id，必须跳过；</li>
 *   <li>用 {@code RAW_USER_MESSAGE_KEY} 传入的用户原话 —— 知识库链路送入模型的是渲染后的
 *       KB prompt（含检索片段），拿它抽取会把知识库内容写进用户画像；</li>
 *   <li>本 Advisor 的 order 必须小于工具 Advisor（见 AiConfig，当前 MIN+220）——
 *       否则会被圈进工具循环，每一轮工具调用都抽一次。</li>
 * </ul>
 * <p>
 * 注意：本 Advisor 位于敏感词、语义缓存之内，二者短路时本 Advisor 不执行，
 * 因此「拒答」「缓存命中」不会触发抽取（语义上更合理，也省一次抽取开销）。
 * 检索与抽取失败均仅告警，绝不阻断主调用链。
 * 提示词模板（查询改写 / 记忆注入）由 {@link PromptConfig} 统一维护。
 */
@Slf4j
public class LongTermMemoryAdvisor implements CallAdvisor, StreamAdvisor {
    private final MemoryService memoryService;
    private final ChatModel chatModel;
    private final PromptConfig promptConfig;
    private final int topK;   // 注入的记忆条数
    private final int order;  // 调用链顺序（order 越小越靠外层）

    public LongTermMemoryAdvisor(MemoryService memoryService, ChatModel chatModel,
                                 PromptConfig promptConfig, int topK, int order) {
        this.memoryService = memoryService;
        this.chatModel = chatModel;
        this.promptConfig = promptConfig;
        this.topK = topK;
        this.order = order;
    }

    @NotNull
    @Override
    public String getName() {
        return "LongTermMemoryAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    @NotNull
    @Override
    public ChatClientResponse adviseCall(@NotNull ChatClientRequest advisedRequest, CallAdvisorChain chain) {
        MemoryContext ctx = prepare(advisedRequest);
        ChatClientRequest updated = injectLongTermMemory(advisedRequest, ctx);

        // ===== 分界线：以上请求阶段（注入记忆），以下响应阶段（抽取记忆）=====
        ChatClientResponse response = chain.nextCall(updated);
        extractAsync(ctx, textOf(response));
        return response;
    }

    @NotNull
    @Override
    public Flux<ChatClientResponse> adviseStream(@NotNull ChatClientRequest advisedRequest, StreamAdvisorChain chain) {
        MemoryContext ctx = prepare(advisedRequest);
        ChatClientRequest updated = injectLongTermMemory(advisedRequest, ctx);
        if (!ctx.extractable()) {
            return chain.nextStream(updated);
        }

        //
        StringBuilder acc = new StringBuilder();
        return chain.nextStream(updated)
                .doOnNext(r -> acc.append(textOf(r)))
                .doOnComplete(() -> extractAsync(ctx, acc.toString()))
                .doOnError(e -> log.warn("[Memory] 流式链路异常，跳过本轮记忆抽取 error={}", e.getMessage()));
    }

    /**
     * 本轮上下文：用户 ID、会话 ID、用户原话
     */
    private record MemoryContext(Long userId, Long conversationId, String rawQuery) {

        /** 是否具备抽取条件：userId 与用户原话缺一不可 */
        boolean extractable() {
            return userId != null && StringUtils.hasText(rawQuery);
        }
    }

    private MemoryContext prepare(ChatClientRequest request) {
        return new MemoryContext(resolveUserId(request), resolveConversationId(request), resolveRawQuery(request));
    }

    /**
     * 响应阶段：提交异步抽取（@Async memoryExecutor，提交即返回，不阻塞主链路）
     */
    private void extractAsync(MemoryContext ctx, String assistantContent) {
        if (!ctx.extractable() || !StringUtils.hasText(assistantContent)) {
            return;
        }
        try {
            memoryService.extractAndStoreAsync(ctx.userId(), ctx.conversationId(), ctx.rawQuery(), assistantContent);
            log.debug("[Memory] 已提交本轮记忆抽取 userId={} conversationId={}", ctx.userId(), ctx.conversationId());
        } catch (Exception e) {
            log.warn("[Memory] 记忆抽取提交失败 userId={} error={}", ctx.userId(), e.getMessage());
        }
    }

    /**
     * 检索并注入用户长期记忆（SystemMessage 插到第一个用户消息之前）
     */
    private ChatClientRequest injectLongTermMemory(ChatClientRequest request, MemoryContext ctx) {
        Long userId = ctx.userId();
        String rawQuery = ctx.rawQuery();
        if (userId == null || !StringUtils.hasText(rawQuery)) {
            return request;
        }
        try {
            String query = rewriteQuery(rawQuery);
            List<MemoryService.MemoryItem> memories = memoryService.search(userId, query, topK);
            if (memories.isEmpty()) {
                return request;
            }
            List<String> contents = new ArrayList<>();
            for (MemoryService.MemoryItem m : memories) {
                contents.add(m.content());
            }
            SystemMessage memoryMessage = new SystemMessage(promptConfig.renderMemoryInjection(contents));

            // 插到第一个用户消息之前（保持 system 提示词在最前）
            List<Message> instructions = request.prompt().getInstructions();
            List<Message> merged = new ArrayList<>(instructions.size() + 1);
            boolean injected = false;
            for (Message m : instructions) {
                if (!injected && MessageType.USER.equals(m.getMessageType())) {
                    merged.add(memoryMessage);
                    injected = true;
                }
                merged.add(m);
            }
            if (!injected) {
                merged.add(0, memoryMessage);
            }

            Prompt newPrompt = new Prompt(merged, request.prompt().getOptions());
            return request.mutate().prompt(newPrompt).build();
        } catch (Exception e) {
            log.warn("[Memory] 长期记忆注入失败，跳过 userId={} error={}", userId, e.getMessage());
            return request;
        }
    }

    private Long resolveUserId(ChatClientRequest request) {
        return toLong(request.context().get(AdvisorConstants.USER_ID_KEY));
    }

    private Long resolveConversationId(ChatClientRequest request) {
        return toLong(request.context().get(AdvisorConstants.CONVERSATION_ID_KEY));
    }

    /**
     * 取用户原话：优先用业务层显式传入的 {@code RAW_USER_MESSAGE_KEY}，
     * 缺失时降级取最后一条 USER 消息（普通对话二者一致）。
     */
    private String resolveRawQuery(ChatClientRequest request) {
        Object raw = request.context().get(AdvisorConstants.RAW_USER_MESSAGE_KEY);
        if (raw != null && StringUtils.hasText(raw.toString())) {
            return raw.toString();
        }
        return extractUserQuery(request);
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 取本轮助手回复文本（流式场景由调用方累加各分片）
     */
    private String textOf(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) {
            return "";
        }
        Generation generation = response.chatResponse().getResult();
        if (generation == null || generation.getOutput() == null || generation.getOutput().getText() == null) {
            return "";
        }
        return generation.getOutput().getText();
    }

    private String extractUserQuery(ChatClientRequest request) {
        List<Message> instructions = request.prompt().getInstructions();
        for (int i = instructions.size() - 1; i >= 0; i--) {
            Message msg = instructions.get(i);
            if (msg.getMessageType() == MessageType.USER) {
                return msg.getText();
            }
        }
        return null;
    }

    /**
     * 把口语化查询改写成更适合向量检索的关键词短句，提升长期记忆召回率。
     * 改写失败仅告警，返回原始查询降级。
     */
    private String rewriteQuery(String query) {
        if (chatModel == null) {
            return query;
        }
        try {
            Prompt prompt = new Prompt(promptConfig.renderMemoryRewrite(query));
            ChatResponse response = chatModel.call(prompt);
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return query;
            }
            String rewritten = response.getResult().getOutput().getText();
            rewritten = cleanRewrittenQuery(rewritten);
            if (StringUtils.hasText(rewritten)) {
                log.debug("[Memory] query rewritten: '{}' -> '{}'", query, rewritten);
                return rewritten;
            }
        } catch (Exception e) {
            log.warn("[Memory] query rewrite failed, fallback to raw query='{}' error={}", query, e.getMessage());
        }
        return query;
    }

    private String cleanRewrittenQuery(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String cleaned = text.replaceAll("```[a-zA-Z]*\\s*", "").replaceAll("```", "");
        cleaned = cleaned.replaceAll("^\\s*改写后[:：]?\\s*", "").trim();
        return cleaned;
    }
}
