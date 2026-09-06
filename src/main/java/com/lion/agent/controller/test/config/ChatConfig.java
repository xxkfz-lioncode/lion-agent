package com.lion.agent.controller.test.config;

import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {
    /**
     * 结构化输出专用 ChatClient：仅开启原生结构化输出（不挂重 Advisor），供测试控制器注入。
     * <p>
     */
    @Bean
    public ChatClient structChatClient(ChatClient.Builder builder) {
        return builder
                .defaultAdvisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .build();
    }
}
