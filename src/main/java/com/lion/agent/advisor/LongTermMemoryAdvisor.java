package com.lion.agent.advisor;

import com.lion.agent.common.constants.AdvisorConstants;
import com.lion.agent.config.PromptConfig;
import com.lion.agent.service.MemoryService;
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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户长期记忆 Advisor（Spring AI 2.0，跨会话记忆）
 * <p>
 * 职责：每次模型调用前，从 context 取 {@code USER_ID_KEY}（用户 ID），
 * 用当前用户消息在 Milvus 检索该用户的长期记忆（事实/偏好），命中则作为
 * SystemMessage 注入 prompt（放在系统提示词之后、会话摘要之前），实现"新会话也能记得老用户"。
 * <p>
 * 顺序：order 可配置，建议位于 QaCacheAdvisor 之外、ConversationSummaryAdvisor 之内
 * （如 200），检索失败仅告警，绝不阻断主调用链。
 * 提示词模板（查询改写 / 记忆注入）由 {@link PromptConfig} 统一维护。
 */
public class LongTermMemoryAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryAdvisor.class);

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
        ChatClientRequest updated = injectLongTermMemory(advisedRequest);
        return chain.nextCall(updated);
    }

    @NotNull
    @Override
    public Flux<ChatClientResponse> adviseStream(@NotNull ChatClientRequest advisedRequest, StreamAdvisorChain chain) {
        ChatClientRequest updated = injectLongTermMemory(advisedRequest);
        return chain.nextStream(updated);
    }

    /**
     * 检索并注入用户长期记忆（SystemMessage 插到第一个用户消息之前）
     */
    private ChatClientRequest injectLongTermMemory(ChatClientRequest request) {
        Long userId = resolveUserId(request);
        String rawQuery = extractUserQuery(request);
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
        Object value = request.context().get(AdvisorConstants.USER_ID_KEY);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String extractUserQuery(ChatClientRequest request) {
        for (Message m : request.prompt().getInstructions()) {
            if (MessageType.USER.equals(m.getMessageType())) {
                return m.getText();
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
