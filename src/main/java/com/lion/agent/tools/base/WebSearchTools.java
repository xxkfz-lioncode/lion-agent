package com.lion.agent.tools.base;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lion.agent.common.exception.BusinessException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 联网搜索工具（对接 Tavily：https://api.tavily.com）
 * <p>
 * 通过 Spring AI {@link Tool} 暴露给大模型，用于补足大模型「知识截止日期之后的信息」与实时信息：
 * 新闻、最新事件、实时行情/天气、需要引用外部网页佐证的问题。
 *
 * <p>两个工具：
 * <ul>
 *   <li>{@code webSearch}：关键词检索，返回标题 / 链接 / 发布时间 / 摘要</li>
 *   <li>{@code webExtract}：给定 URL 抓取正文，适合用户直接贴链接的场景</li>
 * </ul>
 *
 * <p>实现说明（与项目其余工具类保持一致）：
 * <ul>
 *   <li>HTTP 与 JSON 统一使用 Hutool（{@link HttpRequest} / {@link JSONUtil}），与项目其余工具类保持同一套基础设施。</li>
 *   <li>API Key 由 {@code lion.tavily.api-key} 注入（环境变量 TAVILY_API_KEY），未配置时返回明确提示，<b>不抛异常</b>
 *       ——配置缺失是运维问题，不该计入熔断失败率。</li>
 *   <li>网络异常 / 超时 / 非 2xx 统一包装为 {@link BusinessException}，交给 {@link CircuitBreaker} 统计失败率；
 *       熔断打开后由 fallback 返回降级文案，让模型基于已有知识继续作答。</li>
 *   <li>显式设置连接超时与读取超时：Hutool 底层 HttpURLConnection 默认无超时，不设会把业务线程拖死。</li>
 *   <li>返回给模型的内容做两级截断（单条摘要 + 总长度），防止长网页把上下文撑爆。</li>
 * </ul>
 */
@Slf4j
@Component
public class WebSearchTools {

    private static final String SEARCH_PATH = "/search";
    private static final String EXTRACT_PATH = "/extract";

    /** Tavily max_results 合法区间 */
    private static final int MAX_RESULTS_MIN = 1;
    private static final int MAX_RESULTS_MAX = 20;

    /** 一次最多抓取的 URL 数（Tavily 单次最多 20，这里收紧以控制耗时与额度） */
    private static final int EXTRACT_MAX_URLS = 5;

    /** 单条搜索摘要最大字符数 */
    private static final int SNIPPET_MAX_LENGTH = 400;
    /** 搜索结果返回给模型的总长度上限 */
    private static final int SEARCH_TOTAL_MAX_LENGTH = 3000;
    /** 单页抽取正文最大字符数 */
    private static final int EXTRACT_MAX_LENGTH = 2000;
    /** 抓取结果返回给模型的总长度上限 */
    private static final int EXTRACT_TOTAL_MAX_LENGTH = 6000;

    private static final Set<String> VALID_TOPICS = Set.of("general", "news", "finance");
    private static final Set<String> VALID_TIME_RANGES = Set.of("day", "week", "month", "year");
    /** 合法检索深度：配置写错会被 Tavily 判 400 并计入熔断，启动期先校验回退 */
    private static final Set<String> VALID_SEARCH_DEPTHS = Set.of("basic", "advanced", "fast", "ultra-fast");
    private static final Set<String> VALID_EXTRACT_DEPTHS = Set.of("basic", "advanced");

    @Value("${lion.tavily.api-key:}")
    private String apiKey;

    @Value("${lion.tavily.base-url:https://api.tavily.com}")
    private String baseUrl;

    /** 检索深度：basic 1 个额度、advanced 2 个额度。不暴露给模型，避免模型随手选 advanced 导致成本翻倍 */
    @Value("${lion.tavily.search-depth:basic}")
    private String searchDepth;

    /** 默认返回条数 */
    @Value("${lion.tavily.max-results:5}")
    private int defaultMaxResults;

    /** 是否附带 Tavily 生成的 AI 摘要。默认关闭：避免模型被"喂答案"而跳过原文比对 */
    @Value("${lion.tavily.include-answer:false}")
    private boolean includeAnswer;

    @Value("${lion.tavily.extract-depth:basic}")
    private String extractDepth;

    @Value("${lion.tavily.connect-timeout:5s}")
    private Duration connectTimeout;

    @Value("${lion.tavily.read-timeout:20s}")
    private Duration readTimeout;

    @PostConstruct
    public void init() {
        // 深度参数写错会被 Tavily 判 400 并计入熔断失败率，启动期先校验回退
        this.searchDepth = normalizeEnum(searchDepth, VALID_SEARCH_DEPTHS, "basic", "search-depth");
        this.extractDepth = normalizeEnum(extractDepth, VALID_EXTRACT_DEPTHS, "basic", "extract-depth");
        this.connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        this.readTimeout = readTimeout == null ? Duration.ofSeconds(20) : readTimeout;
    }

