package com.lion.agent.config;

import com.lion.agent.advisor.*;
import com.lion.agent.mapper.ChatMessageMapper;
import com.lion.agent.service.SensitiveWordService;
import com.lion.agent.mapper.ConversationSummaryMapper;
import com.lion.agent.advisor.memory.ReadLimitChatMemory;
import com.lion.agent.service.MemoryService;
import com.lion.agent.service.QaCacheService;
import com.lion.agent.service.TokenUsageService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;

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
     * Advisor 调用链顺序（order 越小越靠外层：请求阶段先执行、响应阶段后执行）。
     *
     * <p>为什么都挤在 {@code Integer.MIN_VALUE} 附近：工具调用循环体在
     * {@code ToolCallingAdvisor} 内部，每迭代一轮都会用 {@code chain.copy(this)}
     * 重建「排在它之后」的 Advisor 链并重新执行。所以业务 Advisor 的 order 必须
     * 小于工具的 order，否则会被圈进循环、每轮重复执行（记忆重复注入、摘要重复落库、
     * Token 统计被拆成多条）。工具 Advisor 则放到最内层，循环内只剩模型调用。
     *
     * <pre>
     *         │ 序号 │ Advisor                      │ order      │ 说明
     *   最外层 │  ①  │ SensitiveWordAdvisor          │ MIN + 50   │ 命中即短路：不读记忆、不调模型、不耗 token
     *         │  ②  │ TokenUsageAdvisor            │ MIN + 100  │ 拿含全部工具轮次的累计用量，一次对话一条
     *         │  ③  │ QaCacheAdvisor               │ MIN + 150  │ 语义缓存命中即短路，跳过记忆与工具
     *         │  ④  │ MessageChatMemoryAdvisor     │ MIN + 200  │ 会话历史注入 / 最终回答回写（JDBC 窗口记忆）
     *         │  ⑤  │ LongTermMemoryAdvisor        │ MIN + 220  │ 注入跨会话事实与偏好，响应阶段异步抽取
     *         │  ⑥  │ ConversationSummaryAdvisor   │ MIN + 240  │ 注入历史摘要 + 增量压缩（chat_message 表）
     *         │  ⑦  │ ChatLoggerAdvisor            │ 0          │ 完整日志：注入记忆后的 prompt + 最终响应
     *         │  ⑧  │ ToolSearchToolCallingAdvisor │ MAX - 100  │ 工具循环本体（自动挂载，不手动 add）
     *         │  ⑨  │ ChatLoggerAdvisor(工具轮次)  │ MAX - 50   │ 循环内，打印每轮的工具调用与工具返回
     *   最内层 │     │ ChatModelCallAdvisor         │ MAX        │ 模型
     * </pre>
     *
     * <p>下方常量与 {@code chatClient()} 里的 {@code advisors.add(...)} 均按此序号升序书写，
     * 做到"代码顺序 = 执行顺序"，不依赖框架重排。</p>
     */
    /** ① 敏感词：命中即短路，连记忆都不读、模型都不调 */
    private static final int SENSITIVE_WORD_ORDER = Integer.MIN_VALUE + 50;

    /** ② Token 统计：在工具循环外，拿整次提问（含所有工具轮次）的累计用量，一次对话一条 */
    private static final int TOKEN_USAGE_ORDER = Integer.MIN_VALUE + 100;

    /** ③ 语义缓存：命中即短路，跳过记忆注入与工具执行 */
    private static final int QA_CACHE_ORDER = Integer.MIN_VALUE + 150;

    /** ④ 会话历史：调用前注入 JDBC 窗口记忆，调用后回写最终回答 */
    private static final int CHAT_MEMORY_ORDER = Integer.MIN_VALUE + 200;

    /** ⑤ 长期记忆：注入跨会话事实与偏好（Milvus 检索），响应阶段提交异步抽取 */
    private static final int LONG_TERM_MEMORY_ORDER = Integer.MIN_VALUE + 220;

    /** ⑥ 会话摘要：注入历史摘要，超阈值增量压缩 */
    private static final int CONVERSATION_SUMMARY_ORDER = Integer.MIN_VALUE + 240;

    /**
     * ⑦ 完整日志：0（与官方 SimpleLoggerAdvisor 一致），位于记忆/摘要之内、工具循环之外。
     * <p>
     * 此实例打印的是工具循环<b>结束后</b>的最终响应，所以它的「工具调用」恒定是无，属正常现象；
     * 工具调用明细由 ⑨{@link #TOOL_LOGGER_ORDER} 那个实例负责。
     */
    private static final int LOGGER_ORDER = 0;

    /** ⑧ 工具 Advisor：压到最内层，使所有业务/记忆 Advisor 都位于工具循环之外 */
    private static final int TOOL_CALLING_ORDER = Integer.MAX_VALUE - 100;

    /**
     * ⑨ 工具轮次日志：必须大于 ⑧{@link #TOOL_CALLING_ORDER}（即位于工具循环内部）。
     * <p>
     * 工具循环会把「模型返回 toolCalls → 执行工具 → 回灌结果继续调用」收敛掉，只把最后一轮的
     * 纯文本响应交给外层，因此外层永远看不到工具调用。这个实例贴在模型调用外侧，
     * 每轮迭代都能看到模型原始输出，专门打印 [AI-TOOL]：模型发起了哪些调用、工具返回了什么。
     */
    private static final int TOOL_LOGGER_ORDER = Integer.MAX_VALUE - 50;

    /** 日志里单条消息正文最多打印的字符数（系统提示词、知识库上下文通常很长，截断避免刷屏） */
    @Value("${lion.advisor.logger.max-content-length:300}")
    private int loggerMaxContentLength;

    @Value("${lion.memory.inject-top-k:5}")
    private int memoryInjectTopK;

    /**
     * 输入侧敏感词拦截（词库由页面维护，存 ai_sensitive_word 表），改词即时生效、无需重启。
     * 属于安全基线能力，始终启用，不再提供关闭开关；顺序固定为 {@link #SENSITIVE_WORD_ORDER}，
     * 话术与检测范围仍可由配置调整。
     */
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

        // 同样按 order 升序书写：① 敏感词（最外层短路，命中则不调用视觉模型、不消耗 token）
        // → ② Token 统计（MIN+100）→ ⑦ 日志（0，打印注入后的 prompt 与最终响应）
        List<Advisor> advisors = new ArrayList<>();
        advisors.add(new SensitiveWordAdvisor(sensitiveWordService, sensitiveRejectMessage,
                sensitiveCheckScope, SENSITIVE_WORD_ORDER));
        advisors.add(new TokenUsageAdvisor(TOKEN_USAGE_ORDER, tokenUsageService));
        advisors.add(new ChatLoggerAdvisor(LOGGER_ORDER, loggerMaxContentLength));

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

        // 按 order 升序（① → ⑨，最外层 → 最内层）依次添加，代码顺序即执行顺序
        List<Advisor> advisors = new ArrayList<>();

        // ① 敏感词 MIN+50：最外层短路，命中直接返回拒答，连记忆读取/写入、模型调用都跳过
        advisors.add(new SensitiveWordAdvisor(sensitiveWordService, sensitiveRejectMessage,
                sensitiveCheckScope, SENSITIVE_WORD_ORDER));

        // ② Token 统计 MIN+100：在工具循环之外，拿到整次提问（含所有工具轮次）的累计用量，一次对话落库一条
        advisors.add(new TokenUsageAdvisor(TOKEN_USAGE_ORDER, tokenUsageService));

        // ③ 语义缓存 MIN+150：相似问题命中直接复用历史回答，短路掉记忆注入与工具执行；回答完成后回写缓存
        advisors.add(new QaCacheAdvisor(qaCacheService, QA_CACHE_ORDER));

        // ④ 会话历史 MIN+200：调用前从 ChatMemory（JDBC 窗口记忆）注入历史，调用后把最终回答回写（多轮记忆）
        advisors.add(MessageChatMemoryAdvisor.builder(new ReadLimitChatMemory(chatMemory, 30))
                .order(CHAT_MEMORY_ORDER)
                .build());

        // ⑤ 长期记忆 MIN+220：注入跨会话的用户事实/偏好（Milvus 检索），响应阶段提交异步抽取
        advisors.add(new LongTermMemoryAdvisor(memoryService, chatModel, promptConfig, memoryInjectTopK,
                LONG_TERM_MEMORY_ORDER));

        // ⑥ 会话摘要 MIN+240：从 chat_message 表读历史并注入摘要，超阈值时增量压缩
        advisors.add(new ConversationSummaryAdvisor(chatClientBuilder, chatMessageMapper, summaryMapper,
                100, 5, CONVERSATION_SUMMARY_ORDER, promptConfig));

        // ⑦ 完整日志 0：位于记忆之后、工具之前，打印注入记忆后的完整 prompt + 最终响应（INFO，结构化易读）
        advisors.add(new ChatLoggerAdvisor(LOGGER_ORDER, loggerMaxContentLength));

        // ⑧ ToolSearchToolCallingAdvisor MAX-100：不在此手动添加，由 chatClientBuilder 依据
        // toolSearchToolCallingAdvisorBuilder 自动挂载（它排在 ⑦ 之后、⑨ 之前，即工具循环本体）

        // ⑨ 工具轮次日志 MAX-50（工具循环内部）：只打印每轮「模型发起了哪些工具调用 / 工具返回了什么」，
        // 不打印完整 prompt，避免多轮工具调用时刷屏
        advisors.add(new ChatLoggerAdvisor(TOOL_LOGGER_ORDER, loggerMaxContentLength, true));

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
        // 最内层：工具循环内只剩模型调用，业务/记忆 Advisor 全部位于循环之外，每轮不会重复执行
        ToolSearchToolCallingAdvisor.Builder<?> builder = ToolSearchToolCallingAdvisor.builder()
                .toolIndex(toolIndex)
                .toolCallingManager(toolCallingManager)
                .advisorOrder(TOOL_CALLING_ORDER);
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
                // 每个会话各自最多保留 500 条消息
                .maxMessages(500)
                .build();
    }
}
