package com.lion.agent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提示词模板（持久化到 ai_prompt_template 表）
 *
 * <p>用于在页面管理 {@code classpath:prompts/*.st} 中的提示词模板。
 * 当数据库存在记录时，业务层以该记录内容作为“DB 版本”生效；否则以 classpath 文件为“FILE 版本”。</p>
 */
@Data
@TableName("ai_prompt_template")
public class PromptTemplateEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模板显示名（如 system-prompt） */
    private String name;

    /** 模板文件名（如 system-prompt.st），唯一键 */
    @TableField("file_name")
    private String fileName;

    /** 模板用途描述 */
    private String description;

    /** 模板正文 */
    private String content;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