    /** 枚举型配置校验：非法值回退为默认值并告警，避免配置笔误在运行期放大成熔断 */
    private String normalizeEnum(String value, Set<String> allowed, String fallback, String configKey) {
        String v = (value == null || value.isBlank()) ? fallback : value.trim().toLowerCase();
        if (!allowed.contains(v)) {
            log.warn("lion.tavily.{} 配置非法（{}），回退为 {}", configKey, value, fallback);
            return fallback;
        }
        return v;
    }

    // ==================== 工具 1：联网搜索 ====================

    /**
     * 联网搜索实时信息。
     *
     * @param query      搜索关键词
     * @param maxResults 返回条数（1-20），不传则用默认配置
     * @param topic      搜索类别：general 通用 / news 新闻 / finance 财经
     * @param timeRange  时间范围：day / week / month / year，询问"最近/最新"时传入
     * @return 格式化后的搜索结果文本
     */
    @Tool(description = """
            联网搜索互联网上的实时信息，返回标题、链接、发布时间和摘要。
            适用场景：用户询问新闻、最新事件、实时数据（股价/天气/汇率/排行）、近期发布的产品或政策、
            任何可能发生在我知识截止日期之后的事实，以及需要引用外部网页佐证的问题。
            不适用：常识、概念解释、代码编写、数学计算、以及对已有知识的总结——这类问题请直接回答，不要调用本工具。
            """)
    @CircuitBreaker(name = "webSearch", fallbackMethod = "searchFallback")
    public String webSearch(
            @ToolParam(description = "搜索关键词，建议用简洁的中文或英文短语，不要写成完整句子", required = true) String query,
            @ToolParam(description = "返回结果条数，1 到 20，默认 5", required = false) Integer maxResults,
            @ToolParam(description = "搜索类别：general 通用、news 新闻、finance 财经，默认 general", required = false) String topic,
            @ToolParam(description = "时间范围：day 一天内、week 一周内、month 一个月内、year 一年内。仅当用户在意信息时效性时传入，例如「最近一周」「今天」", required = false) String timeRange) {

        if (query == null || query.isBlank()) {
            return "错误：请提供搜索关键词。";
        }

        String normalizedTopic = "general";
        if (topic != null && !topic.isBlank()) {
            normalizedTopic = topic.trim().toLowerCase();
            if (!VALID_TOPICS.contains(normalizedTopic)) {
                return "错误：不支持的搜索类别 \"" + topic + "\"。可选：general 通用、news 新闻、finance 财经。";
            }
        }

        if (timeRange != null && !timeRange.isBlank()) {
            String normalizedRange = timeRange.trim().toLowerCase();
            if (!VALID_TIME_RANGES.contains(normalizedRange)) {
                return "错误：不支持的时间范围 \"" + timeRange + "\"。可选：day、week、month、year。";
            }
        }

        int size = maxResults == null ? defaultMaxResults : maxResults;
        if (size < MAX_RESULTS_MIN || size > MAX_RESULTS_MAX) {
            return "错误：返回条数必须在 " + MAX_RESULTS_MIN + " 到 " + MAX_RESULTS_MAX + " 之间。";
        }

        if (apiKey == null || apiKey.isBlank()) {
            return "错误：未配置联网搜索服务的 API Key（lion.tavily.api-key），无法执行联网搜索。";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query.trim());
        payload.put("search_depth", searchDepth);
        payload.put("topic", normalizedTopic);
        payload.put("max_results", size);
        payload.put("include_answer", includeAnswer);
        payload.put("include_published_date", true);
        payload.put("include_raw_content", false);
        payload.put("include_images", false);
        if (timeRange != null && !timeRange.isBlank()) {
            payload.put("time_range", timeRange.trim().toLowerCase());
        }

        try {
            String body = post(SEARCH_PATH, payload);
            log.debug("Tavily 搜索响应：{}", body);
            return formatSearchResults(query.trim(), JSONUtil.parseObj(body));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 Tavily 搜索接口失败", e);
            throw new BusinessException("调用联网搜索接口失败：" + e.getMessage());
        }
    }

    /** 降级方法：网络异常或熔断打开（OPEN）时执行，签名需与 webSearch 一致（同入参 + Throwable） */
    public String searchFallback(String query, Integer maxResults, String topic, String timeRange, Throwable throwable) {
        log.error("联网搜索触发熔断降级，原因: {}", throwable.getMessage());
        return "联网搜索服务暂时不可用（" + throwable.getMessage() + "）。请基于你已有的知识回答，并明确告知用户该信息未经联网核实、可能不是最新的。";
    }

