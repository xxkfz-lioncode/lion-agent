package com.lion.agent.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP 外部服务实体
 */
@Data
@TableName("ai_mcp_server")
public class McpServerEntity {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 服务别名 */
    @TableField("name")
    private String name;

    /** MCP Server URL（Streamable HTTP 端点） */
    @TableField("url")
    private String url;

    /** 传输协议：sse（兼容保留）/ streamable-http，连接统一走 Streamable HTTP */
    @TableField("transport_type")
    private String transportType;

    /** 是否启用：1 启用 0 禁用 */
    @TableField("enabled")
    private Boolean enabled;

    /** 连接状态：connected / disconnected / error */
    @TableField("status")
    private String status;

    /** 最近一次连接错误信息 */
    @TableField("error_msg")
    private String errorMsg;

    /** 服务描述 */
    @TableField("description")
    private String description;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
