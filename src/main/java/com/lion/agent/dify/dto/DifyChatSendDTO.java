package com.lion.agent.dify.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * Dify 对话发送请求
 */
@Data
public class DifyChatSendDTO {

    /** 应用编码（lion.dify.apps 里配置的 key，决定调用哪个 Dify 应用） */
    private String appCode;

    /** 用户输入内容 */
    @NotBlank(message = "消息内容不能为空")
    private String content;

    /** 会话 ID（为空则由 Dify 自动创建新会话，首次返回后前端保存并透传） */
    private String conversationId;

    /** 应用编排里定义的变量输入（可选，key 为 Dify 应用里配置的变量名） */
    private Map<String, Object> inputs;
}
