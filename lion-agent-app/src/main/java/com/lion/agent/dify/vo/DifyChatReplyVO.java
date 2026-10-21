package com.lion.agent.dify.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Dify 对话回复（阻塞式）
 */
@Data
@Builder
public class DifyChatReplyVO {

    /** 会话 ID（首轮返回后前端需保存，后续追问透传） */
    private String conversationId;

    /** 本条 AI 消息 ID（反馈点赞/点踩、获取建议问题时使用） */
    private String messageId;

    /** AI 回复内容 */
    private String answer;

    /** 任务 ID（流式模式下可用于停止生成） */
    private String taskId;
}
