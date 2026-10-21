package com.lion.agent.service.support;

import cn.dev33.satoken.stp.StpUtil;
import com.lion.agent.common.constants.AdvisorConstants;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.config.PromptConfig;
import com.lion.agent.service.ModelConfigService;
import com.lion.agent.service.ToolRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 大模型调用支撑组件
 *
 * <p>封装 Spring AI ChatClient 的同步调用（系统提示词、Advisor 上下文、工具注册、模型热切换），
 * 普通对话与知识库对话共用同一套调用参数，仅在 chatType / 送入模型的消息内容上不同。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatLlmSupport {

    private final ChatClient chatClient;
    private final PromptConfig promptConfig;
    private final ToolRegistryService toolRegistryService;
    /** 模型配置管理（对话时按类型取默认模型热切换，见模型管理页面） */
    private final ModelConfigService modelConfigService;
    /** MCP 调用凭据签发：给远程 MCP Server 的 _meta 里放短期 JWT，而不是固定字符串 */
    private final McpAuthSupport mcpAuthSupport;

    /**
     * 同步调用千问大模型（纯文本）
     *
     * @param message        送入模型的用户消息（知识库链路为渲染后的 KB prompt）
     * @param conversationId 会话 ID，用于按会话保存/加载多轮记忆
     * @param chatType       会话类型（chat-一般对话 / kb-知识库问答），供 TokenUsageAdvisor 落库区分
     * @param rawUserMessage 未加工的用户原话，供 LongTermMemoryAdvisor 抽取长期记忆
     */
    public String call(String message, Long conversationId, String chatType, String rawUserMessage) {
        log.info("开始请求LLM大模型（文本）......");
        long userId = StpUtil.getLoginIdAsLong();
        try {
            String systemPrompt = promptConfig.renderSystemPrompt();
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                    .system(systemPrompt)
                    // 注入会话 ID / 用户 ID / 原始提问到 Advisor 上下文（必须放在同一个 advisors 调用里，避免被覆盖）
                    .advisors(a -> a
                            .param(ChatMemory.CONVERSATION_ID, conversationId)
                            .param(AdvisorConstants.USER_ID_KEY, userId)
                            .param(AdvisorConstants.CHAT_TYPE_KEY, chatType)
                            .param(AdvisorConstants.RAW_USER_MESSAGE_KEY, rawUserMessage))
                    // 工具上下文：服务端注入，模型不可见也不可篡改
                    // Authorization 用短期 JWT（含 uid、5 分钟过期、可验签），由 McpAuthSupport 签发
                    .toolContext(Map.of(AdvisorConstants.USER_ID_KEY, userId,
                            McpAuthSupport.AUTH_CONTEXT_KEY, mcpAuthSupport.buildAuthHeader(userId)))
                    // 工具按需注册：常驻（UserTools）+ 向量预筛（StarFortuneTools 等），见 ToolRegistryService
                    .tools(toolRegistryService.selectTools(message, userId));
            // 模型热切换：模型管理页面设置的默认 chat 模型按次覆盖；表中无默认记录时不覆盖（沿用 yml 配置）
            String chatModelName = modelConfigService.getDefaultModelName("chat");
            if (chatModelName != null) {
                // Spring AI 2.0：需传厂商专属 Options（通用 ChatOptions 会在 OpenAiChatModel 内部强转失败）
                spec.options(OpenAiChatOptions.builder().model(chatModelName));
            }
            // 同步调用：工具调用由 Spring AI 自动处理（执行工具后再递归调用模型），返回最终文本
            return spec.user(message).call().content();
        } catch (Exception e) {
            log.error("调用千问大模型失败", e);
            throw new BusinessException("AI 服务调用失败，请检查 QWEN_API_KEY 配置或稍后重试");
        }
    }
}
