package com.lion.agent.service;

import com.lion.agent.common.enums.ChatIntent;
import com.lion.agent.config.PromptConfig;
import com.lion.agent.entity.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 意图识别服务
 *
 * <p>统一对话入口的路由前置：判断用户输入属于{@link ChatIntent#GENERAL 一般对话}
 * 还是{@link ChatIntent#KNOWLEDGE 知识库问答}，决定是否进入知识库检索链路。</p>
 *
 * <p>策略：</p>
 * <ul>
 *   <li>用户显式指定 knowledgeId —— 直接判定为 KNOWLEDGE（用户意图明确，无需 LLM）</li>
 *   <li>用户没有任何知识库 —— 直接判定为 GENERAL（无可检索范围，无需 LLM）</li>
 *   <li>其余情况 —— 使用无 Advisor 的裸 {@link ChatModel} 调用意图分类模板，避免
 *       经过全局 ChatClient 触发语义缓存/会话记忆等 Advisor（污染缓存、误耗 token）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRecognitionService {

    /** 知识库问答（需要检索知识库）意图标记，模型输出中命中该词即视为 KNOWLEDGE */
    private static final String KNOWLEDGE_MARK = "knowledge";

    private final ChatModel chatModel;
    private final PromptConfig promptConfig;
    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 识别用户输入意图
     *
     * @param userId      当前用户 ID
     * @param question    用户输入
     * @param knowledgeId 前端指定的知识库 ID（可为空）
     * @return 意图枚举；LLM 调用失败时降级为 GENERAL，避免阻塞对话
     */
    public ChatIntent classify(Long userId, String question, Long knowledgeId) {
        // 1. 显式指定知识库 → 用户意图明确为知识库问答
        if (knowledgeId != null) {
            return ChatIntent.KNOWLEDGE;
        }

        // 2. 无知识库 → 无需检索，直接一般对话
        List<KnowledgeBase> kbs = knowledgeBaseService.listAllByUser(userId);
        if (kbs.isEmpty()) {
            return ChatIntent.GENERAL;
        }

        // 3. 有知识库 → 裸 LLM 意图分类
        String knowledgeBases = kbs.stream().map(KnowledgeBase::getName).collect(Collectors.joining(", "));
        String instruction = promptConfig.renderIntentClassify(knowledgeBases, question);
        String resp = callLlm(instruction);
        if (!StringUtils.hasText(resp)) {
            log.warn("[Intent] 意图识别调用失败，降级为 GENERAL");
            return ChatIntent.GENERAL;
        }
        String text = resp.trim().toLowerCase();
        ChatIntent intent = text.contains(KNOWLEDGE_MARK) ? ChatIntent.KNOWLEDGE : ChatIntent.GENERAL;
        log.info("[Intent] 用户输入「{}」识别意图为：{}", truncate(question, 30), intent);
        return intent;
    }

    // ==================== 工具方法 ====================

    /** 无 Advisor 的裸 LLM 调用，失败返回 null */
    private String callLlm(String userText) {
        try {
            ChatResponse response = chatModel.call(new Prompt(userText));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return null;
            }
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("[Intent] 辅助 LLM 调用失败: {}", e.getMessage());
            return null;
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
