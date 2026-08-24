package com.lion.agent.utils;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lion.agent.exception.BusinessException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;

/**
 * DashScope Rerank API 客户端工具类。
 * <p>
 * 对接阿里云百炼的文本排序模型（如 qwen3-vl-rerank），对知识库检索召回的候选片段
 * 按与 query 的相关性进行重排序，返回 topN 条结果。
 * <p>
 * 失败/降级时自动回退到原候选顺序的前 topN 条，避免阻塞主问答链路。
 * 请求体构建与响应解析统一使用 Hutool JSON 工具（JSONUtil），不依赖 Jackson。
 * https://www.qianwenai.com/models/qwen3-vl-rerank
 * @see <a href="https://help.aliyun.com/zh/dashscope/developer-reference/api-rerank">DashScope Rerank API</a>
 */
@Slf4j
@Component
public class DashScopeRerankUtils {

    private static final String BASE_URL = "https://dashscope.aliyuncs.com";

    @Value("${lion.dashscope.api-key:${DASHSCOPE_API_KEY:${QWEN_API_KEY:}}}")
    private String apiKey;

    @Value("${lion.dashscope.rerank.model:qwen3-vl-rerank}")
    private String model;

    @Value("${lion.dashscope.rerank.url:/api/v1/services/rerank/text-rerank/text-rerank}")
    private String rerankPath;

    private final RestClient restClient;

    public DashScopeRerankUtils(RestClient.Builder builder) {
        this.restClient = builder.baseUrl(BASE_URL).build();
    }

    /**
     * 调用 DashScope Rerank API 对候选文档按 query 相关性重排序。
     *
     * @param query      查询问题
     * @param candidates 候选文档片段（已按 RRF 等方式粗排）
     * @param topN       最终返回前 N 条
     * @return 重排后的文档列表；API 失败/降级时返回原候选前 topN 条
     */
    @CircuitBreaker(name = "dashScopeRerank", fallbackMethod = "rerankFallback")
    public List<Document> rerank(String query, List<Document> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }
        if (candidates.size() <= topN) {
            return candidates;
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[DashScopeRerank] 未配置 lion.dashscope.api-key 或环境变量 DASHSCOPE_API_KEY/QWEN_API_KEY，回退到原候选顺序");
            return candidates.subList(0, topN);
        }

        List<String> documents = candidates.stream()
                .map(Document::getText)
                .toList();

        // 请求体用 Hutool JSON 构建（字段名与 DashScope API 一致）
        JSONObject requestBody = JSONUtil.createObj()
                .set("model", model)
                .set("input", JSONUtil.createObj()
                        .set("query", query)
                        .set("documents", documents))
                .set("parameters", JSONUtil.createObj()
                        .set("return_documents", true)
                        .set("top_n", topN));

        String body;
        try {
            body = restClient.post()
                    .uri(rerankPath)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(JSONUtil.toJsonStr(requestBody))
                    .retrieve()
                    .body(String.class);
        } catch (ResourceAccessException | RestClientResponseException e) {
            log.error("[DashScopeRerank] 调用 DashScope Rerank API 失败: {}", e.getMessage());
            throw new BusinessException("调用 DashScope Rerank API 失败：" + e.getMessage());
        }

        return parseAndReorder(candidates, body, topN);
    }

    /**
     * 熔断降级方法：API 调用异常或熔断器 OPEN 时返回原候选前 topN 条。
     * 签名与原方法一致（同入参 + 可选 Throwable）。
     */
    public List<Document> rerankFallback(String query, List<Document> candidates, int topN, Throwable throwable) {
        log.error("[DashScopeRerank] 触发熔断降级，原因: {}", throwable.getMessage());
        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }
        return candidates.subList(0, Math.min(topN, candidates.size()));
    }

    /**
     * 解析 DashScope Rerank 响应，按返回的 index 重排并取 topN。
     * <p>
     * 返回结果仅用于调整顺序，仍保留原始 {@link Document} 的文本与 metadata，避免丢失向量库中的来源信息。
     */
    private List<Document> parseAndReorder(List<Document> candidates, String body, int topN) {
        if (body == null || body.isBlank()) {
            log.warn("[DashScopeRerank] API 返回空响应，回退到原候选顺序");
            return candidates.subList(0, topN);
        }

        try {
            JSONObject root = JSONUtil.parseObj(body);
            JSONObject output = root.getJSONObject("output");
            if (output == null) {
                log.warn("[DashScopeRerank] API 响应缺少 output 字段，回退到原候选顺序");
                return candidates.subList(0, topN);
            }
            JSONArray results = output.getJSONArray("results");
            if (results == null || results.isEmpty()) {
                log.warn("[DashScopeRerank] API 响应无 results，回退到原候选顺序");
                return candidates.subList(0, topN);
            }

            List<Document> reranked = new ArrayList<>(results.size());
            for (Object item : results) {
                if (!(item instanceof JSONObject result)) {
                    continue;
                }
                int index = result.getInt("index", -1);
                if (index < 0 || index >= candidates.size()) {
                    log.warn("[DashScopeRerank] 返回 index 越界或无效: {}", index);
                    continue;
                }
                reranked.add(candidates.get(index));
                if (reranked.size() >= topN) {
                    break;
                }
            }

            if (reranked.isEmpty()) {
                log.warn("[DashScopeRerank] 无法解析任何有效结果，回退到原候选顺序");
                return candidates.subList(0, topN);
            }
            return reranked;
        } catch (Exception e) {
            log.error("[DashScopeRerank] 解析响应失败: {}", e.getMessage());
            throw new BusinessException("解析 DashScope Rerank 响应失败：" + e.getMessage());
        }
    }
}
