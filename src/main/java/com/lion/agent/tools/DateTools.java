package com.lion.agent.tools;

import cn.hutool.core.date.DateUtil;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DateTools {

    /**
     * 查询系统中注册用户的总数量（自动排除逻辑删除的用户）
     */
    @Tool(description = "查询当前的时间，返回一个日期字符串，例如 \" 2026-08-23 10:33:06\"")
    @CircuitBreaker(name = "dateTools", fallbackMethod = "getNowDateFallback")
    public String getNowDate() {
       return DateUtil.now();
    }

    /**
     * 演示
     * 降级方法：本地时间获取异常或熔断器打开（OPEN）拒绝调用时执行。
     * 签名与 getNowDate() 匹配（同入参 + 可选 Throwable），返回 String。
     */
    public String getNowDateFallback(Throwable throwable) {
        log.error("获取当前时间失败，触发熔断降级，原因: {}", throwable.getMessage());
        return "获取当前时间失败，请稍后再试。";
    }
}
