package com.lion.agent.controller;

import com.lion.agent.common.Result;
import com.lion.agent.utils.DashScopeRerankUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * DashScope Rerank 测试接口：快速验证重排链路（含熔断降级）。
 * <p>
 * 测试方法：GET /api/test/rerank?query=...&topN=...
 * 返回按模型相关性排序后的片段（附原始序号），可直观看到与输入顺序的差异。
 */
@Tag(name = "Rerank 测试", description = "调用 DashScope 文本排序模型验证 Rerank 链路（测试用）")
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestController {

    private final DashScopeRerankUtils dashScopeRerankUtils;

    @Operation(summary = "Rerank 重排演示",
            description = "使用内置示例文档调用 DashScope Rerank API，返回按相关性排序后的片段（附降级标记）")
    @GetMapping("/rerank")
    public Result<List<Map<String, String>>> rerank(
            @RequestParam(defaultValue = "什么是文本排序模型") String query,
            @RequestParam(defaultValue = "5") int topN) {

        // 模拟知识库检索召回的候选片段（与 query 相关性由高到低混排）
        List<Document> candidates = List.of(
                new Document("文本排序模型广泛用于搜索引擎和推荐系统中，它们根据文本相关性对候选文本进行排序"),
                new Document("量子计算是计算科学的一个前沿领域"),
                new Document("预训练语言模型的发展给文本排序模型带来了新的进展"),
                new Document("Rerank 是检索增强生成（RAG）中的关键环节，用于精排召回结果"),
                new Document("今天天气晴朗，适合户外运动"));

        List<Document> reranked = dashScopeRerankUtils.rerank(query, candidates, topN);

        List<Map<String, String>> result = reranked.stream()
                .map(doc -> Map.of(
                        "text", doc.getText(),
                        "id", doc.getId()))
                .toList();
        return Result.success(result);
    }
}
