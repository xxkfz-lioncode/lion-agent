package com.lion.agent.service.retriever;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.lion.agent.config.PromptConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 问题改写召回器：LLM 将原始问题改写成多种等价表达（默认 3 种），
 * 每种表达分别做向量召回，再按文档 ID 去重合并，覆盖单一表达无法命中的语义盲区。
 *
 * <p>改写失败（LLM 异常/返回不可解析）时降级为原问题单路召回；
 * 单条表达召回失败不影响其他表达。任何故障都不抛出，保证多路检索整体可用。</p>
 */
@Slf4j
@Component
public class QueryRewriteRetriever implements Retriever {

    /** 改写表达数量 */
    private static final int REWRITE_COUNT = 3;

    /** 匹配 ```json ... ``` 代码块 */
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");

    private final VectorStore vectorStore;
    private final ChatModel chatModel;
    private final PromptConfig promptConfig;

    public QueryRewriteRetriever(VectorStore vectorStore, ChatModel chatModel, PromptConfig promptConfig) {
        this.vectorStore = vectorStore;
        this.chatModel = chatModel;
        this.promptConfig = promptConfig;
    }

    @Override
    public List<Document> retrieve(String query, int topK, String filterExpression) {
        // 1. 改写为多种表达（失败降级为原问题单路）
        List<String> rewrittenQueries = rewriteQueries(query);
        if (rewrittenQueries.isEmpty()) {
            rewrittenQueries = List.of(query);
        }

        // 2. 每种表达分别召回，按文档 ID 去重合并（保序：先命中的靠前）
        Map<String, Document> uniqueDocs = new LinkedHashMap<>();
        for (String rewritten : rewrittenQueries) {
            try {
                List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                        .query(rewritten)
                        .topK(topK)
                        .filterExpression(filterExpression)
                        .build());
                for (Document doc : docs) {
                    uniqueDocs.putIfAbsent(doc.getId(), doc);
                }
            } catch (Exception e) {
                log.warn("[Retrieval] 改写表达召回失败 query={}: {}", truncate(rewritten, 30), e.getMessage());
            }
        }
        return List.copyOf(uniqueDocs.values());
    }

    @Override
    public String name() {
        return "问题改写召回";
    }

    /**
     * 调用 LLM 将问题改写为 {@link #REWRITE_COUNT} 种等价表达；
     * 返回不可解析时回退为空列表（由调用方降级为原问题）
     */
    private List<String> rewriteQueries(String query) {
        try {
            String prompt = promptConfig.renderKbRewriteMulti(query, REWRITE_COUNT);
            String response = chatModel.call(new Prompt(prompt)).getResult().getOutput().getText();
            if (response == null || response.isBlank()) {
                return List.of();
            }
            List<String> rewritten = parseRewriteResult(response);
            if (rewritten.isEmpty()) {
                return List.of();
            }
            // 去掉与原问题重复的表达，避免重复检索
            List<String> distinct = new ArrayList<>(rewritten.size());
            for (String rq : rewritten) {
                if (!rq.equalsIgnoreCase(query.trim())) {
                    distinct.add(rq);
                }
            }
            return distinct;
        } catch (Exception e) {
            log.warn("[Retrieval] 问题改写失败，降级为原问题召回: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析改写结果：优先取 JSON 数组，其次按行拆分
     */
    private List<String> parseRewriteResult(String response) {
        String cleaned = extractJson(response);
        try {
            JSONArray array = JSONUtil.parseArray(cleaned);
            List<String> result = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                String item = array.getStr(i);
                if (item != null && !item.isBlank()) {
                    result.add(item.trim());
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        } catch (Exception e) {
            log.warn("[Retrieval] 改写结果 JSON 解析失败，尝试按行解析: {}", e.getMessage());
        }
        List<String> lines = new ArrayList<>();
        for (String line : response.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("```") || trimmed.startsWith("[")) {
                continue;
            }
            trimmed = trimmed.replaceAll("^['\"\\d\\.\\-\\s]+", "").replaceAll("[,，\\]\"]$", "").trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    /** 从 LLM 输出中提取 JSON 片段（优先代码块，其次中括号包裹部分） */
    private String extractJson(String response) {
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return response;
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
