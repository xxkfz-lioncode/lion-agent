package com.lion.agent.tools;

import com.lion.agent.entity.User;
import com.lion.agent.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面向 AI Agent 的用户查询工具集。
 * <p>
 * 方法上的 {@link Tool} 注解由 Spring AI 自动识别，注册为可被大模型调用的工具
 * （通过 MethodToolCallbackProvider 收集后传给 ChatClient.tools(...)）。
 */
@Component
@RequiredArgsConstructor
public class UserTools {

    private final UserMapper userMapper;

    /**
     * 查询系统中注册用户的总数量（自动排除逻辑删除的用户）
     */
    @Tool(description = "查询系统中注册用户的总数量，返回一个数字字符串，例如 \"5\"")
    public String getUserCount() {
        Long count = userMapper.selectCount(null);
        return String.valueOf(count == null ? 0L : count);
    }

    /**
     * 按账号状态汇总用户数量，统计系统用户构成
     */
    @Tool(description = "按账号状态汇总系统用户数量，返回 JSON 对象：total(总用户数)、active(状态为正常的用户数，status=1)、disabled(被禁用的用户数，status=0)")
    public Map<String, Object> getUserStatusSummary() {
        List<User> users = userMapper.selectList(null);
        long active = users.stream()
                .filter(u -> u.getStatus() != null && u.getStatus() == 1)
                .count();
        long disabled = users.stream()
                .filter(u -> u.getStatus() != null && u.getStatus() == 0)
                .count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", (long) users.size());
        summary.put("active", active);
        summary.put("disabled", disabled);
        return summary;
    }
}
