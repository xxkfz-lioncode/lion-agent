package com.lion.agent.pojo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 敏感词新增/修改请求
 */
@Data
public class SensitiveWordRequest {

    @NotBlank(message = "敏感词不能为空")
    private String word;

    /** 分类：custom / politics / porn / violence / abuse，默认 custom */
    private String category;

    /** 是否启用，默认启用 */
    private Boolean enabled;

    /** 备注说明 */
    private String remark;
}
