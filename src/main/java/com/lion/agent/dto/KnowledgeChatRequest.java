package com.lion.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class KnowledgeChatRequest {

    @NotNull(message = "知识库 ID 不能为空")
    private Long knowledgeId;

    @NotBlank(message = "问题不能为空")
    private String question;
}
