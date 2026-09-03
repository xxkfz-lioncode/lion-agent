package com.lion.agent.utils;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Langfuse 原生 Ingestion API 上报工具类（在 OTel 自动链路之外，按需补报自定义事件）。
 * <p>
 * 背景：本项目链路追踪走 OpenTelemetry → Langfuse OTLP 端点（覆盖 Spring AI 全部调用）。
 * 但 OTel 只覆盖链路内的 Span，无法表达「给某次对话补一条评分」「上报链路外的自定义指标」
 * 这类诉求。Langfuse 没有官方 Java SDK，因此封装本类直连其摄取 API：
 * <pre>
 * POST {base-url}/api/public/ingestion
 * Authorization: Basic base64(publicKey:secretKey)
 * Content-Type: application/json
 * { "batch": [ { "id": "事件唯一id", "type": "trace-create", "version": "1",
 *                "timestamp": "ISO8601", "body": {...} } ] }
 * </pre>
 * 服务端返回 HTTP 207 Multi-Status（successes / errors 按事件粒度列出成败），
 * 因此本类自带{@code 攒批 + 定时 flush + 部分失败日志}能力，避免一条事件一次 HTTP。
 * <p>
 * 配置项（application.yml → lion.langfuse.*，均可被同名环境变量覆盖）：
 * <ul>
 *   <li>{@code base-url}：Langfuse 部署根地址，默认官方 US 云；</li>
 *   <li>{@code public-key / secret-key}：项目公钥/私钥，需成对配置（Langfuse 控制台 Project Settings 可查）；</li>
 *   <li>{@code batch-size / flush-interval-ms}：攒批阈值与定时发送间隔。</li>
 * </ul>
 * 未成对配置密钥时组件自动禁用（{@code enabled=false}），所有方法静默跳过并只告警一次，
 * 不影响主问答链路 —— 与 DashScopeRerankUtils 缺 Key 降级策略一致。
 * <p>
 * 典型用法（在 ChatService / Advisor / 业务代码里注入后调用）：
 * <pre>
 * // 1. 给一次已完成对话打评分（最常见，OTel 覆盖不到）
 * langfuseIngestClient.score("answer-satisfaction", 4.5, traceId, null, "整体满意");
 *
 * // 2. 上报一次链路外的独立 LLM 调用（如离线批处理）
 * String genId = UUID.randomUUID().toString();
 * langfuseIngestClient.generationStart(traceId, genId, "offline-extract", "qwen-plus",
 *         "请抽取事实", Map.of("temperature", 0.1));
 * langfuseIngestClient.generationEnd(genId, traceId, "抽取结果", Map.of("input", 30, "output", 12, "total", 42));
 *
 * // 3. 请求收尾时主动冲刷缓冲（也可等定时 flush）
 * langfuseIngestClient.flush();
 * </pre>
 *
 * @see <a href="https://langfuse.com/docs/api">Langfuse API 文档</a>
 */
@Slf4j
@Component
public class LangfuseIngestClient {

    /** 摄取 API 固定路径（拼接在 base-url 之后） */
    private static final String INGESTION_PATH = "/api/public/ingestion";

    /** 评分专用 Public API 路径（score 不走 batch，单独直发以拿到同步结果） */
    private static final String SCORE_PATH = "/api/public/scores";

    /** ISO-8601 时间格式（Langfuse 要求 UTC，如 2026-09-02T03:14:15.618Z） */
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    // ==================== 可配置项（均可被环境变量覆盖） ====================

    @Value("${lion.langfuse.base-url:${LANGFUSE_BASE_URL:https://us.cloud.langfuse.com}}")
    private String baseUrl;

    @Value("${lion.langfuse.public-key:${LANGFUSE_PUBLIC_KEY:}}")
    private String publicKey;

    @Value("${lion.langfuse.secret-key:${LANGFUSE_SECRET_KEY:}}")
    private String secretKey;

    /** 攒批上限：缓冲内事件数达到该值立即发送一次 */
    @Value("${lion.langfuse.batch-size:20}")
    private int batchSize;

    /** 定时发送间隔（毫秒）：距上次发送超过该时长则强制冲刷一次 */
    @Value("${lion.langfuse.flush-interval-ms:5000}")
    private long flushIntervalMs;

    // ==================== 运行时状态 ====================

    /** 组装好的 Authorization 头值（形如 Basic xxxx），为空代表未启用 */
    private String basicAuth = "";

    /** 是否启用上报（配置了密钥即为 true） */
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    /** 已打日志的标记，避免未配置时每个事件都刷告警 */
    private final AtomicBoolean warnLogged = new AtomicBoolean(false);

