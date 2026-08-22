package com.lion.agent.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.lion.agent.common.Result;
import com.lion.agent.dto.KnowledgeChatRequest;
import com.lion.agent.service.impl.KnowledgeChatServiceImpl;
import com.lion.agent.vo.KnowledgeChatResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "知识库问答")
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
@SaCheckLogin
public class KnowledgeChatController {

    private final KnowledgeChatServiceImpl knowledgeChatService;

    @Operation(summary = "知识库问答")
    @PostMapping("/chat")
    public Result<KnowledgeChatResult> chat(@Valid @RequestBody KnowledgeChatRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(knowledgeChatService.chat(userId, request));
    }
}
