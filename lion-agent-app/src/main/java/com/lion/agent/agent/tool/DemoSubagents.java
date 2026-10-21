package com.lion.agent.agent.tool;

import org.springaicommunity.agent.common.task.subagent.SubagentDefinition;
import org.springaicommunity.agent.common.task.subagent.SubagentExecutor;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.common.task.subagent.SubagentResolver;
import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springaicommunity.agent.common.task.subagent.TaskCall;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示用的「子 Agent 三件套」：{@link SubagentDefinition} + {@link SubagentResolver} + {@link SubagentExecutor}。
 *
 * <p><b>为什么自己实现而不用库自带的 claude 包</b>：{@code task/claude/*} 那套按 Anthropic 的模型名
 * （claude-opus-4-64k / claude-sonnet-4-5 ...）映射 provider，再按 provider 取 ChatClient.Builder，
 * 本项目只有千问一个 OpenAI 兼容模型，走不通。这里直接实现三个接口，子 Agent 复用同一个 ChatModel，
 * 逻辑最简、可控。
 *
 * <p>职责划分：
 * <ul>
 *   <li>{@link Definition} —— 一个子 Agent 的"名片"：名字、能力描述、类型 kind</li>
 *   <li>{@link Resolver} —— 按 {@link SubagentReference} 找到对应的 Definition</li>
 *   <li>{@link Executor} —— 真正跑这个子 Agent（这里就是一次独立的 ChatClient 调用）</li>
 * </ul>
 */
public final class DemoSubagents {

    /** 本例子 Agent 的类型标识（模型的 subagent_type 参数会带上它对应的名字） */
    public static final String KIND = "demo";

    private DemoSubagents() {
    }

    /** 子 Agent 名片 */
    public record Definition(String name, String description) implements SubagentDefinition {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public String getKind() {
            return KIND;
        }

        @Override
        public SubagentReference getReference() {
            return new SubagentReference("demo://" + name, KIND);
        }
    }

    /** 按引用找名片：这里用内存 Map，实际可换成读 .md 文件 / 数据库 */
    public static final class Resolver implements SubagentResolver {

        private final Map<String, Definition> registry;

        public Resolver(List<Definition> definitions) {
            Map<String, Definition> map = new LinkedHashMap<>();
            for (Definition d : definitions) {
                map.put(d.name(), d);
            }
            this.registry = map;
        }

        @Override
        public boolean canResolve(SubagentReference reference) {
            return KIND.equals(reference.kind()) && registry.containsKey(nameOf(reference));
        }

        @Override
        public SubagentDefinition resolve(SubagentReference reference) {
            return registry.get(nameOf(reference));
        }

        /** 引用 uri 形如 demo://reviewer，取最后一段当名字 */
        private static String nameOf(SubagentReference reference) {
            String uri = reference.uri();
            int idx = uri.lastIndexOf('/');
            return idx < 0 ? uri : uri.substring(idx + 1);
        }
    }

    /**
     * 子 Agent 执行器：用子 Agent 的描述作为 system prompt，用户任务作为 user prompt，跑一次对话。
     *
     * <p>注意：这里刻意<b>不再挂 Task 工具</b>——子 Agent 若还能派生子 Agent 会无限递归。
     */
    public static final class Executor implements SubagentExecutor {

        private final ChatModel chatModel;

        public Executor(ChatModel chatModel) {
            this.chatModel = chatModel;
        }

        @Override
        public String getKind() {
            return KIND;
        }

        @Override
        public String execute(TaskCall call, SubagentDefinition definition) {
            return ChatClient.builder(chatModel)
                    .build()
                    .prompt()
                    .system(definition.getDescription())
                    .user(call.prompt())
                    .call()
                    .content();
        }
    }

    /**
     * 组装 {@link SubagentType}：库里 TaskTool 正是靠它知道「有哪些子 Agent 类型、怎么解析、怎么执行」。
     *
     * @param chatModel   子 Agent 复用的模型
     * @param definitions 允许派活的子 Agent 列表
     */
    public static SubagentType subagentType(ChatModel chatModel, List<Definition> definitions) {
        return new SubagentType(new Resolver(definitions), new Executor(chatModel));
    }
}
