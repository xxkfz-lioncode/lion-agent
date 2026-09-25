package com.lion.agent.dify.service;

import com.lion.agent.dify.dto.DifyWorkflowRunDTO;
import io.github.guoshiqiufeng.dify.workflow.dto.response.WorkflowRunResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Dify 工作流服务（对接 dify-spring-boot4-starter 的 DifyWorkflow）
 *
 * <p>运行哪个工作流由 {@link DifyWorkflowRunDTO#getAppCode()} 指定（对应配置里的应用编码）。
 */
public interface DifyWorkflowService {

    /**
     * 阻塞式运行工作流，等待执行完成后返回最终结果
     */
    WorkflowRunResponse run(DifyWorkflowRunDTO dto);

    /**
     * 流式运行工作流（SSE）：每个分片以 {event, data} 事件推送
     */
    SseEmitter runStream(DifyWorkflowRunDTO dto);
}
