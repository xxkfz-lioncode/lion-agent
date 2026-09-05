package com.lion.agent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话摘要实体
 * <p>
 * 由 ConversationSummaryAdvisor 在历史压缩时写入，每个会话保留多版本摘要，
 * 通过 conversation_id 关联 {@link Conversation}。
 */
@Data
@TableName("chat_conversation_summary")
public class ConversationSummary {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话 ID（关联 chat_conversation.id） */
    private Long conversationId;

    /** 压缩后的对话摘要内容 */
    private String summary;

    /** 本次压缩的新增消息条数 */
    private Integer messageCount;

    /** 本次摘要覆盖到的最大消息 ID（chat_message.id 游标，用于增量摘要） */
    private Long lastMessageId;

    /** 摘要版本（同一会话每次压缩递增） */
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
