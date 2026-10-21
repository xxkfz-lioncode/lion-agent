package com.lion.agent.tools.base;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * HTML 文件生成工具：把大模型产出的 HTML 源码落盘为 .html 文件，并给出可访问的 URL。
 * <p>
 * 典型场景：用户要"做个页面/报表/简历/落地页"时，模型直接返回一大段 HTML 会淹没对话，
 * 这里把源码写成文件，模型只需把访问地址回给用户。
 * <p>
 * 设计要点：
 * <ol>
 *   <li>输出目录默认落在 {@code upload/html/}：{@link com.lion.agent.config.WebConfig} 已把
 *       {@code /upload/**} 静态映射到工作目录，因此写进去就能通过 URL 直接访问，无需新增映射。</li>
 *   <li>文件名必须净化：模型可能传入 {@code ../../etc/passwd} 或带空格/特殊字符的名字，
 *       先剔除非法字符，再校验解析后的路径仍在输出目录内（防路径穿越）。</li>
 *   <li>纯本地磁盘 IO，无外部依赖，故不加熔断；所有失败统一返回可读文本而不是抛异常，
 *       避免打断工具调用链路。</li>
 * </ol>
 */
@Slf4j
@Component
public class HtmlFileTools {

    /** 文件名时间戳格式：page-20261011-153000.html */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 文件名非法字符（保留中英文、数字、下划线、中划线、点） */
    private static final Pattern ILLEGAL_CHARS = Pattern.compile("[^A-Za-z0-9_.\\-\\u4e00-\\u9fa5]");

    /** 单文件大小上限：1MB，避免模型吐出超长内容把磁盘/响应拖垮 */
    private static final int MAX_HTML_LENGTH = 1024 * 1024;

    /** 落盘目录（绝对路径） */
    private final Path outputDir;

    /** 浏览器访问前缀，与 WebConfig 的 /upload/** 静态映射对应 */
    private final String urlPrefix;

    public HtmlFileTools(@Value("${lion.tools.html.dir:upload/html/}") String dir,
                         @Value("${lion.tools.html.url-prefix:/upload/html/}") String urlPrefix) {
        this.outputDir = Paths.get(dir).toAbsolutePath().normalize();
        this.urlPrefix = urlPrefix.endsWith("/") ? urlPrefix : urlPrefix + "/";
    }

    /**
     * 生成 HTML 文件。
     *
     * @param html      完整 HTML 源码
     * @param fileName  可选文件名，缺省按时间戳自动生成
     * @param overwrite 是否覆盖同名文件，缺省为 false
     * @return 多行结果：文件名 + 访问地址 + 绝对路径 + 大小；失败时返回错误说明
     */
    @Tool(name = "generateHtmlFile",
          description = "Save a complete HTML document to disk as a .html file and return the URL where it can be opened. "
                  + "Use it whenever the user asks for a web page, report, resume, dashboard, landing page, form or any "
                  + "visual HTML deliverable, instead of dumping a huge code block into the chat. "
                  + "Pass the full HTML source (including <!DOCTYPE html> or at least <html>) as 'html'. "
                  + "Returns the file name, the accessible URL, the absolute path and the file size in KB, "
                  + "or an explicit error message when the content is empty, too large, or the file already exists.")
    public String generateHtmlFile(
            @ToolParam(description = "Complete HTML source code to write, e.g. \"<!DOCTYPE html><html><body><h1>Hello</h1></body></html>\". Must be valid, self-contained HTML (inline CSS/JS).") String html,
            @ToolParam(description = "Optional file name, with or without the .html extension, e.g. \"report\" or \"report.html\". Leave empty to auto-generate one from the current timestamp.", required = false) String fileName,
            @ToolParam(description = "Set true to overwrite an existing file with the same name. Defaults to false.", required = false) Boolean overwrite
    ) {
        if (!StringUtils.hasText(html)) {
            return "错误：HTML 内容为空，请提供完整的 HTML 源码。";
        }
        if (html.length() > MAX_HTML_LENGTH) {
            return "错误：HTML 内容过长（" + html.length() + " 字符），上限 1MB，请精简后再生成。";
        }

        String name = resolveFileName(fileName);
        Path target = outputDir.resolve(name).normalize();
        if (!target.startsWith(outputDir)) {
            return "错误：文件名不合法，禁止写入输出目录之外的路径。";
        }

        if (Files.exists(target) && !Boolean.TRUE.equals(overwrite)) {
            return "错误：文件已存在「" + name + "」。换一个文件名，或传 overwrite=true 覆盖。";
        }

        try {
            Files.createDirectories(outputDir);
            Files.writeString(target, html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("生成 HTML 文件失败, dir={}, name={}", outputDir, name, e);
            return "错误：生成 HTML 文件失败：" + e.getMessage();
        }

        int sizeKb = Math.max(1, html.getBytes(StandardCharsets.UTF_8).length / 1024);
        return String.join("\n",
                "HTML 文件已生成。",
                "文件名：" + name,
                "访问地址：" + urlPrefix + name,
                "绝对路径：" + target,
                "大小：约 " + sizeKb + " KB");
    }

    /** 文件名净化：去路径分隔符与非法字符，补齐 .html 后缀；为空时按时间戳生成 */
    private String resolveFileName(String fileName) {
        String raw = StringUtils.hasText(fileName) ? fileName.trim() : "";
        String cleaned = ILLEGAL_CHARS.matcher(raw).replaceAll("-");
        // 去掉首尾连续的中划线，避免出现 "-.html" 这类空名字
        cleaned = cleaned.replaceAll("^-+", "");
        if (!StringUtils.hasText(cleaned) || ".".equals(cleaned)) {
            cleaned = "page-" + LocalDateTime.now().format(TIMESTAMP);
        }
        return cleaned.toLowerCase().endsWith(".html") ? cleaned : cleaned + ".html";
    }
}
