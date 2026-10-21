package com.lion.agent.agent.controller;

import com.lion.agent.agent.service.TaskToolDemoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TaskTool（子 Agent 派活）示例入口（免登录，仅本地演示用）
 */
@Tag(name = "24-Agent Utils 示例", description = "spring-ai-agent-utils 社区库示例：TodoWrite / TaskTool")
@RestController
@RequestMapping("/api/agent/demo/task")
@RequiredArgsConstructor
public class TaskToolDemoController {

    private static final String DEFAULT_TASK = "请把这句话翻译成英文：我们计划在下半年上线新的优惠券系统。";

    private final TaskToolDemoService taskToolDemoService;

    @Operation(summary = "TaskTool 子 Agent 示例",
            description = "主 Agent 调用 Task 工具，把任务派给 translator / summarizer 子 Agent 执行")
    @GetMapping
    public String run(@RequestParam(required = false) String task) {
        String target = (task == null || task.isBlank()) ? DEFAULT_TASK : task;
        return taskToolDemoService.run(target);
    }

    @Operation(summary = "TaskTool 示例（POST，任务文本较长时用）")
    @PostMapping
    public String runPost(@RequestParam String task) {
        return taskToolDemoService.run(task);
    }
}
