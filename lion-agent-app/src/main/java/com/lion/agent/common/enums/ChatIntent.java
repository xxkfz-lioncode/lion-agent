package com.lion.agent.common.enums;

/**
 * 对话意图枚举
 *
 * <p>统一对话入口根据该枚举路由处理链路：</p>
 * <ul>
 *   <li>{@link #GENERAL} —— 一般对话：闲聊/通用咨询/工具调用，无需检索知识库</li>
 *   <li>{@link #KNOWLEDGE} —— 知识库问答：需要检索用户知识库后基于资料回答</li>
 * </ul>
 */
public enum ChatIntent {

    /** 一般对话 */
    GENERAL,

    /** 知识库问答 */
    KNOWLEDGE
}
