package com.lion.agent.agent.service;

import com.lion.agent.advisor.ChatLoggerAdvisor;
import com.lion.agent.agent.tool.DemoSubagents;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.tools.task.TaskOutputTool;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;
import org.springaicommunity.agent.tools.task.repository.TaskRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * spring-ai-agent-utils 的 {@link TaskTool} 示例：主 Agent 把任务派给子 Agent 执行。
 *
 * <p>链路：用户提问 → 主 Agent 判断该派给哪个子 Agent → 调 Task 工具（subagent_type=子 Agent 名）
 * → 子 Agent 用同一个 ChatModel 独立跑完 → 结果回传给主 Agent → 主 Agent 汇总成最终回答。
 *
 * <p>两个工具配套使用：{@code Task} 负责派活（可后台），{@code TaskOutput} 负责按 task_id 取结果。
 */
@Slf4j
@Service
public class TaskToolDemoService {

    private static final String SYSTEM_PROMPT = """
            你是一个可以派活给子 Agent 的中文助手。
            当用户的需求明显属于某个子 Agent 的职责时，用 Task 工具把任务交给它，并等待它返回结果。
            拿到子 Agent 的结果后，用中文简要转述给用户，不要原样大段复述。
            如果需求与所有子 Agent 的职责都不匹配，就直接自己回答。
            """;

    private final ChatModel chatModel;

    public TaskToolDemoService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** 可用的子 Agent（名字 + 能力描述，描述会同时作为子 Agent 的 system prompt） */
    private static final List<DemoSubagents.Definition> SUBAGENTS = List.of(
            new DemoSubagents.Definition("translator",
                    "你是一个中英翻译专家。把用户给的文本翻译成英文，只输出译文，不要解释，不要加引号。"),
            new DemoSubagents.Definition("summarizer",
                    "你是一个摘要专家。把用户给的文本压缩成不超过 3 句话的中文摘要，只输出摘要。"));

    public String run(String task) {
        // 后台任务的存储：Task 工具与 TaskOutput 工具必须共用同一个，否则取不到结果
        TaskRepository taskRepository = new DefaultTaskRepository();

        var taskTool = TaskTool.builder()
                .subagentTypes(DemoSubagents.subagentType(chatModel, SUBAGENTS))
                .subagentReferences(SUBAGENTS.stream()
                        .map(d -> new SubagentReference("demo://" + d.name(), DemoSubagents.KIND))
                        .toList())
                .taskRepository(taskRepository)
                .build();

        var taskOutputTool = TaskOutputTool.builder()
                .taskRepository(taskRepository)
                .build();

        log.info("[agent-utils] 注册子 Agent：{}", SUBAGENTS.stream().map(DemoSubagents.Definition::name).toList());

        return ChatClient.builder(chatModel)
                .build()
                .prompt()
                .system(SYSTEM_PROMPT)
                .user(task)
                // TaskTool / TaskOutputTool 的 build() 直接返回 ToolCallback。
                // 注册走 tools(Object...)：2.0.0 起 toolCallbacks(...) 已标记 @Deprecated(forRemoval)，
                // 而 tools(...) 内部会判断 instanceof ToolCallback 并直接收下，是官方推荐写法
                .tools(taskTool, taskOutputTool)
                // demo 是自建 ChatClient，拿不到 AiConfig 里配的默认 Advisor，日志要自己挂：
                // - order 0：打印完整 prompt + 最终响应
                // - order MAX-50 + toolRoundOnly=true：位于工具循环内部，打印每轮「调了 Task/TaskOutput、
                //   工具返回了什么」，子 Agent 的结果就是在这里回流的，重点看这个
                .advisors(new ChatLoggerAdvisor(0, 500),
                        new ChatLoggerAdvisor(Integer.MAX_VALUE - 50, 500, true))
                .call()
                .content();
    }
}
