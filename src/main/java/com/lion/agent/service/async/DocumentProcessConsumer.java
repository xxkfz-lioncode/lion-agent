package com.lion.agent.service.async;

import com.lion.agent.common.async.AbstractRedisTaskConsumer;
import com.lion.agent.common.async.RedisTaskQueue;
import com.lion.agent.dto.DocumentProcessTask;
import com.lion.agent.service.KnowledgeDocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 知识库文档异步处理消费者
 * <p>
 * 从 Redis 队列弹出 {@link DocumentProcessTask}，调用
 * {@link KnowledgeDocumentService#processDocument(Long, Long, String, String)} 执行
 * 解析/切分/向量化。处理失败时若未超过最大重试次数，重新入队重试。
 */
@Slf4j
@Component
public class DocumentProcessConsumer extends AbstractRedisTaskConsumer<DocumentProcessTask> {

    /** 队列名（与生产者 upload 一致） */
    public static final String QUEUE_NAME = "document:process";

    /** 处理中锁 key 前缀（幂等：同一文档只允许一个消费者线程处理） */
    private static final String LOCK_KEY_PREFIX = "lion:task:lock:doc:";

    private static final Duration LOCK_TTL = Duration.ofMinutes(30);

    private final KnowledgeDocumentService documentService;
    private final RedisTaskQueue taskQueue;
    private final StringRedisTemplate redisTemplate;

    @Value("${lion.async.max-retry:3}")
    private int maxRetry;

    public DocumentProcessConsumer(KnowledgeDocumentService documentService, RedisTaskQueue taskQueue,
                                   StringRedisTemplate redisTemplate) {
        super(taskQueue, DocumentProcessTask.class);
        this.documentService = documentService;
        this.taskQueue = taskQueue;
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected String queueName() {
        return QUEUE_NAME;
    }

    @Override
    protected void handle(DocumentProcessTask task) {
        if (task == null || task.getDocId() == null) {
            log.warn("[DocTask] 任务缺少 docId，丢弃 task={}", task);
            return;
        }
        // 幂等锁：拿到锁才处理，防止同一文档重复入队/并发处理
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY_PREFIX + task.getDocId(), "1", LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            log.info("[DocTask] 文档处理中（已有消费者在处理），跳过 docId={}", task.getDocId());
            return;
        }
        try {
            documentService.processDocument(task.getKnowledgeId(), task.getDocId(), task.getFilePath(), task.getSplitter());
        } catch (Exception e) {
            // 处理失败：未超重试上限则重新入队
            int retry = task.getRetryCount() == null ? 0 : task.getRetryCount();
            if (retry < maxRetry) {
                task.setRetryCount(retry + 1);
                boolean pushed = taskQueue.push(QUEUE_NAME, task);
                log.warn("[DocTask] 文档处理失败，重新入队（第 {}/{} 次）docId={} pushed={}",
                        retry + 1, maxRetry, task.getDocId(), pushed, e);
            } else {
                log.error("[DocTask] 文档处理失败且超过最大重试次数 docId={}", task.getDocId(), e);
            }
        } finally {
            redisTemplate.delete(LOCK_KEY_PREFIX + task.getDocId());
        }
    }
}
