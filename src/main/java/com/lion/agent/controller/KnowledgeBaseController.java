package com.lion.agent.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.lion.agent.common.result.PageResult;
import com.lion.agent.common.result.R;
import com.lion.agent.pojo.dto.KnowledgeBaseRequest;
import com.lion.agent.pojo.entity.KnowledgeBase;
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
    public R<PageResult<KnowledgeBase>> list(
            @RequestParam(required = false, defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(required = false, defaultValue = "10") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String keyword) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.success(knowledgeBaseService.listByUser(userId, pageNum, pageSize, keyword));
    }

    @Operation(summary = "创建知识库")
    @PostMapping
    public R<KnowledgeBase> create(@Valid @RequestBody KnowledgeBaseRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.success(knowledgeBaseService.create(userId, request));
    }

    @Operation(summary = "修改知识库")
    @PutMapping("/{id}")
    public R<KnowledgeBase> update(@PathVariable Long id,
                                   @Valid @RequestBody KnowledgeBaseRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.success(knowledgeBaseService.update(id, userId, request));
    }

    @Operation(summary = "删除知识库")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        knowledgeBaseService.delete(id, userId);
        return R.success();
    }
}
