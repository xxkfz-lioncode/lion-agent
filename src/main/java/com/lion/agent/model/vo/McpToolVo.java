package com.lion.agent.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP 服务发现的工具视图对象
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpToolVo {

    private Long id;

    private Long serverId;

    private String serverName;

    private String name;

    private String description;

    /** 输入参数 JSON Schema（JSON 字符串） */
    private String inputSchema;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
