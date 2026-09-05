package com.lion.agent.model.vo;

import com.lion.agent.model.entity.TokenUsage;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Token 用量展示对象（附加会话标题）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TokenUsageVO extends TokenUsage {

    /** 会话标题（无会话时为空） */
    private String conversationTitle;

    /** 格式化后的创建时间（yyyy-MM-dd HH:mm:ss） */
    private String createdAtStr;
}
