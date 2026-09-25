package com.lion.agent.dify.controller;

import com.lion.agent.common.result.R;
import com.lion.agent.dify.service.DifyAppService;
import com.lion.agent.dify.vo.DifyAppVO;
import com.lion.agent.dify.vo.DifyFlowGraphVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Dify 应用接口：前端「应用市场」据此渲染卡片，点击后再进入对话页（/dify/chat/{code}）
 * 或工作流页（/dify/workflow/{code}）
 */
@Tag(name = "20-Dify 应用", description = "Dify 应用列表与详情（不含密钥）")
@RestController
@RequestMapping("/api/dify/apps")
@RequiredArgsConstructor
public class DifyAppController {

    private final DifyAppService difyAppService;

    @Operation(summary = "应用列表", description = "按配置顺序返回；chat 进入对话页，workflow 进入工作流页")
    @GetMapping
    public R<List<DifyAppVO>> list() {
        return R.success(difyAppService.listApps());
    }

    @Operation(summary = "应用详情")
    @GetMapping("/{appCode}")
    public R<DifyAppVO> detail(@PathVariable String appCode) {
        return R.success(difyAppService.getApp(appCode));
    }

    @Operation(summary = "应用编排流程图",
            description = "读取 Dify 画布（workflow.graph）并归一化；基础聊天助手无画布，返回空节点列表")
    @GetMapping("/{appCode}/graph")
    public R<DifyFlowGraphVO> graph(@PathVariable String appCode) {
        return R.success(difyAppService.graph(appCode));
    }
}
