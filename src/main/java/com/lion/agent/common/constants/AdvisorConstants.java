package com.lion.agent.common.constants;

/**
 * Advisor 上下文 Key 常量
 *
 * <p>Advisor / 业务层共用的上下文 Key 统一在此维护，避免散落在各 Advisor
 * 实现类中、互相静态引用造成隐式耦合。取值与 Spring AI 内置常量保持一致，
 * 便于与 Spring AI 生态互操作。</p>
 */
public final class AdvisorConstants {

    private AdvisorConstants() {
    }

    /**
     * 上下文 Key：用户 ID
     * <p>与 Spring AI {@code UserMemoryAdvisor.USER_ID} 取值一致：{@code user_id}。
     * 业务层发起调用时通过 {@code .advisors(spec -> spec.param(...))} 注入。</p>
     */
    public static final String USER_ID_KEY = "user_id";

    /**
     * 上下文 Key：会话 ID
     * <p>与 Spring AI {@code ChatMemory.CONVERSATION_ID} 取值一致：{@code chat_memory_conversation_id}。
     * 业务层发起调用时通过 {@code .advisors(spec -> spec.param(...))} 注入。</p>
     */
    public static final String CONVERSATION_ID_KEY = "chat_memory_conversation_id";

    /**
     * 上下文 Key：会话类型
     * <p>取值：{@code chat}-常规对话、{@code kb}-知识库问答。
     * 业务层发起调用时通过 {@code .advisors(spec -> spec.param(...))} 注入，
     * 供 {@code TokenUsageAdvisor} 落库区分统计口径。</p>
     */
    public static final String CHAT_TYPE_KEY = "chat_type";
}
