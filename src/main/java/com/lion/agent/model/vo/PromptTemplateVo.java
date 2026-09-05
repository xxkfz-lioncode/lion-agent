package com.lion.agent.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提示词模板视图对象（数据以数据库为准，缺失时回退到 classpath 文件）
 */
@Data
public class PromptTemplateVo {

    /** 模板显示名（如 system-prompt） */
    private String name;

    /** 模板文件名（如 system-prompt.st） */
    private String fileName;

    /** 模板用途描述 */
    private String description;

    /** 模板内容 */
    private String content;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
