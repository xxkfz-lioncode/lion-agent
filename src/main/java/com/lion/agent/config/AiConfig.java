package com.lion.agent.config;

import com.lion.agent.advisor.ConversationSummaryAdvisor;
import com.lion.agent.advisor.QaCacheAdvisor;
import com.lion.agent.advisor.TokenUsageAdvisor;
import com.lion.agent.mapper.ChatMessageMapper;
import com.lion.agent.mapper.ConversationSummaryMapper;
import com.lion.agent.service.QaCacheService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 配置
 * <p>
 * 由 Spring AI 自动配置提供 ChatClient.Builder 与 ChatMemoryRepository（内存版），
 * 此处显式构建 ChatClient Bean 与窗口记忆 Bean，供业务层注入使用。
 * ChatMemoryRepository 内存存储
 *
 */
@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory,
                                 QaCacheService qaCacheService, ChatMessageMapper chatMessageMapper,
                                 ConversationSummaryMapper summaryMapper) {
        ChatClient.Builder builder = chatClientBuilder
                .defaultAdvisors(
                        // 全局 Token 用量统计（同步 + 流式），置于调用链最外层，拿到最终响应
                        new TokenUsageAdvisor(),
                        // 语义缓存：相似问题命中直接复用历史回答（短路跳过模型调用），回答完成后自动回写缓存
                        new QaCacheAdvisor(qaCacheService),
                        // 会话记忆：历史从 chat_message 表读取 + 增量压缩摘要（持久化到 chat_conversation_summary 表）
                        new ConversationSummaryAdvisor(chatClientBuilder, chatMessageMapper, summaryMapper, 100)
                );
        return builder.build();
    }

    /**
     * 记忆方式一：滑动窗口（按条数）
     * 用于在调用大模型时携带历史上下文实现多轮记忆。
     */
    @Bean
    public ChatMemory messageWindowChatMemory(JdbcChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                // 每个会话保留最近 20 条消息
                .maxMessages(500)
                .build();
    }


}
