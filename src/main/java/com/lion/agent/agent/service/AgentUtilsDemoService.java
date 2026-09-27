package com.lion.agent.agent.service;

import com.lion.agent.advisor.ChatLoggerAdvisor;
import com.lion.agent.agent.dto.AgentPlanResult;
import com.lion.agent.agent.dto.TodoItemView;
import com.lion.agent.agent.tool.TodoWriteToolAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.Status;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * spring-ai-agent-utils 使用示例（{@code org.springaicommunity:spring-ai-agent-utils}）
 *
 * <p>演示库里最直观的一个工具：{@link TodoWriteTool}。模型面对复杂任务时先拆解成结构化
 * 待办，再随着执行把任务从 pending 推进到 in_progress / completed；工具的每次更新都会
 * 回调 {@code todoEventHandler}，这里把它收集起来，最后随答案一起返回给前端渲染进度。
 *
 * <p>注意：这里用 {@code ChatClient.builder(chatModel)} 现建一个干净的 ChatClient，
 * 而不是注入全局 chatClient——全局那个挂了敏感词、语义缓存、长期记忆等一堆 Advisor，
 * 会干扰本示例的观察效果。
 */
@Slf4j
@Service
public class AgentUtilsDemoService {

    private static final String SYSTEM_PROMPT = """
            你是一个会显式管理任务进度的中文 AI 助手。
            当任务包含 3 个或更多明确步骤，或用户要求你组织任务时，必须先调用 TodoWrite 创建任务清单。
            工作过程中只允许一个任务处于 in_progress；开始某项前先把它标为 in_progress，完成后立刻标为 completed。
            Todo 的 content 使用中文祈使句，activeForm 使用“正在……”形式。
            最终回答要简短总结完成了哪些步骤。
            """;

    private final ChatModel chatModel;

    public AgentUtilsDemoService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 跑一个会拆任务的用户目标，返回最终回答 + 任务清单快照。
     *
     * @param task 用户任务描述
     * @return 回答与进度
     */
    public AgentPlanResult plan(String task) {
        // 每次请求一份快照：TodoWriteTool 是有状态对象，不能做成单例 Bean 复用
        List<Todos> snapshots = new ArrayList<>();

        TodoWriteTool todoWriteTool = TodoWriteTool.builder()
                .todoEventHandler(todos -> {
                    log.info("[agent-utils] TodoWrite 更新：{}/{} 已完成",
                            countByStatus(todos, Status.completed), todos.todos().size());
                    snapshots.add(todos);
                })
                .build();

        String answer = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .system(SYSTEM_PROMPT)
                .user(task)
                // 注册的是适配器而非库工具本身：库工具的入参是包了一层的 Todos 记录，
                // 模型会发单层数组导致反序列化失败（见 TodoWriteToolAdapter 的类注释）
                .tools(new TodoWriteToolAdapter(todoWriteTool))
                // 自建 ChatClient 不带 AiConfig 的默认 Advisor，日志需手动挂（说明见 TaskToolDemoService）
                .advisors(new ChatLoggerAdvisor(0, 500),
                        new ChatLoggerAdvisor(Integer.MAX_VALUE - 50, 500, true))
                .call()
                .content();

        Todos last = snapshots.isEmpty() ? new Todos(List.of()) : snapshots.get(snapshots.size() - 1);
        return new AgentPlanResult(task, answer, toItems(last), snapshots.size());
    }

    private static List<TodoItemView> toItems(Todos todos) {
        List<TodoItemView> views = new ArrayList<>();
        for (Todos.TodoItem item : todos.todos()) {
            views.add(new TodoItemView(item.content(), item.status().name(), item.activeForm()));
        }
        return views;
    }

    private static long countByStatus(Todos todos, Status status) {
        return todos.todos().stream().filter(t -> t.status() == status).count();
    }
}
