package com.lion.agent.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 知识库对话请求
 *
 * <p>与普通对话 {@link ChatRequest} 分离：本请求专供 {@code /api/knowledge/chat} 使用，
 * 固定走知识库检索链路（高级 RAG：改写 / 多路召回 / RRF / Rerank / 门控），
 * 不再经过意图识别，也不会混入普通对话链路。</p>
 */
@Data
@Schema(description = "知识库对话请求")
public class KnowledgeChatRequest {

    @Schema(description = "会话 ID（为空则自动创建新会话）", example = "1")
    private Long conversationId;

    @Schema(description = "用户输入内容", example = "这份文档里提到的交付时间是什么", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "消息内容不能为空")
    private String message;

    @Schema(description = "指定检索的知识库 ID（可选；不指定则检索当前用户全部知识库）", example = "1")
    private Long knowledgeId;
}
