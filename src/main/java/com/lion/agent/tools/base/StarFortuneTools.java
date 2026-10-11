package com.lion.agent.tools.base;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lion.agent.common.exception.BusinessException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 星座运势查询工具（对接 ALAPI：https://v3.alapi.cn/api/star）
 * <p>
 * 通过 Spring AI {@link Tool} 暴露给大模型，当用户询问星座运势时由模型自动调用。
 * 接口返回今日 / 明日 / 本周 / 本月 / 本年 五个维度的运势，本类负责解析并格式化为
 * 清晰、简洁的文案返回给大模型。
 * <p>
 * 注意：{@link Tool} / {@link ToolParam} 的 description 是模型判断"要不要调用、怎么传参"的
 * 唯一依据，统一使用英文书写（明确能力 + 何时使用 + 返回什么）；入参同时兼容英文与中文写法，
 * 避免模型直接把用户口述的"狮子座""本周"传进来就被拒。
 */
@Slf4j
@Component
public class StarFortuneTools {

    private static final String API_URL = "https://v3.alapi.cn/api/star";

    /** 外部接口超时：HttpURLConnection 默认无超时，必须显式设置，否则外部服务挂起会拖死业务线程 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /** 缺省时段：今日 */
    private static final String DEFAULT_PERIOD = "day";

    /**
     * 星座别名表：英文主键 + 中文名/简称 → 接口要求的英文名
     */
    private static final Map<String, String> STAR_ALIASES = Map.ofEntries(
            Map.entry("aries", "aries"), Map.entry("taurus", "taurus"), Map.entry("gemini", "gemini"),
            Map.entry("cancer", "cancer"), Map.entry("leo", "leo"), Map.entry("virgo", "virgo"),
            Map.entry("libra", "libra"), Map.entry("scorpio", "scorpio"), Map.entry("sagittarius", "sagittarius"),
            Map.entry("capricorn", "capricorn"), Map.entry("aquarius", "aquarius"), Map.entry("pisces", "pisces"),
            Map.entry("白羊座", "aries"), Map.entry("金牛座", "taurus"), Map.entry("双子座", "gemini"),
            Map.entry("巨蟹座", "cancer"), Map.entry("狮子座", "leo"), Map.entry("处女座", "virgo"),
            Map.entry("天秤座", "libra"), Map.entry("天蝎座", "scorpio"), Map.entry("射手座", "sagittarius"),
            Map.entry("摩羯座", "capricorn"), Map.entry("水瓶座", "aquarius"), Map.entry("双鱼座", "pisces"),
            Map.entry("白羊", "aries"), Map.entry("金牛", "taurus"), Map.entry("双子", "gemini"),
            Map.entry("巨蟹", "cancer"), Map.entry("狮子", "leo"), Map.entry("处女", "virgo"),
            Map.entry("天秤", "libra"), Map.entry("天蝎", "scorpio"), Map.entry("射手", "sagittarius"),
            Map.entry("摩羯", "capricorn"), Map.entry("水瓶", "aquarius"), Map.entry("双鱼", "pisces")
    );

    /**
     * 时段别名表：英文主键 + 常见中文/口语写法 → 接口要求的时段值
     */
    private static final Map<String, String> PERIOD_ALIASES = Map.ofEntries(
            Map.entry("day", "day"), Map.entry("tomorrow", "tomorrow"), Map.entry("week", "week"),
            Map.entry("month", "month"), Map.entry("year", "year"),
            Map.entry("today", "day"), Map.entry("今日", "day"), Map.entry("今天", "day"),
            Map.entry("明日", "tomorrow"), Map.entry("明天", "tomorrow"), Map.entry("明日运势", "tomorrow"),
            Map.entry("本周", "week"), Map.entry("本周运势", "week"),
            Map.entry("本月", "month"), Map.entry("本月运势", "month"),
            Map.entry("本年", "year"), Map.entry("今年", "year"), Map.entry("本年运势", "year")
    );

