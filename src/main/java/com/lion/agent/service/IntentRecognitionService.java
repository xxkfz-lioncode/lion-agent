package com.lion.agent.service;

import com.lion.agent.common.enums.ChatIntent;
import com.lion.agent.entity.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 意图识别服务
 *
 * <p>统一对话入口的路由前置：判断用户输入属于{@link ChatIntent#GENERAL 一般对话}
 * 还是{@link ChatIntent#KNOWLEDGE 知识库问答}，决定是否进入知识库检索链路。</p>
 *
 * <p>策略：</p>
 * <ul>
 *   <li>用户显式指定 knowledgeId —— 直接判定为 KNOWLEDGE（用户意图明确）</li>
 *   <li>用户未指定具体知识库但存在可用知识库 —— 默认判定为 KNOWLEDGE（全部知识库检索模式）</li>
 *   <li>用户没有任何知识库 —— 直接判定为 GENERAL（无可检索范围）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRecognitionService {

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
        // 1. 显式指定某个知识库 → 用户意图明确为知识库问答
        if (knowledgeId != null) {
            return ChatIntent.KNOWLEDGE;
        }

        // 2. 未指定具体知识库：只要有可用知识库，默认视为「全部知识库」检索模式
        List<KnowledgeBase> kbs = knowledgeBaseService.listAllByUser(userId);
        if (kbs.isEmpty()) {
            return ChatIntent.GENERAL;
        }
        log.info("[Intent] 未指定具体知识库但存在 {} 个知识库，默认走知识库检索", kbs.size());
        return ChatIntent.KNOWLEDGE;
    }
}
