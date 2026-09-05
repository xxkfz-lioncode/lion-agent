package com.lion.agent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Token 用量统计实体（ai_token_usage）
 *
 * <p>由 {@code TokenUsageAdvisor} 在每次模型调用完成后写入，记录用户 / 会话 /
 * 模型 / 输入输出 token 数与耗时，供前端用量页面查询。</p>
 */
@Data
@TableName("ai_token_usage")
public class TokenUsage {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户 ID */
    private Long userId;

    /** 会话 ID（知识库问答为 NULL） */
    private Long conversationId;

    /** 会话类型：chat-常规对话 kb-知识库问答 */
    private String chatType;

    /** 调用方式：sync-同步 stream-流式 */
    private String callType;

    /** 模型名称 */
    private String model;

    /** 输入 token 数 */
    private Integer promptTokens;

    /** 输出 token 数 */
    private Integer completionTokens;

    /** 总 token 数 */
    private Integer totalTokens;

    /** 调用耗时（毫秒） */
    private Long costMs;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
