package com.lion.agent.tools;

import cn.hutool.core.date.DateUtil;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 日期时间工具集：向大模型暴露获取系统当前时间的能力。
 * <p>
 * 通过 {@code @CircuitBreaker} 保护：获取时间异常或熔断器打开（OPEN）时，
 * 自动降级到 {@link #getNowDateFallback}，避免异常直接抛给模型调用链路。
 */
@Slf4j
@Component
public class DateTools {

    /**
     * 获取系统当前时间。
     *
     * @return 当前时间字符串，格式 yyyy-MM-dd HH:mm:ss，例如 "2026-08-23 10:33:06"
     */
    @Tool(description = "获取当前时间，返回格式如 \"2026-08-23 10:33:06\" 的日期字符串")
    @CircuitBreaker(name = "dateTools", fallbackMethod = "getNowDateFallback")
    public String getNowDate() {
       return DateUtil.now();
    }

    /**
     * 降级方法：本地时间获取异常或熔断器打开（OPEN）拒绝调用时执行。
     * <p>
     * 签名与 {@link #getNowDate()} 匹配（同入参 + 可选 Throwable），返回 String，
     * 保证熔断期间大模型仍能拿到可读文案，而非收到异常中断工具调用。
     * </p>
     *
     * @param throwable 触发降级的原始异常（熔断器 OPEN 时可能为 null）
     * @return 降级文案，提示调用方稍后重试
     */
    public String getNowDateFallback(Throwable throwable) {
        log.error("获取当前时间失败，触发熔断降级，原因: {}", throwable.getMessage());
        return "获取当前时间失败，请稍后再试。";
    }
}
