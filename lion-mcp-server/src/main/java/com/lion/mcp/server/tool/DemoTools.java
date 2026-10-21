package com.lion.mcp.server.tool;

import com.lion.mcp.server.auth.McpAuthVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpMeta;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * MCP 工具示例（逻辑刻意保持简单，重点在演示传输与上下文透传）。
 *
 * <p>方法上加 {@link McpTool} 即被 {@code annotation-scanner} 扫描注册为 MCP 工具；
 * 参数上的 {@link McpToolParam} 会生成工具的 JSON Schema，模型据此决定怎么传参。</p>
 */
@Component
public class DemoTools {

    private static final Logger log = LoggerFactory.getLogger(DemoTools.class);

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Environment env;

    /** 凭据校验：逻辑和常量都收在 McpAuthVerifier 里，这里只取结果用于展示 */
    private final McpAuthVerifier authVerifier;

    public DemoTools(Environment env, McpAuthVerifier authVerifier) {
        this.env = env;
        this.authVerifier = authVerifier;
    }

    /**
     * 无参工具：返回服务端当前配置，并打印调用日志（便于确认客户端是否真的调到了本工具）
     */
    @McpTool(name = "getServerConfig",
             description = "Get the runtime configuration of this MCP server: server name, version, "
                     + "active profile, transport mode and endpoint. "
                     + "Use it when the user asks how this server is configured or what transport is in use.")
    public String getServerConfig() {
        log.info("[MCP TOOL] getServerConfig invoked");

        String[] active = env.getActiveProfiles();
        String profile = active.length > 0 ? String.join(",", active)
                                           : String.join(",", env.getDefaultProfiles());
        boolean stdio = Boolean.parseBoolean(env.getProperty("spring.ai.mcp.server.stdio", "false"));
        String transport = stdio ? "stdio" : "streamable-http";
        String endpoint = stdio ? "stdin/stdout"
                : "http://localhost:" + env.getProperty("server.port", "8080")
                + env.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp");

        String config = String.join("\n",
                "server.name      : " + env.getProperty("spring.ai.mcp.server.name", "mcp-server"),
                "server.version   : " + env.getProperty("spring.ai.mcp.server.version", "unknown"),
                "active.profile   : " + profile,
                "transport        : " + transport,
                "endpoint         : " + endpoint,
                "mcp.server.type  : " + env.getProperty("spring.ai.mcp.server.type", "SYNC"),
                "java.version     : " + System.getProperty("java.version"),
                "os.name          : " + System.getProperty("os.name"),
                "server.time      : " + LocalDateTime.now().format(FORMATTER));

        log.info("[MCP TOOL] getServerConfig returned:\n{}", config);
        return config;
    }

    /**
     * 带参工具：两数四则运算
     */
    @McpTool(name = "calculate",
             description = "Evaluate a simple arithmetic expression with two numbers. "
                     + "Supported operators: + - * /.")
    public String calculate(
            @McpToolParam(description = "The first number", required = true) double left,
            @McpToolParam(description = "The second number", required = true) double right,
            @McpToolParam(description = "Operator, one of: + - * /", required = true) String operator) {
        double result = switch (operator.trim()) {
            case "+" -> left + right;
            case "-" -> left - right;
            case "*" -> left * right;
            case "/" -> {
                if (right == 0) {
                    throw new IllegalArgumentException("division by zero");
                }
                yield left / right;
            }
            default -> throw new IllegalArgumentException("unsupported operator: " + operator);
        };
        return left + " " + operator + " " + right + " = " + result;
    }

    /**
     * 文本处理工具
     */
    @McpTool(name = "textTransform",
             description = "Transform the given text: convert to upper case, lower case, or reverse it.")
    public String textTransform(
            @McpToolParam(description = "The text to transform", required = true) String text,
            @McpToolParam(description = "Mode: upper | lower | reverse", required = true) String mode) {
        return switch (mode.trim().toLowerCase(Locale.ROOT)) {
            case "upper" -> text.toUpperCase(Locale.ROOT);
            case "lower" -> text.toLowerCase(Locale.ROOT);
            case "reverse" -> new StringBuilder(text).reverse().toString();
            default -> throw new IllegalArgumentException("unsupported mode: " + mode);
        };
    }

    /**
     * 演示「客户端上下文透传」：读取 lion-agent 通过 {@code ToolContextToMcpMetaConverter}
     * 放进 {@code _meta} 的 user-id。
     *
     * <p>{@link McpMeta} 由框架按参数类型自动注入（它是 record，取值用 {@code get(key)}），
     * 不需要也不应该自己 new。客户端没传 meta 时这里会是空内容而不是 null 报错。</p>
     */
    @McpTool(name = "whoAmI",
             description = "Show the metadata that the MCP client attached to this call "
                     + "(for example the current user id). Useful to verify context propagation.")
    public String whoAmI(McpMeta meta) {
        if (meta == null || meta.meta() == null || meta.meta().isEmpty()) {
            return "未收到客户端透传的上下文（_meta 为空）";
        }
        Object userId = meta.get("user-id");
        McpAuthVerifier.AuthResult auth = authVerifier.verify(meta.get(McpAuthVerifier.AUTH_META_KEY));
        return "客户端透传的上下文 -> 用户ID=" + userId + "，凭据校验：" + auth.message();
    }

}
