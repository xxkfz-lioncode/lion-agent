package com.lion.agent.controller;

import com.lion.agent.common.result.PageResult;
import com.lion.agent.common.result.R;
import com.lion.agent.pojo.entity.ChatMessage;
import com.lion.agent.pojo.vo.ChatRecordStatsVo;
import com.lion.agent.pojo.vo.ConversationRecordVo;
import com.lion.agent.service.ChatRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话记录查询接口（只读）
 *
 * <p>供「会话记录」页面使用：左侧会话列表（含消息数统计），右侧用户提问 / AI 回复原文。</p>
 */
@Tag(name = "04-会话记录", description = "会话列表 / 消息明细查询（只读）")
@RestController
@RequestMapping("/api/chat-records")
@RequiredArgsConstructor
public class ChatRecordController {

    private final ChatRecordService chatRecordService;

    @Operation(summary = "会话列表（支持按标题/消息内容搜索）")
    @GetMapping("/conversations")
    public R<PageResult<ConversationRecordVo>> conversations(
            @RequestParam(required = false, defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String keyword) {
        return R.success(chatRecordService.listConversations(pageNum, pageSize, keyword));
    }

    @Operation(summary = "会话消息明细（用户提问 / AI 回复）")
    @GetMapping("/conversations/{id}/messages")
    public R<PageResult<ChatMessage>> messages(
            @PathVariable("id") Long id,
            @RequestParam(required = false, defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "true") boolean asc) {
        return R.success(chatRecordService.listMessages(id, pageNum, pageSize, role, keyword, asc));
    }

    @Operation(summary = "会话记录统计（会话数 / 消息数 / 提问数 / 回复数）")
    @GetMapping("/stats")
    public R<ChatRecordStatsVo> stats() {
        return R.success(chatRecordService.stats());
    }
}
