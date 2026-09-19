package com.lion.agent.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型连通性测试结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModelTestVo {

    /** 测试的模型名 */
    private String modelName;

    /** 模型回复内容（截取前 200 字） */
    private String reply;

    /** 耗时（毫秒） */
    private long costMs;
}
