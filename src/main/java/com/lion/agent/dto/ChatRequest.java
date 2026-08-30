package com.lion.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 对话请求
 */
@Data
@Schema(description = "对话请求")
public class ChatRequest {

    @Schema(description = "会话 ID（为空则自动创建新会话）", example = "1")
    private Long conversationId;

    @Schema(description = "用户输入内容", example = "你好，介绍一下你自己", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "消息内容不能为空")
    private String message;

    @Schema(description = "指定检索的知识库 ID（可选；不指定则由意图识别决定是否检索，检索时覆盖用户全部知识库）", example = "1")
    private Long knowledgeId;
}
