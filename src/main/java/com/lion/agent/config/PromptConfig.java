package com.lion.agent.config;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 系统提示词统一配置管理
 *
 * <p>集中维护系统提示词模板（{@code resources/prompts/*.st}）及其渲染变量，
 * 业务层通过注入本组件获取渲染后的提示词，避免模板路径与变量在各业务类中散落。</p>
 */
@Component
public class PromptConfig {

    /** 系统提示词模板路径（支持 {agentName} 等变量） */
    private static final String SYSTEM_PROMPT_TEMPLATE_PATH = "prompts/system-prompt.st";

    /** 系统提示词模板（类加载时初始化一次，不可变、线程安全） */
    private static final PromptTemplate SYSTEM_PROMPT_TEMPLATE =
            new PromptTemplate(new ClassPathResource(SYSTEM_PROMPT_TEMPLATE_PATH));

    /** 渲染变量：Agent 角色名（可由 application.yml 的 lion.prompt.agent-name 覆盖） */
    @Value("${lion.prompt.agent-name:Lion Agent}")
    private String agentName;

    /**
     * 渲染系统提示词（使用配置的角色名）
     */
    public String renderSystemPrompt() {
        return SYSTEM_PROMPT_TEMPLATE.render(Map.of("agentName", agentName));
    }

    /**
     * 渲染系统提示词（自定义角色名，覆盖默认配置）
     */
    public String renderSystemPrompt(String agentName) {
        return SYSTEM_PROMPT_TEMPLATE.render(Map.of("agentName", agentName));
    }
}
