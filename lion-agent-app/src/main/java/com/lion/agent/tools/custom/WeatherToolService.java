package com.lion.agent.tools.custom;

import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 天气查询服务：模拟外部天气接口（实际项目里把 {@link #queryWeatherByCity} 里的数据换成真实 API 调用即可）。
 *
 * <p>演示数据说明：城市由调用方任意传入，天气内容为一串<b>写死的固定值</b>，不随城市、日期变化。
 */
@Service
public class WeatherToolService {

    /** 写死的演示天气：天气现象、最低温°C、最高温°C */
    private static final String MOCK_CONDITION = "晴";
    private static final int MOCK_LOW_C = 20;
    private static final int MOCK_HIGH_C = 29;

    /**
     * 查询指定城市某一天的天气。
     *
     * @param city 城市名称，例如「北京」「广州市」
     * @param date 目标日期，留空表示今天；演示数据不区分日期，仅原样回显
     * @param unit 温度单位：C=摄氏度（缺省同 C），F=华氏度
     * @return 多行天气文本；城市为空或单位非法时返回错误说明
     */
    public String queryWeatherByCity(String city, String date, String unit) {
        String normalizedCity = normalizeCity(city);
        if (normalizedCity == null) {
            return "错误：请提供要查询的城市名称，例如「北京」。";
        }

        String normalizedUnit = normalizeUnit(unit);
        if (normalizedUnit == null) {
            return "错误：不支持的温度单位 \"" + unit + "\"，可选：C（摄氏度）、F（华氏度）。";
        }

        String target = (date == null || date.isBlank()) ? LocalDate.now().toString() : date.trim();
        return String.join("\n",
                "城市：" + normalizedCity,
                "日期：" + target,
                "天气：" + MOCK_CONDITION,
                "温度：" + temp(MOCK_LOW_C, normalizedUnit) + " ~ " + temp(MOCK_HIGH_C, normalizedUnit),
                "注意：以上为本地演示 Mock 数据，不是真实天气预报，请务必向用户说明这一点。");
    }

    /** 城市名归一化：去空格、去「市 / 特别行政区」后缀；为空返回 null */
    private String normalizeCity(String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        return city.trim().replaceAll("(市|特别行政区)$", "");
    }

    /** 温度单位归一化：空值按摄氏度处理，非法值返回 null */
    private String normalizeUnit(String unit) {
        if (unit == null || unit.isBlank()) {
            return "C";
        }
        String normalized = unit.trim().toUpperCase();
        return "C".equals(normalized) || "F".equals(normalized) ? normalized : null;
    }

    private String temp(int celsius, String unit) {
        return "F".equals(unit)
                ? Math.round(celsius * 9.0 / 5 + 32) + "°F"
                : celsius + "°C";
    }
}
