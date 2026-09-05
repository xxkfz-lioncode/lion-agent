package com.lion.agent.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.lion.agent.common.PageResult;
import com.lion.agent.common.Result;
import com.lion.agent.model.dto.KnowledgeBaseRequest;
import com.lion.agent.model.entity.KnowledgeBase;
import com.lion.agent.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "知识库管理")
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
@SaCheckLogin
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @Operation(summary = "知识库列表")
    @GetMapping
    public Result<PageResult<KnowledgeBase>> list(
            @RequestParam(required = false, defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(required = false, defaultValue = "10") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String keyword) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(knowledgeBaseService.listByUser(userId, pageNum, pageSize, keyword));
    }

    @Operation(summary = "创建知识库")
    @PostMapping
    public Result<KnowledgeBase> create(@Valid @RequestBody KnowledgeBaseRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(knowledgeBaseService.create(userId, request));
    }

    @Operation(summary = "修改知识库")
    @PutMapping("/{id}")
    public Result<KnowledgeBase> update(@PathVariable Long id,
                                        @Valid @RequestBody KnowledgeBaseRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(knowledgeBaseService.update(id, userId, request));
    }

    @Operation(summary = "删除知识库")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        knowledgeBaseService.delete(id, userId);
        return Result.success();
    }
}
