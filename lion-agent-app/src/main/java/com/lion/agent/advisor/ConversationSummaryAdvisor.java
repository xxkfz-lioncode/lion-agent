package com.lion.agent.advisor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lion.agent.config.PromptConfig;
import com.lion.agent.pojo.entity.ChatMessage;
import com.lion.agent.pojo.entity.ConversationSummary;
import com.lion.agent.mapper.ChatMessageMapper;
import com.lion.agent.mapper.ConversationSummaryMapper;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.lion.agent.common.constants.AdvisorConstants.CONVERSATION_ID_KEY;

/**
 * 会话记忆摘要压缩 Advisor（Spring AI 2.0）
 * <p>
 * 职责：只负责「长历史的压缩摘要」，基于业务表 {@code chat_message}：
 * <ol>
 *   <li>调用前按 conversationId 读取历史，仅对「上次摘要之后的新增消息」做增量压缩，
 *       最新摘要持久化到 {@code chat_conversation_summary} 表
 *       （version 递增、last_message_id 游标推进）；</li>
 *   <li>把摘要以 SystemMessage 注入 prompt，<b>不注入历史原文</b>——近期原文由
 *       {@code MessageChatMemoryAdvisor}（窗口记忆）负责。两者若都注入原文，同一段历史会在
 *       prompt 里出现两次：多轮对话出现整段重复、token 翻倍，且模型可能误以为用户重复提问；</li>
 *   <li>本次「用户问题 + 助手回复」由 ChatServiceImpl 落库到 chat_message，本 Advisor 不写记忆。</li>
 * </ol>
 * <p>
 * 注意：{@code summaryChatClient} 必须是不含本 Advisor 的干净 ChatClient，否则摘要请求会递归触发自身。
 */
@Slf4j
public class ConversationSummaryAdvisor implements CallAdvisor, StreamAdvisor {
    private final ChatClient summaryChatClient;
    private final ChatMessageMapper chatMessageMapper;
    private final ConversationSummaryMapper summaryMapper;
    private final PromptConfig promptConfig;
    private final int summaryThreshold;   // 触发摘要压缩的新增消息条数阈值
    private final int order;              // 调用链顺序（order 越小越靠外层）

    public ConversationSummaryAdvisor(ChatClient.Builder builder, ChatMessageMapper chatMessageMapper,
                                      ConversationSummaryMapper summaryMapper, int summaryThreshold,
                                      PromptConfig promptConfig) {
        this(builder, chatMessageMapper, summaryMapper, summaryThreshold, 100, promptConfig);
    }

    public ConversationSummaryAdvisor(ChatClient.Builder builder, ChatMessageMapper chatMessageMapper,
                                      ConversationSummaryMapper summaryMapper,
                                      int summaryThreshold, int order, PromptConfig promptConfig) {
        this.summaryChatClient = builder.build();
        this.chatMessageMapper = chatMessageMapper;
        this.summaryMapper = summaryMapper;
        this.promptConfig = promptConfig;
        this.summaryThreshold = summaryThreshold;
        this.order = order;
    }

    @NotNull
    @Override
    public String getName() {
        return "ConversationSummaryAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    @NotNull
    @Override
    public ChatClientResponse adviseCall(@NotNull ChatClientRequest advisedRequest, CallAdvisorChain chain) {
        String conversationId = resolveConversationId(advisedRequest);
        // 读取历史（按需增量压缩）并注入到 prompt，然后调用下游（大模型）
        ChatClientRequest updated = injectHistory(advisedRequest, conversationId);
        return chain.nextCall(updated);
    }

    @NotNull
    @Override
    public Flux<ChatClientResponse> adviseStream(@NotNull ChatClientRequest advisedRequest, StreamAdvisorChain chain) {
        String conversationId = resolveConversationId(advisedRequest);
        ChatClientRequest updated = injectHistory(advisedRequest, conversationId);
        return chain.nextStream(updated)
                .doOnError(e -> log.warn("[Summary] 流式调用异常 conversationId={} error={}", conversationId, e.getMessage()));
    }

    // ==================== 1. 历史读取 + 增量压缩 + 注入 ====================

    private ChatClientRequest injectHistory(ChatClientRequest request, String conversationId) {
        if (conversationId == null) {
            return request;
        }
        Long cid = toLong(conversationId);
        if (cid == null) {
            return request;
        }
        // 从业务表 chat_message 读取该会话全部 user/assistant 消息（按 id 升序）
        List<ChatMessage> historyRows = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, cid)
                        .in(ChatMessage::getRole, "user", "assistant")
                        .orderByAsc(ChatMessage::getId));
        if (historyRows.isEmpty()) {
            return request;
        }

