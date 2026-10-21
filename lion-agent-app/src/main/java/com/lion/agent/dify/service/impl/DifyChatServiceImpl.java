package com.lion.agent.dify.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.lion.agent.common.utils.SseEmitterUtils;
import com.lion.agent.dify.dto.DifyChatSendDTO;
import com.lion.agent.dify.dto.DifyFeedbackDTO;
import com.lion.agent.dify.dto.DifyRenameDTO;
import com.lion.agent.dify.service.DifyAppService;
import com.lion.agent.dify.service.DifyChatService;
import com.lion.agent.dify.vo.DifyChatReplyVO;
import io.github.guoshiqiufeng.dify.chat.DifyChat;
import io.github.guoshiqiufeng.dify.chat.dto.request.ChatMessageSendRequest;
import io.github.guoshiqiufeng.dify.chat.dto.request.MessageConversationsRequest;
import io.github.guoshiqiufeng.dify.chat.dto.request.MessageFeedbackRequest;
import io.github.guoshiqiufeng.dify.chat.dto.request.MessagesRequest;
import io.github.guoshiqiufeng.dify.chat.dto.request.RenameConversationRequest;
import io.github.guoshiqiufeng.dify.chat.dto.response.AppParametersResponseVO;
import io.github.guoshiqiufeng.dify.chat.dto.response.ChatMessageSendCompletionResponse;
import io.github.guoshiqiufeng.dify.chat.dto.response.ChatMessageSendResponse;
import io.github.guoshiqiufeng.dify.chat.dto.response.MessageConversationsResponse;
import io.github.guoshiqiufeng.dify.core.pojo.DifyPageResult;
import io.github.guoshiqiufeng.dify.core.pojo.response.MessagesResponseVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dify 对话服务实现
 *
 * <p>密钥来源：由 {@link DifyAppService#resolveApiKey} 按应用编码解析（后端持有，前端只传 appCode）；
 * userId 取当前登录用户 ID，保证 Dify 侧的会话/历史按终端用户隔离。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DifyChatServiceImpl implements DifyChatService {

    private static final String APP_TYPE = "chat";

    private final DifyChat difyChat;
    private final DifyAppService difyAppService;

    /** 当前登录用户在 Dify 侧的标识 */
    private String currentUserId() {
        return "u" + StpUtil.getLoginIdAsLong();
    }

    /** 组装发送请求（阻塞与流式共用） */
    private ChatMessageSendRequest buildSendRequest(DifyChatSendDTO dto) {
        ChatMessageSendRequest request = new ChatMessageSendRequest();
        request.setApiKey(difyAppService.resolveApiKey(dto.getAppCode(), APP_TYPE));
        request.setUserId(currentUserId());
        request.setContent(dto.getContent());
        request.setConversationId(dto.getConversationId());
        request.setInputs(dto.getInputs());
        // 会话名由 Dify 依据首条消息自动生成
        request.setAutoGenerateName(true);
        return request;
    }

    @Override
    public DifyChatReplyVO send(DifyChatSendDTO dto) {
        ChatMessageSendResponse response = difyChat.send(buildSendRequest(dto));
        return DifyChatReplyVO.builder()
                .conversationId(response.getConversationId())
                .messageId(response.getId())
                .answer(response.getAnswer())
                .taskId(response.getTaskId())
                .build();
    }

    @Override
    public SseEmitter stream(DifyChatSendDTO dto) {
        SseEmitter emitter = new SseEmitter(0L);

        // 会话 ID：首轮请求为空，从 Dify 返回的任意分片里回填
        AtomicReference<String> conversationId = new AtomicReference<>(dto.getConversationId());
        AtomicReference<String> messageId = new AtomicReference<>();
        StringBuilder answer = new StringBuilder();

        Disposable disposable = difyChat.sendChatMessageStream(buildSendRequest(dto)).subscribe(
                chunk -> onChunk(emitter, chunk, conversationId, messageId, answer),
                err -> {
                    log.error("[Dify-Chat] 流式调用失败", err);
                    SseEmitterUtils.error(emitter, "Dify 服务调用失败：" + err.getMessage());
                },
                () -> {
                    Map<String, Object> done = new HashMap<>();
                    done.put("conversationId", conversationId.get());
                    done.put("messageId", messageId.get());
                    done.put("reply", answer.toString());
                    if (SseEmitterUtils.send(emitter, "done", done)) {
                        SseEmitterUtils.complete(emitter);
                    }
                });

        // 客户端断开/超时时取消订阅，避免泄漏
        emitter.onTimeout(disposable::dispose);
        emitter.onCompletion(disposable::dispose);
        return emitter;
    }

    /** 处理每个流式分片：增量为空的事件（如 message_end）不发 message，只用于回填会话 ID */
    private void onChunk(SseEmitter emitter, ChatMessageSendCompletionResponse chunk,
                         AtomicReference<String> conversationId, AtomicReference<String> messageId,
                         StringBuilder answer) {
        if (chunk.getConversationId() != null && !chunk.getConversationId().isEmpty()) {
            conversationId.set(chunk.getConversationId());
        }
        if (chunk.getId() != null && !chunk.getId().isEmpty()) {
            messageId.set(chunk.getId());
        }
        String delta = chunk.getAnswer();
        if (delta != null && !delta.isEmpty()) {
            answer.append(delta);
            SseEmitterUtils.send(emitter, "message", Map.of("content", delta));
        }
        // 编排事件（node_started / workflow_started …）：供前端「编排流程」面板展示运行状态。
        // SDK 目前只暴露事件名，不带节点 ID，因此这里只做阶段提示。
        String event = chunk.getEvent();
        if (event != null && !event.isBlank() && !"message".equals(event) && !"agent_message".equals(event)) {
            SseEmitterUtils.send(emitter, "stage", Map.of("event", event));
        }
    }

    @Override
    public DifyPageResult<MessageConversationsResponse> conversations(String appCode, String lastId, Integer limit) {
        MessageConversationsRequest request = new MessageConversationsRequest();
        request.setApiKey(difyAppService.resolveApiKey(appCode, APP_TYPE));
        request.setUserId(currentUserId());
        request.setLastId(lastId);
        request.setLimit(limit == null ? 20 : limit);
        request.setSortBy("-updated_at");
        return difyChat.conversations(request);
    }

    @Override
    public DifyPageResult<MessagesResponseVO> messages(String appCode, String conversationId, String firstId,
                                                       Integer limit) {
        MessagesRequest request = new MessagesRequest();
        request.setApiKey(difyAppService.resolveApiKey(appCode, APP_TYPE));
        request.setUserId(currentUserId());
        request.setConversationId(conversationId);
        request.setFirstId(firstId);
        request.setLimit(limit == null ? 20 : limit);
        return difyChat.messages(request);
    }

    @Override
    public List<String> suggested(String appCode, String messageId) {
        // 建议问题依赖 Dify 应用开启「下一轮问题建议」功能，未开启时 Dify 直接返回 400 Bad Request。
        // 属于正常业务场景（非系统异常），捕获后返回空列表，前端不展示建议区。
        try {
            return difyChat.messagesSuggested(messageId,
                    difyAppService.resolveApiKey(appCode, APP_TYPE), currentUserId());
        } catch (Exception e) {
            log.warn("[Dify-Chat] 获取建议问题失败（应用未开启该功能时为正常现象）messageId={} 原因={}",
                    messageId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void feedback(DifyFeedbackDTO dto) {
        MessageFeedbackRequest request = new MessageFeedbackRequest();
        request.setApiKey(difyAppService.resolveApiKey(dto.getAppCode(), APP_TYPE));
        request.setUserId(currentUserId());
        request.setMessageId(dto.getMessageId());
        request.setRating(MessageFeedbackRequest.Rating.valueOf(dto.getRating().toUpperCase()));
        request.setContent(dto.getContent());
        difyChat.messageFeedback(request);
    }

    @Override
    public void rename(DifyRenameDTO dto) {
        RenameConversationRequest request = new RenameConversationRequest();
        request.setApiKey(difyAppService.resolveApiKey(dto.getAppCode(), APP_TYPE));
        request.setUserId(currentUserId());
        request.setConversationId(dto.getConversationId());
        request.setName(dto.getName());
        request.setAutoGenerate(false);
        difyChat.renameConversation(request);
    }

    @Override
    public void deleteConversation(String appCode, String conversationId) {
        difyChat.deleteConversation(conversationId, difyAppService.resolveApiKey(appCode, APP_TYPE), currentUserId());
    }

    @Override
    public AppParametersResponseVO parameters(String appCode) {
        return difyChat.parameters(difyAppService.resolveApiKey(appCode, APP_TYPE));
    }
}
