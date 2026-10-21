package com.lion.agent.service;

import com.lion.agent.config.PromptConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 长期记忆抽取器
 *
 * <p>用干净的 {@link ChatClient}（不挂任何 Advisor，避免递归触发调用链）从「用户消息 + AI 回复」
 * 中抽取用户主动陈述的持久性事实与偏好，经 Spring AI 原生结构化输出直接映射为记忆条目，
 * 无需手动解析 JSON。抽取失败返回空列表，不抛出异常。</p>
 * <p>{@link #merge} 负责把已有画像与新抽取条目整合为最新画像（语义去重、矛盾以新陈述为准），
 * 整合失败返回空列表，由调用方回退字符串级合并。</p>
 * <p>提示词模板由 {@link PromptConfig} 统一维护（{@code prompts/memory-extract.st}、
 * {@code prompts/memory-merge.st}），输出结构由 {@link MemoryExtractResult} 的 JSON Schema 约束。</p>
 */
@Slf4j
@Component
public class MemoryExtractor {

    private final ChatClient chatClient;
    private final PromptConfig promptConfig;

    public MemoryExtractor(ChatModel chatModel, PromptConfig promptConfig) {
        this.chatClient = ChatClient.create(chatModel);
        this.promptConfig = promptConfig;
    }

    /**
     * 抽取长期记忆
     *
     * @param userContent       用户消息（必填）
     * @param assistantContent  AI 回复（可为空）
     * @return 抽取到的记忆条目，失败或无有效记忆时返回空列表
     */
    public List<MemoryService.MemoryItem> extract(String userContent, String assistantContent) {
        if (!StringUtils.hasText(userContent)) {
            return List.of();
        }
        String prompt = promptConfig.renderMemoryExtract(userContent, assistantContent);
        try {
            List<MemoryService.MemoryItem> items = normalize(callStructured(prompt));
            if (items.isEmpty()) {
                log.warn("[Memory] 本轮对话无可抽取的长期记忆");
            } else {
                log.info("[Memory] 抽取到 {} 条长期记忆", items.size());
            }
            return items;
        } catch (Exception e) {
            log.warn("[Memory] 抽取调用失败，返回空：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 整合已有画像与新抽取记忆：语义去重、矛盾以新陈述为准、保留不冲突的旧记忆
     *
     * @param existingContent 已有画像内容（可为空）
     * @param newItems        本轮新抽取的记忆条目
     * @return 整合后的记忆条目（已包含旧记忆中需保留的部分），失败时返回空列表
     */
    public List<MemoryService.MemoryItem> merge(String existingContent, List<MemoryService.MemoryItem> newItems) {
        if (newItems == null || newItems.isEmpty()) {
            return List.of();
        }
        String prompt = promptConfig.renderMemoryMerge(existingContent, formatItems(newItems));
        try {
            return normalize(callStructured(prompt));
        } catch (Exception e) {
            log.warn("[Memory] 记忆整合调用失败，返回空：{}", e.getMessage());
            return List.of();
        }
    }

    private MemoryExtractResult callStructured(String prompt) {
        return chatClient.prompt()
                .user(prompt)
                .call()
                .entity(MemoryExtractResult.class, ChatClient.EntityParamSpec::useProviderStructuredOutput);
    }

    /**
     * 过滤空内容条目，importance 收敛到 1-5
     */
    private List<MemoryService.MemoryItem> normalize(MemoryExtractResult result) {
        if (result == null || result.items() == null) {
            return List.of();
        }
        return result.items().stream()
                .filter(item -> item != null && StringUtils.hasText(item.content()))
                .map(item -> new MemoryService.MemoryItem(item.content().trim(),
                        Math.max(1, Math.min(5, item.importance()))))
                .toList();
    }

    private String formatItems(List<MemoryService.MemoryItem> items) {
        StringBuilder sb = new StringBuilder();
        for (MemoryService.MemoryItem item : items) {
            sb.append("- ").append(item.content()).append("（重要性 ").append(item.importance()).append("）\n");
        }
        return sb.toString();
    }

    /**
     * 结构化输出包装：OpenAI 兼容接口不支持顶层 JSON 数组，需用 Record 把 List 包起来
     */
    public record MemoryExtractResult(List<MemoryService.MemoryItem> items) {
    }
}
