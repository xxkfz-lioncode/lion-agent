package com.lion.agent.pojo.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 敏感词实体（ai_sensitive_word）
 *
 * <p>由「敏感词管理」页面维护，供 {@link com.lion.agent.advisor.SensitiveWordAdvisor}
 * 在对话入口做输入侧拦截；enabled=false 的词只保留记录，不参与匹配。</p>
 */
@Data
@TableName("ai_sensitive_word")
public class SensitiveWordEntity {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 敏感词 */
    @TableField("word")
    private String word;

    /** 分类：custom / politics / porn / violence / abuse */
    @TableField("category")
    private String category;

    /** 是否启用：true 参与拦截 false 仅保留记录 */
    @TableField("enabled")
    private Boolean enabled;

    /** 备注说明 */
    @TableField("remark")
    private String remark;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
