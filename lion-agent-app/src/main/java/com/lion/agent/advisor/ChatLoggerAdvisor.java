package com.lion.agent.advisor;

import com.lion.agent.common.constants.AdvisorConstants;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话链路日志 Advisor（自定义版，替代官方 {@code SimpleLoggerAdvisor}）
 *
 * <p>官方实现是把整个 {@code ChatClientRequest/ChatResponse} 的 toString 直接打出来，
 * 字段冗长、消息正文与元数据混在一起，排查问题时很难一眼看清"送了什么进去、回了什么出来"。
 * 这里按人读的习惯重排为两块结构化日志：</p>
 *
 * <pre>
 * ==================== [AI-REQUEST] ====================
 * 会话=154 | 用户=1 | 类型=chat | 模型=qwen-plus
 * 消息(3 条):
 *   [1] SYSTEM     128字  你是 Lion Agent……
 *   [2] USER        12字  获取当前时间
 *   [3] ASSISTANT   30字  好的，我来帮你查。
 * 工具(5 个): getNowDate, queryUserCount, ...
 * ======================================================
 *
 * ==================== [AI-RESPONSE] ===================
 * 会话=154 | 模型=qwen-plus | 耗时=1234ms
 * 工具调用: 无（最终应答；中间轮次的调用明细见 [AI-TOOL] 日志）
 * 回复(26字): 当前时间是 2026-09-23……
 * 用量: 输入=429 输出=26 合计=455
 * ======================================================
 *
 * ==================== [AI-TOOL] =======================
 * 会话=154
 * 工具返回: toolSearchTool -> 可用工具: getNowDate……
 * 模型调用: getNowDate()
 * ======================================================
 * </pre>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>以 {@code chain.nextCall / nextStream} 为分界线</b>：之前打请求、之后打响应，
 *       因此打印到的是「记忆/摘要注入之后、工具执行之前」的真实 prompt，正好是排工具问题的关键视角；</li>
 *   <li><b>order 默认 0</b>（与官方一致）：位于所有业务/记忆 Advisor 之内、工具 Advisor 之外，
 *       所以工具循环内每一轮迭代不会重复打印；</li>
 *   <li><b>流式在 {@code doOnComplete} 一次性打印</b>：分片逐个打会刷屏，这里累加完整回复后只打一次；</li>
 *   <li><b>长文本按 {@code maxContentLength} 截断</b>：系统提示词、知识库上下文动辄上千字，
 *       截断后保留"共 N 字"信息，既不刷屏也不丢位置感；</li>
 *   <li><b>先判 {@code log.isInfoEnabled()}</b>：日志关闭时不拼接字符串，避免无谓开销。</li>
 * </ul>
 *
 * <p><b>为什么日志里「工具调用」总是无：</b>工具循环（{@code ToolCallingAdvisor}）会把
 * 「模型返回带 toolCalls 的响应 → 执行工具 → 把结果作为新消息继续调用」整个收敛掉，
 * 只把最后一轮的纯文本响应交给外层。所以放在循环<b>之外</b>的本实例，看到的响应里永远不会再有
 * tool call —— 这是框架行为，不是链路没跑工具。</p>
 *
 * <p>因此 {@code AiConfig} 注册了<b>两个</b>实例：</p>
 * <ul>
 *   <li>order=0（循环外）：打印完整 prompt + 最终回复 + 用量，一次对话一份；</li>
 *   <li>order=MAX-50（循环内，{@code toolRoundOnly=true}）：只打印 {@code [AI-TOOL]} ——
 *       每轮模型发起了哪些工具调用、工具返回了什么，最后一轮无调用时自动不打印，避免刷屏。</li>
 * </ul>
 *
 * <p>排查"工具没执行"类问题时看 {@code [AI-TOOL]}：
 * 一次都没出现 = 模型压根没发起工具调用（查工具是否注册进 options、系统提示词是否引导）；
 * 出现了但结果不对 = 工具本身或参数的问题。</p>
 */
@Slf4j
public class ChatLoggerAdvisor implements CallAdvisor, StreamAdvisor {

    /** 单条消息正文默认最多打印的字符数（超出截断并标注总字数） */
    private static final int DEFAULT_MAX_CONTENT_LENGTH = 300;

