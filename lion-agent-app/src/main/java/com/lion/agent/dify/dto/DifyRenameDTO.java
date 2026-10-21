package com.lion.agent.dify.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Dify 会话重命名请求
 */
@Data
public class DifyRenameDTO {

    /** 应用编码（会话所属应用） */
    private String appCode;

    /** 会话 ID */
    @NotBlank(message = "conversationId 不能为空")
    private String conversationId;

    /** 新会话名称 */
    @NotBlank(message = "会话名称不能为空")
    private String name;
}
