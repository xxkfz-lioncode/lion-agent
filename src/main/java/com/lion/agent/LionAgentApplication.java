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
     * 输出启动信息：应用名、运行环境、服务地址、启动耗时、JDK 版本
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

        log.info("""
                
                ==========================================================
                        {} 启动成功！
                ==========================================================
                  应用名称  : {}
                  运行环境  : {}
                  服务地址  : http://localhost:{}{}
                  启动耗时  : {}
                  JDK 版本 : {}
                ==========================================================
                """, appName, appName, profiles, port, contextPath, cost, jdk);
    }
}