        // 增量压缩：仅摘要游标之后的新增消息，返回最新摘要（未达阈值时为 null）
        String latestSummary = ensureSummary(cid, historyRows);
        if (latestSummary == null || latestSummary.isBlank()) {
            return request;
        }
        // 只注入摘要；历史原文由 MessageChatMemoryAdvisor 注入，避免同一段历史在 prompt 里出现两次
        List<Message> finalHistory = List.of(new SystemMessage("[对话历史摘要]\n" + latestSummary));

        // 把历史插到第一个用户消息之前（保持 system 提示词在最前）
        List<Message> instructions = request.prompt().getInstructions();
        List<Message> merged = new ArrayList<>(instructions.size() + finalHistory.size());
        boolean injected = false;
        for (Message m : instructions) {
            if (!injected && MessageType.USER.equals(m.getMessageType())) {
                merged.addAll(finalHistory);
                injected = true;
            }
            merged.add(m);
        }
        if (!injected) {
            merged.addAll(0, finalHistory);
        }

        Prompt newPrompt = new Prompt(merged, request.prompt().getOptions());
        return request.mutate().prompt(newPrompt).build();
    }

    /**
     * 增量摘要：只对 {@code last_message_id} 游标之后的新增消息做压缩。
     * 新增条数达到阈值时，基于「旧摘要 + 新增消息」重新生成摘要并落库（version + 1，游标前移）；
     * 否则直接返回最新摘要（可能为 null）。
     */
    private String ensureSummary(Long cid, List<ChatMessage> historyRows) {
        ConversationSummary latest = summaryMapper.selectOne(
                new LambdaQueryWrapper<ConversationSummary>()
                        .eq(ConversationSummary::getConversationId, cid)
                        .orderByDesc(ConversationSummary::getVersion)
                        .last("LIMIT 1"));
        long cursor = latest == null || latest.getLastMessageId() == null ? 0L : latest.getLastMessageId();

        List<ChatMessage> newRows = historyRows.stream()
                .filter(r -> r.getId() > cursor)
                .collect(Collectors.toList());
        if (newRows.size() < summaryThreshold) {
            return latest == null ? null : latest.getSummary();
        }

        String oldSummaryText = latest == null ? null : latest.getSummary();
        String historyText = newRows.stream()
                .map(r -> r.getRole() + ": " + r.getContent())
                .collect(Collectors.joining("\n"));
        try {
            // 摘要压缩/合并模板由 PromptConfig 统一维护（prompts/summary-compress.st / summary-merge.st）
            String promptText = oldSummaryText != null && !oldSummaryText.isBlank()
                    ? promptConfig.renderSummaryMerge(oldSummaryText, historyText)
                    : promptConfig.renderSummaryCompress(historyText);
            String summary = summaryChatClient.prompt()
                    .user(promptText)
                    .call()
                    .content();
            int nextVersion = latest == null || latest.getVersion() == null ? 1 : latest.getVersion() + 1;
            ConversationSummary record = new ConversationSummary();
            record.setConversationId(cid);
            record.setSummary(summary);
            record.setMessageCount(newRows.size());
            record.setLastMessageId(newRows.get(newRows.size() - 1).getId());
            record.setVersion(nextVersion);
            summaryMapper.insert(record);
            log.info("[Summary] 增量压缩完成 conversationId={} version={} 新增{}条（游标{}）",
                    cid, nextVersion, newRows.size(), record.getLastMessageId());
            return summary;
        } catch (Exception e) {
            log.warn("[Summary] 压缩失败 conversationId={} error={}", cid, e.getMessage());
            return latest == null ? null : latest.getSummary();
        }
    }

    // ==================== 工具方法 ====================

    private Long toLong(String conversationId) {
        try {
            return Long.parseLong(conversationId.trim());
        } catch (NumberFormatException e) {
            log.warn("[Summary] conversationId 非数字：{}", conversationId);
            return null;
        }
    }

    private String resolveConversationId(ChatClientRequest request) {
        Object value = request.context().get(CONVERSATION_ID_KEY);
        return value != null ? value.toString() : null;
    }
}
