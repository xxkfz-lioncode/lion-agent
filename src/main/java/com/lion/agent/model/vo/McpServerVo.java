package com.lion.agent.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MCP 服务视图对象
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpServerVo {

    private Long id;

    private String name;

    private String url;

    private String transportType;

    private Boolean enabled;

    private String status;

    private String errorMsg;

    private String description;

    /** 该服务下已发现的工具数量 */
    private Integer toolCount;

    /** 已发现的工具列表（可选，仅在详情/发现接口返回） */
    private List<McpToolVo> tools;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