    private static final String SEPARATOR = "======================================================";

    private final int order;

    private final int maxContentLength;

    /**
     * 工具轮次模式：true 表示只打印「模型发起了哪些工具调用 / 工具返回了什么」，
     * 不打印完整 prompt 与最终回复（用于放在工具循环内部的实例，避免每轮刷屏）。
     */
    private final boolean toolRoundOnly;

    public ChatLoggerAdvisor() {
        this(0, DEFAULT_MAX_CONTENT_LENGTH, false);
    }

    public ChatLoggerAdvisor(int order) {
        this(order, DEFAULT_MAX_CONTENT_LENGTH, false);
    }

    public ChatLoggerAdvisor(int order, int maxContentLength) {
        this(order, maxContentLength, false);
    }

    public ChatLoggerAdvisor(int order, int maxContentLength, boolean toolRoundOnly) {
        this.order = order;
        this.maxContentLength = maxContentLength;
        this.toolRoundOnly = toolRoundOnly;
    }

    @NotNull
    @Override
    public ChatClientResponse adviseCall(@NotNull ChatClientRequest request, CallAdvisorChain chain) {
        if (toolRoundOnly) {
            // 工具循环内部：请求里的 TOOL 消息是上一轮工具的执行结果，响应里是本轮模型发起的调用
            ChatClientResponse response = chain.nextCall(request);
            logToolRound(request, response);
            return response;
        }
        logRequest(request);
        Instant start = Instant.now();
        ChatClientResponse response = chain.nextCall(request);
        logResponse(request, response, null, Duration.between(start, Instant.now()));
        return response;
    }

    @NotNull
    @Override
    public Flux<ChatClientResponse> adviseStream(@NotNull ChatClientRequest request, StreamAdvisorChain chain) {
        if (toolRoundOnly) {
            logToolResults(request);
            return chain.nextStream(request)
                    .doOnNext(response -> logToolRound(request, response));
        }
        logRequest(request);
        Instant start = Instant.now();
        // 流式：只累加助手正文、只保留最后一个响应（携带最终 usage），完成时统一打印一次
        StringBuilder accumulated = new StringBuilder();
        AtomicReference<ChatClientResponse> last = new AtomicReference<>();
        return chain.nextStream(request)
                .doOnNext(response -> {
                    accumulated.append(textOf(response));
                    last.set(response);
                })
                .doOnComplete(() -> logResponse(request, last.get(), accumulated.toString(),
                        Duration.between(start, Instant.now())))
                .doOnError(error -> log.warn("[AI] 流式调用异常 | 会话={} 错误={}",
                        conversationIdOf(request), error.getMessage()));
    }

    // ==================== 请求阶段 ====================

