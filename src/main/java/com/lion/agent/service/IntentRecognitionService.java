package com.lion.agent.service;

import com.lion.agent.common.enums.ChatIntent;
import com.lion.agent.config.PromptConfig;
import com.lion.agent.model.entity.KnowledgeBase;
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
 *   <li>用户没有任何知识库 —— 直接判定为 GENERAL（无可检索范围，无需 LLM）</li>
 *   <li>存在可用知识库 —— 无论用户是否指定具体知识库，都使用无 Advisor 的裸 LLM 综合判断：
 *       该问题是走知识库检索（knowledge）还是普通对话（general）。「当前选择的知识库」
 *       （未指定时为「全部知识库」）作为上下文提供给模型，即使选了具体库，
 *       明显是通用常识/闲聊的问题仍会判为 general</li>
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
     * @param knowledgeId 前端指定的知识库 ID（可为空，空表示全部知识库）
     * @return 意图枚举；LLM 调用失败时降级为 GENERAL，避免阻塞对话
     */
    public ChatIntent classify(Long userId, String question, Long knowledgeId) {
        // 1. 没有任何知识库 → 无可检索范围，直接一般对话
        List<KnowledgeBase> kbs = knowledgeBaseService.listAllByUser(userId);
        if (kbs.isEmpty()) {
            return ChatIntent.GENERAL;
        }

        // 2. 存在知识库：无论是否指定具体知识库，都交给 LLM 综合判断。
        //    将「当前选择的知识库」作为上下文，避免选了库却问通用常识（如城市人口）被误判。
        String knowledgeBases = kbs.stream().map(KnowledgeBase::getName).collect(Collectors.joining(", "));
        String selectedKnowledge = resolveSelectedKnowledge(kbs, knowledgeId);

        String instruction = promptConfig.renderIntentClassify(knowledgeBases, selectedKnowledge, question);
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

    /**
     * 解析当前用户选择的知识库名称（用于意图识别上下文）
     *
     * @param kbs        用户全部可用知识库
     * @param knowledgeId 前端指定知识库 ID（可为空，空表示全部知识库）
     * @return 具体知识库名称；未指定或 ID 无效时返回「全部知识库」
     */
    private String resolveSelectedKnowledge(List<KnowledgeBase> kbs, Long knowledgeId) {
        if (knowledgeId == null) {
            return "全部知识库";
        }
        return kbs.stream()
                .filter(kb -> kb.getId().equals(knowledgeId))
                .map(KnowledgeBase::getName)
                .findFirst()
                .orElse("全部知识库");
    }

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
