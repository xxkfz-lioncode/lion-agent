package com.lion.agent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lion.agent.model.dto.McpServerRequest;
import com.lion.agent.model.entity.McpServerEntity;
import com.lion.agent.model.entity.McpServerToolEntity;
import com.lion.agent.event.McpServerChangedEvent;
import com.lion.agent.exception.BusinessException;
import cn.hutool.json.JSONUtil;
import com.lion.agent.mapper.McpServerMapper;
import com.lion.agent.mapper.McpServerToolMapper;
import com.lion.agent.service.McpServerService;
import com.lion.agent.model.vo.McpServerVo;
import com.lion.agent.model.vo.McpToolVo;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * MCP 外部服务管理实现
 *
 * <p>支持动态新增/删除/连接外部 MCP Server（Streamable HTTP 协议）。
 * 对每一个启用的服务，建立 {@link McpSyncClient}，初始化连接后 listTools，
 * 把工具定义缓存到 {@code ai_mcp_server_tool} 表，并把工具回调暴露给
 * {@link com.lion.agent.service.ToolRegistryService}，实现运行时动态扩展工具池。</p>
 *
 * <p>注意：SSE 传输已被 MCP 规范与 Spring AI 2.0 废弃，本类统一使用
 * {@code HttpClientStreamableHttpTransport}（Streamable HTTP）作为客户端传输。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpServerServiceImpl implements McpServerService {

    private final McpServerMapper mcpServerMapper;
    private final McpServerToolMapper mcpServerToolMapper;
    private final ApplicationEventPublisher eventPublisher;

    /** 已连接成功的 MCP 客户端：serverId -> McpSyncClient */
    private final Map<Long, McpSyncClient> clientCache = new ConcurrentHashMap<>();

    /** 已连接成功的工具回调：serverId -> List<ToolCallback> */
    private final Map<Long, List<ToolCallback>> callbackCache = new ConcurrentHashMap<>();

    /** 连接任务线程池 */
    private ExecutorService connectExecutor;

    /** 销毁标记 */
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        connectExecutor = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                r -> {
                    Thread t = new Thread(r, "mcp-connect-" + System.nanoTime());
                    t.setDaemon(true);
                    return t;
                });
        // 异步加载所有启用服务，不阻塞 Spring 启动
        List<McpServerEntity> enabledServers = mcpServerMapper.selectList(
                Wrappers.<McpServerEntity>lambdaQuery()
                        .eq(McpServerEntity::getEnabled, true));
        for (McpServerEntity server : enabledServers) {
            connectExecutor.submit(() -> connectInternal(server.getId(), server, true));
        }
        log.info("MCP 服务启动初始化：已提交 {} 个连接任务", enabledServers.size());
    }

    @PreDestroy
    public void destroy() {
        destroyed.set(true);
        if (connectExecutor != null) {
            connectExecutor.shutdownNow();
        }
        clientCache.values().forEach(client -> {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭 MCP client 异常: {}", e.getMessage());
            }
        });
        clientCache.clear();
        callbackCache.clear();
    }

    @Override
    @Transactional
    public McpServerVo create(McpServerRequest request) {
        McpServerEntity entity = toEntity(request);
        entity.setStatus("disconnected");
        mcpServerMapper.insert(entity);
        if (Boolean.TRUE.equals(entity.getEnabled())) {
            connectExecutor.submit(() -> connectInternal(entity.getId(), entity, true));
        }
        return toVo(entity);
    }

    @Override
    @Transactional
    public McpServerVo update(Long id, McpServerRequest request) {
        McpServerEntity entity = mcpServerMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("MCP 服务不存在");
        }
        entity.setName(request.getName());
        entity.setUrl(request.getUrl());
        entity.setDescription(request.getDescription());
        entity.setEnabled(request.getEnabled());
        entity.setTransportType(request.getTransportType());
        mcpServerMapper.updateById(entity);

        // URL/启用状态变化时，关闭旧连接并重建
        closeAndRemove(id);
        if (Boolean.TRUE.equals(entity.getEnabled())) {
            McpServerEntity fresh = mcpServerMapper.selectById(id);
            connectExecutor.submit(() -> connectInternal(id, fresh, true));
        }
        return toVo(entity);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        if (mcpServerMapper.selectById(id) == null) {
            throw new BusinessException("MCP 服务不存在");
        }
        closeAndRemove(id);
        mcpServerToolMapper.delete(
                Wrappers.<McpServerToolEntity>lambdaQuery()
                        .eq(McpServerToolEntity::getServerId, id));
        mcpServerMapper.deleteById(id);
        publishChanged();
    }

    @Override
    public List<McpServerVo> list() {
        return mcpServerMapper.selectList(
                        Wrappers.<McpServerEntity>lambdaQuery()
                                .orderByDesc(McpServerEntity::getCreatedAt))
                .stream()
                .map(this::toVo)
                .collect(Collectors.toList());
    }

    @Override
    public McpServerVo getById(Long id) {
        McpServerEntity entity = mcpServerMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("MCP 服务不存在");
        }
        McpServerVo vo = toVo(entity);
        vo.setTools(listTools(id));
        return vo;
    }

    @Override
    public McpServerVo connect(Long id) {
        McpServerEntity entity = mcpServerMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("MCP 服务不存在");
        }
        entity.setEnabled(true);
        mcpServerMapper.updateById(entity);
        closeAndRemove(id);
        connectInternal(id, entity, true);
        return getById(id);
    }

    @Override
    public McpServerVo disconnect(Long id) {
        McpServerEntity entity = mcpServerMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("MCP 服务不存在");
        }
        entity.setEnabled(false);
        entity.setStatus("disconnected");
        entity.setErrorMsg("");
        mcpServerMapper.updateById(entity);
        closeAndRemove(id);
        publishChanged();
        return toVo(entity);
    }

    @Override
    public List<McpToolVo> discoverTools(Long id) {
        McpServerEntity entity = mcpServerMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("MCP 服务不存在");
        }
        McpSyncClient client = getConnectedClient(id);
        if (client == null) {
            throw new BusinessException("MCP 服务未连接");
        }
        return doDiscover(id, entity.getName(), client, true);
    }

    @Override
    public List<McpToolVo> listTools(Long serverId) {
        return mcpServerToolMapper.selectList(
                        Wrappers.<McpServerToolEntity>lambdaQuery()
                                .eq(McpServerToolEntity::getServerId, serverId)
                                .orderByAsc(McpServerToolEntity::getName))
                .stream()
                .map(this::toToolVo)
                .collect(Collectors.toList());
    }

    @Override
    public String testTool(Long serverId, String toolName, String argsJson) {
        List<ToolCallback> callbacks = callbackCache.get(serverId);
        if (callbacks == null) {
            throw new BusinessException("MCP 服务未连接，无法测试调用");
        }
        ToolCallback target = callbacks.stream()
                .filter(cb -> toolName.equals(cb.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("工具不存在或未连接: " + toolName));
        try {
            return target.call(argsJson);
        } catch (Exception e) {
            throw new BusinessException("工具调用失败: " + e.getMessage());
        }
    }

    @Override
    public List<ToolCallback> getAllToolCallbacks() {
        return callbackCache.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    // ==================== 内部方法 ====================

    /**
     * 同步建立连接、初始化、发现并保存工具。
     * 成功后发布 {@link McpServerChangedEvent} 触发 ToolRegistryService 重建工具索引。
     */
    private void connectInternal(Long id, McpServerEntity entity, boolean publishEvent) {
        if (destroyed.get()) {
            return;
        }
        try {
            log.info("[MCP] 连接服务 {}: {}", entity.getName(), entity.getUrl());
            McpSyncClient client = buildClient(entity.getUrl());
            client.initialize();

            List<ToolCallback> callbacks = McpToolUtils.getToolCallbacksFromSyncClients(List.of(client));
            log.info("[MCP] 服务 {} 发现 {} 个工具", entity.getName(), callbacks.size());

            doDiscover(id, entity.getName(), callbacks, false);
            clientCache.put(id, client);
            callbackCache.put(id, callbacks);

            entity.setStatus("connected");
            entity.setErrorMsg("");
            mcpServerMapper.updateById(entity);
            if (publishEvent) {
                publishChanged();
            }
        } catch (Exception e) {
            log.warn("[MCP] 服务 {} 连接失败: {}", entity.getName(), e.getMessage());
            entity.setStatus("error");
            entity.setErrorMsg(e.getMessage());
            entity.setEnabled(false);
            mcpServerMapper.updateById(entity);
            throw e;

        }
    }

    /**
     * 构建 MCP 客户端。
     * <p>SSE 传输协议已在 MCP 2025-03-26 规范中废弃，对应的 Spring AI {@code WebFluxSseClientTransport}
     * 也在 2.0.0 标记为 {@code @Deprecated(forRemoval = true)}。这里改用基于 JDK {@code HttpClient} 的
     * {@link HttpClientStreamableHttpTransport}（Streamable HTTP 单端点协议）。</p>
     * <p>URL 语义兼容处理：
     * <ul>
     *   <li>库表里填写服务根地址（如 {@code http://host:port}）→ 默认请求 {@code /mcp} 端点；</li>
     *   <li>填写了具体 MCP 端点路径（如 {@code http://host:port/custom/mcp}）→ 原样使用该路径；</li>
     *   <li>历史 SSE 地址（如 {@code http://host:port/sse}）→ 自动映射到同主机的 {@code /mcp}。</li>
     * </ul>
     */
    private McpSyncClient buildClient(String url) {
        URI uri = URI.create(url);
        String path = uri.getPath();
        String baseUrl = url;
        String endpoint = "/mcp";
        if (path != null && !path.isBlank() && !"/".equals(path) && !"/sse".equals(path)) {
            int idx = url.indexOf(path);
            baseUrl = url.substring(0, idx);
            endpoint = path;
        }
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint(endpoint)
                .build();
        return McpClient.sync(transport).build();
    }

    private List<McpToolVo> doDiscover(Long serverId, String serverName, McpSyncClient client, boolean publishEvent) {
        List<ToolCallback> callbacks = McpToolUtils.getToolCallbacksFromSyncClients(List.of(client));
        return doDiscover(serverId, serverName, callbacks, publishEvent);
    }

    private List<McpToolVo> doDiscover(Long serverId, String serverName, List<ToolCallback> callbacks, boolean publishEvent) {
        // 清空旧工具，写入新发现的工具
        mcpServerToolMapper.delete(
                Wrappers.<McpServerToolEntity>lambdaQuery()
                        .eq(McpServerToolEntity::getServerId, serverId));
        List<McpToolVo> result = new ArrayList<>();
        for (ToolCallback cb : callbacks) {
            ToolDefinition def = cb.getToolDefinition();
            McpServerToolEntity toolEntity = new McpServerToolEntity();
            toolEntity.setServerId(serverId);
            toolEntity.setName(def.name());
            toolEntity.setDescription(def.description());
            toolEntity.setInputSchema(JSONUtil.toJsonStr(def.inputSchema()));
            mcpServerToolMapper.insert(toolEntity);
            result.add(toToolVo(toolEntity, serverName));
        }
        if (publishEvent) {
            publishChanged();
        }
        return result;
    }

    private void closeAndRemove(Long id) {
        McpSyncClient old = clientCache.remove(id);
        callbackCache.remove(id);
        if (old != null) {
            try {
                old.close();
            } catch (Exception e) {
                log.warn("关闭旧 MCP client 异常: {}", e.getMessage());
            }
        }
    }

    private McpSyncClient getConnectedClient(Long id) {
        return clientCache.get(id);
    }

    private void publishChanged() {
        if (destroyed.get()) {
            return;
        }
        try {
            eventPublisher.publishEvent(new McpServerChangedEvent(this));
        } catch (Exception e) {
            log.warn("发布 MCP 服务变更事件失败: {}", e.getMessage());
        }
    }

    // ==================== 转换工具 ====================

    private McpServerEntity toEntity(McpServerRequest request) {
        McpServerEntity entity = new McpServerEntity();
        entity.setName(request.getName());
        entity.setUrl(request.getUrl());
        entity.setTransportType(request.getTransportType() != null ? request.getTransportType() : "sse");
        entity.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
        entity.setDescription(request.getDescription());
        return entity;
    }

    private McpServerVo toVo(McpServerEntity entity) {
        McpServerVo vo = new McpServerVo();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setUrl(entity.getUrl());
        vo.setTransportType(entity.getTransportType());
        vo.setEnabled(entity.getEnabled());
        vo.setStatus(entity.getStatus());
        vo.setErrorMsg(entity.getErrorMsg());
        vo.setDescription(entity.getDescription());
        vo.setToolCount(countTools(entity.getId()));
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    private McpToolVo toToolVo(McpServerToolEntity entity) {
        return toToolVo(entity, null);
    }

    private McpToolVo toToolVo(McpServerToolEntity entity, String serverName) {
        McpToolVo vo = new McpToolVo();
        vo.setId(entity.getId());
        vo.setServerId(entity.getServerId());
        vo.setServerName(serverName);
        vo.setName(entity.getName());
        vo.setDescription(entity.getDescription());
        vo.setInputSchema(entity.getInputSchema());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    private int countTools(Long serverId) {
        if (serverId == null) {
            return 0;
        }
        Long count = mcpServerToolMapper.selectCount(
                Wrappers.<McpServerToolEntity>lambdaQuery()
                        .eq(McpServerToolEntity::getServerId, serverId));
        return count != null ? count.intValue() : 0;
    }
}