    /**
     * 打印本次送入模型的完整 prompt（记忆/摘要注入之后的内容）
     */
    private void logRequest(ChatClientRequest request) {
        if (!log.isInfoEnabled() || request == null || request.prompt() == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("\n").append(SEPARATOR).append("\n")
                .append("[AI-REQUEST]  会话=").append(conversationIdOf(request))
                .append(" | 用户=").append(userIdOf(request))
                .append(" | 类型=").append(contextOf(request, AdvisorConstants.CHAT_TYPE_KEY))
                .append(" | 模型=").append(modelOf(request.prompt().getOptions()))
                .append("\n");

        List<Message> messages = request.prompt().getInstructions();
        sb.append("消息(").append(messages == null ? 0 : messages.size()).append(" 条):\n");
        if (messages != null) {
            for (int i = 0; i < messages.size(); i++) {
                Message message = messages.get(i);
                String text = message.getText() == null ? "" : message.getText();
                sb.append("  [").append(i + 1).append("] ")
                        .append(String.format("%-9s", typeNameOf(message)))
                        .append(String.format("%6d字  ", text.length()))
                        .append(ellipsis(text))
                        .append('\n');
            }
        }
        String tools = toolNamesOf(request.prompt().getOptions());
        if (tools != null) {
            sb.append("工具: ").append(tools).append('\n');
        }
        sb.append(SEPARATOR);
        log.info(sb.toString());
    }

    // ==================== 响应阶段 ====================

    /**
     * 打印模型返回
     *
     * @param streamText 流式场景累加到的完整正文；同步场景传 null，直接取响应里的文本
     */
    private void logResponse(ChatClientRequest request, ChatClientResponse response,
                             String streamText, Duration cost) {
        if (!log.isInfoEnabled()) {
            return;
        }
        ChatResponse chatResponse = response == null ? null : response.chatResponse();
        String text = streamText != null ? streamText : textOf(response);
        ChatOptions options = request == null || request.prompt() == null ? null : request.prompt().getOptions();
        String model = chatResponse != null && chatResponse.getMetadata() != null
                ? chatResponse.getMetadata().getModel() : modelOf(options);

        StringBuilder sb = new StringBuilder("\n").append(SEPARATOR).append("\n")
                .append("[AI-RESPONSE] 会话=").append(conversationIdOf(request))
                .append(" | 模型=").append(model)
                .append(" | 耗时=").append(cost.toMillis()).append("ms\n");

        if (chatResponse == null) {
            sb.append("响应为空（链路可能被上游 Advisor 短路）\n");
        } else {
            sb.append("工具调用: ").append(finalToolCallsOf(chatResponse)).append('\n');
            sb.append("回复(").append(text.length()).append("字): ").append(ellipsis(text)).append('\n');
            sb.append("用量: ").append(usageOf(chatResponse)).append('\n');
        }
        sb.append(SEPARATOR);
        log.info(sb.toString());
    }

    // ==================== 工具轮次（工具循环内部的实例才打印） ====================

    /**
     * 打印一轮工具循环：工具的执行结果（请求侧）+ 模型发起的工具调用（响应侧）
     *
     * <p>为什么必须有一个实例放在工具循环内部：{@code ToolCallingAdvisor} 会在循环里把
     * 「模型返回带 toolCalls 的响应 → 执行工具 → 把结果作为新消息继续调用」收敛掉，
     * 最终只把<b>最后一轮</b>的纯文本响应交给外层。所以外层日志永远看不到 tool call，
     * 只能靠内层实例记录。</p>
     */
    private void logToolRound(ChatClientRequest request, ChatClientResponse response) {
        if (!log.isInfoEnabled()) {
            return;
        }
        String invoked = invokedToolCallsOf(response == null ? null : response.chatResponse());
        String returned = toolResultsOf(request);
        if (invoked.isEmpty() && returned.isEmpty()) {
            // 最后一轮（模型直接给出答案、不再调工具）：交给外层完整日志，这里不再重复打印
            return;
        }
        StringBuilder sb = new StringBuilder("\n").append(SEPARATOR).append("\n")
                .append("[AI-TOOL] 会话=").append(conversationIdOf(request)).append('\n');
        if (!returned.isEmpty()) {
            sb.append("工具返回: ").append(returned).append('\n');
        }
        if (!invoked.isEmpty()) {
            sb.append("模型调用: ").append(invoked).append('\n');
        }
        sb.append(SEPARATOR);
        log.info(sb.toString());
    }

    /** 流式场景：请求侧的工具结果在流式开始前就能确定，单独打一次 */
    private void logToolResults(ChatClientRequest request) {
        if (!log.isInfoEnabled()) {
            return;
        }
        String returned = toolResultsOf(request);
        if (returned.isEmpty()) {
            return;
        }
        log.info("\n{}\n[AI-TOOL] 会话={}\n工具返回: {}\n{}",
                SEPARATOR, conversationIdOf(request), returned, SEPARATOR);
    }

    // ==================== 取值辅助 ====================

    private String conversationIdOf(ChatClientRequest request) {
        return contextOf(request, AdvisorConstants.CONVERSATION_ID_KEY);
    }

    private String userIdOf(ChatClientRequest request) {
        return contextOf(request, AdvisorConstants.USER_ID_KEY);
    }

    /** 读上下文，缺失返回占位符 "-"，避免日志里出现难看的 null */
    private String contextOf(ChatClientRequest request, String key) {
        Object value = request.context() == null ? null : request.context().get(key);
        return value == null ? "-" : value.toString();
    }

    /** 请求阶段模型名可能未设置（用 yml 默认），此时显示 default */
    private String modelOf(ChatOptions options) {
        String model = options == null ? null : options.getModel();
        return model == null || model.isBlank() ? "default" : model;
    }

    /** 工具名列表（含数量），无工具注册时返回 null（不打印该行） */
    private String toolNamesOf(ChatOptions options) {
        List<ToolCallback> callbacks = options instanceof ToolCallingChatOptions toolOptions
                ? toolOptions.getToolCallbacks() : null;
        if (callbacks == null || callbacks.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < callbacks.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            ToolCallback callback = callbacks.get(i);
            sb.append(callback.getToolDefinition() == null ? "unknown" : callback.getToolDefinition().name());
        }
        return "(" + callbacks.size() + " 个): " + sb;
    }

    /**
     * 外层完整日志里的「工具调用」行
     *
     * <p>注意：外层拿到的是工具循环结束后的最终响应，其中不会再有 tool call，
     * 所以这里恒定是「无」—— 真正的调用明细由工具循环内部的实例打印 {@code [AI-TOOL]}。</p>
     */
    private String finalToolCallsOf(ChatResponse chatResponse) {
        String invoked = invokedToolCallsOf(chatResponse);
        return invoked.isEmpty() ? "无（最终应答；中间轮次的调用明细见 [AI-TOOL] 日志）" : invoked;
    }

    /** 模型在本轮发起的工具调用，格式 name(arguments)；没有则返回空串（便于调用方判断是否打印） */
    private String invokedToolCallsOf(ChatResponse chatResponse) {
        List<AssistantMessage.ToolCall> toolCalls = toolCallsOf(chatResponse);
        if (toolCalls.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < toolCalls.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            AssistantMessage.ToolCall call = toolCalls.get(i);
            sb.append(call.name()).append('(').append(ellipsis(call.arguments())).append(')');
        }
        return sb.toString();
    }

    /**
     * 请求侧的工具执行结果（上一轮工具跑完回灌的消息），格式 name -> 结果；没有则返回空串
     */
    private String toolResultsOf(ChatClientRequest request) {
        if (request == null || request.prompt() == null || request.prompt().getInstructions() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message message : request.prompt().getInstructions()) {
            if (!(message instanceof ToolResponseMessage toolResponse)) {
                continue;
            }
            if (toolResponse.getResponses() == null) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(response.name()).append(" -> ").append(ellipsis(response.responseData()));
            }
        }
        return sb.toString();
    }

