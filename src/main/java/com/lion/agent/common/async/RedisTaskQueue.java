package com.lion.agent.common.async;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis List 的通用任务队列（生产者侧）
 * <p>
 * 结构：LPUSH 入队 / BRPOP 出队（FIFO），key = {@code lion:task:queue:{queueName}}。
 * 队列本身是消息的"暂存区"：生产者在请求线程内入队后立即返回，消费者在后台线程阻塞弹出处理。
 * 单实例/多实例均适用（BRPOP 天然支持多消费者竞争消费，同一任务只会被一个消费者取走）。
 */
@Slf4j
@Component
public class RedisTaskQueue {

    /** 队列 key 前缀 */
    public static final String QUEUE_KEY_PREFIX = "lion:task:queue:";

    private final StringRedisTemplate redisTemplate;

    public RedisTaskQueue(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 推送任务到指定队列（序列化为 JSON 字符串）
     *
     * @param queueName 队列名（同一队列的消费者与生产者必须使用相同名称）
     * @param task      任务对象（需能被 Jackson 序列化）
     * @param <T>       任务类型
     * @return true 表示入队成功
     */
    public <T> boolean push(String queueName, T task) {
        try {
            String json = JSONUtil.toJsonStr(task);
            Long size = redisTemplate.opsForList().leftPush(queueKey(queueName), json);
            if (size != null && size > 0) {
                log.debug("[TaskQueue] 任务入队成功 queue={} task={}", queueName, json);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("[TaskQueue] 任务入队失败 queue={}", queueName, e);
            return false;
        }
    }

    /**
     * 阻塞弹出队尾任务（BRPOP）。队列为空时阻塞等待，超时返回 null。
     *
     * @param queueName   队列名
     * @param timeoutSecs 阻塞超时（秒），建议 1~5 秒以便及时感知关闭信号
     * @param <T>         任务类型
     * @return 反序列化后的任务；超时返回 null
     */
    public <T> T poll(String queueName, long timeoutSecs, Class<T> taskType) {
        String raw = redisTemplate.opsForList().rightPop(queueKey(queueName), timeoutSecs, java.util.concurrent.TimeUnit.SECONDS);
        if (raw == null) {
            return null;
        }
        try {
            return JSONUtil.toBean(raw, taskType);
        } catch (Exception e) {
            log.error("[TaskQueue] 任务反序列化失败 queue={} raw={}", queueName, raw, e);
            return null;
        }
    }

    /**
     * 队列中的待处理任务数量（监控用）
     */
    public Long size(String queueName) {
        Long size = redisTemplate.opsForList().size(queueKey(queueName));
        return size == null ? 0L : size;
    }

    private String queueKey(String queueName) {
        return QUEUE_KEY_PREFIX + queueName;
    }
}
