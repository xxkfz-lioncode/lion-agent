package com.lion.agent.dify.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Dify 知识库「文本新建文档」请求
 */
@Data
public class DifyDocTextDTO {

    /** 文档名称 */
    @NotBlank(message = "文档名称不能为空")
    private String name;

    /** 文档正文内容 */
    @NotBlank(message = "文档内容不能为空")
    private String text;
}
