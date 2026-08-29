package com.lion.agent.config;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;

/**
 * 编程式使用
 * 天气查询工具注册配置：演示绕开 {@code @Tool} 注解、纯手工构建 {@link ToolCallback}。
 *
 * <p>适用场景：
 * <ul>
 *   <li>工具方法来自第三方/历史代码，无法直接在方法上加 {@code @Tool} 注解；</li>
 *   <li>工具名、描述、参数结构需要运行时动态生成（如多租户差异化、配置驱动）。</li>
 * </ul>
 *
 * <p>关键约束：Spring AI 2.0 启动时会校验每个 ToolCallback 的 {@link ToolDefinition}
 * 必须包含非空的 {@code inputSchema}（参数 JSON Schema，模型靠它生成入参），
 * 缺失会抛 {@code IllegalStateException: inputSchema cannot be null or empty}。
 */
@Configuration
public class WeatherToolConfig {

    /**
     * 天气服务：模拟无注解的外部服务，方法签名与下方 inputSchema 的字段一一对应。
     */
    static class WeatherService {

        /**
         * 按城市查询气温。
         *
         * @param city 城市名称
         * @param unit 温度单位：C=摄氏度，F=华氏度
         * @return 气温文本，未知城市返回提示
         */
        public String queryWeatherByCity(String city, String unit) {
            if ("北京".equals(city)) {
                return "28°" + ("C".equals(unit) ? "C" : "F");
            }


            return "未知城市";
        }
    }

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
        WeatherService service = new WeatherService();

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
                .toolObject(service)         // 绑定方法所属对象（含业务状态）
                .build();
    }



}
