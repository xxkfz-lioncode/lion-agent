package com.lion.a2a.config;

import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 远程 Agent 的「名片」。
 *
 * <p>用 A2A SDK 的 {@link AgentCard} 类型构造，而不是手写 JSON——手写 JSON 很容易漏字段
 * （{@code defaultInputModes} / {@code skills[].tags} 等都是客户端必填），
 * 一旦缺失客户端会直接报 {@code Could not unmarshal agent card response}。
 * 交给 SDK 的类型能保证字段齐全、命名正确。
 *
 * <p>注意：这里只放「名字 + 一句话描述」，客户端上下文里永远只有这点信息（渐进式披露），
 * Agent 内部的实现、工具、prompt 都不会外泄。
 */
@Configuration
public class A2aAgentCardConfig {

    @Bean
    public AgentCard agentCard(@Value("${lion.a2a.self-url:http://localhost:9999}") String selfUrl) {
        return new AgentCard.Builder()
                .name("远程天气助手")
                .description("远程 A2A 天气 Agent（真实模型版）：调用天气工具查询指定城市的"
                        + "温度、天气现象、风力与湿度。只处理天气相关需求，其他问题不要派给它。")
                // url 决定客户端后续 JSON-RPC 打向哪里，必须是客户端能访问到的地址
                .url(selfUrl)
                .version("1.0.0")
                .protocolVersion("0.3.0")
                // 与客户端 SDK 默认传输方式对齐
                .preferredTransport("JSONRPC")
                // 不声明流式 → 客户端走 message/send 而不是 message/stream
                .capabilities(new AgentCapabilities(false, false, false, null))
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                // skills：主 Agent 在 Task 工具目录里看到的就是这些（id + 描述）
                // 描述写得越清楚，主 Agent 越容易在正确的场景选中这个远程 Agent。
                // 只声明天气一项——职责单一，主 Agent 才不会把翻译/摘要之类的活也派过来
                .skills(List.of(
                        new AgentSkill("weather", "查天气",
                                "查询指定城市的当前天气（温度、天气现象、风力、湿度）。"
                                        + "当用户询问某个城市的天气、气温、是否下雨时使用。",
                                List.of("weather", "temperature"),
                                List.of("广州今天天气怎么样？", "北京现在多少度？"),
                                List.of("text"), List.of("text"), null)))
                .build();
    }
}
