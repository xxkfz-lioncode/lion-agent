package com.lion.agent.service;

import com.lion.agent.model.dto.McpServerRequest;
import com.lion.agent.model.vo.McpServerVo;
import com.lion.agent.model.vo.McpToolVo;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * MCP 外部服务管理
 */
public interface McpServerService {

    /**
     * 新增 MCP 服务
     */
    McpServerVo create(McpServerRequest request);

    /**
     * 修改 MCP 服务
     */
    McpServerVo update(Long id, McpServerRequest request);

    /**
     * 删除 MCP 服务
     */
    void delete(Long id);

    /**
     * 列表
     */
    List<McpServerVo> list();

    /**
     * 详情
     */
    McpServerVo getById(Long id);

    /**
     * 连接/重连：建立连接并刷新工具列表
     */
    McpServerVo connect(Long id);

    /**
     * 断开连接
     */
    McpServerVo disconnect(Long id);

    /**
     * 重新发现工具（不重建 client，只 listTools）
     */
    List<McpToolVo> discoverTools(Long id);

    /**
     * 列出某服务已缓存的工具
     */
    List<McpToolVo> listTools(Long serverId);

    /**
     * 测试调用某个工具
     */
    String testTool(Long serverId, String toolName, String argsJson);

    /**
     * 获取所有已启用且连接成功的 MCP 工具回调，供 ToolRegistryService 集成
     */
    List<ToolCallback> getAllToolCallbacks();
}
