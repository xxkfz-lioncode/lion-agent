package com.lion.agent.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 本地 @Tool / 手工注册工具视图对象
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LocalToolVo {

    private String name;

    private String description;

    /** 输入参数 JSON Schema（JSON 字符串） */
    private String inputSchema;

    /** 来源：类名（@Tool 方法）或 manual:beanName（手工注册） */
    private String source;
}
