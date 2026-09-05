package com.lion.agent.tools;

import cn.hutool.json.JSONUtil;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * ToolCallback 构建辅助类
 * <p>
 * 提供简洁的 API 将工具名称、描述、参数定义和执行逻辑组装为 Spring AI ToolCallback。
 */
public final class ToolCallbackBuilder {

    private ToolCallbackBuilder() {
    }

    /**
     * 构建一个 ToolCallback
     *
     * @param name           工具名称
     * @param description    工具描述
     * @param properties     参数属性定义（JSON Schema properties）
     * @param requiredParams 必填参数列表
     * @param executor       执行函数，接收原始 JSON 字符串，返回结果字符串
     * @return Spring AI ToolCallback 实例
     */
    public static ToolCallback build(String name,
                                     String description,
                                     Map<String, Map<String, String>> properties,
                                     List<String> requiredParams,
                                     Function<String, String> executor) {

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(properties));
        schema.put("required", requiredParams);
        String inputSchema = JSONUtil.toJsonStr(schema);

        ToolDefinition toolDefinition = DefaultToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();

        return new ToolCallback() {
            @NotNull
            @Override
            public ToolDefinition getToolDefinition() {
                return toolDefinition;
            }

            @NotNull
            @Override
            public String call(String toolInput) {
                printBox("🔧 [Tool Call] " + name,
                        "入参: " + truncate(toolInput, 200));

                long start = System.currentTimeMillis();
                try {
                    String result = executor.apply(toolInput);
                    printBox("✅ [Tool Result] " + name,
                            "耗时: " + elapsedMs(start),
                            "结果: " + truncate(result, 300));
                    return result;
                } catch (Exception exception) {
                    // 输出整个异常对象（含类型与 message），避免 getMessage() 为 null 时只剩 "null"
                    printBox("❌ [Tool Error] " + name,
                            "耗时: " + elapsedMs(start),
                            "异常: " + exception);
                    throw exception;
                }
            }
        };
    }

    private static final String BOX_TOP = "╔══════════════════════════════════════════";
    private static final String BOX_BOTTOM = "╚══════════════════════════════════════════";

    /**
     * 以「盒子」排版输出一段工具调用日志（入参 / 结果 / 异常共用同一套模板）
     * 说明：故意使用 System.out 而非 SLF4J —— 多行盒子文本经 logback 输出时每行都会被
     * 追加 pattern 前缀，破坏排版；若想接入日志框架，可改为单行 log.info(...) 格式。
     */
    private static void printBox(String title, String... lines) {
        System.out.println();
        System.out.println(BOX_TOP);
        System.out.println("║ " + title);
        for (String line : lines) {
            if (line != null) {
                System.out.println("║ " + line);
            }
        }
        System.out.println(BOX_BOTTOM);
    }

    private static String elapsedMs(long start) {
        return (System.currentTimeMillis() - start) + "ms";
    }

    /**
     * 截断字符串，超过 maxLength 时追加省略号，同时将换行替换为空格以保持日志单行可读
     */
    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "(null)";
        }
        String oneLine = text.replace("\n", " ").replace("\r", "");
        if (oneLine.length() <= maxLength) {
            return oneLine;
        }
        return oneLine.substring(0, maxLength) + "...（共" + oneLine.length() + "字符）";
    }
}
