package com.lion.agent.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 敏感词命中检测结果（页面「试一试」用，不落库、不消耗 token）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SensitiveWordCheckVo {

    /** 是否命中 */
    private Boolean hit;

    /** 命中的敏感词（原始大小写） */
    private List<String> hitWords;

    /** 命中时返回给用户的拒绝话术 */
    private String rejectMessage;

    public static SensitiveWordCheckVo miss() {
        return new SensitiveWordCheckVo(false, List.of(), null);
    }
}
