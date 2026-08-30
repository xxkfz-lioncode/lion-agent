package com.lion.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

/**
 * 技能创建/修改请求
 */
@Data
public class SkillRequest {

    /** 技能名（工具名，模型可见） */
    @NotBlank(message = "技能名称不能为空")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]*$", message = "技能名称仅支持字母开头，后续为字母、数字或下划线")
    private String name;

    /** 技能描述（模型判断何时调用的依据） */
    @NotBlank(message = "技能描述不能为空")
    private String description;

    /** 提示词模板，{{param}} 占位符运行时替换 */
    @NotBlank(message = "提示词模板不能为空")
    private String promptTemplate;

    /** 状态：0-禁用 1-启用（缺省启用） */
    private Integer status;

    /** 参数定义（可为空，技能也可以没有参数） */
    private List<SkillParam> parameters;

    @Data
    public static class SkillParam {

        /** 参数名，模板中写作 {{name}} */
        @NotBlank(message = "参数名不能为空")
        @Pattern(regexp = "^[a-zA-Z_][a-zA-Z0-9_]*$", message = "参数名仅支持字母或下划线开头，后续为字母、数字或下划线")
        private String name;

        /** 参数类型：string / number / integer / boolean */
        private String type;

        /** 参数说明（模型按此决定填什么值） */
        private String description;

        /** 是否必填 */
        private Boolean required;

        /** 缺省值（模型未提供或为空时使用） */
        private String defaultValue;
    }
}
