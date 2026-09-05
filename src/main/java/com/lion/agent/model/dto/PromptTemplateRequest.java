package com.lion.agent.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 提示词模板更新请求
 */
@Data
public class PromptTemplateRequest {

    /** 模板正文（保存到数据库后，DB 版本优先于 classpath 文件版本生效） */
    @NotBlank(message = "模板内容不能为空")
    private String content;
}
