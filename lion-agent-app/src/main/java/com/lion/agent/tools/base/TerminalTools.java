package com.lion.agent.tools.base;

import com.lion.agent.aspect.annotation.ToolPermission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 终端命令工具：让大模型在受控范围内执行 Shell / CMD 命令。
 * <p>
 * 典型场景：查看目录结构、读文件内容、跑构建/测试命令（mvn、npm）、查进程与端口、执行脚本。
 * 让模型"能动手"而不是只给建议。
 * <p>
 * 安全护栏（四层，缺一不可——把终端交给模型是高风险能力，必须默认收紧）：
 * <ol>
 *   <li><b>权限码</b>：类上标注 {@code @ToolPermission("terminal:exec")}，接入 Sa-Token 后按权限过滤；
 *       当前未实现 {@code StpInterface}，默认放行，接入即自动生效。</li>
 *   <li><b>高危命令黑名单</b>：删库/格式化/提权/fork 炸弹/下载即执行等命中直接拒绝，
 *       返回可读提示而不是抛异常。</li>
 *   <li><b>工作目录围栏</b>：命令只能在配置的工作目录及其子目录内执行，
 *       {@code ..} 逃逸一律拒绝。</li>
 *   <li><b>超时 + 输出截断</b>：默认 30s（上限 120s）后强制 kill 进程树，
 *       输出超过 8000 字符截断——既不拖死业务线程，也不把模型上下文撑爆。</li>
 * </ol>
 *
 * <p>执行留痕：每次调用（含超时、被黑名单拒绝）都把"时间/状态/工作目录/退出码/命令/完整输出"
 * 写入 {@code logs/terminal/<时间戳>-<命令摘要>.log}（UTF-8），日志文件绝对路径随结果一并返回；
 * 日志写失败只 warn，不影响命令结果。
 *
 * <p>已知限制：无交互能力（不支持需要 stdin 输入的命令），不做命令白名单，
 * 输出为一次性快照（非持续会话）。
 */
@Slf4j
@Component
@ToolPermission("terminal:exec")
public class TerminalTools {

    /** 默认超时（秒） */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** 超时上限（秒）：防止模型传一个超大值把线程挂死 */
    private static final int MAX_TIMEOUT_SECONDS = 120;

    /** 返回给模型的输出上限（字符），避免长日志挤爆上下文 */
    private static final int MAX_OUTPUT_LENGTH = 8000;

    /** 命令长度上限，避免模型拼接出巨型命令 */
    private static final int MAX_COMMAND_LENGTH = 2000;

    /** 日志文件名时间戳：20261011-153000 */
    private static final DateTimeFormatter LOG_FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 日志内可读时间：2026-10-11 15:30:00 */
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 日志文件名里命令摘要的最大字符数 */
    private static final int LOG_NAME_SUMMARY_LENGTH = 30;

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    /** Windows 控制台默认 GBK（cmd/PowerShell 中文输出），强按 UTF-8 解码会乱码 */
    private static final Charset OUTPUT_CHARSET = IS_WINDOWS ? Charset.forName("GBK") : StandardCharsets.UTF_8;

