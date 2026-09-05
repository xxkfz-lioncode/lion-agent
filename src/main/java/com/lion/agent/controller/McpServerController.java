package com.lion.agent.controller;

import com.lion.agent.common.Result;
import com.lion.agent.model.dto.McpServerRequest;
import com.lion.agent.service.McpServerService;
import com.lion.agent.service.ToolRegistryService;
import com.lion.agent.model.vo.LocalToolVo;
import com.lion.agent.model.vo.McpServerVo;
import com.lion.agent.model.vo.McpToolVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP 服务管理接口
 */
@Tag(name = "MCP 服务管理", description = "管理外部 MCP Server：连接、发现工具、测试调用")
@RestController
@RequestMapping("/api/mcp-server")
@RequiredArgsConstructor
public class McpServerController {

    private final McpServerService mcpServerService;
    private final ToolRegistryService toolRegistryService;

    @Operation(summary = "列表")
    @GetMapping
    public Result<List<McpServerVo>> list() {
        return Result.success(mcpServerService.list());
    }

    @Operation(summary = "新增 MCP 服务")
    @PostMapping
    public Result<McpServerVo> create(@RequestBody @Valid McpServerRequest request) {
        return Result.success(mcpServerService.create(request));
    }

    @Operation(summary = "修改 MCP 服务")
    @PutMapping("/{id}")
    public Result<McpServerVo> update(@PathVariable Long id, @RequestBody @Valid McpServerRequest request) {
        return Result.success(mcpServerService.update(id, request));
    }

    @Operation(summary = "删除 MCP 服务")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        mcpServerService.delete(id);
        return Result.success();
    }

    @Operation(summary = "详情")
    @GetMapping("/{id}")
    public Result<McpServerVo> getById(@PathVariable Long id) {
        return Result.success(mcpServerService.getById(id));
    }

    @Operation(summary = "连接/重连")
    @PostMapping("/{id}/connect")
    public Result<McpServerVo> connect(@PathVariable Long id) {
        return Result.success(mcpServerService.connect(id));
    }

    @Operation(summary = "断开连接")
    @PostMapping("/{id}/disconnect")
    public Result<McpServerVo> disconnect(@PathVariable Long id) {
        return Result.success(mcpServerService.disconnect(id));
    }

    @Operation(summary = "发现/刷新工具列表")
    @PostMapping("/{id}/tools/discover")
    public Result<List<McpToolVo>> discoverTools(@PathVariable Long id) {
        return Result.success(mcpServerService.discoverTools(id));
    }

    @Operation(summary = "列出某服务已发现的工具")
    @GetMapping("/{id}/tools")
    public Result<List<McpToolVo>> listTools(@PathVariable Long id) {
        return Result.success(mcpServerService.listTools(id));
    }

    @Operation(summary = "测试调用某个工具")
    @PostMapping("/{id}/tools/{toolName}/test")
    public Result<String> testTool(@PathVariable Long id,
                                   @PathVariable String toolName,
                                   @RequestBody Map<String, Object> body) {
        String argsJson = body.getOrDefault("argsJson", "{}").toString();
        return Result.success(mcpServerService.testTool(id, toolName, argsJson));
    }

    @Operation(summary = "列出本地 @Tool / 手工注册的工具")
    @GetMapping("/local-tools")
    public Result<List<LocalToolVo>> listLocalTools() {
        return Result.success(toolRegistryService.listLocalTools());
    }
}
