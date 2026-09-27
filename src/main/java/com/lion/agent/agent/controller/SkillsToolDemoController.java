package com.lion.agent.agent.controller;

import com.lion.agent.agent.service.SkillsToolDemoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SkillsTool（Skill 按需加载 / 渐进式披露）示例入口（免登录，仅本地演示用）
 */
@Tag(name = "24-Agent Utils 示例", description = "spring-ai-agent-utils 社区库示例：TodoWrite / TaskTool / SkillsTool")
@RestController
@RequestMapping("/api/agent/demo/skill")
@RequiredArgsConstructor
public class SkillsToolDemoController {

    private static final String DEFAULT_TASK = "帮我梳理一下发版前要检查什么";

    private final SkillsToolDemoService skillsToolDemoService;

    @Operation(summary = "SkillsTool 示例",
            description = "模型判断需求命中哪个 Skill 后，调 Skill 工具加载该 SKILL.md 的完整正文再回答")
    @GetMapping
    public String run(@RequestParam(required = false) String task) {
        String target = (task == null || task.isBlank()) ? DEFAULT_TASK : task;
        return skillsToolDemoService.run(target);
    }

    @Operation(summary = "SkillsTool 示例（POST，问题文本较长时用）")
    @PostMapping
    public String runPost(@RequestParam String task) {
        return skillsToolDemoService.run(task);
    }
}
