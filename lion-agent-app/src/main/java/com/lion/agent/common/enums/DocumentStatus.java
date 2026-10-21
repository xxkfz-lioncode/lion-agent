package com.lion.agent.common.enums;

import lombok.Getter;

/**
 * 知识文档处理状态
 * <p>
 * 与 {@code ai_knowledge_document.status} 字段值一一对应：
 * 0-失败 1-成功 2-处理中
 */
@Getter
public enum DocumentStatus {

    /** 0-失败 */
    FAIL(0, "失败"),
    /** 1-成功 */
    SUCCESS(1, "成功"),
    /** 2-处理中 */
    PROCESSING(2, "处理中");

    private final int code;
    private final String desc;

    DocumentStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 按数据库存储值反查枚举，未匹配时返回 null
     */
    public static DocumentStatus of(int code) {
        for (DocumentStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }
}