    /** 取响应里的工具调用列表（无则空列表） */
    private List<AssistantMessage.ToolCall> toolCallsOf(ChatResponse chatResponse) {
        if (chatResponse == null) {
            return List.of();
        }
        Generation generation = chatResponse.getResult();
        if (generation == null || generation.getOutput() == null || generation.getOutput().getToolCalls() == null) {
            return List.of();
        }
        return generation.getOutput().getToolCalls();
    }

    private String usageOf(ChatResponse chatResponse) {
        Usage usage = chatResponse.getMetadata() == null ? null : chatResponse.getMetadata().getUsage();
        if (usage == null) {
            return "无用量信息";
        }
        return "输入=" + usage.getPromptTokens()
                + " 输出=" + usage.getCompletionTokens()
                + " 合计=" + usage.getTotalTokens();
    }

    /** 取助手正文（流式场景由调用方累加，这里只取同步响应） */
    private String textOf(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) {
            return "";
        }
        Generation generation = response.chatResponse().getResult();
        if (generation == null || generation.getOutput() == null || generation.getOutput().getText() == null) {
            return "";
        }
        return generation.getOutput().getText();
    }

    /**
     * 单行化 + 截断：换行替换为空格（保证一条消息占一行），超长保留前 N 字并标注总长度
     */
    private String ellipsis(String text) {
        if (text == null) {
            return "";
        }
        String single = text.replace("\r", " ").replace("\n", " ").trim();
        if (single.length() <= maxContentLength) {
            return single;
        }
        return single.substring(0, maxContentLength) + "…";
    }

    @NotNull
    @Override
    public String getName() {
        return "ChatLoggerAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    /** 消息类型占位宽度对齐用（保持与 getMessageType() 输出一致的枚举名） */
    private static String typeNameOf(Message message) {
        MessageType type = message.getMessageType();
        return type == null ? "UNKNOWN" : type.name();
    }
}
