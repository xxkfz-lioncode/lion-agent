package com.lion.agent.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * 对话结果
 */
@Data
@Builder
@Schema(description = "对话结果")
public class ChatResult {

    @Schema(description = "会话 ID")
    private Long conversationId;

    @Schema(description = "用户消息 ID")
    private Long userMessageId;

    @Schema(description = "AI 回复消息 ID")
    private Long assistantMessageId;

    @Schema(description = "AI 回复内容")
    private String reply;

    @Schema(description = "知识库问答时返回的引用来源（一般对话为 null）")
    private java.util.List<ChunkSource> referencedChunks;
}
