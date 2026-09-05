package com.lion.agent.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lion.agent.config.PromptConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆抽取器
 *
 * <p>用干净的 {@link ChatModel}（不含任何 Advisor，避免递归触发调用链）从「用户消息 + AI 回复」
 * 中抽取用户主动陈述的持久性事实与偏好，输出结构化记忆条目。抽取失败返回空列表，不抛出异常。</p>
 * <p>抽取提示词模板由 {@link PromptConfig} 统一维护（{@code prompts/memory-extract.st}）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryExtractor {

    private final ChatModel chatModel;
    private final PromptConfig promptConfig;

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
            ChatResponse response = chatModel.call(new Prompt(prompt));
            String text = response == null || response.getResult() == null
                    ? null : response.getResult().getOutput().getText();
            return parse(text);
        } catch (Exception e) {
            log.warn("[Memory] 抽取调用失败，返回空：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析 LLM 返回的 JSON 数组，容错去除 markdown 代码块包裹
     */
    private List<MemoryService.MemoryItem> parse(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String json = text.trim();
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
        }
        try {
            JSONArray arr = JSONUtil.parseArray(json);
            List<MemoryService.MemoryItem> items = new ArrayList<>();
            for (Object obj : arr) {
                JSONObject node = (JSONObject) obj;
                String content = node.getStr("content", "").trim();
                int importance = node.getInt("importance", 3);
                if (!StringUtils.hasText(content)) {
                    continue;
                }
                importance = Math.max(1, Math.min(5, importance));
                items.add(new MemoryService.MemoryItem(content, importance));
            }
            if (items.isEmpty()) {
                log.warn("[Memory] 本轮对话无可抽取的长期记忆");
            } else {
                log.info("[Memory] 抽取到 {} 条长期记忆", items.size());
            }
            return items;
        } catch (Exception e) {
            log.warn("[Memory] 抽取结果解析失败，返回空：{}", truncate(text, 120));
            return List.of();
        }
    }

    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, max) + "...";
    }
}
