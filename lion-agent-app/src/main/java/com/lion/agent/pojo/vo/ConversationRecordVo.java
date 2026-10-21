package com.lion.agent.pojo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话记录列表项（「会话记录」页面左侧列表使用）
 *
 * <p>在 chat_conversation 基础上补充消息统计：消息总数与最后一条消息时间，
 * 由 {@code chat_message} 按 conversation_id 分组聚合得出。</p>
 */
@Data
public class ConversationRecordVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话 ID */
    private Long id;

    /** 会话标题 */
    private String title;

    /** 会话创建时间 */
    private LocalDateTime createdAt;

    /** 会话更新时间 */
    private LocalDateTime updatedAt;

    /** 消息总条数（user + assistant） */
    private Long messageCount;

    /** 用户提问条数（role = user） */
    private Long userMessageCount;

    /** AI 回复条数（role = assistant） */
    private Long assistantMessageCount;

    /** 最后一条消息时间 */
    private LocalDateTime lastMessageAt;
}
