package com.lion.agent.dify.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Dify 知识库创建请求
 */
@Data
public class DifyDatasetCreateDTO {

    /** 知识库名称 */
    @NotBlank(message = "知识库名称不能为空")
    private String name;

    /** 知识库描述（可选） */
    private String description;
}
