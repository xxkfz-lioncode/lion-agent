package com.lion.agent;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import java.time.Duration;
import java.time.Instant;

/**
 * Lion Agent 智能问答系统启动类
 * Spring AI 中文文档：https://www.spring-doc.cn/spring-ai/2.0.0-SNAPSHOT/getting-started.html
 */
@Slf4j
@SpringBootApplication
@MapperScan("com.lion.agent.mapper")
public class LionAgentApplication {

    public static void main(String[] args) {
        Instant start = Instant.now();
        ConfigurableApplicationContext context = SpringApplication.run(LionAgentApplication.class, args);
        // 应用完全就绪后打印启动信息，便于确认端口、环境等关键参数
        printStartupInfo(context, start);
    }

    /**
     * 输出启动信息：应用名、运行环境、服务地址、接口文档地址、启动耗时、JDK 版本
     */
    private static void printStartupInfo(ConfigurableApplicationContext context, Instant start) {
        ConfigurableEnvironment env = context.getEnvironment();
        String appName = env.getProperty("spring.application.name", "lion-agent");
        String port = env.getProperty("server.port", "8080");
        String contextPath = env.getProperty("server.servlet.context-path", "");
        String profiles = String.join(",", env.getActiveProfiles());
        if (profiles.isBlank()) {
            profiles = "default";
        }
        double costSeconds = Duration.between(start, Instant.now()).toMillis() / 1000.0;
        String cost = String.format("%.2f s", costSeconds);
        String jdk = System.getProperty("java.version");
        // springdoc.swagger-ui.enabled 在 dev 默认为 true，prod 显式关闭
        boolean docEnabled = env.getProperty("springdoc.swagger-ui.enabled", Boolean.class, true);
        String docInfo = docEnabled
                ? String.format("接口文档  : http://localhost:%s%s/swagger-ui/index.html%n"
                        + "  接口JSON : http://localhost:%s%s/v3/api-docs", port, contextPath, port, contextPath)
                : "  接口文档  : 已关闭（prod 环境默认禁用）";

        // Actuator 监控：management.endpoints.web.exposure.include=* 已暴露全部端点（yml 中配置）；
        // base-path 未显式配置时默认 /actuator
        String actBaseRaw = env.getProperty("management.endpoints.web.base-path", "/actuator");
        String actBase = actBaseRaw.startsWith("/") ? actBaseRaw : "/" + actBaseRaw;
        String monitorInfo = String.format("监控端点  : http://localhost:%s%s%s%n"
                + "  /health     : http://localhost:%s%s%s/health%n"
                + "  /beans      : http://localhost:%s%s%s/beans%n"
                + "  /metrics    : http://localhost:%s%s%s/metrics%n"
                + "  /env        : http://localhost:%s%s%s/env%n"
                + "  /loggers    : http://localhost:%s%s%s/loggers%n"
                + "  /threaddump : http://localhost:%s%s%s/threaddump",
                port, contextPath, actBase,
                port, contextPath, actBase,
                port, contextPath, actBase,
                port, contextPath, actBase,
                port, contextPath, actBase,
                port, contextPath, actBase,
                port, contextPath, actBase);

        log.info("""
                
                ==========================================================
                        {} 启动成功！
                ==========================================================
                  应用名称  : {}
                  运行环境  : {}
                  服务地址  : http://localhost:{}{}
                  {}
                  {}
                  启动耗时  : {}
                  JDK 版本 : {}
                ==========================================================
                """, appName, appName, profiles, port, contextPath, docInfo, monitorInfo, cost, jdk);
    }
}
