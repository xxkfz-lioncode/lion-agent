package com.lion.agent.rag.controller;

import com.lion.common.result.R;
import com.lion.agent.rag.dto.KnowledgeChatRequest;
import com.lion.agent.pojo.vo.ChatResult;
import com.lion.agent.rag.service.KnowledgeChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 知识库问答接口（RAG）
 *
 * <p>与普通对话 {@code /api/chat} 分离：本接口固定走知识库检索链路，
 * 普通对话不再接收 knowledgeId，也不再承担检索职责。</p>
 */
@Tag(name = "知识库问答", description = "基于知识库检索的问答（RAG），独立于普通对话 /api/chat")
@RestController
@RequestMapping("/api/knowledge/chat")
@RequiredArgsConstructor
public class KnowledgeChatController {

    private final KnowledgeChatService knowledgeChatService;

    @Operation(summary = "知识库问答", description = "检索指定（或全部）知识库后基于资料作答；conversationId 为空时自动创建新会话")
    @PostMapping("/send")
    public R<ChatResult> send(@Valid @RequestBody KnowledgeChatRequest request) {
        return R.success(knowledgeChatService.send(request));
    }

    @Operation(summary = "知识库问答流式接口（SSE）",
            description = "以 text/event-stream 流式返回基于知识库的回复；事件类型：start/message/done/error，done 携带 referencedChunks 引用来源")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody KnowledgeChatRequest request) {
        return knowledgeChatService.stream(request);
    }
}
