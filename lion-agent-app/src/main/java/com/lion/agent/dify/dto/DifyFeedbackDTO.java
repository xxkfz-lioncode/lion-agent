package com.lion.agent.dify.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Dify 消息点赞/点踩请求
 */
@Data
public class DifyFeedbackDTO {

    /** 应用编码（会话所属应用） */
    private String appCode;

    /** 消息 ID（assistant 消息） */
    @NotBlank(message = "messageId 不能为空")
    private String messageId;

    /** 评价：like / dislike / none（撤销） */
    @NotBlank(message = "rating 不能为空")
    private String rating;

    /** 评价原因（可选） */
    private String content;
}
