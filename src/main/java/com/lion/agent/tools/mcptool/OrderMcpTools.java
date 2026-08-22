package com.lion.agent.tools.mcptool;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * 测试的话，可以在powerShell里面使用命令：npx -y @modelcontextprotocol/inspector
 */

@Component
public class OrderMcpTools {

    @McpTool(description = "按订单号查询订单详情与当前异常状态")
    public String getOrder(@McpToolParam(description = "订单号", required = true) String orderId) {

        // 这里调用你真实的业务逻辑或数据库查询
        // 为演示方便，这里直接返回模拟数据
        return String.format("{\"orderId\":\"%s\", \"status\":\"已发货\", \"estimatedTime\":\"2026-08-18\"}", orderId);
    }
}