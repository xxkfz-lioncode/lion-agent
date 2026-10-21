package com.lion.agent.dify.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.lion.agent.common.utils.SseEmitterUtils;
import com.lion.agent.dify.dto.DifyWorkflowRunDTO;
import com.lion.agent.dify.service.DifyAppService;
import com.lion.agent.dify.service.DifyWorkflowService;
import io.github.guoshiqiufeng.dify.workflow.DifyWorkflow;
import io.github.guoshiqiufeng.dify.workflow.dto.request.WorkflowRunRequest;
import io.github.guoshiqiufeng.dify.workflow.dto.response.WorkflowRunResponse;
import io.github.guoshiqiufeng.dify.workflow.dto.response.WorkflowRunStreamResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.HashMap;
import java.util.Map;

/**
 * Dify 工作流服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DifyWorkflowServiceImpl implements DifyWorkflowService {

    private static final String APP_TYPE = "workflow";

    private final DifyWorkflow difyWorkflow;
    private final DifyAppService difyAppService;

    /** 组装运行请求（阻塞与流式共用） */
    private WorkflowRunRequest buildRequest(DifyWorkflowRunDTO dto) {
        WorkflowRunRequest request = new WorkflowRunRequest();
        request.setApiKey(difyAppService.resolveApiKey(dto.getAppCode(), APP_TYPE));
        request.setUserId("u" + StpUtil.getLoginIdAsLong());
        request.setInputs(dto.getInputs() == null ? Map.of() : dto.getInputs());
        return request;
    }

    @Override
    public WorkflowRunResponse run(DifyWorkflowRunDTO dto) {
        return difyWorkflow.runWorkflow(buildRequest(dto));
    }

    @Override
    public SseEmitter runStream(DifyWorkflowRunDTO dto) {
        SseEmitter emitter = new SseEmitter(0L);

        Disposable disposable = difyWorkflow.runWorkflowStream(buildRequest(dto)).subscribe(
                chunk -> {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("event", chunk.getEvent() == null ? "unknown" : chunk.getEvent().name());
                    payload.put("data", chunk.getData());
                    SseEmitterUtils.send(emitter, "message", payload);
                },
                err -> {
                    log.error("[Dify-Workflow] 流式执行失败", err);
                    SseEmitterUtils.error(emitter, "Dify 工作流执行失败：" + err.getMessage());
                },
                () -> SseEmitterUtils.complete(emitter));

        emitter.onTimeout(disposable::dispose);
        emitter.onCompletion(disposable::dispose);
        return emitter;
    }
}
