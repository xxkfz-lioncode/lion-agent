package com.lion.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lion.agent.common.Result;
import com.lion.agent.model.entity.AiMemory;
import com.lion.agent.service.MemoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 长期记忆接口（查看当前用户积累的用户画像）
 */
@Tag(name = "06-长期记忆", description = "查看跨会话积累的用户长期记忆画像")
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryService memoryService;

    @Operation(summary = "查询当前用户全部长期记忆画像",
            description = "返回该用户所有记忆画像记录（按更新时间倒序），内容为跨会话抽取合并后的用户事实/偏好")
    @GetMapping("/list")
    public Result<List<AiMemory>> list() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(memoryService.listByUser(userId));
    }
}
