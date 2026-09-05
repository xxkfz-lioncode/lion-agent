package com.lion.agent.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * MCP 服务新增/修改请求
 */
@Data
public class McpServerRequest {

    @NotBlank(message = "服务别名不能为空")
    private String name;

    @NotBlank(message = "MCP Server URL 不能为空")
    private String url;

    /** 传输协议，默认 sse */
    private String transportType;

    /** 服务描述 */
    private String description;

    /** 是否启用：true/false */
    private Boolean enabled;
}
