package com.lion.agent.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 节日倒计时服务：负责"查询指定节日距离今天还有多少天"的全部业务逻辑。
 * 与工具注册（{@code CustomToolsConfig}）分离，逻辑可独立复用/测试。
 */
@Service
public class HolidayCountdownService {

    /**
     * 工具输入参数：record 的字段即工具参数。
     * {@code FunctionToolCallback.Builder#inputType} 会据此自动生成 JSON Schema
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
     * 倒计时核心逻辑：输入节日名，返回"今天距离下一次该节日还有 N 天"。
     * 若今天的日期已过今年该节日，自动滚动到下一次（跨年）。
     *
     * @param input 模型解析出的入参（holiday 为节日名称）
     * @return 倒计时文案；输入不支持时返回友好提示
     */
    public String countdown(HolidayInput input) {
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
