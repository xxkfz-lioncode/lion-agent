package com.lion.agent.config;

import com.lion.agent.advisor.ConversationSummaryAdvisor;
import com.lion.agent.advisor.LongTermMemoryAdvisor;
import com.lion.agent.advisor.QaCacheAdvisor;
import com.lion.agent.advisor.SensitiveWordAdvisor;
import com.lion.agent.advisor.TokenUsageAdvisor;
import com.lion.agent.mapper.ChatMessageMapper;
import com.lion.agent.service.SensitiveWordService;
import com.lion.agent.mapper.ConversationSummaryMapper;
import com.lion.agent.advisor.memory.ReadLimitChatMemory;
import com.lion.agent.service.MemoryService;
import com.lion.agent.service.QaCacheService;
import com.lion.agent.service.TokenUsageService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.autoconfigure.ToolSearchAdvisorProperties;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

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
     * Advisor 调用链顺序（order 越小越靠外层：先处理请求、后处理响应）。
     * <p>
     * 链路结构固定，故统一写死为常量，不再由 application.yml 维护。
     *
     * <pre>
     *   最外层 │ SensitiveWordAdvisor      -200 │ 命中即短路：不调模型、不耗 token、不写会话记忆
     *         │ TokenUsageAdvisor         -100 │ 拿到经完整链路后的最终响应并统计落库
     *         │ SimpleLoggerAdvisor          0 │ 官方默认 order，打印该位置看到的请求/响应（DEBUG）
     *         │ QaCacheAdvisor              10 │ 语义缓存命中即短路
     *         │ LongTermMemoryAdvisor      200 │ 注入跨会话事实/偏好
     *   最内层 │ ConversationSummaryAdvisor 300 │ 注入会话历史与摘要
     *         ↓ 工具调用循环 / 模型调用
     * </pre>
     */
    private static final int SENSITIVE_WORD_ORDER = -200;

    private static final int TOKEN_USAGE_ORDER = -100;

    private static final int QA_CACHE_ORDER = 10;

    private static final int LONG_TERM_MEMORY_ORDER = 200;

    private static final int CONVERSATION_SUMMARY_ORDER = 300;

    @Value("${lion.memory.inject-top-k:5}")
    private int memoryInjectTopK;

    /**
     * 输入侧敏感词拦截（词库由页面维护，存 ai_sensitive_word 表），改词即时生效、无需重启。
     * 顺序固定为 {@link #SENSITIVE_WORD_ORDER}；开关、话术、检测范围仍可由配置调整。
     */
    @Value("${lion.sensitive.enabled:true}")
    private boolean sensitiveEnabled;

    @Value("${lion.sensitive.reject-message:您的问题包含敏感内容，我无法回答。}")
    private String sensitiveRejectMessage;

    /**
     * 检测范围：user = 只检测本轮用户输入（默认，避免系统提示词/历史/知识库内容误杀）；
     * all = 检测整个 prompt（与官方 SafeGuardAdvisor 行为一致）。
     */
    @Value("${lion.sensitive.check-scope:user}")
    private String sensitiveCheckScope;

    /**
     * 自定义 Embedding 分批策略（DashScope 适配）。
     * <p>
     * 背景：容器中所有向量化入口（VectorStore 写入、ETL 流水线等）默认使用
     * {@code TokenCountBatchingStrategy}（按 token 预算分批），其单批条数上限为 512，
     * 远超 DashScope embedding 接口「单批最多 10 条」的硬性限制；
     * 工具索引、知识库文档等批量写入时一旦超过 10 条即报
     * {@code 400 batch size is invalid, it should not be larger than 10}。
     * <p>
     * 这里将分批策略固定为「每批至多 10 条」，Spring AI 会自动装配到
     * {@code EmbeddingModel} 的批处理链路，从根源上避免超限。
     */
    @Bean
    public BatchingStrategy customBatchingStrategy() {
        return documents -> {
            List<List<Document>> batches = new ArrayList<>();
            int maxBatchSize = 10; // DashScope 硬性限制
            for (int i = 0; i < documents.size(); i += maxBatchSize) {
                batches.add(documents.subList(i, Math.min(i + maxBatchSize, documents.size())));
            }
            return batches;
        };
    }

    /**
     * 多模态对话专用 ChatClient（图片 + 文本）。
     * <p>
     * 与 {@link #chatClient(ChatClient.Builder, ChatMemory, QaCacheService, ChatMessageMapper,
     * ConversationSummaryMapper, TokenUsageService, MemoryService, ChatModel, PromptConfig) chatClient} 隔离：
     * 不挂载语义缓存/长期记忆/会话摘要等重 Advisor；除日志与 Token 统计外，
     * 额外挂载 {@link SensitiveWordAdvisor}（order -200，最外层短路，命中即不调用视觉模型）。
     * <p>
     * 模型：复用自动配置的 OpenAI 兼容 ChatModel（DashScope 端点与主模型一致），
     * 仅通过 defaultOptions 切换独立的多模态模型名（默认 qwen-vl-max，可用
     * {@code lion.multimodal.model} 覆盖）。不新增 ChatModel Bean，避免容器内多模型注入歧义。
     */
    @Bean
    public ChatClient multimodalChatClient(ChatModel chatModel,
                                           PromptConfig promptConfig,
                                           TokenUsageService tokenUsageService,
                                           SensitiveWordService sensitiveWordService,
                                           @Value("${lion.multimodal.model:qwen-vl-max}") String model,
                                           @Value("${lion.multimodal.temperature:0.1}") double temperature) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(model).temperature(temperature);

        // 与主链路一致的敏感词拦截器：order 默认 -200，最外层短路，命中则不调用视觉模型、不消耗 token
        List<Advisor> advisors = new ArrayList<>();
        if (sensitiveEnabled) {
            advisors.add(new SensitiveWordAdvisor(sensitiveWordService, sensitiveRejectMessage,
                    sensitiveCheckScope, SENSITIVE_WORD_ORDER));
        }
        advisors.add(new SimpleLoggerAdvisor());
        advisors.add(new TokenUsageAdvisor(TOKEN_USAGE_ORDER, tokenUsageService));

        return ChatClient.builder(chatModel)
                .defaultOptions(builder)
                .defaultAdvisors(advisors.toArray(new Advisor[0]))
                .defaultSystem(promptConfig.renderSystemPrompt())
                .build();
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory,
                                 QaCacheService qaCacheService, ChatMessageMapper chatMessageMapper,
                                 ConversationSummaryMapper summaryMapper, TokenUsageService tokenUsageService,
                                 MemoryService memoryService, ChatModel chatModel, PromptConfig promptConfig,
                                 SensitiveWordService sensitiveWordService) {

        // Advisor 列表（顺序即 order 排序依据，DefaultChatClient 会统一按 getOrder() 重排）
        List<Advisor> advisors = new ArrayList<>();
        // 全局 Token 用量统计（同步 + 流式），置于调用链最外层，拿到最终响应并落库 ai_token_usage
        advisors.add(new TokenUsageAdvisor(TOKEN_USAGE_ORDER, tokenUsageService));
        // 语义缓存：相似问题命中直接复用历史回答（短路跳过模型调用），回答完成后自动回写缓存
        advisors.add(new QaCacheAdvisor(qaCacheService, QA_CACHE_ORDER));
        // 长期记忆：跨会话注入用户历史事实/偏好（Milvus 检索，失败自动降级跳过）
        advisors.add(new LongTermMemoryAdvisor(memoryService, chatModel, promptConfig, memoryInjectTopK, LONG_TERM_MEMORY_ORDER));
        // 会话记忆：历史从 chat_message 表读取 + 增量压缩摘要（持久化到 chat_conversation_summary 表）
        advisors.add(new ConversationSummaryAdvisor(chatClientBuilder, chatMessageMapper, summaryMapper,
                100, 5, CONVERSATION_SUMMARY_ORDER, promptConfig));
        // 日志顾问,order：0
        advisors.add(new SimpleLoggerAdvisor());
        // 输入侧敏感词拦截器,替换官方 SafeGuardAdvisor
        if (sensitiveEnabled) {
            advisors.add(new SensitiveWordAdvisor(sensitiveWordService, sensitiveRejectMessage, sensitiveCheckScope, SENSITIVE_WORD_ORDER));
        }
        // 会话记忆：调用前自动从 ChatMemory（JDBC 窗口记忆）读取该会话历史注入上下文，
        // 调用完成后把本轮问答追加写入存储，实现多轮对话记忆
        advisors.add(MessageChatMemoryAdvisor.builder(new ReadLimitChatMemory(chatMemory, 30)).build());

        ChatClient.Builder builder = chatClientBuilder.defaultAdvisors(advisors.toArray(new Advisor[0]));
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
