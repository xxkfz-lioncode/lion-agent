package com.lion.agent.agent.service;

import com.lion.agent.advisor.ChatLoggerAdvisor;
import com.lion.agent.agent.tool.DemoSubagents;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentDefinition;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentExecutor;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentResolver;
import org.springaicommunity.agent.tools.task.TaskOutputTool;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;
import org.springaicommunity.agent.tools.task.repository.TaskRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * A2A（Agent2Agent）子 Agent 示例：把「远程 Agent」和本地子 Agent 一起挂到 Task 工具上。
 *
 * <p>与 {@link TaskToolDemoService} 的唯一区别是子 Agent 的类型多了一个 A2A：
 * <pre>
 *   本地：SubagentReference("demo://translator", "demo")  → 本 JVM 内新建 ChatClient 跑
 *   远程：SubagentReference("http://host:port",   "A2A")  → 走 A2A 协议 HTTP 调用别人的 Agent
 * </pre>
 * 对模型来说两者没有区别，都在 Task 工具的目录里，由它自己决定派给谁。
 *
 * <p><b>服务端要求</b>（当前是假地址，先空着）：远程服务必须是一个标准 A2A 服务端，至少提供
 * <ol>
 *   <li>{@code GET /.well-known/agent-card.json} —— 返回 AgentCard（名字、能力描述、支持的内容类型）</li>
 *   <li>{@code POST /} 的 JSON-RPC 2.0 端点 —— 至少实现 {@code message/send}（{@code message/stream} 可选，用于流式）</li>
 * </ol>
 * 本模块（spring-ai-agent-utils-a2a）只实现<b>客户端</b>，服务端要用 a2a-java-sdk-server 自己写。
 *
 * <p>因为远程地址当前不可达，这里做了降级：发现 AgentCard 失败时自动退回「仅本地子 Agent」，
 * 接口照常可用，方便先看链路、后接服务端。
 */
@Slf4j
@Service
public class A2aDemoService {

    private static final String SYSTEM_PROMPT = """
            你是一个可以派活给子 Agent 的中文助手。
            可用的子 Agent 分两类：本地的 translator / summarizer，以及通过 A2A 协议接入的远程 Agent。
            当用户的需求明显属于某个子 Agent 的职责时，用 Task 工具把任务交给它，并等待它返回结果。
            拿到结果后用中文简要转述给用户，不要原样大段复述。
            如果所有子 Agent 都不匹配，就直接自己回答。
            """;

    /**
     * 远程 A2A 服务端地址（独立进程 lion-a2a-server，9999，接真实模型 + 天气工具）。
     *
     * <p>配置：{@code lion.demo.a2a.agent-url=http://localhost:9999}
     * 注意只写「协议+主机+端口」，库会自动拼 {@code /.well-known/agent-card.json} 去发现名片。
     */
    @Value("${lion.demo.a2a.agent-url:http://localhost:9999}")
    private String agentUrl;

    private final ChatModel chatModel;

    public A2aDemoService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** 本地子 Agent（与 TaskToolDemoService 同一份） */
    private static final List<DemoSubagents.Definition> LOCAL_SUBAGENTS = List.of(
            new DemoSubagents.Definition("translator",
                    "你是一个中英翻译专家。把用户给的文本翻译成英文，只输出译文，不要解释，不要加引号。"),
            new DemoSubagents.Definition("summarizer",
                    "你是一个摘要专家。把用户给的文本压缩成不超过 3 句话的中文摘要，只输出摘要。"));

    /**
     * 跑一次对话：模型在「本地子 Agent + 远程 A2A Agent」之间自主选择。
     *
     * @param task 用户任务
     * @return 模型最终回答（如远程发现失败，会在回答前加上降级说明）
     */
    public String run(String task) {
        TaskRepository taskRepository = new DefaultTaskRepository();

        List<SubagentType> types = new ArrayList<>();
        List<SubagentReference> references = new ArrayList<>();

        // ① 本地子 Agent
        types.add(DemoSubagents.subagentType(chatModel, LOCAL_SUBAGENTS));
        LOCAL_SUBAGENTS.forEach(d -> references.add(new SubagentReference("demo://" + d.name(), DemoSubagents.KIND)));

        // ② 远程 A2A 子 Agent：地址不通时降级，不让它把整个请求搞挂
        boolean a2aReady = false;
        var a2aType = new SubagentType(new A2ASubagentResolver(), new A2ASubagentExecutor());
        var a2aRef = new SubagentReference(agentUrl, A2ASubagentDefinition.KIND);
        types.add(a2aType);
        references.add(a2aRef);

        ToolCallback taskTool = buildTaskTool(types, references, taskRepository);
        if (taskTool == null) {
            log.warn("[a2a] 远程 Agent {} 不可用，降级为仅本地子 Agent", agentUrl);
            // 注意：这里不能用 types/references 重新赋值——它们被 lambda 引用，必须保持 effectively final，
            // 所以降级分支用独立的局部变量
            List<SubagentType> localTypes = List.of(DemoSubagents.subagentType(chatModel, LOCAL_SUBAGENTS));
            List<SubagentReference> localRefs = LOCAL_SUBAGENTS.stream()
                    .map(d -> new SubagentReference("demo://" + d.name(), DemoSubagents.KIND))
                    .toList();
            taskTool = buildTaskTool(localTypes, localRefs, taskRepository);
        } else {
            a2aReady = true;
        }

        var taskOutputTool = TaskOutputTool.builder()
                .taskRepository(taskRepository)
                .build();

        log.info("[a2a] 子 Agent 目录：本地 {}，远程 A2A {}（{}）",
                LOCAL_SUBAGENTS.stream().map(DemoSubagents.Definition::name).toList(), agentUrl, a2aReady ? "已发现" : "不可用，已降级");

        String answer = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .system(SYSTEM_PROMPT)
                .user(task)
                .tools(taskTool, taskOutputTool)
                // demo 自建 ChatClient，日志 Advisor 手动挂（说明见 TaskToolDemoService）
                .advisors(new ChatLoggerAdvisor(0, 500),
                        new ChatLoggerAdvisor(Integer.MAX_VALUE - 50, 500, true))
                .call()
                .content();

        return a2aReady ? answer : "【提示】远程 A2A 服务端 " + agentUrl + " 当前不可用，本次只使用了本地子 Agent。\n\n" + answer;
    }

    /**
     * 单独探测远程 AgentCard，方便在跑对话前先确认服务端是否就绪。
     *
     * @return 名片摘要；失败时返回错误信息文本
     */
    public String probe() {
        try {
            var resolver = new A2ASubagentResolver();
            var definition = resolver.resolve(new SubagentReference(agentUrl, A2ASubagentDefinition.KIND));
            return String.format("✅ 已发现远程 Agent%n地址：%s%n名称：%s%n描述：%s",
                    agentUrl, definition.getName(), definition.getDescription());
        } catch (Exception e) {
            return String.format("❌ 未发现远程 Agent%n地址：%s%n原因：%s", agentUrl, e.getMessage());
        }
    }

    /** 构建 Task 工具；远程地址不通时返回 null（A2ASubagentResolver 发现名片失败会抛异常） */
    private static ToolCallback buildTaskTool(List<SubagentType> types,
                                              List<SubagentReference> references,
                                              TaskRepository repository) {
        try {
            return TaskTool.builder()
                    .subagentTypes(types)
                    .subagentReferences(references)
                    .taskRepository(repository)
                    .build();
        } catch (Exception e) {
            log.warn("[a2a] Task 工具构建失败（可能是远程 Agent 不可达）：{}", e.getMessage());
            return null;
        }
    }
}
