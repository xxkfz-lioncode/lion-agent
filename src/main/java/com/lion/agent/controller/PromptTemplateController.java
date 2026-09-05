package com.lion.agent.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.lion.agent.common.Result;
import com.lion.agent.model.dto.PromptTemplateRequest;
import com.lion.agent.service.PromptTemplateService;
import com.lion.agent.model.vo.PromptTemplateVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 提示词模板管理
 *
 * <p>提供提示词模板的查看、编辑保存与从文件同步能力。
 * 模板数据以 {@code ai_prompt_template} 表为准（启动时自动从 {@code classpath:prompts/*.st} 导入），
 * 保存修改后下一次请求即实时生效，无需重启服务。</p>
 */
@Tag(name = "提示词模板管理")
@RestController
@RequestMapping("/api/prompt-template")
@RequiredArgsConstructor
@SaCheckLogin
public class PromptTemplateController {

    private final PromptTemplateService promptTemplateService;

    @Operation(summary = "提示词模板列表（数据库全部模板）")
    @GetMapping
    public Result<List<PromptTemplateVo>> list() {
        return Result.success(promptTemplateService.list());
    }

    @Operation(summary = "查看单个提示词模板（name 不含 .st 后缀，如 system-prompt）")
    @GetMapping("/{name}")
    public Result<PromptTemplateVo> get(@PathVariable String name) {
        return Result.success(promptTemplateService.getByName(name));
    }

    @Operation(summary = "将 classpath 文件内容全量同步到数据库（新增 + 覆盖）")
    @PostMapping("/refresh")
    public Result<Void> refresh() {
        promptTemplateService.refreshFromFiles();
        return Result.success();
    }

    @Operation(summary = "更新数据库中的提示词模板（保存后该模板以 DB 版本生效）")
    @PutMapping("/{name}")
    public Result<PromptTemplateVo> update(@PathVariable String name,
                                           @Valid @RequestBody PromptTemplateRequest request) {
        return Result.success(promptTemplateService.update(name, request));
    }
}
