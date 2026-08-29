package com.lion.agent.config;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 节日倒计时工具注册配置：演示 Spring AI 2.0 <b>函数式声明</b>工具（{@link FunctionToolCallback}）。
 *
 * <p>与 {@link WeatherToolConfig}（{@code MethodToolCallback}，绑定一个既有方法）不同，
 * 本类以"按工具语义命名配置类"为原则：类名即职责——{@code HolidayToolConfig} 只负责节日倒计时这一个工具。
 * 函数式风格直接传一个 Lambda/方法引用作为执行体，无需反射定位方法、无需手写 inputSchema——
 * 用 {@code .inputType(输入类型.class)} 声明入参结构，Spring AI 自动生成参数 JSON Schema。
 *
 * <p>三种 builder 重载：
 * <ul>
 *   <li>{@code builder(name, Function<I, O>)} —— 无状态输入，最常见；</li>
 *   <li>{@code builder(name, BiFunction<I, ToolContext, O>)} —— 需要读取调用上下文（如会话 ID/用户 ID）；</li>
 *   <li>{@code builder(name, Supplier<O>)} / {@code builder(name, Consumer<I>)} —— 无参/无返回的极端场景。</li>
 * </ul>
 */
@Configuration
public class HolidayToolConfig {

    /**
     * 工具输入参数：record 的字段即工具参数。
     * {@link FunctionToolCallback.Builder#inputType} 会据此自动生成 JSON Schema
     * （字段名、类型、必填），无需手写。
     */
    public record HolidayInput(String holiday) {
    }

    /** 公历固定日期节日：节日名 -> 月/日（农历春节单独走查表，见 SPRING_FESTIVAL） */
    private static final Map<String, int[]> SOLAR_HOLIDAYS = Map.of(
            "元旦", new int[]{1, 1},
            "劳动节", new int[]{5, 1},
            "国庆节", new int[]{10, 1},
            "圣诞节", new int[]{12, 25}
    );

    /**
     * 春节（农历正月初一）对应的公历日期表：年份 -> 月/日。
     * 春节是农历节日，依赖天文历算，示例用查表法覆盖 2025-2035；
     * 生产环境如需任意年份，可替换为农历转换库（如 lunar-java）。
     */
    private static final Map<Integer, int[]> SPRING_FESTIVAL = Map.ofEntries(
            Map.entry(2025, new int[]{1, 29}),
            Map.entry(2026, new int[]{2, 17}),
            Map.entry(2027, new int[]{2, 6}),
            Map.entry(2028, new int[]{1, 26}),
            Map.entry(2029, new int[]{2, 13}),
            Map.entry(2030, new int[]{2, 3}),
            Map.entry(2031, new int[]{1, 23}),
            Map.entry(2032, new int[]{2, 11}),
            Map.entry(2033, new int[]{1, 31}),
            Map.entry(2034, new int[]{2, 19}),
            Map.entry(2035, new int[]{2, 8})
    );

    /**
     * 注册"节日倒计时"工具。
     *
     * <p>函数式声明四要素：
     * <ol>
     *   <li><b>工具名</b>（第一个参数）：模型看到的名称，同一 ChatClient 内不可重名；</li>
     *   <li><b>执行函数</b>（第二个参数）：入参就是自动反序列化的 {@link HolidayInput} 对象，
     *       返回 String 即工具结果；</li>
     *   <li>{@code description}：模型判断何时调用的依据，要写清支持的节日范围；</li>
     *   <li>{@code inputType}：入参类型，自动生成 Schema（等价于手写 inputSchema，但不会写错）。</li>
     * </ol>
     *
     * @return 节日倒计时工具回调
     */
    @Bean
    public ToolCallback holidayCountdownTool() {
        return FunctionToolCallback.builder("holidayCountdown", this::countdown)
                .description("查询指定节日距离今天还有多少天。支持的节日：元旦、春节、劳动节、国庆节、圣诞节")
                .inputType(HolidayInput.class)
                .build();
    }

    /**
     * 倒计时核心逻辑：输入节日名，返回"今天距离下一次该节日还有 N 天"。
     * 若今天的日期已过今年该节日，自动滚动到下一次（跨年）。
     *
     * @param input 模型解析出的入参（holiday 为节日名称）
     * @return 倒计时文案；输入不支持时返回友好提示
     */
    private String countdown(HolidayInput input) {
        if (input == null || input.holiday() == null || input.holiday().isBlank()) {
            return "请告诉我您想查询的节日名称，例如：元旦、春节、劳动节、国庆节、圣诞节";
        }

        String name = input.holiday().trim();
        LocalDate today = LocalDate.now();

        // 春节：农历节日，按年份查公历对照表
        if ("春节".equals(name)) {
            LocalDate target = nextSpringFestival(today);
            return formatCountdown(name, today, target);
        }

        // 公历节日：取今年该日，已过则顺延到下一年
        int[] md = SOLAR_HOLIDAYS.get(name);
        if (md == null) {
            return "暂不支持该节日，支持的节日：元旦、春节、劳动节、国庆节、圣诞节";
        }
        LocalDate target = LocalDate.of(today.getYear(), md[0], md[1]);
        if (target.isBefore(today)) {
            target = target.plusYears(1);
        }
        return formatCountdown(name, today, target);
    }

    /** 计算下一次春节：今年没到取今年，过了取明年（查不到年份返回提示文案） */
    private LocalDate nextSpringFestival(LocalDate today) {
        int year = today.getYear();
        int[] md = SPRING_FESTIVAL.get(year);
        LocalDate target = LocalDate.of(year, md[0], md[1]);
        if (!target.isBefore(today)) {
            return target;
        }
        md = SPRING_FESTIVAL.get(year + 1);
        if (md == null) {
            throw new IllegalArgumentException("该年份春节日期暂未收录（表覆盖 2025-2035）");
        }
        return LocalDate.of(year + 1, md[0], md[1]);
    }

    /** 统一格式化输出：当天=祝福语，否则=还有 N 天 + 目标日期 */
    private String formatCountdown(String name, LocalDate today, LocalDate target) {
        long days = ChronoUnit.DAYS.between(today, target);
        if (days == 0) {
            return "今天是" + name + "！祝您节日快乐！";
        }
        return "距离" + target.getYear() + "年的" + name + "还有 " + days + " 天（" + target + "）";
    }
}
