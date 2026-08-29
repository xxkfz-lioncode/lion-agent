package com.lion.agent.service;

import org.springframework.stereotype.Service;

/**
 * 天气查询服务：模拟无注解的外部服务（实际项目中可替换为真实天气 API 调用）。
 * 方法签名与 {@code CustomToolsConfig} 中手写的 inputSchema 字段一一对应。
 */
@Service
public class WeatherService {

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
