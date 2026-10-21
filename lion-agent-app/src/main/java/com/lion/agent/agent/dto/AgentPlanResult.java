package com.lion.agent.agent.dto;

import java.util.List;

/**
 * 任务规划示例的返回结果。
 *
 * @param task        用户原始任务
 * @param answer      模型最终回答
 * @param items       任务清单最终状态（前端可直接渲染 ✓ / > / 空格）
 * @param updateCount 模型调用 TodoWrite 的次数（看清它真的在推进任务）
 */
public record AgentPlanResult(String task, String answer, List<TodoItemView> items, int updateCount) {
}
