package com.lion.agent.common.enums;

import lombok.Getter;

/**
 * 会话类型
 *
 * <p>通过 {@code AdvisorConstants.CHAT_TYPE_KEY} 注入 Advisor 上下文，
 * 供 {@code TokenUsageAdvisor} 落库区分统计口径（chat-一般对话 / kb-知识库问答）。</p>
 */
@Getter
public enum ChatType {

    /** 一般对话 */
    CHAT("chat", "一般对话"),
    /** 知识库问答 */
    KB("kb", "知识库问答");

    private final String value;
    private final String desc;

    ChatType(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /**
     * 按存储值反查枚举，未匹配时返回 null
     */
    public static ChatType of(String value) {
        for (ChatType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
