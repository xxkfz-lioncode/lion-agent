package com.lion.agent.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.lion.agent.common.PageResult;
import com.lion.agent.common.Result;
import com.lion.agent.dto.SkillRequest;
import com.lion.agent.entity.Skill;
import com.lion.agent.service.SkillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 自定义技能管理（页面维护 → SkillToolRegistry 动态构建 ToolCallback）
 */
@Tag(name = "技能管理")
@RestController
@RequestMapping("/api/skill")
@RequiredArgsConstructor
@SaCheckLogin
public class SkillController {

    private final SkillService skillService;

    @Operation(summary = "技能列表")
    @GetMapping
    public Result<PageResult<Skill>> list(
            @RequestParam(required = false, defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(required = false, defaultValue = "10") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String keyword) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(skillService.listByUser(userId, pageNum, pageSize, keyword));
    }

    @Operation(summary = "创建技能")
    @PostMapping
    public Result<Skill> create(@Valid @RequestBody SkillRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(skillService.create(userId, request));
    }

    @Operation(summary = "修改技能")
    @PutMapping("/{id}")
    public Result<Skill> update(@PathVariable Long id,
                                @Valid @RequestBody SkillRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(skillService.update(id, userId, request));
    }

    @Operation(summary = "删除技能")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        skillService.delete(id, userId);
        return Result.success();
    }

    @Operation(summary = "导出技能为 Markdown（前端预览/下载）")
    @GetMapping("/{id}/export")
    public Result<String> export(@PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(skillService.exportMarkdown(id, userId));
    }

    @Operation(summary = "试跑技能（填参数看替换后模板与模型输出）")
    @PostMapping("/{id}/test")
    public Result<Map<String, String>> test(@PathVariable Long id,
                                            @RequestBody(required = false) Map<String, Object> args) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(skillService.test(id, userId, args));
    }
}
