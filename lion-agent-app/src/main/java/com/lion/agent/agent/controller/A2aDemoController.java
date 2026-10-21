package com.lion.agent.agent.controller;

import com.lion.agent.agent.service.A2aDemoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A2A（Agent2Agent）子 Agent 示例入口（免登录，仅本地演示用）
 */
@Tag(name = "24-Agent Utils 示例", description = "spring-ai-agent-utils 社区库示例：TodoWrite / TaskTool / SkillsTool / A2A")
@RestController
@RequestMapping("/api/agent/demo/a2a")
@RequiredArgsConstructor
public class A2aDemoController {

    private static final String DEFAULT_TASK = "请把这句话翻译成英文：我们计划在下半年上线新的优惠券系统。";

    private final A2aDemoService a2aDemoService;

    @Operation(summary = "A2A 子 Agent 示例",
            description = "本地子 Agent 与远程 A2A Agent 一起注册到 Task 工具，由模型自主选择派给谁")
    @GetMapping
    public String run(@RequestParam(required = false) String task) {
        String target = (task == null || task.isBlank()) ? DEFAULT_TASK : task;
        return a2aDemoService.run(target);
    }

    @Operation(summary = "探测远程 A2A 服务端的 AgentCard",
            description = "请求 /.well-known/agent-card.json，确认服务端是否就绪（当前默认是占位地址，预期失败）")
    @GetMapping("/card")
    public String probe() {
        return a2aDemoService.probe();
    }
}
