package com.lion.agent.common.enums;

import lombok.Getter;

/**
 * 长期记忆类型（MySQL {@code ai_memory.memory_type} 字段取值）
 */
@Getter
public enum MemoryType {

    /** 用户画像 */
    PROFILE("profile", "用户画像");

    private final String value;
    private final String desc;

    MemoryType(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /**
     * 按存储值反查枚举，未匹配时返回 null
     */
    public static MemoryType of(String value) {
        for (MemoryType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
