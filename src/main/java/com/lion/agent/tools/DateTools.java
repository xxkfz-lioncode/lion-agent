package com.lion.agent.tools;

import cn.hutool.core.date.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DateTools {

    /**
     * 查询系统中注册用户的总数量（自动排除逻辑删除的用户）
     */
    @Tool(description = "查询当前的时间，返回一个日期字符串，例如 \" 2026-08-23 10:33:06\"")
    public String getNowDate() {
       return DateUtil.now();
    }
}
