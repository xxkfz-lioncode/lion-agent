package com.lion.agent.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import com.lion.agent.common.Result;
import com.lion.agent.model.dto.ChatRequest;
import com.lion.agent.service.ChatService;
import com.lion.agent.model.vo.ChatResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 对话接口
 */
@Tag(name = "02-智能对话", description = "与千问大模型对话")
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @Operation(summary = "发送消息", description = "发送一条用户消息并获取 AI 回复；conversationId 为空时自动创建新会话")
    @PostMapping("/send")
    public Result<ChatResult> send(@Valid @RequestBody ChatRequest request) {
        return Result.success(chatService.send(request));
    }

    @Operation(summary = "流式发送消息（SSE）", description = "以 text/event-stream 流式返回 AI 回复，边生成边推送；事件类型：start/message/done")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        return chatService.stream(request);
    }

    @Operation(summary = "多模态对话（图片 + 文字）",
            description = "multipart/form-data 上传：message（文本，必填）、conversationId（会话，可选）、images（图片文件，可选，支持多张）、imageUrls（图片 URL，可选，支持多个）。图片与文本一起发送给多模态大模型")
    @PostMapping(value = "/multimodal", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SaIgnore
    public Result<ChatResult> multimodal(
            @RequestParam("message") @NotBlank(message = "消息内容不能为空") String message,
            @RequestParam(value = "conversationId", required = false) Long conversationId,
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "imageUrls", required = false) List<String> imageUrls) {
        return Result.success(chatService.sendMultimodal(message, conversationId, images, imageUrls));
    }
}
