package com.lion.agent.pojo.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 模型配置新增/更新请求
 */
@Data
public class ModelConfigRequest {

    /** 显示名 */
    @NotBlank(message = "显示名不能为空")
    @Size(max = 64, message = "显示名最长 64 字符")
    private String displayName;

    /** 模型名（DashScope OpenAI 兼容接口的 model 参数） */
    @NotBlank(message = "模型名不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9._\\-]+$", message = "模型名仅支持字母、数字、点、下划线与中划线")
    @Size(max = 128, message = "模型名最长 128 字符")
    private String modelName;

    /** 模型类型：chat-文本对话 / multimodal-多模态 */
    @NotBlank(message = "模型类型不能为空")
    @Pattern(regexp = "chat|multimodal", message = "模型类型仅支持 chat / multimodal")
    private String modelType;

    /** 采样温度（可选，0~2） */
    @DecimalMin(value = "0", message = "温度不能小于 0")
    @DecimalMax(value = "2", message = "温度不能大于 2")
    private Double temperature;

    /** 是否启用（可选，默认 true） */
    private Boolean enabled;

    /** 是否设为该类型默认模型（可选，默认 false） */
    private Boolean isDefault;

    /** 备注说明（可选） */
    @Size(max = 512, message = "备注最长 512 字符")
    private String remark;
}
