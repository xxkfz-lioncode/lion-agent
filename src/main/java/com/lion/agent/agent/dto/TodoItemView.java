package com.lion.agent.agent.dto;

/**
 * 任务清单里的一项（给前端渲染用）。
 *
 * @param content    任务内容（中文祈使句）
 * @param status     状态：pending / in_progress / completed
 * @param activeForm 进行中描述（“正在……”）
 */
public record TodoItemView(String content, String status, String activeForm) {
}
