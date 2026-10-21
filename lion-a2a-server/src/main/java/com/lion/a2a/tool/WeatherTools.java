package com.lion.a2a.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 远程 Agent 自己的工具箱。
 *
 * <p>这是「远程 Agent 是真 Agent」的关键证据：它不是把用户的话直接丢给模型，
 * 而是像本地子 Agent 一样挂着自己的工具，模型会自己决定要不要调用。
 *
 * <p>想加工具就在这个类里加 {@code @Tool} 方法，然后在
 * {@link com.lion.a2a.service.RemoteAgentService} 里注册即可。
 */
@Slf4j
@Service
public class WeatherTools {

    /** 演示用数据（真实项目里换成调用气象 API / 自己的业务接口即可） */
    private static final Map<String, String> MOCK_WEATHER = new LinkedHashMap<>();

    static {
        MOCK_WEATHER.put("北京", "26℃，晴，东南风 2 级，湿度 41%");
        MOCK_WEATHER.put("上海", "29℃，多云，东风 3 级，湿度 68%");
        MOCK_WEATHER.put("广州", "33℃，雷阵雨，南风 2 级，湿度 82%");
        MOCK_WEATHER.put("深圳", "31℃，多云转晴，西南风 3 级，湿度 75%");
        MOCK_WEATHER.put("杭州", "28℃，小雨，北风 2 级，湿度 77%");
    }

    /**
     * 查询城市天气。
     *
     * <p>注意：演示数据是写死的，但「调不调、什么时候调、调完怎么用」完全由模型决定，
     * 和真实 Agent 的执行逻辑一致。
     */
    @Tool(description = "查询指定城市的当前天气，返回温度、天气现象、风力和湿度")
    public String getWeather(@ToolParam(description = "城市名称，例如 北京、广州") String city) {
        String name = city == null ? "" : city.trim();
        log.info("[a2a-server] ⚙ 模型调用天气工具：{}", name);

        String result = MOCK_WEATHER.get(name);
        if (result == null) {
            // 未收录的城市给个稳定的兜底值，避免模型拿不到结果就开始编造
            result = String.format("%s：25℃，晴转多云，微风，湿度 60%%", name.isEmpty() ? "该城市" : name);
        }
        return name + "当前天气：" + result;
    }
}
