package com.lion.agent.pojo.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型配置（持久化到 ai_model_config 表）
 *
 * <p>用于在页面管理可切换的大模型列表。所有模型统一复用服务端的单个端点与密钥
 * （{@code spring.ai.openai.base-url} / {@code spring.ai.openai.api-key}），
 * 切换模型时只按次覆盖 model 名，不重建客户端、不重启服务。</p>
 *
 * <p>每个 model_type（chat-文本对话 / multimodal-多模态）可设一条默认记录；
 * 对话链路调用时取该类型的默认模型；表中无记录时回退到 yml 配置的模型。</p>
 */
@Data
@TableName("ai_model_config")
public class ModelConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 显示名（如 千问旗舰） */
    private String displayName;

    /** 模型名（DashScope OpenAI 兼容接口的 model 参数，如 qwen-plus），唯一键 */
    private String modelName;

    /** 模型类型：chat-文本对话 / multimodal-多模态 */
    private String modelType;

    /**
     * OpenAI 兼容端点。表字段保留，当前不使用：所有模型统一复用 yml 配置的单个端点，
     * 不再支持每条记录自定义厂商地址。
     */
    private String baseUrl;

    /**
     * API Key。表字段保留，当前不使用：统一使用 yml 配置的密钥，接口层不再接收与回传密钥。
     */
    private String apiKey;

    /** 采样温度（空=沿用全局默认 0.1） */
    private Double temperature;

    /** 是否启用（停用的模型不参与对话切换） */
    private Boolean enabled;

    /** 是否为该类型的默认模型（每类型至多一条） */
    private Boolean isDefault;

    /** 备注说明 */
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
