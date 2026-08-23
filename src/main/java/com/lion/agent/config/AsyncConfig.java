package com.lion.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置
 *
 * <p>开启 {@code @Async}，提供异步线程池：
 * <ul>
 *   <li>{@code tokenUsageExecutor}：Token 用量落库，写库与主调用链路解耦，不阻塞响应；</li>
 *   <li>{@code memoryExecutor}：长期记忆抽取与落库（LLM 抽取 + MySQL/Milvus 写入），
 *       异步执行，抽取失败不影响主对话。</li>
 * </ul>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Token 用量落库线程池。
     *
     * <p>参数由 {@code application.yml} 的 {@code lion.token-usage.executor.*} 配置维护；
     * 队列满时采用 CallerRunsPolicy（由提交线程同步兜底执行），宁可慢一点也不丢统计。</p>
     */
    @Bean("tokenUsageExecutor")
    public Executor tokenUsageExecutor(
            @Value("${lion.token-usage.executor.core-pool-size:2}") int corePoolSize,
            @Value("${lion.token-usage.executor.max-pool-size:8}") int maxPoolSize,
            @Value("${lion.token-usage.executor.queue-capacity:1024}") int queueCapacity,
            @Value("${lion.token-usage.executor.keep-alive-seconds:60}") int keepAliveSeconds,
            @Value("${lion.token-usage.executor.thread-name-prefix:token-usage-}") String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix(threadNamePrefix);
        // 队列满时由提交线程（调用方）同步执行，保证统计不丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    /**
     * 长期记忆抽取/落库线程池。
     *
     * <p>参数由 {@code application.yml} 的 {@code lion.memory.executor.*} 配置维护；
     * 队列满时采用 CallerRunsPolicy（由调用线程同步兜底），保证记忆不丢失。</p>
     */
    @Bean("memoryExecutor")
    public Executor memoryExecutor(
            @Value("${lion.memory.executor.core-pool-size:2}") int corePoolSize,
            @Value("${lion.memory.executor.max-pool-size:4}") int maxPoolSize,
            @Value("${lion.memory.executor.queue-capacity:512}") int queueCapacity,
            @Value("${lion.memory.executor.keep-alive-seconds:60}") int keepAliveSeconds,
            @Value("${lion.memory.executor.thread-name-prefix:memory-}") String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