    private static final String STAR_DESCRIPTION = """
            Zodiac sign to query. Accepts English names: aries, taurus, gemini, cancer, leo, virgo, \
            libra, scorpio, sagittarius, capricorn, aquarius, pisces. Chinese names (狮子座) or short forms (狮子) \
            are also accepted. Examples: Aries = aries/白羊座, Scorpio = scorpio/天蝎座.
            """;

    private static final String PERIOD_DESCRIPTION = """
            Fortune period to query. One of: day (today, default), tomorrow, week, month, year. \
            Chinese values such as 今日/明日/本周/本月/本年 are also accepted. Leave empty for day.
            """;

    /** ALAPI 调用凭证，未配置时直接返回可读错误，而不是让请求打到外部接口 */
    private final String alapiToken;

    public StarFortuneTools(@Value("${lion.alapi.token:}") String alapiToken) {
        this.alapiToken = alapiToken;
    }

    /**
     * 查询指定星座的运势（默认今日，可选明日 / 周 / 月 / 年）。
     *
     * @param star   星座英文名
     * @param period 运势时段：day 今日、tomorrow 明日、week 本周、month 本月、year 本年（默认 day）
     * @return 格式化后的运势文本
     */
    @Tool(name = "queryStarFortune",
          description = "Query daily/tomorrow/weekly/monthly/yearly horoscope for one of the twelve zodiac signs. "
                  + "Call it whenever the user asks about horoscope, fortune, lucky color or lucky number for a zodiac sign. "
                  + "Returns a structured text report (scores for overall/health/work/money/love, lucky star, lucky color, "
                  + "lucky number and detailed descriptions).")
    @CircuitBreaker(name = "queryStarFortune", fallbackMethod = "fallbackChat")
    public String queryStarFortune(
            @ToolParam(description = STAR_DESCRIPTION, required = true) String star,
            @ToolParam(description = PERIOD_DESCRIPTION, required = false) String period) {
        // 1. 入参归一化：兼容中英文输入
        String normalized = normalizeStar(star);
        if (normalized == null) {
            return "错误：不支持的星座 \"" + star + "\"，请使用十二星座英文名或中文名，例如 aries / 白羊座。";
        }

        String normalizedPeriod = normalizePeriod(period);
        if (normalizedPeriod == null) {
            return "错误：不支持的运势时段 \"" + period + "\"，可选：day 今日、tomorrow 明日、week 本周、month 本月、year 本年。";
        }

        if (StrUtil.isBlank(alapiToken)) {
            return "错误：未配置 ALAPI Token（lion.alapi.token），无法查询星座运势。";
        }

        try {
            String body = requestFortune(normalized);

            log.debug("ALAPI 星座运势响应：{}", body);

            JSONObject root = JSONUtil.parseObj(body);
            int code = root.getInt("code", 0);
            String msg = root.getStr("msg", "未知");
            if (code != 200) {
                // 业务错误码视为调用失败，抛异常让 @CircuitBreaker 统计并触发 fallback
                throw new BusinessException("星座运势接口调用失败，错误码：" + code + "，错误信息：" + msg);
            }

            JSONObject data = root.getJSONObject("data");
            if (data == null) {
                return "接口返回成功，但无运势内容：" + msg;
            }

            JSONObject periodNode = data.getJSONObject(normalizedPeriod);
            if (periodNode == null) {
                return "接口返回成功，但缺少时段 \"" + normalizedPeriod + "\" 的数据。";
            }

            return "【" + normalized + " · " + periodName(normalizedPeriod) + "运势】\n" + formatPeriod(periodNode);
        } catch (BusinessException e) {
            // 已包装过的业务异常直接上抛，避免重复包一层丢失原始语义
            throw e;
        } catch (Exception e) {
            // 异常向上抛出交给 @CircuitBreaker 统计失败率；熔断开启后由 fallbackChat 返回降级文案
            log.error("调用 ALAPI 星座运势接口失败", e);
            throw new BusinessException("调用星座运势接口失败：" + e.getMessage());
        }
    }

