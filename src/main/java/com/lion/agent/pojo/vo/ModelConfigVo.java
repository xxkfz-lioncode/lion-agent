package com.lion.agent.pojo.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型配置视图对象
 */
@Data
public class ModelConfigVo {

    private Long id;

    /** 显示名 */
    private String displayName;

    /** 模型名（OpenAI 兼容接口的 model 参数） */
    private String modelName;

    /** 模型类型：chat-文本对话 / multimodal-多模态 */
    private String modelType;

    /** 采样温度（空=沿用全局默认） */
    private Double temperature;

    /** 是否启用 */
    private Boolean enabled;

    /** 是否为该类型默认模型 */
    private Boolean isDefault;

    /** 备注说明 */
    private String remark;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
