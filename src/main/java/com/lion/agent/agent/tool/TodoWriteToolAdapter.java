package com.lion.agent.agent.tool;

import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;

/**
 * TodoWrite 薄适配器：把入参从「包装记录 {@link Todos}」摊平成 {@code List<TodoItem>}。
 *
 * <p><b>为什么需要它</b>：社区库 {@link TodoWriteTool} 的 {@code @Tool} 方法签名是
 * {@code todoWrite(Todos)}，而 {@code Todos} 唯一的字段也叫 {@code todos}。Spring AI 按参数名
 * 再包一层生成 schema，「正确」的 JSON 就成了双层 {@code {"todos":{"todos":[...]}}}。
 * 模型（尤其非 Claude 系）几乎必然塌成直觉的单层 {@code {"todos":[...]}}，Spring AI 便拿一个数组
 * 去反序列化 {@code Todos} 记录 → {@code MismatchedInputException: Cannot deserialize Todos from
 * Array value}。
 *
 * <p><b>做法</b>：本适配器的 {@code @Tool} 方法签名直接是 {@code List<TodoItem>}，生成的 schema
 * 即单层，正好对上模型天然产出的形状；校验（唯一 in_progress、content/activeForm 非空、合法 status）
 * 与事件派发全部委托给库工具，行为不变，工具名仍为 {@code TodoWrite}。
 *
 * <p>注意：参数名 {@code todos} 必须进入字节码（{@code -parameters}），否则 Spring AI 只能拿到
 * {@code arg0} 作为 schema 属性名（spring-boot-starter-parent 默认已开启该编译参数）。
 */
public final class TodoWriteToolAdapter {

    private final TodoWriteTool delegate;

    public TodoWriteToolAdapter(TodoWriteTool delegate) {
        this.delegate = delegate;
    }

    @Tool(name = "TodoWrite", description = """
            为当前任务创建并维护结构化的任务清单。每次调用必须传入完整清单（会整体替换旧清单）。
            每一项需要：content（中文祈使句，如"设计数据模型"）、activeForm（"正在……"形式，如"正在设计数据模型"）
            以及 status（pending | in_progress | completed）。同一时间最多只能有一个任务处于 in_progress。""")
    public String todoWrite(List<Todos.TodoItem> todos) {
        // 摊平的 List 重新包回库记录，复用库工具的校验 + 事件派发，仅入参形状不同
        return delegate.todoWrite(new Todos(todos));
    }
}
