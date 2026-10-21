package com.lion.agent.dify.controller;

import com.lion.agent.common.result.R;
import com.lion.agent.dify.dto.DifyWorkflowRunDTO;
import com.lion.agent.dify.service.DifyWorkflowService;
import io.github.guoshiqiufeng.dify.workflow.dto.response.WorkflowRunResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Dify 工作流接口（appCode 指定运行哪个工作流应用）
 */
@Tag(name = "22-Dify 工作流", description = "对接 Dify 工作流应用：阻塞/流式运行")
@RestController
@RequestMapping("/api/dify/workflow")
@RequiredArgsConstructor
public class DifyWorkflowController {

    private final DifyWorkflowService difyWorkflowService;

    @Operation(summary = "运行工作流（阻塞）", description = "等待工作流执行完成后返回最终输出")
    @PostMapping("/run")
    public R<WorkflowRunResponse> run(@RequestBody(required = false) DifyWorkflowRunDTO dto) {
        return R.success(difyWorkflowService.run(dto == null ? new DifyWorkflowRunDTO() : dto));
    }

    @Operation(summary = "运行工作流（流式 SSE）",
            description = "text/event-stream 流式推送执行过程，每个分片为 {event, data}")
    @PostMapping(value = "/run/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runStream(@RequestBody(required = false) DifyWorkflowRunDTO dto) {
        return difyWorkflowService.runStream(dto == null ? new DifyWorkflowRunDTO() : dto);
    }
}
