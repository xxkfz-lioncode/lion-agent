package com.lion.agent.agent.controller;

import com.lion.agent.agent.dto.AgentPlanResult;
import com.lion.agent.agent.service.AgentUtilsDemoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * spring-ai-agent-utils 示例入口（免登录，仅本地演示用）
 */
@Tag(name = "24-Agent Utils 示例", description = "spring-ai-agent-utils 社区库示例：TodoWrite 任务清单")
@RestController
@RequestMapping("/api/agent/demo")
@RequiredArgsConstructor
public class AgentUtilsDemoController {

    private static final String DEFAULT_TASK = """
            请帮我组织一个小型开发计划：为在线商城增加优惠券功能。
            你需要先拆解任务，再依次说明数据模型、接口、测试点和发布注意事项。
            请使用 TodoWrite 来组织你的任务。
            """;

    private final AgentUtilsDemoService agentUtilsDemoService;

    @Operation(summary = "TodoWrite 任务清单示例",
            description = "用 spring-ai-agent-utils 的 TodoWriteTool，让模型显式拆解并推进任务")
    @GetMapping("/plan")
    public AgentPlanResult plan(@RequestParam(required = false) String task) {
        String target = (task == null || task.isBlank()) ? DEFAULT_TASK : task;
        return agentUtilsDemoService.plan(target);
    }

    @Operation(summary = "TodoWrite 示例（POST，任务文本较长时用）")
    @PostMapping("/plan")
    public AgentPlanResult planPost(@RequestParam String task) {
        return agentUtilsDemoService.plan(task);
    }
}
