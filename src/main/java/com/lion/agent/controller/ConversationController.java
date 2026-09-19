package com.lion.agent.controller;

import com.lion.agent.common.result.PageResult;
import com.lion.agent.common.result.R;
import com.lion.agent.pojo.entity.ChatMessage;
import com.lion.agent.pojo.entity.Conversation;
import com.lion.agent.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 会话管理接口
 */
@Tag(name = "03-会话管理", description = "会话列表 / 消息记录 / 删除 / 重命名")
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ChatService chatService;

    @Operation(summary = "获取当前用户的会话列表")
    @GetMapping
    public R<PageResult<Conversation>> list(
            @RequestParam(required = false, defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String keyword) {
        return R.success(chatService.listConversations(pageNum, pageSize, keyword));
    }

    @Operation(summary = "获取会话消息记录")
    @GetMapping("/{id}/messages")
    public R<PageResult<ChatMessage>> messages(
            @PathVariable("id") Long id,
            @RequestParam(required = false, defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return R.success(chatService.getMessages(id, pageNum, pageSize));
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable("id") Long id) {
        chatService.deleteConversation(id);
        return R.success();
    }

    @Operation(summary = "清空当前用户的所有会话")
    @DeleteMapping("/all")
    public R<Void> clearAll() {
        chatService.clearAllConversations();
        return R.success();
    }

    @Operation(summary = "重命名会话")
    @PutMapping("/{id}")
    public R<Void> rename(@PathVariable("id") Long id,
                          @RequestBody Map<String, String> body) {
        chatService.renameConversation(id, body.getOrDefault("title", "新对话"));
        return R.success();
    }
}
