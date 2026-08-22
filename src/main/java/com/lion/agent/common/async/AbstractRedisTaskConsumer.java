package com.lion.agent.common.async;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Redis List 的通用任务消费者基类
 * <p>
 * 启动时开启 N 个后台线程，循环 {@code BRPOP} 阻塞弹出任务并交给 {@link #handle(Object)} 处理；
 * 队列为空时阻塞等待（不空转），应用关闭时优雅停止（先中断阻塞等待，再等待在途任务完成）。
 *
 * @param <T> 任务类型
 */
@Slf4j
public abstract class AbstractRedisTaskConsumer<T> implements InitializingBean, DisposableBean {

    private static final long BRPOP_TIMEOUT_SECS = 3;

    private final RedisTaskQueue taskQueue;
    private final Class<T> taskType;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private ExecutorService executor;

    @Value("${lion.async.consume-threads:1}")
    private int consumeThreads;

    protected AbstractRedisTaskConsumer(RedisTaskQueue taskQueue, Class<T> taskType) {
        this.taskQueue = taskQueue;
        this.taskType = taskType;
    }

    /** 队列名（子类实现，消费者与生产者必须一致） */
    protected abstract String queueName();

    /** 实际处理逻辑（子类实现；异常需自行捕获，避免线程退出） */
    protected abstract void handle(T task);

    /** 消费线程数，默认取配置 lion.async.consume-threads（可覆盖） */
    protected int consumeThreads() {
        return Math.max(1, consumeThreads);
    }

    @Override
    public void afterPropertiesSet() {
        int threads = consumeThreads();
        executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "task-consumer-" + queueName());
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < threads; i++) {
            executor.submit(this::consumeLoop);
        }
        log.info("[TaskQueue] 消费者已启动 queue={} threads={}", queueName(), threads);
    }

    private void consumeLoop() {
        while (running.get()) {
            T task = null;
            try {
                task = taskQueue.poll(queueName(), BRPOP_TIMEOUT_SECS, taskType);
            } catch (Exception e) {
                log.warn("[TaskQueue] 弹出任务异常 queue={}", queueName(), e);
            }
            if (task == null) {
                continue; // BRPOP 超时（队列为空），继续轮询
            }
            try {
                handle(task);
            } catch (Exception e) {
                log.error("[TaskQueue] 任务处理异常 queue={} task={}", queueName(), task, e);
            }
        }
        log.info("[TaskQueue] 消费者已停止 queue={}", queueName());
    }

    @Override
    public void destroy() throws InterruptedException {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow(); // 中断 BRPOP 阻塞等待
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("[TaskQueue] 消费者关闭超时 queue={}", queueName());
            }
        }
        log.info("[TaskQueue] 消费者已关闭 queue={}", queueName());
    }
}
