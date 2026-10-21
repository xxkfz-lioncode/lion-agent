package com.lion.agent.event;

import org.springframework.context.ApplicationEvent;

/**
 * MCP 服务发生变更事件（新增/更新/删除/连接/断开）。
 * ToolRegistryService 监听此事件以重新构建工具索引。
 */
public class McpServerChangedEvent extends ApplicationEvent {

    public McpServerChangedEvent(Object source) {
        super(source);
    }
}
