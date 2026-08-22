package com.lion.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 星座运势查询工具（对接 ALAPI：https://v3.alapi.cn/api/star）
 * <p>
 * 通过 Spring AI {@link Tool} 暴露给大模型，当用户询问星座运势时由模型自动调用。
 * 接口返回今日 / 明日 / 本周 / 本月 / 本年 五个维度的运势，本类负责解析并格式化为
 * 清晰、简洁的中文文本返回给大模型。
 */
@Slf4j
@Component
public class StarFortuneTools {

    private static final String API_BASE = "https://v3.alapi.cn";
    private static final String API_PATH = "/api/star";

    /** 支持查询的时段 */
    private static final Set<String> VALID_PERIODS = new HashSet<>(Arrays.asList(
            "day", "tomorrow", "week", "month", "year"
    ));

    private static final String PERIOD_DESCRIPTION = """
            运势时段，不传则默认返回【今日运势】，可选：
            day 今日、tomorrow 明日、week 本周、month 本月、year 本年
            """;

    /** 支持的十二星座英文名 */
    private static final Set<String> VALID_STARS = new HashSet<>(Arrays.asList(
            "aries", "taurus", "gemini", "cancer", "leo", "virgo",
            "libra", "scorpio", "sagittarius", "capricorn", "aquarius", "pisces"
    ));

    private static final String STAR_DESCRIPTION = """
            星座英文名，支持：
            aries 白羊座、taurus 金牛座、gemini 双子座、cancer 巨蟹座、leo 狮子座、virgo 处女座、
            libra 天秤座、scorpio 天蝎座、sagittarius 射手座、capricorn 摩羯座、aquarius 水瓶座、pisces 双鱼座
            """;

    @Value("${lion.alapi.token:}")
    private String alapiToken;

    private final RestClient restClient = RestClient.builder()
            .baseUrl(API_BASE)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 查询指定星座的运势（默认今日，可选明日 / 周 / 月 / 年）。
     *
     * @param star   星座英文名
     * @param period 运势时段：day 今日、tomorrow 明日、week 本周、month 本月、year 本年（默认 day）
     * @return 格式化后的运势文本
     */
    @Tool(description = "查询十二星座运势（今日/明日/本周/本月/本年）。当用户询问星座运势时调用，返回中文运势文本。")
    public String queryStarFortune(
            @ToolParam(description = STAR_DESCRIPTION, required = true) String star,
            @ToolParam(description = PERIOD_DESCRIPTION, required = false) String period) {
        if (star == null || star.isBlank()) {
            return "错误：请提供要查询的星座（例如 aries / 白羊座）。";
        }

        String normalized = star.trim().toLowerCase();
        if (!VALID_STARS.contains(normalized)) {
            return "错误：不支持的星座 \"" + star + "\"。请使用十二星座英文名，例如 aries（白羊座）、libra（天秤座）等。";
        }

        String normalizedPeriod = (period == null || period.isBlank()) ? "day" : period.trim().toLowerCase();
        if (!VALID_PERIODS.contains(normalizedPeriod)) {
            return "错误：不支持的运势时段 \"" + period + "\"。可选：day 今日、tomorrow 明日、week 本周、month 本月、year 本年。";
        }

        if (alapiToken == null || alapiToken.isBlank()) {
            return "错误：未配置 ALAPI Token（lion.alapi.token），无法查询星座运势。";
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", alapiToken.trim());
        form.add("star", normalized);

        try {
            String body = restClient.post()
                    .uri(API_PATH)
                    .body(form)
                    .retrieve()
                    .body(String.class);

            log.debug("ALAPI 星座运势响应：{}", body);

            if (body == null || body.isBlank()) {
                return "错误：星座运势接口返回空响应。";
            }

            JsonNode root = objectMapper.readTree(body);
            int code = root.path("code").asInt(0);
            String msg = root.path("msg").asText("unknown");
            if (code != 200) {
                return "星座运势接口调用失败，错误码：" + code + "，错误信息：" + msg;
            }

            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return "接口返回成功，但无运势内容：" + msg;
            }

            JsonNode periodNode = data.path(normalizedPeriod);
            if (periodNode.isMissingNode() || periodNode.isNull()) {
                return "接口返回成功，但缺少时段 \"" + normalizedPeriod + "\" 的数据。";
            }

            return "【" + normalized + " · " + periodName(normalizedPeriod) + "运势】\n" + formatPeriod(periodNode);
        } catch (Exception e) {
            log.error("调用 ALAPI 星座运势接口失败", e);
            return "调用星座运势接口失败：" + e.getMessage();
        }
    }

    /**
     * 将一个时段节点的运势格式化为清晰的中文文本。
     */
    private String formatPeriod(JsonNode node) {
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

    private String safe(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "-";
        }
        String text = value.asText().trim();
        return text.isEmpty() ? "-" : text;
    }
}
