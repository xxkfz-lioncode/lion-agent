package com.lion.agent.splitter;

import com.lion.agent.exception.BusinessException;

/**
 * 文档切分方式枚举：统一管理策略标识，避免魔法字符串散落
 */
public enum SplitterType {

    /** Token 切分（默认） */
    TOKEN("token"),

    /** 递归切分 */
    RECURSIVE("recursive"),

    /** 段落切分 */
    PARAGRAPH("paragraph"),

    /** 句子切分 */
    SENTENCE("sentence"),

    /** 按行切分 */
    LINE("line"),

    /** 语义切分 */
    SEMANTIC("semantic");

    /** 对外标识：对应前端上传时选择的切分方式 */
    private final String value;

    SplitterType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * 按字符串标识解析枚举（忽略大小写），未识别抛 {@link BusinessException}
     */
    public static SplitterType of(String value) {
        for (SplitterType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new BusinessException("不支持的切分方式：" + value);
    }
}
