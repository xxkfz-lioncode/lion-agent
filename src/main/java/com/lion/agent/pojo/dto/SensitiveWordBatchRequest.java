package com.lion.agent.pojo.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 敏感词批量导入请求
 *
 * <p>words 支持前端按行/逗号切分后传入；重复的库内词自动跳过，不报错。</p>
 */
@Data
public class SensitiveWordBatchRequest {

    @NotEmpty(message = "待导入的敏感词不能为空")
    private List<String> words;

    /** 分类：默认 custom */
    private String category;

    /** 是否启用，默认启用 */
    private Boolean enabled;
}
