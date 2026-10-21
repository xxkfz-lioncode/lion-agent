package com.lion.mcp.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * MCP Server 演示启动类。
 *
 * <p>同一份代码、同一个 jar，通过 profile 切换两种传输方式：</p>
 * <pre>
 *   java -jar mcp-server.jar --spring.profiles.active=stdio   # stdio（子进程，被客户端拉起）
 *   java -jar mcp-server.jar --spring.profiles.active=http    # streamable-http（独立 HTTP 服务）
 * </pre>
 *
 * <p>启动信息一律走日志框架输出：http 模式打到 stdout，stdio 模式由 logback 改道 stderr，
 * 避免污染 JSON-RPC 通道，因此这里不要用 System.out.println。</p>
 */
@SpringBootApplication
public class McpServerApplication {

    private static final Logger log = LoggerFactory.getLogger(McpServerApplication.class);

    public static void main(String[] args) {
        ConfigurableApplicationContext context =
                SpringApplication.run(McpServerApplication.class, args);

        Environment env = context.getEnvironment();
        String[] active = env.getActiveProfiles();
        String profile = active.length > 0 ? String.join(",", active)
                                           : String.join(",", env.getDefaultProfiles());
        boolean stdio = Boolean.parseBoolean(env.getProperty("spring.ai.mcp.server.stdio", "false"));

        log.info("=========================================================");
        log.info("MCP Server 启动成功!  (pid={})", ProcessHandle.current().pid());
        log.info("  name      : {}", env.getProperty("spring.ai.mcp.server.name", "mcp-server"));
        log.info("  version   : {}", env.getProperty("spring.ai.mcp.server.version", "unknown"));
        log.info("  profile   : {}", profile);
        if (stdio) {
            log.info("  transport : stdio (stdout 为协议通道，日志走 stderr)");
        } else {
            log.info("  transport : streamable-http");
            log.info("  endpoint  : http://localhost:{}{}",
                    env.getProperty("server.port", "8080"),
                    env.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp"));
        }
        log.info("=========================================================");
    }
}
