package com.lion.agent.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 普通对话请求
 *
 * <p>仅用于 {@code /api/chat}（一般对话：闲聊 / 通用咨询 / 工具调用），不含知识库相关参数。
 * 需要基于知识库作答请使用 {@link KnowledgeChatRequest}（/api/knowledge/chat）。</p>
 */
@Data
@Schema(description = "对话请求")
public class ChatRequest {

    @Schema(description = "会话 ID（为空则自动创建新会话）", example = "1")
    private Long conversationId;

    @Schema(description = "用户输入内容", example = "你好，介绍一下你自己", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "消息内容不能为空")
    private String message;
}
