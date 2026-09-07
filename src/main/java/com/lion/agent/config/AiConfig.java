package com.lion.agent.config;

import com.lion.agent.advisor.ConversationSummaryAdvisor;
import com.lion.agent.advisor.LongTermMemoryAdvisor;
import com.lion.agent.advisor.QaCacheAdvisor;
import com.lion.agent.advisor.TokenUsageAdvisor;
import com.lion.agent.mapper.ChatMessageMapper;
import com.lion.agent.mapper.ConversationSummaryMapper;
import com.lion.agent.memory.ReadLimitChatMemory;
import com.lion.agent.service.MemoryService;
import com.lion.agent.service.QaCacheService;
import com.lion.agent.service.TokenUsageService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.autoconfigure.ToolSearchAdvisorProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
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

    /**
     * 多模态对话专用 ChatClient（图片 + 文本）。
     * <p>
     * 与 {@link #chatClient(ChatClient.Builder, ChatMemory, QaCacheService, ChatMessageMapper,
     * ConversationSummaryMapper, TokenUsageService, MemoryService, ChatModel, PromptConfig) chatClient} 隔离：
     * 不挂载 Token 统计/语义缓存/长期记忆/会话摘要等重 Advisor，仅保留日志顾问，链路保持简单。
     * <p>
     * 模型：复用自动配置的 OpenAI 兼容 ChatModel（DashScope 端点与主模型一致），
     * 仅通过 defaultOptions 切换独立的多模态模型名（默认 qwen-vl-max，可用
     * {@code lion.multimodal.model} 覆盖）。不新增 ChatModel Bean，避免容器内多模型注入歧义。
     */


    @Bean
    public ChatClient multimodalChatClient(ChatModel chatModel,
                                           PromptConfig promptConfig,
                                           TokenUsageService tokenUsageService,
                                           @Value("${lion.multimodal.model:qwen-vl-max}") String model,
                                           @Value("${lion.multimodal.temperature:0.1}") double temperature) {
        ChatOptions.Builder<?> builder = ChatOptions.builder().model(model).temperature(temperature);

        return ChatClient.builder(chatModel)
                .defaultOptions(builder)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        new TokenUsageAdvisor(tokenUsageOrder, tokenUsageService))
                .defaultSystem(promptConfig.renderSystemPrompt())
                .build();
    }

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
                        MessageChatMemoryAdvisor.builder(new ReadLimitChatMemory(chatMemory, 30)).build()
                );
        return builder.build();
    }

    /**
     * ToolSearch 版 ToolCallingAdvisor.Builder（修复 2.0.0 自动配置注册顺序问题）。
     * <p>
     * Spring AI 2.0.0 中 {@code ChatClientAutoConfiguration} 与
     * {@code ToolSearchAdvisorAutoConfiguration} 都声明了同名 @Bean 方法
     * {@code toolCallingAdvisorBuilder}（均带 {@code @ConditionalOnMissingBean}），
     * 注册顺序决定谁生效。实际运行时 ChatClient 自带"普通版"（不含 ToolIndex，
     * 不做工具搜索）先注册，ToolSearch 版被跳过 —— 导致容器中唯一的
     * {@link ToolCallingAdvisor.Builder} 不是工具搜索版，{@code chatClientBuilder}
     * 内部 {@code ObjectProvider#getIfAvailable()} 拿到的自然也不是
     * {@link ToolSearchToolCallingAdvisor}（断点打不到、工具索引从不写入）。
     * <p>
     * 这里主动声明一个 ToolSearch 版 {@link ToolCallingAdvisor.Builder} Bean：
     * 用户配置类先于自动配置解析注册 Bean 定义，两个自动配置的同名方法都会因
     * {@code @ConditionalOnMissingBean} 让路跳过，{@code chatClientBuilder} 即可拿到本 Bean，
     * 自动挂上 {@link ToolSearchToolCallingAdvisor}（无需改动 {@link #chatClient} 的装配）。
     * <p>
     * 注意：依赖 {@code spring.ai.chat.client.tool-search-advisor.enabled=true} 时
     * 自动创建的 {@link ToolIndex}（vector 版）与 {@link ToolSearchAdvisorProperties}，
     * 关闭该开关会导致启动失败。
     */
    @Bean
    public ToolCallingAdvisor.Builder<?> toolSearchToolCallingAdvisorBuilder(ToolIndex toolIndex,
                                                                             ToolCallingManager toolCallingManager,
                                                                             ToolSearchAdvisorProperties properties) {
        ToolSearchToolCallingAdvisor.Builder<?> builder = ToolSearchToolCallingAdvisor.builder()
                .toolIndex(toolIndex)
                .toolCallingManager(toolCallingManager);
        // max-results 走 yml 配置（spring.ai.chat.client.tool-search-advisor.max-results），未配置则为 null 不设置
        if (properties.getMaxResults() != null) {
            builder.maxResults(properties.getMaxResults());
        }
        return builder;
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
