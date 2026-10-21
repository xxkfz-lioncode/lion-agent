package com.lion.agent.common.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * SSE 推送工具类：统一事件格式、异常收尾与日志，避免每个接口重复 try-catch。
 *
 * <p>约定事件（与前端一一对应）：
 * <ul>
 *   <li>{@code start}   —— 携带 conversationId，供前端拿到新建会话 ID；</li>
 *   <li>{@code message} —— 一个文本分片（当前为整段回复，真流式改造后为增量片段）；</li>
 *   <li>{@code done}    —— 完整回复 + 引用来源，并关闭连接；</li>
 *   <li>{@code error}   —— 失败信息，随后关闭连接。</li>
 * </ul>
 *
 * <p>推送失败（客户端断开 / 连接已失效）时统一 {@code completeWithError}，
 * 避免连接悬挂占用容器线程。
 */
public final class SseEmitterUtils {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterUtils.class);

    private SseEmitterUtils() {
    }

    /**
     * 推送一个事件
     *
     * @return true=推送成功；false=连接已不可用（内部已收尾）
     */
    public static boolean send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.warn("[SSE] 推送事件失败 event={} error={}", eventName, e.getMessage());
            completeWithError(emitter, e);
            return false;
        }
    }

    /** 推送会话信息（前端据此拿到新会话 ID） */
    public static boolean sendStart(SseEmitter emitter, Long conversationId) {
        return send(emitter, "start", Map.of("conversationId", conversationId));
    }

    /** 推送一段回复文本 */
    public static boolean sendMessage(SseEmitter emitter, String content) {
        return send(emitter, "message", Map.of("content", content));
    }

    /** 推送完成事件：完整回复 + 引用来源 */
    public static boolean sendDone(SseEmitter emitter, String reply, List<?> referencedChunks) {
        return send(emitter, "done", Map.of(
                "reply", reply,
                "referencedChunks", referencedChunks == null ? List.of() : referencedChunks));
    }

    /** 推送错误事件并关闭连接 */
    public static void error(SseEmitter emitter, String message) {
        if (send(emitter, "error", Map.of("message", message))) {
            complete(emitter);
        }
    }

    /** 正常关闭连接 */
    public static void complete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            log.debug("[SSE] 连接已关闭，忽略重复 complete");
        }
    }

    private static void completeWithError(SseEmitter emitter, Throwable cause) {
        try {
            emitter.completeWithError(cause);
        } catch (IllegalStateException e) {
            log.debug("[SSE] 连接已关闭，忽略重复 completeWithError");
        }
    }
}
