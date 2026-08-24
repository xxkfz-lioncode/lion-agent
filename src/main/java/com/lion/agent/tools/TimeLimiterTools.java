package com.lion.agent.tools;

import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.concurrent.CompletableFuture;

/**
 * {@code @TimeLimiter} 限时降级演示工具。
 * <p>
 * 背景：resilience4j 的 {@code @TimeLimiter} 注解要求方法返回 {@link CompletableFuture}，
 * 与 SseEmitter 不兼容（此前已在 ChatServiceImpl.stream 上移除）。本类用独立的最小示例
 * 验证限时链路：任务休眠超过配置的 timeout-duration 即被判定超时，走 fallback 返回降级文案。
 * <p>
 * 设计要点：
 * <ol>
 *   <li>{@link Tool} 方法必须返回可被 Spring AI 序列化的文本，而 {@code @TimeLimiter} 要求
 *       返回 {@code CompletableFuture}，因此拆成两层：{@code slowTask}（String）委托给
 *       {@code runSlowTask}（CompletableFuture）并 {@code join()} 等待结果。</li>
 *   <li>Spring AOP 对同类内部调用（this.runSlowTask()）不生效，必须通过代理调用，
 *       故用 {@code @Lazy} 自注入 {@code self}，绕开代理时才不生效的经典坑。</li>
 * </ol>
 */
@Slf4j
@Component
public class TimeLimiterTools {

    /**
     * 自注入代理：@TimeLimiter 依赖 AOP 代理，同类内部 this 调用不会经过切面，必须经代理调用
     */
    @Lazy
    @Autowired
    private TimeLimiterTools self;

    /**
     * 大模型可见的工具方法：模拟一个休眠 seconds 秒的慢速任务。
     * 超过限时（yml 中 slowTask 实例 timeout-duration = 3s）时返回降级文案。
     */
    @Tool(description = "模拟慢速任务：休眠指定秒数后返回当前时间（测试 @TimeLimiter 限时降级，超过 3 秒会被限时器终止并返回降级文案）")
    public String slowTask(@ToolParam(description = "任务休眠秒数（建议 1~5）：1~3 秒正常返回，4 秒以上触发限时降级") int seconds) {
        if (seconds <= 0 || seconds > 30) {
            return "参数不合法：seconds 应在 1~30 之间。";
        }
        // join() 阻塞等待：正常路径返回真实结果；限时超时路径由 fallback 产出降级结果，不会抛异常
        return self.runSlowTask(seconds).join();
    }

    /**
     * 被 {@code @TimeLimiter} 保护的异步任务：必须返回 CompletableFuture。
     * 超时后（默认由 yml slowTask 实例控制，3s）原 future 被取消，AOP 捕获 TimeoutException 走 fallback。
     */
    @TimeLimiter(name = "slowTask", fallbackMethod = "runSlowTaskFallback")
    public CompletableFuture<String> runSlowTask(int seconds) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("任务被中断", e);
            }
            return "耗时任务完成，当前时间: " + LocalTime.now();
        });
    }

    /**
     * 降级方法：签名与原方法一致（同参数 + 可选 Throwable），返回类型一致（CompletableFuture&lt;String&gt;）。
     */
    public CompletableFuture<String> runSlowTaskFallback(int seconds, Throwable throwable) {
        log.error("慢速任务触发 @TimeLimiter 降级，原因: {}", throwable.getMessage());
        return CompletableFuture.completedFuture("任务超时降级：休眠 " + seconds + " 秒超过限时（3 秒），任务已被终止。");
    }
}
