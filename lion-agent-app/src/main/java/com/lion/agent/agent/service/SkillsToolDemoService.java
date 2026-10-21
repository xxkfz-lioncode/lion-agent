package com.lion.agent.agent.service;

import com.lion.agent.advisor.ChatLoggerAdvisor;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * spring-ai-agent-utils 的 {@link SkillsTool} 示例：Skill 的「渐进式披露」。
 *
 * <p>与把 SOP 全部塞进 system prompt 的做法对比：
 * <ul>
 *   <li><b>启动时</b>：只把每份 SKILL.md 的 {@code name + description} 列进工具描述（每份几十 token）</li>
 *   <li><b>命中时</b>：模型调 {@code Skill(name="xxx")}，工具把该 SKILL.md 的<b>完整正文</b>作为返回值注入对话</li>
 *   <li><b>没命中</b>：正文永远不进上下文，不花 token、也不干扰判断</li>
 * </ul>
 *
 * <p>想看清楚这个过程，打开日志观察两段：
 * <ol>
 *   <li>{@code [AI-REQUEST] 工具: (1 个): Skill}——工具描述里只有 available_skills 的摘要</li>
 *   <li>{@code [AI-TOOL] 工具返回: Skill -> ...}——整份 SKILL.md 正文在这里回流</li>
 * </ol>
 */
@Slf4j
@Service
public class SkillsToolDemoService {

    private static final String SYSTEM_PROMPT = """
            你是一个中文技术助手。
            当用户的需求明显属于某个 Skill 的职责时，先用 Skill 工具加载它的完整说明，再严格按说明中的结构与要求回答。
            加载后请说明你加载了哪个 Skill，然后按说明书的章节组织回答。
            如果所有 Skill 都不匹配，就正常回答，不要编造 Skill。
            """;

    /**
     * classpath 下的 Skill <b>目录</b>清单（每个目录下放一份 SKILL.md）。
     *
     * <p><b>坑（0.12.0 实测）</b>：{@code addSkillsResources} 传的是「目录」而不是 SKILL.md 文件——
     * 它内部对每个 Resource 走 {@code resource.getFile().toPath()} 再调 {@code Skills.loadDirectory()}，
     * 传文件路径会直接抛 {@code RuntimeException: Path is not a directory}。
     *
     * <p>用 ClassPathResource 而不是 {@code addSkillsDirectory("src/main/resources/skills")}：
     * 后者依赖开发期的文件系统相对路径，打成 jar 后失效；ClassPathResource 在开发态（target/classes）
     * 与 jar 态（走 {@code loadFromClasspath} 扫描）都能加载。
     */
    private static final List<String> SKILL_DIRECTORIES = List.of(
            "skills/release-checklist",
            "skills/sql-review");

    private final ChatModel chatModel;

    public SkillsToolDemoService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 跑一次对话，让模型自己判断要不要加载 Skill。
     *
     * @param task 用户问题，例如「帮我梳理一下发版前要检查什么」
     * @return 模型最终回答
     */
    public String run(String task) {
        List<Resource> resources = SKILL_DIRECTORIES.stream()
                .map(ClassPathResource::new)
                .map(Resource.class::cast)
                .toList();

        // build() 直接返回 ToolCallback，与其他工具一样注册进 tools(...)
        var skillTool = SkillsTool.builder()
                .addSkillsResources(resources)
                .build();

        log.info("[agent-utils] 已注册 Skill 目录：{}", SKILL_DIRECTORIES);

        return ChatClient.builder(chatModel)
                .build()
                .prompt()
                .system(SYSTEM_PROMPT)
                .user(task)
                .tools(skillTool)
                // demo 是自建 ChatClient，拿不到 AiConfig 的默认 Advisor，日志手动挂（说明见 TaskToolDemoService）
                .advisors(new ChatLoggerAdvisor(0, 500),
                        new ChatLoggerAdvisor(Integer.MAX_VALUE - 50, 3000, true))
                .call()
                .content();
    }
}
