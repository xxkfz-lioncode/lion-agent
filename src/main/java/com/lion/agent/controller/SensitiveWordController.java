package com.lion.agent.controller;

import com.lion.agent.common.result.PageResult;
import com.lion.agent.common.result.R;
import com.lion.agent.pojo.dto.SensitiveWordBatchRequest;
import com.lion.agent.pojo.dto.SensitiveWordRequest;
import com.lion.agent.pojo.entity.SensitiveWordEntity;
import com.lion.agent.pojo.vo.SensitiveWordCheckVo;
import com.lion.agent.service.SensitiveWordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 敏感词管理接口
 *
 * <p>维护的输入侧拦截词库；改词后 {@link com.lion.agent.advisor.SensitiveWordAdvisor}
 * 下一次对话即按新词表拦截，无需重启服务。</p>
 */
@Tag(name = "敏感词管理")
@RestController
@RequestMapping("/api/sensitive-word")
@RequiredArgsConstructor
public class SensitiveWordController {

    private final SensitiveWordService sensitiveWordService;

    @Operation(summary = "敏感词分页列表")
    @GetMapping
    public R<PageResult<SensitiveWordEntity>> list(
            @RequestParam(required = false, defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(required = false, defaultValue = "10") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled) {
        return R.success(sensitiveWordService.list(pageNum, pageSize, keyword, enabled));
    }

    @Operation(summary = "新增敏感词")
    @PostMapping
    public R<SensitiveWordEntity> create(@Valid @RequestBody SensitiveWordRequest request) {
        return R.success(sensitiveWordService.create(request));
    }

    @Operation(summary = "修改敏感词")
    @PutMapping("/{id}")
    public R<SensitiveWordEntity> update(@PathVariable Long id,
                                         @Valid @RequestBody SensitiveWordRequest request) {
        return R.success(sensitiveWordService.update(id, request));
    }

    @Operation(summary = "删除敏感词")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        sensitiveWordService.delete(id);
        return R.success();
    }

    @Operation(summary = "启停敏感词")
    @PostMapping("/{id}/toggle")
    public R<SensitiveWordEntity> toggle(@PathVariable Long id, @RequestParam boolean enabled) {
        return R.success(sensitiveWordService.toggle(id, enabled));
    }

    @Operation(summary = "批量导入敏感词（已存在的自动跳过）")
    @PostMapping("/batch-import")
    public R<Integer> batchImport(@Valid @RequestBody SensitiveWordBatchRequest request) {
        return R.success(sensitiveWordService.batchImport(request));
    }

    @Operation(summary = "命中检测（试一试：不落库、不调用模型）")
    @PostMapping("/check")
    public R<SensitiveWordCheckVo> check(@RequestBody Map<String, String> body) {
        return R.success(sensitiveWordService.check(body == null ? null : body.get("text")));
    }

    @Operation(summary = "刷新词表缓存（正常情况下写操作会自动失效，无需手动调用）")
    @PostMapping("/cache/refresh")
    public R<Void> refreshCache() {
        sensitiveWordService.refreshCache();
        return R.success();
    }
}