    // ==================== 工具 2：网页正文抓取 ====================

    /**
     * 抓取指定网页的正文内容。
     *
     * @param urls 网页 URL，多个用英文逗号分隔，最多 5 个
     * @return 格式化后的网页正文
     */
    @Tool(description = """
            抓取指定网页的正文内容，返回 Markdown 文本。
            适用场景：用户在对话中直接给出了链接并想了解其内容，或联网搜索后需要深入阅读某个具体页面。
            不适用：你没有拿到具体 URL 的情况——请改用联网搜索工具。
            """)
    @CircuitBreaker(name = "webSearch", fallbackMethod = "extractFallback")
    public String webExtract(
            @ToolParam(description = "要抓取正文的网页 URL，多个用英文逗号分隔，最多 5 个", required = true) String urls) {

        if (urls == null || urls.isBlank()) {
            return "错误：请提供要抓取的网页 URL。";
        }

        List<String> urlList = new ArrayList<>();
        for (String part : urls.split("[,，\\s]+")) {
            String u = part.trim();
            if (!u.isEmpty()) {
                urlList.add(u);
            }
        }
        if (urlList.isEmpty()) {
            return "错误：未能从输入中解析出有效的 URL。";
        }
        if (urlList.size() > EXTRACT_MAX_URLS) {
            return "错误：一次最多抓取 " + EXTRACT_MAX_URLS + " 个 URL，本次传入 " + urlList.size() + " 个。";
        }

        for (String u : urlList) {
            if (!u.toLowerCase().startsWith("http://") && !u.toLowerCase().startsWith("https://")) {
                return "错误：URL 必须以 http:// 或 https:// 开头：\"" + u + "\"。";
            }
            if (isBlockedHost(u)) {
                // SSRF 防护：模型可能被引诱去探测内网地址
                return "错误：不允许抓取内网或本机地址：\"" + u + "\"。";
            }
        }

        if (apiKey == null || apiKey.isBlank()) {
            return "错误：未配置联网搜索服务的 API Key（lion.tavily.api-key），无法抓取网页。";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("urls", urlList);
        payload.put("extract_depth", extractDepth);
        payload.put("format", "markdown");

        try {
            String body = post(EXTRACT_PATH, payload);
            log.debug("Tavily 抓取响应：{}", body);
            return formatExtractResults(JSONUtil.parseObj(body));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 Tavily 抓取接口失败", e);
            throw new BusinessException("调用网页抓取接口失败：" + e.getMessage());
        }
    }

    /** 降级方法：网络异常或熔断打开（OPEN）时执行 */
    public String extractFallback(String urls, Throwable throwable) {
        log.error("网页抓取触发熔断降级，原因: {}", throwable.getMessage());
        return "网页抓取服务暂时不可用（" + throwable.getMessage() + "）。请告知用户稍后再试，或请用户自行打开链接查看。";
    }

    // ==================== HTTP 调用 ====================

    /**
     * 向 Tavily 发送 JSON POST 请求。
     *
     * @throws BusinessException 网络失败 / 超时 / 非 2xx 状态码
     */
    private String post(String path, Map<String, Object> payload) {
        String json = JSONUtil.toJsonStr(payload);
        // HttpResponse 实现 Closeable，用 try-with-resources 释放底层连接流
        try (HttpResponse response = HttpRequest.post(resolveUrl(path))
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Accept", "application/json")
                // HttpURLConnection 默认无超时，必须显式设置，否则外部服务挂起会拖死业务线程
                .setConnectionTimeout((int) connectTimeout.toMillis())
                .setReadTimeout((int) readTimeout.toMillis())
                .body(json)
                .execute()) {

            int status = response.getStatus();
            String body = response.body();
            if (status < 200 || status >= 300) {
                // 4xx/5xx 视为失败，抛 BusinessException 让熔断器统计
                throw new BusinessException("Tavily 接口返回异常状态码 " + status + "：" + parseError(body));
            }
            if (body == null || body.isBlank()) {
                throw new BusinessException("Tavily 接口返回空响应。");
            }
            return body;
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            // Hutool 底层异常（IORuntimeException / HttpException）：连接失败、读取超时、DNS 解析失败等
            throw new BusinessException("调用 Tavily 接口失败：" + e.getMessage());
        }
    }

    private String resolveUrl(String path) {
        String base = (baseUrl == null || baseUrl.isBlank()) ? "https://api.tavily.com" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    /** Tavily 错误响应有两种形态：{"detail":{"error":"..."}} 与 {"detail":[{"msg":"..."}]} */
    private String parseError(String body) {
        if (body == null || body.isBlank()) {
            return "(空响应体)";
        }
        try {
            Object detail = JSONUtil.parseObj(body).get("detail");
            if (detail instanceof String s) {
                return s;
            }
            if (detail instanceof JSONObject obj) {
                return obj.getStr("error");
            }
            if (detail instanceof JSONArray arr && !arr.isEmpty()) {
                Object first = arr.get(0);
                return first instanceof JSONObject jo ? jo.getStr("msg") : String.valueOf(first);
            }
        } catch (RuntimeException ignore) {
            // 错误响应不是 JSON（如网关返回的 HTML），原样截断返回便于排错
        }
        return truncate(body, 200);
    }

    // ==================== 结果格式化 ====================

    private String formatSearchResults(String query, JSONObject root) {
        JSONArray results = root.getJSONArray("results");
        if (results == null || results.isEmpty()) {
            return "联网搜索未找到与「" + query + "」相关的结果，建议换个关键词重试。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【联网搜索结果】\n");
        sb.append("查询词：").append(query).append('\n');
        sb.append("命中 ").append(results.size()).append(" 条");

        String responseTime = root.getStr("response_time");
        if (responseTime != null && !responseTime.isBlank()) {
            sb.append("，耗时 ").append(responseTime).append(" 秒");
        }
        sb.append("\n\n");

        if (includeAnswer) {
            String answer = root.getStr("answer");
            if (answer != null && !answer.isBlank()) {
                sb.append("AI 摘要：").append(normalize(answer)).append("\n\n");
            }
        }

        for (int i = 0; i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            String title = str(item, "title");
            sb.append('[').append(i + 1).append("] ")
                    .append(title.isEmpty() ? "(无标题)" : truncate(title, 120)).append('\n');
            sb.append("链接：").append(str(item, "url")).append('\n');
            String published = str(item, "published_date");
            if (!published.isEmpty()) {
                sb.append("发布时间：").append(published).append('\n');
            }
            sb.append("摘要：").append(truncate(normalize(str(item, "content")), SNIPPET_MAX_LENGTH)).append('\n');
            sb.append('\n');
        }
        return truncate(sb.toString(), SEARCH_TOTAL_MAX_LENGTH);
    }

    private String formatExtractResults(JSONObject root) {
        JSONArray results = root.getJSONArray("results");
        JSONArray failed = root.getJSONArray("failed_results");
        int successCount = results == null ? 0 : results.size();
        int failedCount = failed == null ? 0 : failed.size();

        StringBuilder sb = new StringBuilder();
        sb.append("【网页正文抓取结果】\n");
        sb.append("成功 ").append(successCount).append(" 个");
        if (failedCount > 0) {
            sb.append("，失败 ").append(failedCount).append(" 个");
        }
        sb.append("\n\n");

        if (successCount > 0) {
            for (int i = 0; i < successCount; i++) {
                JSONObject item = results.getJSONObject(i);
                sb.append('[').append(i + 1).append("] ").append(str(item, "url")).append('\n');
                String content = str(item, "raw_content");
                sb.append("正文：").append(content.isEmpty() ? "(页面无正文内容)" : truncate(content, EXTRACT_MAX_LENGTH));
                sb.append("\n\n");
            }
        } else {
            sb.append("没有成功抓取到任何网页正文。\n\n");
        }

        if (failedCount > 0) {
            sb.append("抓取失败的 URL：\n");
            for (int i = 0; i < failedCount; i++) {
                JSONObject item = failed.getJSONObject(i);
                String reason = str(item, "error");
                sb.append("- ").append(str(item, "url")).append("：")
                        .append(reason.isEmpty() ? "未知原因" : reason).append('\n');
            }
        }
        return truncate(sb.toString(), EXTRACT_TOTAL_MAX_LENGTH);
    }

    /** 取字段并做 null 安全处理；Hutool 的 getStr 在字段缺失或为 JSON null 时返回 null */
    private String str(JSONObject obj, String key) {
        String v = obj.getStr(key);
        return v == null ? "" : v.trim();
    }

    /** SSRF 防护：禁止抓取本机 / 内网 / 链路本地地址 */
    private boolean isBlockedHost(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (Exception e) {
            return true;
        }
        if (host == null || host.isBlank()) {
            return true;
        }
        String h = host.toLowerCase();
        if (h.equals("localhost") || h.endsWith(".localhost") || h.equals("::1") || h.equals("[::1]")
                || h.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        if (h.startsWith("127.") || h.startsWith("0.") || h.startsWith("10.")
                || h.startsWith("192.168.") || h.startsWith("169.254.")) {
            return true;
        }
        // 172.16.0.0 ~ 172.31.255.255
        if (h.startsWith("172.")) {
            String[] parts = h.split("\\.");
            if (parts.length > 1) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 压缩空白字符，避免网页里的换行/缩进占满上下文 */
    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…（内容过长已截断）";
    }
}
