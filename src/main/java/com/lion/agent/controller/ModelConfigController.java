package com.lion.agent.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.lion.agent.common.result.R;
import com.lion.agent.pojo.dto.ModelConfigRequest;
import com.lion.agent.pojo.vo.ModelConfigVo;
import com.lion.agent.pojo.vo.ModelTestVo;
import com.lion.agent.service.ModelConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 模型配置管理
 *
 * <p>维护可切换的大模型列表（{@code ai_model_config} 表）。
 * 将某模型设为默认后，下一次对话请求即通过 ChatOptions 覆盖生效，热切换、无需重启。</p>
 */
@Tag(name = "模型配置管理")
@RestController
@RequestMapping("/api/model")
@RequiredArgsConstructor
@SaCheckLogin
public class ModelConfigController {

    private final ModelConfigService modelConfigService;

    @Operation(summary = "模型配置列表")
    @GetMapping
    public R<List<ModelConfigVo>> list() {
        return R.success(modelConfigService.list());
    }

    @Operation(summary = "新增模型配置")
    @PostMapping
    public R<ModelConfigVo> create(@Valid @RequestBody ModelConfigRequest request) {
        return R.success(modelConfigService.create(request));
    }

    @Operation(summary = "更新模型配置")
    @PutMapping("/{id}")
    public R<ModelConfigVo> update(@PathVariable Long id,
                                   @Valid @RequestBody ModelConfigRequest request) {
        return R.success(modelConfigService.update(id, request));
    }

    @Operation(summary = "删除模型配置（默认模型不可删除）")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        modelConfigService.delete(id);
        return R.success();
    }

    @Operation(summary = "设为该类型默认模型（对话链路下次请求即生效）")
    @PostMapping("/{id}/default")
    public R<Void> setDefault(@PathVariable Long id) {
        modelConfigService.setDefault(id);
        return R.success();
    }

    @Operation(summary = "连通性测试（用该模型发送一条极短消息）")
    @PostMapping("/{id}/test")
    public R<ModelTestVo> test(@PathVariable Long id) {
        return R.success(modelConfigService.test(id));
    }
}
