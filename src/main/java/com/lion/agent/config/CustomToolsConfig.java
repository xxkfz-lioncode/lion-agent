package com.lion.agent.config;

import com.lion.agent.service.HolidayCountdownService;
import com.lion.agent.service.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;

/**
 * 自定义工具统一注册配置：只负责把业务 Service 声明为 Spring AI 的 {@link ToolCallback}，
 * 具体业务逻辑放在对应的 Service 类中（{@link WeatherService} / {@link HolidayCountdownService}），
 * 实现"配置与实现分离"。
 *
 * <p>演示 Spring AI 2.0 两种工具声明方式：
 * <ul>
 *   <li><b>编程式（{@link MethodToolCallback}）</b>：{@link #weatherTool()} —— 反射绑定既有方法，
 *       手写 inputSchema。适用：工具方法来自第三方/历史代码，无法直接加 {@code @Tool} 注解，
 *       或工具名、描述、参数结构需运行时动态生成的场景。</li>
 *   <li><b>函数式（{@link FunctionToolCallback}）</b>：{@link #holidayCountdownTool()} ——
 *       直接传方法引用作为执行体，用 {@code .inputType(Class)} 声明入参结构，
 *       Spring AI 自动生成参数 JSON Schema，无需反射、无需手写。</li>
 * </ul>
 *
 * <p>关键约束：Spring AI 2.0 启动时会校验每个 ToolCallback 的 {@link ToolDefinition}
 * 必须包含非空的 {@code inputSchema}（参数 JSON Schema，模型靠它生成入参），
 * 缺失会抛 {@code IllegalStateException: inputSchema cannot be null or empty}。
 */
@Configuration
@RequiredArgsConstructor
public class CustomToolsConfig {

    /** 天气查询业务实现 */
    private final WeatherService weatherService;

    /** 节日倒计时业务实现 */
    private final HolidayCountdownService holidayCountdownService;

    // =====================================================================
    // 工具一：天气查询 —— MethodToolCallback（编程式）
    // =====================================================================

    /**
     * 手工构建天气查询工具并注册为 Bean，可直接用于 {@code ChatClient.defaultTools(...)}。
     *
     * <p>构建分两步：
     * <ol>
     *   <li><b>定义元信息</b>（{@link ToolDefinition.Builder}）：name 全局唯一、description
     *       供模型判断何时调用、inputSchema 声明参数结构（必填，否则启动报错）；</li>
     *   <li><b>绑定执行体</b>（{@link MethodToolCallback.Builder}）：把定义 + 方法 + 目标对象
     *       组装成可执行回调，调用时按 inputSchema 解析模型传入的 JSON，反射执行方法。</li>
     * </ol>
     *
     * @return 天气查询工具回调
     */
    @Bean
    public ToolCallback weatherTool() {
        // 反射定位业务方法：两个 String 参数，与下方 inputSchema 的 properties 一一对应
        Method method = ReflectionUtils.findMethod(WeatherService.class,
                "queryWeatherByCity", String.class, String.class);
        if (method == null) {
            throw new RuntimeException("WeatherService.queryWeatherByCity 方法不存在");
        }

        // 1. 工具元信息：inputSchema 用 JSON Schema 描述参数结构（类型/枚举/必填），
        //    模型据此生成入参 JSON；缺省会直接启动失败
        ToolDefinition definition = ToolDefinition.builder()
                .name("queryWeatherByCity")  // 模型看到的工具名，同一 ChatClient 内不可重名
                .description("查询指定城市的气温，可指定摄氏度(C)或华氏度(F)")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "city": { "type": "string", "description": "城市名称，例如：北京" },
                            "unit": { "type": "string", "description": "温度单位", "enum": ["C", "F"] }
                          },
                          "required": ["city", "unit"]
                        }
                        """)
                .build();

        // 2. 定义 + 方法 + 目标对象绑定，生成可执行回调
        return MethodToolCallback.builder()
                .toolDefinition(definition)  // 传入上一步构建的定义
                .toolMethod(method)          // 绑定被调用的方法
                .toolObject(weatherService)  // 绑定方法所属对象（注入的 Service）
                .build();
    }

    // =====================================================================
    // 工具二：节日倒计时 —— FunctionToolCallback（函数式）
    // =====================================================================

    /**
     * 注册"节日倒计时"工具。
     *
     * <p>函数式声明四要素：
     * <ol>
     *   <li><b>工具名</b>（第一个参数）：模型看到的名称，同一 ChatClient 内不可重名；</li>
     *   <li><b>执行函数</b>（第二个参数）：方法引用，入参就是自动反序列化的
     *       {@link HolidayCountdownService.HolidayInput} 对象，返回 String 即工具结果；</li>
     *   <li>{@code description}：模型判断何时调用的依据，要写清支持的节日范围；</li>
     *   <li>{@code inputType}：入参类型，自动生成 Schema（等价于手写 inputSchema，但不会写错）。</li>
     * </ol>
     *
     * @return 节日倒计时工具回调
     */
    @Bean
    public ToolCallback holidayCountdownTool() {
        return FunctionToolCallback.builder("holidayCountdown", holidayCountdownService::countdown)
                .description("查询指定节日距离今天还有多少天。支持的节日：元旦、春节、劳动节、国庆节、圣诞节")
                .inputType(HolidayCountdownService.HolidayInput.class)
                .build();
    }
}