    /** 待上报事件缓冲（线程安全：由 synchronized 保护） */
    private final List<JSONObject> buffer = new ArrayList<>();

    /** 定时冲刷线程（守护线程，不阻止应用退出） */
    private ScheduledExecutorService scheduler;

    private final RestClient restClient;

    public LangfuseIngestClient(RestClient.Builder builder) {
        // 与 DashScopeRerankUtils 相同：注入 Spring 自动配置的 RestClient.Builder
        this.restClient = builder.build();
    }

    /**
     * 初始化：拼装 Basic Auth、启动定时冲刷线程。
     * 密钥必须同时配置 public-key 与 secret-key；任缺其一视为未配置（禁用上报）。
     */
    @PostConstruct
    public void init() {
        if (hasText(publicKey) && hasText(secretKey)) {
            String raw = publicKey.trim() + ":" + secretKey.trim();
            basicAuth = "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        } else {
            log.warn("[LangfuseIngest] 未配置 lion.langfuse.public-key/secret-key（需同时配置），Langfuse 上报已禁用（不影响主链路）");
            return;
        }
        enabled.set(true);
        if (flushIntervalMs > 0) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "langfuse-ingest-flusher");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(this::flushSafely, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
        }
        log.info("[LangfuseIngest] 初始化完成：baseUrl={}, batchSize={}, flushIntervalMs={}", baseUrl, batchSize, flushIntervalMs);
    }

    /**
     * 应用关闭前冲刷剩余事件并停止定时线程。
     */
    @PreDestroy
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        flushSafely();
        log.info("[LangfuseIngest] 已关闭，缓冲内剩余事件已尝试冲刷");
    }

    // ==================== 对外方法 ====================

    /**
     * 上报一条评分（score），用于给 Trace / Generation 打主观分、反馈分。
     * <p>
     * 与 Trace/Generation 走 ingestion batch 不同，评分单独直发
     * {@code POST /api/public/scores}（Public API 同步接口）：
     * 返回 2xx 即真正入库，失败时异常里带服务端响应体，可直接看到原因，
     * 避免 score-create 事件混在 batch 里出现「整批成功但 UI 不显示」的模糊情况。
     *
     * @param name          评分指标名，如 answer-satisfaction / relevance
     * @param value         分值（数值）
     * @param traceId       关联的 Trace id（可为 null，但 observationId 与 traceId 至少给一个）
     * @param observationId 关联的 Observation/Generation id（可为 null）
     * @param comment       备注（可为 null）
     */
    public void score(String name, Number value, String traceId, String observationId, String comment) {
        if (!checkEnabled()) {
            return;
        }
        if (!hasText(traceId) && !hasText(observationId)) {
            log.warn("[LangfuseIngest] score 上报被忽略：traceId 与 observationId 均为空，无法归属评分");
            return;
        }
        JSONObject body = JSONUtil.createObj();
        body.set("name", name);
        body.set("value", value);
        putIfNotNull(body, "traceId", traceId);
        putIfNotNull(body, "observationId", observationId);
        putIfNotNull(body, "comment", comment);
        postScoreDirect(body);
    }

    /**
     * 直发评分到 Public API：2xx 记 info 成功日志，4xx/5xx 记 error 并带上服务端响应体。
     */
    private void postScoreDirect(JSONObject body) {
        try {
            String response = restClient.post()
                    .uri(buildFullUrl(SCORE_PATH))
                    .header(HttpHeaders.AUTHORIZATION, basicAuth)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(JSONUtil.toJsonStr(body))
                    .retrieve()
                    .body(String.class);
            log.info("[LangfuseIngest] 评分上报成功: name={}, value={}, 响应={}", body.get("name"), body.get("value"), response);
        } catch (RestClientResponseException e) {
            log.error("[LangfuseIngest] 评分上报失败: name={}, value={}, HTTP {}, 服务端响应: {}",
                    body.get("name"), body.get("value"), e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("[LangfuseIngest] 评分上报失败: name={}, value={}, 原因: {}",
                    body.get("name"), body.get("value"), e.getMessage());
        }
    }

    /**
     * 便捷重载：只关联 Trace 打分的常用场景。
     *
     * @param traceId 关联的 Trace id
     * @param name    评分指标名
     * @param value   分值
     * @param comment 备注（可为 null）
     */
    public void scoreTrace(String traceId, String name, Number value, String comment) {
        score(name, value, traceId, null, comment);
    }

    /**
     * 开启一次链路外的 LLM 调用（generation-create，官方推荐的新版事件类型）。
     * 注意：不要用 legacy 的 observation-create/observation-update（已废弃，服务端校验更严格）。
     * 上报后需在调用结束调用 {@link #generationEnd} 补输出与用量。
     *
     * @param traceId          所属 Trace id（传 null 时需先有 trace-create；建议先建 trace）
     * @param generationId     本次调用的唯一 id（调用方生成，如 UUID）
     * @param name             调用名，如 offline-extract
     * @param model            模型名，如 qwen-plus
     * @param input            Prompt/输入内容（String 或结构化对象）
     * @param modelParameters  采样参数等（可为 null），如 {"temperature": 0.1}
     */
    public void generationStart(String traceId, String generationId, String name, String model,
                                Object input, Map<String, Object> modelParameters) {
        if (!checkEnabled()) {
            return;
        }
        JSONObject body = JSONUtil.createObj();
        body.set("id", generationId);
        putIfNotNull(body, "traceId", traceId);
        body.set("name", name);
        body.set("startTime", now());
        putIfNotNull(body, "model", model);
        putIfNotNull(body, "input", input);
        if (modelParameters != null && !modelParameters.isEmpty()) {
            body.set("modelParameters", JSONUtil.parseObj(modelParameters));
        }
        submitEvent("generation-create", body);
    }

    /**
     * 结束一次 LLM 调用并补报输出与 Token 用量（generation-update，新版事件类型）。
     *
     * @param generationId 与 {@link #generationStart} 相同的 generationId
     * @param traceId      所属 Trace id（可为 null，能定位即可）
     * @param output       模型输出
     * @param usage        Token 用量，如 {"input": 30, "output": 12, "total": 42}
     */
    public void generationEnd(String generationId, String traceId, Object output, Map<String, Object> usage) {
        if (!checkEnabled()) {
            return;
        }
        JSONObject body = JSONUtil.createObj();
        body.set("id", generationId);
        putIfNotNull(body, "traceId", traceId);
        putIfNotNull(body, "output", output);
        if (usage != null && !usage.isEmpty()) {
            // Langfuse 的 usage 结构：{input, output, total, unit}，unit 缺省即 TOKENS
            JSONObject usageObj = JSONUtil.createObj();
            usage.forEach(usageObj::set);
            body.set("usage", usageObj);
        }
        body.set("endTime", now());
        submitEvent("generation-update", body);
    }

    /**
     * 上报一条独立的 Trace（trace-create），通常作为后续 generation/score 的归属容器。
     *
     * @param traceId   Trace 唯一 id
     * @param name      Trace 名，如 offline-batch / custom-job
     * @param input     输入快照（可为 null）
     * @param metadata  附加元数据（可为 null）
     */
    public void createTrace(String traceId, String name, Object input, Map<String, Object> metadata) {
        if (!checkEnabled()) {
            return;
        }
        JSONObject body = JSONUtil.createObj();
        body.set("id", traceId);
        body.set("name", name);
        putIfNotNull(body, "input", input);
        if (metadata != null && !metadata.isEmpty()) {
            body.set("metadata", JSONUtil.parseObj(metadata));
        }
        submitEvent("trace-create", body);
    }

    /**
     * 通用入口：发送任意类型的事件（type + body），供未来 Langfuse 新增事件类型时扩展。
     *
     * @param type 事件类型，如 trace-create / observation-create / score-create
     * @param body 事件体（body 字段内容）
     */
    public void sendEvent(String type, JSONObject body) {
        if (checkEnabled()) {
            submitEvent(type, body);
        }
    }

    /**
     * 立即冲刷缓冲区（异步发送前手动落点，常用于请求收尾，保证数据尽快可见）。
     * 幂等：无缓冲数据时直接返回。
     */
    public synchronized void flush() {
        flushBuffer();
    }

    // ==================== 内部实现 ====================

    /**
     * 检查是否启用；未启用时仅告警一次。
     */
    private boolean checkEnabled() {
        if (enabled.get()) {
            return true;
        }
        if (warnLogged.compareAndSet(false, true)) {
            log.warn("[LangfuseIngest] 组件未启用（缺少密钥配置），本条上报已忽略，如需上报请配置 lion.langfuse.*");
        }
        return false;
    }

    /**
     * 构造事件信封并入缓冲；达到攒批阈值立即发送。
     */
    private synchronized void submitEvent(String type, JSONObject body) {
        JSONObject event = JSONUtil.createObj()
                .set("id", IdUtil.fastSimpleUUID())   // 事件唯一 id，服务端按此去重/返回结果
                .set("type", type)
                .set("version", "1")                  // 事件信封版本，Langfuse 要求固定为 1
                .set("timestamp", now())
                .set("body", body);
        buffer.add(event);
        if (buffer.size() >= batchSize) {
            flushBuffer();
        }
    }

    /**
     * 定时任务的安全包装：任何异常只记日志，不能终止调度线程。
     */
    private void flushSafely() {
        try {
            flushBuffer();
        } catch (Exception e) {
            log.error("[LangfuseIngest] 定时冲刷异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 将缓冲中的事件整批 POST 到摄取 API 并解析 207 结果。
     * 发送失败（网络/4xx/5xx）时记录错误并丢弃本批 —— 宁可丢也不重试阻塞调用方，
     * 如需强可靠请自行扩展重投策略。
     */
    private synchronized void flushBuffer() {
        if (!enabled.get() || buffer.isEmpty()) {
            return;
        }
        // 整体搬出缓冲，避免发送期间新事件被本批带走
        List<JSONObject> batch = new ArrayList<>(buffer);
        buffer.clear();

        JSONObject payload = JSONUtil.createObj().set("batch", batch);
        String response;
        try {
            response = restClient.post()
                    .uri(buildFullUrl(INGESTION_PATH))
                    .header(HttpHeaders.AUTHORIZATION, basicAuth)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(JSONUtil.toJsonStr(payload))
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.error("[LangfuseIngest] 上报失败（{} 条事件已丢弃）: {}", batch.size(), e.getMessage());
            return;
        }
        parseResult(response, batch);
    }

    /**
     * 解析 207 Multi-Status 响应：统计 successes / errors。
     * 失败条目仅记日志（id + 原因 + 对应事件类型），不做自动重发。
     *
     * @param response 服务端返回的 207 响应体
     * @param batch    本次发送的事件批次（用于把失败事件 id 反查到具体类型和 body）
     */
    private void parseResult(String response, List<JSONObject> batch) {
        try {
            if (response == null || response.isBlank()) {
                log.warn("[LangfuseIngest] 上报返回空响应（{} 条）", batch.size());
                return;
            }
            JSONObject root = JSONUtil.parseObj(response);
            JSONArray successes = root.getJSONArray("successes");
            JSONArray errors = root.getJSONArray("errors");
            int okCount = successes == null ? 0 : successes.size();
            int errCount = errors == null ? 0 : errors.size();
            if (errCount > 0 && errors != null) {
                // 建立 信封id -> 事件 的索引，失败时反查出具体是哪种事件、body 是什么
                Map<String, JSONObject> byId = new java.util.HashMap<>();
                for (JSONObject event : batch) {
                    byId.put(event.getStr("id"), event);
                }
                List<String> reasons = new ArrayList<>();
                for (Object item : errors) {
                    if (item instanceof JSONObject err) {
                        String eventId = err.getStr("id");
                        JSONObject failed = byId.get(eventId);
                        String eventType = failed == null ? "unknown" : failed.getStr("type");
                        String eventBody = failed == null ? "" : JSONUtil.toJsonStr(failed.get("body"));
                        reasons.add("id=" + eventId + " type=" + eventType + " code=" + err.getStr("status")
                                + " msg=" + err.getStr("message") + " body=" + eventBody);
                    }
                }
                log.warn("[LangfuseIngest] 上报部分失败：成功 {} / 失败 {}，失败明细: {}", okCount, errCount, reasons);
            } else {
                log.debug("[LangfuseIngest] 上报成功 {} 条（共 {} 条）", okCount, batch.size());
            }
        } catch (Exception e) {
            log.warn("[LangfuseIngest] 解析 207 响应失败（不影响已发送）: {}", e.getMessage());
        }
    }

    // ==================== 小工具 ====================

    /**
     * 拼装完整的摄取 API URL。
     * 关键：RestClient 未配置 baseUrl 时，URI 不能传相对路径（如 /api/public/ingestion），
     * 否则会被解析到本机默认 80 端口（localhost:80）导致 Connection refused。
     * 因此这里始终拼接 baseUrl + 固定路径，并兼容 base-url 尾部多写的斜杠。
     */
    private String buildFullUrl(String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /** JSON 只 put 非空值，避免把 null 写进请求体引发 Langfuse 校验告警 */
    private static void putIfNotNull(JSONObject obj, String key, Object value) {
        if (value != null) {
            obj.set(key, value);
        }
    }

    /** 当前 UTC 时间，ISO-8601 格式 */
    private static String now() {
        return ISO_FORMATTER.format(Instant.now());
    }
}
