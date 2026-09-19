package com.lion.agent.controller;

import com.lion.agent.common.result.R;
import com.lion.agent.pojo.dto.McpServerRequest;
import com.lion.agent.service.McpServerService;
import com.lion.agent.service.ToolRegistryService;
import com.lion.agent.pojo.vo.LocalToolVo;
import com.lion.agent.pojo.vo.McpServerVo;
import com.lion.agent.pojo.vo.McpToolVo;
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
    public R<List<McpServerVo>> list() {
        return R.success(mcpServerService.list());
    }

    @Operation(summary = "新增 MCP 服务")
    @PostMapping
    public R<McpServerVo> create(@RequestBody @Valid McpServerRequest request) {
        return R.success(mcpServerService.create(request));
    }

    @Operation(summary = "修改 MCP 服务")
    @PutMapping("/{id}")
    public R<McpServerVo> update(@PathVariable Long id, @RequestBody @Valid McpServerRequest request) {
        return R.success(mcpServerService.update(id, request));
    }

    @Operation(summary = "删除 MCP 服务")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        mcpServerService.delete(id);
        return R.success();
    }

    @Operation(summary = "详情")
    @GetMapping("/{id}")
    public R<McpServerVo> getById(@PathVariable Long id) {
        return R.success(mcpServerService.getById(id));
    }

    @Operation(summary = "连接/重连")
    @PostMapping("/{id}/connect")
    public R<McpServerVo> connect(@PathVariable Long id) {
        return R.success(mcpServerService.connect(id));
    }

    @Operation(summary = "断开连接")
    @PostMapping("/{id}/disconnect")
    public R<McpServerVo> disconnect(@PathVariable Long id) {
        return R.success(mcpServerService.disconnect(id));
    }

    @Operation(summary = "发现/刷新工具列表")
    @PostMapping("/{id}/tools/discover")
    public R<List<McpToolVo>> discoverTools(@PathVariable Long id) {
        return R.success(mcpServerService.discoverTools(id));
    }

    @Operation(summary = "列出某服务已发现的工具")
    @GetMapping("/{id}/tools")
    public R<List<McpToolVo>> listTools(@PathVariable Long id) {
        return R.success(mcpServerService.listTools(id));
    }

    @Operation(summary = "测试调用某个工具")
    @PostMapping("/{id}/tools/{toolName}/test")
    public R<String> testTool(@PathVariable Long id,
                              @PathVariable String toolName,
                              @RequestBody Map<String, Object> body) {
        String argsJson = body.getOrDefault("argsJson", "{}").toString();
        return R.success(mcpServerService.testTool(id, toolName, argsJson));
    }

    @Operation(summary = "列出本地 @Tool / 手工注册的工具")
    @GetMapping("/local-tools")
    public R<List<LocalToolVo>> listLocalTools() {
        return R.success(toolRegistryService.listLocalTools());
    }
}