    /**
     * 高危命令黑名单（忽略大小写）：命中即拒绝执行。
     * 覆盖：递归删除根目录/家目录、提权、磁盘级写入/格式化、关机重启、强制删除、
     * 杀系统进程、全网可读可写、下载即执行、fork 炸弹、改注册表/系统账号。
     */
    private static final List<Pattern> DENIED_COMMANDS = Arrays.asList(
            Pattern.compile("rm\\s+(-[rfv]+\\s+)*(/|~|\\*|\\.\\.)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(^|\\s)(sudo|su|runas|doas)\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("mkfs|dd\\s+if=|>\\s*/dev/(sd|nvme|zero)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(^|\\s)(shutdown|reboot|halt|poweroff|init\\s+0)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("format\\s+[a-z]:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(^|\\s)(del|rd|rmdir)\\s+.*(/[fsq]|/s\\s+/q)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(^|\\s)(taskkill|tskill)\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("kill\\s+-9\\s+(-1|1\\b)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("chmod\\s+(-r\\s+)?777\\s+/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(curl|wget)[^|\\n]*\\|\\s*(ba|z|k|fi)?sh", Pattern.CASE_INSENSITIVE),
            Pattern.compile(":\\(\\)\\s*\\{", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(^|\\s)reg\\s+(delete|add)\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(^|\\s)net\\s+(user|localgroup)\\s", Pattern.CASE_INSENSITIVE)
    );

    /** 允许执行命令的根目录（绝对、已规范化） */
    private final Path workDir;

    /** 执行日志落盘目录（绝对、已规范化）：每次调用写一个 .log 文件，便于事后审计/排错 */
    private final Path logDir;

    public TerminalTools(@Value("${lion.tools.terminal.work-dir:}") String workDir,
                         @Value("${lion.tools.terminal.log-dir:logs/terminal/}") String logDir) {
        this.workDir = StringUtils.hasText(workDir)
                ? Paths.get(workDir.trim()).toAbsolutePath().normalize()
                : Paths.get("").toAbsolutePath().normalize();
        this.logDir = Paths.get(logDir).toAbsolutePath().normalize();
    }

    /**
     * 执行终端命令。
     *
     * @param command        命令行（Windows 走 cmd /c，其它走 sh -c）
     * @param workingDir     可选执行目录，只能是工作目录的相对子目录
     * @param timeoutSeconds 可选超时秒数，缺省 30，最大 120
     * @return 命令 + 工作目录 + 退出码 + 标准输出/错误；被拦截或执行失败时返回错误说明
     */
    @Tool(name = "runTerminalCommand",
          description = "Execute a shell command on the server (cmd /c on Windows, sh -c on Linux/macOS) and return its exit code and combined output. "
                  + "Use it when the user asks to inspect the workspace: list files, read a file, run a build or test command "
                  + "such as mvn/npm/git, check ports or processes, or run a script. "
                  + "Returns \"exit code\" plus stdout/stderr, truncated to 8000 characters. "
                  + "The full output is also persisted to a .log file and its absolute path is returned (rejected commands are logged too). "
                  + "Constraints: non-interactive commands only (no stdin input), default timeout 30s (max 120s), "
                  + "and the working directory must stay inside the configured workspace root. "
                  + "Destructive commands (rm -rf /, format, shutdown, curl | sh, ...) are rejected with an explicit error message.")
    public String runTerminalCommand(
            @ToolParam(description = "The command line to execute, e.g. \"dir\", \"git status\", \"mvn -q -DskipTests compile\". Non-interactive commands only.") String command,
            @ToolParam(description = "Optional working directory, relative to the workspace root, e.g. \"src\". Leave empty to use the workspace root.", required = false) String workingDir,
            @ToolParam(description = "Optional timeout in seconds, default 30, maximum 120.", required = false) Integer timeoutSeconds
    ) {
        if (!StringUtils.hasText(command)) {
            return "错误：命令不能为空。";
        }
        String cmd = command.trim();
        if (cmd.length() > MAX_COMMAND_LENGTH) {
            return "错误：命令过长（" + cmd.length() + " 字符），上限 " + MAX_COMMAND_LENGTH + " 字符。";
        }

        String denied = matchDenied(cmd);
        if (denied != null) {
            log.warn("终端命令被黑名单拦截: {}", cmd);
            // 被拒绝的请求同样留痕：安全审计既要看"执行了什么"，也要看"试图执行什么"
            String auditLog = writeLog(cmd, workDir, null, "（命令未执行，命中黑名单：" + denied + "）", "已拒绝");
            return "错误：该命令涉及高危操作，已被安全策略拒绝执行（匹配规则：" + denied + "）。"
                    + "如需执行请由人工在服务器上操作。\n审计日志：" + auditLog;
        }

        Path dir = resolveDir(workingDir);
        if (dir == null) {
            return "错误：工作目录不合法，只能在工作目录「" + workDir + "」及其子目录内执行命令。";
        }

        int timeout = timeoutSeconds == null || timeoutSeconds <= 0
                ? DEFAULT_TIMEOUT_SECONDS
                : Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS);

        Process process;
        try {
            ProcessBuilder builder = IS_WINDOWS
                    ? new ProcessBuilder("cmd.exe", "/c", cmd)
                    : new ProcessBuilder("/bin/sh", "-c", cmd);
            builder.directory(dir.toFile());
            // 合并 stderr 到 stdout：省掉一个读取线程，也避免输出交错
            builder.redirectErrorStream(true);
            process = builder.start();
        } catch (IOException e) {
            log.error("启动终端进程失败, cmd={}", cmd, e);
            return "错误：命令启动失败：" + e.getMessage();
        }

        // 必须在 waitFor 之前持续排空输出流，否则管道缓冲区写满会导致子进程阻塞假死
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread pump = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(buffer);
            } catch (IOException ignored) {
                // 进程被 kill 时流会提前关闭，属预期内
            }
        }, "terminal-output-pump");
        pump.setDaemon(true);
        pump.start();

