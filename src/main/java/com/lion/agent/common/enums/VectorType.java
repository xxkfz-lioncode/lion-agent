package com.lion.agent.common.enums;

import lombok.Getter;

/**
 * Milvus 向量索引的 metadata type 标记
 *
 * <p>知识库文档/工具索引/技能索引/QA 缓存/长期记忆共用同一个 collection，
 * 靠 {@code metadata.type} 标量过滤隔离，检索时必须带对应 type 防止跨类型误召回。</p>
 */
@Getter
public enum VectorType {

    /** 知识库文档分片 */
    KB("kb", "知识库文档分片"),
    /** 工具索引 */
    TOOL_INDEX("tool_index", "工具索引"),
    /** 技能索引 */
    SKILL_INDEX("skill_index", "技能索引"),
    /** 语义缓存 */
    QA_CACHE("qa_cache", "语义缓存"),
    /** 长期记忆 */
    LONG_TERM_MEMORY("long_term_memory", "长期记忆");

    private final String value;
    private final String desc;

    VectorType(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /**
     * 按存储值反查枚举，未匹配时返回 null
     */
    public static VectorType of(String value) {
        for (VectorType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