    /**
     * 降级方法：ALAPI 接口调用异常、或熔断器打开（OPEN）拒绝调用时执行。
     * 签名必须与 queryStarFortune(String, String) 匹配（同入参 + 可选 Throwable），且返回 String。
     */
    public String fallbackChat(String star, String period, Throwable throwable) {
        log.error("星座运势查询触发熔断降级，原因: {}", throwable == null ? "熔断器已打开（OPEN）" : throwable.getMessage());
        return "抱歉，星座运势查询服务暂时不可用，请稍后再试。";
    }

    /**
     * 向 ALAPI 发送表单 POST 请求（form-urlencoded）。
     *
     * @throws BusinessException 网络失败 / 超时 / 非 2xx / 空响应
     */
    private String requestFortune(String star) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("token", alapiToken.trim());
        form.put("star", star);

        // HttpResponse 实现 Closeable，用 try-with-resources 释放底层连接流
        try (HttpResponse response = HttpRequest.post(API_URL)
                .setConnectionTimeout((int) CONNECT_TIMEOUT.toMillis())
                .setReadTimeout((int) READ_TIMEOUT.toMillis())
                .form(form)
                .execute()) {

            int status = response.getStatus();
            String body = response.body();
            if (status < 200 || status >= 300) {
                // 4xx/5xx 视为失败，抛 BusinessException 让熔断器统计
                throw new BusinessException("星座运势接口返回异常状态码 " + status);
            }
            if (StrUtil.isBlank(body)) {
                // 空响应视为调用失败，抛异常让 @CircuitBreaker 统计失败率
                throw new BusinessException("星座运势接口返回空响应");
            }
            return body;
        }
    }

    /**
     * 星座入参归一化：忽略大小写、去空格、兼容中英文写法。
     *
     * @return 接口要求的星座英文名，无法识别时返回 null
     */
    private String normalizeStar(String star) {
        if (StrUtil.isBlank(star)) {
            return null;
        }
        return STAR_ALIASES.get(star.trim().toLowerCase());
    }

    /**
     * 时段入参归一化：空值默认今日，兼容中英文写法。
     *
     * @return 接口要求的时段值，无法识别时返回 null
     */
    private String normalizePeriod(String period) {
        if (StrUtil.isBlank(period)) {
            return DEFAULT_PERIOD;
        }
        return PERIOD_ALIASES.get(period.trim().toLowerCase());
    }

    /**
     * 将一个时段节点的运势格式化为清晰的文本。
     */
    private String formatPeriod(JSONObject node) {
        List<String> lines = new ArrayList<>();
        lines.add("日期：" + safe(node, "date"));
        lines.add("综合指数：" + safe(node, "all") + "　宜：" + safe(node, "yi") + "　忌：" + safe(node, "ji"));
        lines.add("健康：" + safe(node, "health") + "　工作：" + safe(node, "work") + "　财运：" + safe(node, "money") + "　爱情：" + safe(node, "love"));
        lines.add("幸运星：" + safe(node, "lucky_star") + "　幸运色：" + safe(node, "lucky_color") + "　幸运数字：" + safe(node, "lucky_number"));
        lines.add("温馨提示：" + safe(node, "notice"));
        lines.add("");
        lines.add("综合运势：" + safe(node, "all_text"));
        lines.add("");
        lines.add("健康运势：" + safe(node, "health_text"));
        lines.add("");
        lines.add("工作运势：" + safe(node, "work_text"));
        lines.add("");
        lines.add("财运运势：" + safe(node, "money_text"));
        lines.add("");
        lines.add("爱情运势：" + safe(node, "love_text"));
        return String.join("\n", lines);
    }

    private String periodName(String period) {
        return switch (period) {
            case "day" -> "今日";
            case "tomorrow" -> "明日";
            case "week" -> "本周";
            case "month" -> "本月";
            case "year" -> "本年";
            default -> period;
        };
    }

    /**
     * 安全取值：字段缺失、null、空串统一返回占位符 "-"
     */
    private String safe(JSONObject node, String field) {
        String text = node.getStr(field);
        return StrUtil.isBlank(text) ? "-" : text.trim();
    }
}
