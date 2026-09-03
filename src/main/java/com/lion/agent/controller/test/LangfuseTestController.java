package com.lion.agent.controller.test;

import com.lion.agent.common.Result;
import com.lion.agent.utils.LangfuseIngestClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Langfuse 原生上报测试接口：快速验证 LangfuseIngestClient 的上报链路。
 * <p>
 * 测试方法：
 * <ul>
 *   <li>完整链路：GET /api/test/langfuse/demo —— 模拟一次问答：建 Trace → Generation(开始/结束带 token 用量) → 打分</li>
 *   <li>单独打分：GET /api/test/langfuse/score?traceId=... —— 给指定 Trace 补一条评分</li>
 *   <li>单独建 Trace：GET /api/test/langfuse/trace —— 建一条不含 LLM 调用的独立 Trace</li>
 * </ul>
 * 验证：调用后到 Langfuse 控制台 Project → Traces 中查看最新一条数据是否完整。
 */
@Tag(name = "Langfuse 上报测试", description = "调用 LangfuseIngestClient 验证原生摄取 API 链路（测试用）")
@RestController
@RequestMapping("/api/test/langfuse")
@RequiredArgsConstructor
public class LangfuseTestController {

    private final LangfuseIngestClient langfuseIngestClient;

    /**
     * 完整链路演示：模拟一次问答并全量上报（Trace + Generation + Score）。
     */
    @Operation(summary = "Langfuse 完整上报演示",
            description = "模拟一次问答链路：创建 Trace → 记录 LLM 调用（输入/输出/token 用量）→ 打满意度分，随后立即冲刷缓冲")
    @GetMapping("/demo")
    public Result<Map<String, Object>> demo(
            @RequestParam(defaultValue = "什么是 RAG？") String question,
            @RequestParam(defaultValue = "4.5") double scoreValue) {

        String traceId = UUID.randomUUID().toString();
        String generationId = UUID.randomUUID().toString();

        // 1. 建 Trace（作为本次问答的归属容器）
        langfuseIngestClient.createTrace(traceId, "qa-demo",
                Map.of("question", question), Map.of("channel", "test", "user", "demo-user"));

        // 2. Generation 开始：记录 prompt 与模型参数
        langfuseIngestClient.generationStart(traceId, generationId, "qa-llm-call", "qwen-plus",
                question, Map.of("temperature", 0.1, "top_p", 0.9));

        // 3. 模拟 LLM 返回后，Generation 结束：补输出与 token 用量
        String answer = "RAG 即检索增强生成，先检索相关知识片段，再交给大模型生成回答，可减少幻觉。";
        langfuseIngestClient.generationEnd(generationId, traceId, answer,
                Map.of("input", question.length(), "output", answer.length(), "total", question.length() + answer.length()));

        // 4. 给该 Trace 打满意度分
        langfuseIngestClient.scoreTrace(traceId, "answer-satisfaction", scoreValue, "自动化测试打分");

        // 5. 请求收尾：立即冲刷缓冲，保证数据尽快可见（生产代码通常依赖定时 flush）
        langfuseIngestClient.flush();

        // 返回信息供到 Langfuse 控制台核对
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("generationId", generationId);
        result.put("hint", "请在 Langfuse 控制台 Project → Traces 搜索该 traceId 查看");
        return Result.success(result);
    }

    /**
     * 单独打分：给指定 Trace 补一条评分（不新建 Trace/Generation）。
     */
    @Operation(summary = "单独打分",
            description = "给指定 traceId 补一条 score-create 事件（若该 Trace 由 OTel 自动上报也能关联）")
    @GetMapping("/score")
    public Result<Map<String, Object>> score(
            @RequestParam String traceId,
            @RequestParam(defaultValue = "relevance") String name,
            @RequestParam(defaultValue = "5") double value,
            @RequestParam(required = false) String comment) {

        langfuseIngestClient.scoreTrace(traceId, name, value, comment);
        langfuseIngestClient.flush();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("scoreName", name);
        result.put("scoreValue", value);
        result.put("hint", "请在 Langfuse 控制台查看该 Trace 的 Scores 标签页");
        return Result.success(result);
    }

    /**
     * 单独建 Trace：上报一条链路外的自定义 Trace（不包含 LLM 调用）。
     */
    @Operation(summary = "单独建 Trace",
            description = "上报一条 trace-create 事件，可验证 Trace 容器与 metadata 是否正确入库")
    @GetMapping("/trace")
    public Result<Map<String, Object>> createTrace(
            @RequestParam(defaultValue = "custom-trace") String name,
            @RequestParam(required = false) String input) {

        String traceId = UUID.randomUUID().toString();
        langfuseIngestClient.createTrace(traceId, name, input, Map.of("source", "LangfuseTestController"));
        langfuseIngestClient.flush();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("hint", "请在 Langfuse 控制台 Project → Traces 搜索该 traceId 查看");
        return Result.success(result);
    }
}
