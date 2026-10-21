package com.lion.agent.tools.base;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 日期时间工具集：向大模型暴露获取系统当前时间的能力。
 * <p>
 * 纯本地计算（无外部依赖、无 IO），因此工具方法本身不需要熔断降级：
 * 唯一的失败点是入参格式非法，已被 try-catch 兜住并返回可读提示。
 * <p>
 * 注意：{@link Tool} 的 description 是模型判断"要不要调用、怎么传参"的唯一依据，
 * 统一使用英文书写（明确能力 + 何时使用 + 返回格式），中文描述在部分模型上召回率更低。
 */
@Slf4j
@Component
public class DateTools {

    /** 默认返回格式：yyyy-MM-dd HH:mm:ss */
    private static final DateTimeFormatter DEFAULT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 获取系统当前时间。
     *
     * @param pattern 可选输出格式（Java DateTimeFormatter 语法），为空时使用默认格式 yyyy-MM-dd HH:mm:ss
     * @return 当前时间字符串，例如 "2026-08-23 10:33:06"
     */
    @Tool(name = "getNowDate",
          description = "Get the current system date and time. Returns a formatted string such as \"2026-08-23 10:33:06\". "
                  + "Use it whenever the user asks for today's date, the current time, the current year/month/weekday, "
                  + "or when answering questions like \"how many days until ...\" that require the real current time. "
                  + "The optional 'pattern' argument customizes the output format (Java DateTimeFormatter syntax, "
                  + "e.g. \"yyyy-MM-dd\" or \"HH:mm:ss\"); leave it empty for the default \"yyyy-MM-dd HH:mm:ss\".")
    public String getNowDate(@ToolParam(description = "Optional output format in Java DateTimeFormatter syntax, e.g. \"yyyy-MM-dd\". Leave empty for the default \"yyyy-MM-dd HH:mm:ss\".", required = false) String pattern) {
        LocalDateTime now = LocalDateTime.now();
        if (!StringUtils.hasText(pattern)) {
            return now.format(DEFAULT_FORMATTER);
        }
        try {
            return now.format(DateTimeFormatter.ofPattern(pattern.trim()));
        } catch (IllegalArgumentException e) {
            log.warn("非法的日期格式 pattern: {}", pattern);
            return "错误：日期格式不合法 \"" + pattern + "\"，请使用 Java DateTimeFormatter 语法，例如 \"yyyy-MM-dd HH:mm:ss\"。";
        }
    }
}
