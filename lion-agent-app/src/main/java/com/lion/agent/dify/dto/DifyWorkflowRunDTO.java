package com.lion.agent.dify.dto;

import lombok.Data;

import java.util.Map;

/**
 * Dify 工作流运行请求
 */
@Data
public class DifyWorkflowRunDTO {

    /** 应用编码（lion.dify.apps 里 type=workflow 的应用） */
    private String appCode;

    /** 工作流「开始节点」定义的输入变量（key 为变量名） */
    private Map<String, Object> inputs;
}
