package com.lion.a2a.controller;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lion.a2a.service.RemoteAgentService;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Artifact;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.SendMessageResponse;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TextPart;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A2A 服务端的两个必需端点。
 *
 * <p>协议要点：
 * <ul>
 *   <li>发现：客户端请求 {@code baseUrl + /.well-known/agent-card.json} 拿名片，
 *       名片里的 {@code url} 决定后续 JSON-RPC 打向哪里</li>
 *   <li>调用：{@code POST /}，body 是 JSON-RPC 2.0，方法名 {@code message/send}；
 *       返回 {@code result} 是一个 {@link Task} 对象，答案放在 {@code artifacts[0].parts[0].text}</li>
 * </ul>
 *
 * <p>入参解析用 Hutool（请求结构松散，直接用 Map 语义读最省事）；
 * 出参一律用 A2A SDK 的类型构造（{@link AgentCard} / {@link Task} / {@link TextPart}），
 * 由 Jackson 序列化——字段名和类型都由 SDK 保证，不会再出现手写 JSON 漏字段导致客户端解析失败。
 */
@Slf4j
@RestController
public class A2aServerController {

    private final AgentCard agentCard;
    private final RemoteAgentService agentService;

    @Value("${lion.a2a.self-url:http://localhost:9999}")
    private String selfUrl;

    public A2aServerController(AgentCard agentCard, RemoteAgentService agentService) {
        this.agentCard = agentCard;
        this.agentService = agentService;
    }

    /* ==================== ① AgentCard ==================== */

    /**
     * GET /.well-known/agent-card.json
     * spring-ai-agent-utils-a2a 的 A2ASubagentResolver 会来这里发现远程 Agent。
     */
    @GetMapping(path = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentCard agentCard() {
        log.debug("[a2a-server] 返回 AgentCard：{}", agentCard.name());
        return agentCard;
    }

    /* ==================== ② JSON-RPC ==================== */

    /**
     * POST / （同时兼容 /a2a）——JSON-RPC 2.0 端点。
     *
     * <p>请求示例：
     * <pre>{@code
     * {"jsonrpc":"2.0","id":"1","method":"message/send",
     *  "params":{"message":{"role":"user","messageId":"m1","parts":[{"kind":"text","text":"广州天气"}]}}}
     * }</pre>
     */
    @PostMapping(path = {"/", "/a2a"}, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public SendMessageResponse jsonRpc(@RequestBody String body) {
        JSONObject request = JSONUtil.parseObj(body);
        Object id = request.get("id");
        String method = request.getStr("method", "");
        log.debug("[a2a-server] 收到 method={} body={}", method, body);

        if (!"message/send".equals(method)) {
            // 老版本规范叫 tasks/send，这里给明确提示，避免排查时一头雾水
            return new SendMessageResponse(id, new JSONRPCError(-32601,
                    "不支持的方法：" + method + "（本服务端只实现 message/send）", null));
        }

        JSONObject params = request.getJSONObject("params");
        String userText = extractText(params == null ? null : params.getJSONObject("message"));

        long start = System.currentTimeMillis();
        String answer;
        try {
            answer = agentService.answer(userText);
        } catch (Exception e) {
            log.error("[a2a-server] 远程 Agent 执行失败", e);
            return new SendMessageResponse(id, new JSONRPCError(-32603,
                    "远程 Agent 执行失败：" + e.getMessage(), null));
        }
        log.info("[a2a-server] 任务完成，耗时 {}ms", System.currentTimeMillis() - start);

        return new SendMessageResponse(id, buildTask(userText, answer));
    }

    /* ==================== 内部方法 ==================== */

    /** 从 message.parts 里把所有 text 部分拼起来 */
    private String extractText(JSONObject message) {
        if (message == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        JSONArray parts = message.getJSONArray("parts");
        if (parts != null) {
            for (Object item : parts) {
                if (item instanceof JSONObject part && "text".equals(part.getStr("kind"))) {
                    sb.append(part.getStr("text", ""));
                }
            }
        }
        // 兜底：有些实现直接把内容放在 content 字段
        if (sb.isEmpty()) {
            sb.append(message.getStr("content", ""));
        }
        return sb.toString();
    }

    /** 组装 A2A 的 Task 结果（客户端读的就是 artifacts 里的文本） */
    private Task buildTask(String userText, String answer) {
        String taskId = "task-" + UUID.randomUUID().toString().substring(0, 8);
        String contextId = "ctx-" + taskId;

        // history 可选，带上方便排查（客户端主要看 artifacts）
        Message userMsg = new Message.Builder()
                .role(Message.Role.USER)
                .parts(List.<Part<?>>of(new TextPart(userText)))
                .messageId("msg-user-" + taskId)
                .contextId(contextId)
                .build();
        Message agentMsg = new Message.Builder()
                .role(Message.Role.AGENT)
                .parts(List.<Part<?>>of(new TextPart(answer)))
                .messageId("msg-agent-" + taskId)
                .contextId(contextId)
                .taskId(taskId)
                .build();

        Artifact artifact = new Artifact("artifact-" + taskId, "answer", "远程 Agent 的回答",
                List.<Part<?>>of(new TextPart(answer)), null, null);

        return new Task(taskId, contextId, new TaskStatus(TaskState.COMPLETED),
                List.of(artifact), List.of(userMsg, agentMsg), new HashMap<>());
    }

    /** 便于浏览器自测：GET / 给一句说明 */
    @GetMapping(path = "/", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> index() {
        return Map.of(
                "service", "lion-a2a-server",
                "agentCard", selfUrl + "/.well-known/agent-card.json",
                "jsonrpc", "POST " + selfUrl + "  (method=message/send)");
    }
}