        boolean finished;
        try {
            finished = process.waitFor(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return "错误：命令等待被中断。";
        }

        if (!finished) {
            process.destroyForcibly();
            try {
                pump.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String output = text(buffer);
            String logFile = writeLog(cmd, dir, null, output, "超时终止（" + timeout + " 秒）");
            return "错误：命令执行超时（超过 " + timeout + " 秒），已强制终止。已产生的输出：\n"
                    + truncate(output) + "\n日志文件：" + logFile;
        }

        try {
            pump.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int exitCode = process.exitValue();
        String output = text(buffer);
        String logFile = writeLog(cmd, dir, exitCode, output, "完成");
        return String.join("\n",
                "命令：" + cmd,
                "工作目录：" + dir,
                "退出码：" + exitCode + (exitCode == 0 ? "（成功）" : "（失败）"),
                "输出：",
                truncate(output),
                "日志文件：" + logFile);
    }

    /**
     * 把一次命令执行的完整信息落盘为 .log 文件（UTF-8），返回文件绝对路径。
     * <p>日志写失败不影响命令本身的结果：只 warn 并返回可读提示，绝不因为记日志把工具调用打挂。
     *
     * @param exitCode 退出码；命令未执行/超时被 kill 时为 null
     * @param status   执行状态：完成 / 超时终止 / 已拒绝
     */
    private String writeLog(String command, Path dir, Integer exitCode, String output, String status) {
        String fileName = LocalDateTime.now().format(LOG_FILE_STAMP) + "-" + summarize(command) + ".log";
        Path target = logDir.resolve(fileName).normalize();
        String content = String.join("\n",
                "执行时间：" + LocalDateTime.now().format(LOG_TIME),
                "状态：" + status,
                "工作目录：" + dir,
                "退出码：" + (exitCode == null ? "-" : exitCode),
                "命令：" + command,
                "---------- 输出 ----------",
                output == null || output.isBlank() ? "（无输出）" : output.trim(),
                "");
        try {
            Files.createDirectories(logDir);
            Files.writeString(target, content, StandardCharsets.UTF_8);
            return target.toString();
        } catch (IOException e) {
            log.warn("终端执行日志写入失败, file={}", target, e);
            return "（日志写入失败：" + e.getMessage() + "）";
        }
    }

    /** 日志文件名里的命令摘要：截断 + 去掉文件名非法字符 */
    private String summarize(String command) {
        String summary = command.length() > LOG_NAME_SUMMARY_LENGTH
                ? command.substring(0, LOG_NAME_SUMMARY_LENGTH)
                : command;
        String cleaned = summary.replaceAll("[^A-Za-z0-9_.\\-\\u4e00-\\u9fa5]", "_");
        return cleaned.isEmpty() ? "command" : cleaned;
    }

    /** 命中黑名单返回命中的规则，否则返回 null */
    private String matchDenied(String command) {
        for (Pattern pattern : DENIED_COMMANDS) {
            if (pattern.matcher(command).find()) {
                return pattern.pattern();
            }
        }
        return null;
    }

    /** 解析执行目录：限定在 workDir 内，越界或不存在返回 null */
    private Path resolveDir(String workingDir) {
        Path target = workDir;
        if (StringUtils.hasText(workingDir)) {
            target = workDir.resolve(workingDir.trim()).normalize();
        }
        if (!target.startsWith(workDir) || !Files.isDirectory(target)) {
            return null;
        }
        return target;
    }

    private String text(ByteArrayOutputStream buffer) {
        return new String(buffer.toByteArray(), OUTPUT_CHARSET);
    }

    private String truncate(String output) {
        String trimmed = output.trim();
        if (trimmed.length() <= MAX_OUTPUT_LENGTH) {
            return trimmed.isEmpty() ? "（无输出）" : trimmed;
        }
        return trimmed.substring(0, MAX_OUTPUT_LENGTH) + "\n...（输出过长已截断，仅显示前 " + MAX_OUTPUT_LENGTH + " 字符）";
    }
}
