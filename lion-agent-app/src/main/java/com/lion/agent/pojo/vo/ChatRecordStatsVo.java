package com.lion.agent.pojo.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 会话记录页顶部统计
 */
@Data
public class ChatRecordStatsVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话总数 */
    private Long conversationCount;

    /** 消息总条数 */
    private Long messageCount;

    /** 用户提问条数（role = user） */
    private Long userMessageCount;

    /** AI 回复条数（role = assistant） */
    private Long assistantMessageCount;
}
