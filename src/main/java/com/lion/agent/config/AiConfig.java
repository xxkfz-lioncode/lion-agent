package com.lion.agent.config;

import com.lion.agent.advisor.ConversationSummaryAdvisor;
import com.lion.agent.advisor.LongTermMemoryAdvisor;
import com.lion.agent.advisor.QaCacheAdvisor;
import com.lion.agent.advisor.TokenUsageAdvisor;
import com.lion.agent.mapper.ChatMessageMapper;
import com.lion.agent.mapper.ConversationSummaryMapper;
import com.lion.agent.service.MemoryService;
import com.lion.agent.service.QaCacheService;
import com.lion.agent.service.TokenUsageService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * 各 Advisor 的调用链顺序（order 越小越靠外层：先处理请求、后处理响应）。
     * 由 application.yml 的 {@code lion.advisor.*} 配置维护，默认值与原硬编码一致：
     * TokenUsageAdvisor（最外层） → QaCacheAdvisor（次外层） → ConversationSummaryAdvisor（内层）。
     */
    @Value("${lion.advisor.token-usage-order:-100}")
    private int tokenUsageOrder;

    @Value("${lion.advisor.qa-cache-order:10}")
    private int qaCacheOrder;

    @Value("${lion.advisor.conversation-summary-order:300}")
    private int conversationSummaryOrder;

    @Value("${lion.advisor.long-term-memory-order:200}")
    private int longTermMemoryOrder;

    @Value("${lion.memory.inject-top-k:5}")
    private int memoryInjectTopK;

    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory,
                                 QaCacheService qaCacheService, ChatMessageMapper chatMessageMapper,
                                 ConversationSummaryMapper summaryMapper, TokenUsageService tokenUsageService,
                                 MemoryService memoryService, ChatModel chatModel, PromptConfig promptConfig) {

        ChatClient.Builder builder = chatClientBuilder
                .defaultAdvisors(
                        // 全局 Token 用量统计（同步 + 流式），置于调用链最外层，拿到最终响应并落库 ai_token_usage
                        new TokenUsageAdvisor(tokenUsageOrder, tokenUsageService),
                        // 语义缓存：相似问题命中直接复用历史回答（短路跳过模型调用），回答完成后自动回写缓存
                        new QaCacheAdvisor(qaCacheService, qaCacheOrder),
                        // 长期记忆：跨会话注入用户历史事实/偏好（Milvus 检索，失败自动降级跳过）
                        new LongTermMemoryAdvisor(memoryService, chatModel, promptConfig, memoryInjectTopK, longTermMemoryOrder),
                        // 会话记忆：历史从 chat_message 表读取 + 增量压缩摘要（持久化到 chat_conversation_summary 表）
                        new ConversationSummaryAdvisor(chatClientBuilder, chatMessageMapper, summaryMapper,
                                100, 5, conversationSummaryOrder, promptConfig),
                        // 日志顾问,order：0
                        new SimpleLoggerAdvisor(),
                        // 会话记忆：调用前自动从 ChatMemory（JDBC 窗口记忆）读取该会话历史注入上下文，
                        // 调用完成后把本轮问答追加写入存储，实现多轮对话记忆
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
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
