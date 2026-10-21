package com.lion.agent.dify.controller;

import com.lion.agent.common.result.R;
import com.lion.agent.dify.dto.DifyChatSendDTO;
import com.lion.agent.dify.dto.DifyFeedbackDTO;
import com.lion.agent.dify.dto.DifyRenameDTO;
import com.lion.agent.dify.service.DifyChatService;
import com.lion.agent.dify.vo.DifyChatReplyVO;
import io.github.guoshiqiufeng.dify.chat.dto.response.AppParametersResponseVO;
import io.github.guoshiqiufeng.dify.chat.dto.response.MessageConversationsResponse;
import io.github.guoshiqiufeng.dify.core.pojo.DifyPageResult;
import io.github.guoshiqiufeng.dify.core.pojo.response.MessagesResponseVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Dify 对话接口
 *
 * <p>所有接口都需要 {@code appCode}（应用编码）：GET 走 query 参数 {@code app}，POST 走请求体字段。
 * 会话与具体应用绑定，跨应用的会话 ID 不通用。
 */
@Tag(name = "21-Dify 对话", description = "对接 Dify 聊天型应用：对话、会话管理、消息反馈")
@RestController
@RequestMapping("/api/dify/chat")
@RequiredArgsConstructor
public class DifyChatController {

    private final DifyChatService difyChatService;

    @Operation(summary = "发送消息（阻塞）", description = "等待 Dify 生成完整回复后一次性返回")
    @PostMapping("/send")
    public R<DifyChatReplyVO> send(@Valid @RequestBody DifyChatSendDTO dto) {
        return R.success(difyChatService.send(dto));
    }

    @Operation(summary = "发送消息（流式 SSE）",
            description = "text/event-stream 流式返回，事件：message（增量片段）/ done（完整回复）/ error")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody DifyChatSendDTO dto) {
        return difyChatService.stream(dto);
    }

    @Operation(summary = "会话列表", description = "游标分页：lastId 为上一页最后一条会话 ID，首页不传")
    @GetMapping("/conversations")
    public R<DifyPageResult<MessageConversationsResponse>> conversations(
            @RequestParam(required = false) String app,
            @RequestParam(required = false) String lastId,
            @RequestParam(defaultValue = "20") Integer limit) {
        return R.success(difyChatService.conversations(app, lastId, limit));
    }

    @Operation(summary = "会话聊天历史", description = "游标分页：firstId 为上一页最后一条消息 ID，首页不传")
    @GetMapping("/messages")
    public R<DifyPageResult<MessagesResponseVO>> messages(
            @RequestParam(required = false) String app,
            @RequestParam String conversationId,
            @RequestParam(required = false) String firstId,
            @RequestParam(defaultValue = "20") Integer limit) {
        return R.success(difyChatService.messages(app, conversationId, firstId, limit));
    }

    @Operation(summary = "下一轮建议问题", description = "应用未开启该功能时返回空数组")
    @GetMapping("/suggested")
    public R<List<String>> suggested(
            @RequestParam(required = false) String app,
            @RequestParam String messageId) {
        return R.success(difyChatService.suggested(app, messageId));
    }

    @Operation(summary = "消息点赞/点踩", description = "rating：like / dislike / none（撤销）")
    @PostMapping("/feedback")
    public R<Void> feedback(@Valid @RequestBody DifyFeedbackDTO dto) {
        difyChatService.feedback(dto);
        return R.success();
    }

    @Operation(summary = "会话重命名")
    @PostMapping("/conversations/rename")
    public R<Void> rename(@Valid @RequestBody DifyRenameDTO dto) {
        difyChatService.rename(dto);
        return R.success();
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/conversations/{conversationId}")
    public R<Void> deleteConversation(
            @PathVariable String conversationId,
            @RequestParam(required = false) String app) {
        difyChatService.deleteConversation(app, conversationId);
        return R.success();
    }

    @Operation(summary = "应用编排参数", description = "开场白、用户输入变量、文件上传配置等")
    @GetMapping("/parameters")
    public R<AppParametersResponseVO> parameters(@RequestParam(required = false) String app) {
        return R.success(difyChatService.parameters(app));
    }
}
