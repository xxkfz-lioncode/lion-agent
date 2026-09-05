package com.lion.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lion.agent.common.PageResult;
import com.lion.agent.common.Result;
import com.lion.agent.service.TokenUsageService;
import com.lion.agent.model.vo.TokenUsageVO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Token 用量统计接口
 */
@Validated
@RestController
@RequestMapping("/api/token-usage")
@RequiredArgsConstructor
public class TokenUsageController {

    private final TokenUsageService tokenUsageService;

    /**
     * 分页查询当前用户的用量记录
     */
    @GetMapping
    public Result<PageResult<TokenUsageVO>> page(
            @RequestParam(defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String chatType) {
        return Result.success(tokenUsageService.page(StpUtil.getLoginIdAsLong(), pageNum, pageSize, chatType));
    }

    /**
     * 汇总统计（总调用次数 / 总 token / 今日 / 平均耗时）
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.success(tokenUsageService.statistics(StpUtil.getLoginIdAsLong()));
    }
}
