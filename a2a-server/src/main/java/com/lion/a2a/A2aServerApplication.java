package com.lion.a2a;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 最小可用的 A2A 服务端（演示用）。
 *
 * <p>只做两件事，符合 A2A 规范的最低要求：
 * <ol>
 *   <li>{@code GET /.well-known/agent-card.json} —— 暴露 AgentCard（名字/能力/技能）</li>
 *   <li>{@code POST /} 的 JSON-RPC 2.0 —— 实现 {@code message/send}，返回 Task</li>
 * </ol>
 *
 * <p>返回内容是 mock 的（按关键词匹配），不接真实模型，启动快、无需 Key。
 */
@SpringBootApplication
public class A2aServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(A2aServerApplication.class, args);
    }
}
