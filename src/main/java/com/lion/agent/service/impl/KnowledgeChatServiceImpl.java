package com.lion.agent.service.impl;

import com.lion.agent.advisor.TokenUsageAdvisor;
import com.lion.agent.dto.KnowledgeChatRequest;
import com.lion.agent.service.KnowledgeBaseService;
import com.lion.agent.vo.KnowledgeChatResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 知识库问答（高级 RAG 流水线）
 * <p>
 * 检索链路：语义改写 → 扩容多路检索 → RRF 融合 + 粗筛 → Rerank → 复评门控 → 大模型作答
 * <pre>
 * 1. 语义改写：LLM 将口语化问题改写为检索友好查询（失败回退原文）
 * 2. 扩容多路检索：原始问题 + 改写后问题各召回 TopK=20，扩大候选池（recall 扩容）
 * 3. RRF 融合 + 粗筛：两路结果按 Reciprocal Rank Fusion 分数去重排序，保留前 10
 * 4. Rerank：LLM 按与问题的相关性对候选片段打分重排，取前 5（可替换为专用 Rerank API）
 * 5. 复评门控：LLM 判断资料是否足以回答；不足则降级返回，不再浪费一次主模型调用
 * </pre>
 * 说明：改写/重排/门控使用无 Advisor 的裸 {@link ChatModel} 调用，
 * 避免经过全局 ChatClient 触发语义缓存、会话记忆等 Advisor（污染缓存、误耗 token）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeChatServiceImpl {

    /** 扩容召回：每路检索取的分片数（先多召回，靠后置重排精挑） */
    private static final int RECALL_TOP_K = 20;
    /** RRF 融合 + 粗筛后保留的候选数 */
    private static final int FUSED_TOP_N = 10;
    /** 重排后最终送入回答的片段数 */
    private static final int RERANK_TOP_N = 5;
    /** RRF 平滑常数（标准值 60） */
    private static final int RRF_K = 60;

    private final KnowledgeBaseService knowledgeBaseService;
    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    /** 无 Advisor 的裸模型：用于改写/重排/门控等辅助调用，避免污染语义缓存与会话记忆 */
    private final ChatModel chatModel;

    public KnowledgeChatResult chat(Long userId, KnowledgeChatRequest request) {
        knowledgeBaseService.getById(request.getKnowledgeId(), userId);

        String question = request.getQuestion();
        String filter = "knowledgeId == " + request.getKnowledgeId();

        // 1. 语义改写：LLM 将口语化问题改写为检索友好查询（失败回退原文）
        String rewritten = rewriteQuestion(question);

        // 2. 扩容多路检索：原始问题 + 改写后问题各召回 TopK=20，扩大候选池
        List<Document> rawDocs = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(RECALL_TOP_K).filterExpression(filter).build());
        List<Document> rewriteDocs = vectorStore.similaritySearch(
                SearchRequest.builder().query(rewritten).topK(RECALL_TOP_K).filterExpression(filter).build());

        // 3. RRF 融合 + 粗筛：两路结果按 RRF 分数去重排序，保留前 10
        List<Document> fused = rrfFusion(rawDocs, rewriteDocs, FUSED_TOP_N);
        log.info("[KnowledgeChat] 召回统计：原始 {} 条 / 改写 {} 条 / 融合后 {} 条",
                rawDocs.size(), rewriteDocs.size(), fused.size());

        // 4. Rerank：LLM 按相关性打分重排，取前 5
        List<Document> reranked = rerank(rewritten, fused, RERANK_TOP_N);

        // 5. 复评门控：LLM 判断资料是否足以回答问题，不足则降级返回
        GateResult gate = evaluateSufficiency(rewritten, reranked);
        if (!gate.qualified()) {
            log.info("[KnowledgeChat] 门控拦截：资料不足以回答 knowledgeId={} reason={}",
                    request.getKnowledgeId(), gate.reason());
            KnowledgeChatResult insufficient = new KnowledgeChatResult();
            insufficient.setAnswer("抱歉，当前知识库中的资料不足以回答该问题。"
                    + (StringUtils.hasText(gate.reason()) ? " 缺失信息：" + gate.reason() : ""));
            insufficient.setReferencedChunks(toChunks(reranked));
            return insufficient;
        }

        // 6. 构造上下文 + prompt
        String context = toContext(reranked);
        String prompt = String.format(
                "请根据以下参考资料回答问题。如果资料不足以回答，请明确说明。\n\n参考资料：\n%s\n\n问题：%s",
                context, question);

        // 7. 调用大模型（带记忆/工具/缓存等全局 Advisor）
        String answer = chatClient.prompt()
                .user(prompt)
                // 注入用户 ID 到上下文，供 TokenUsageAdvisor 统计 token 用量时读取
                .advisors(a -> a.param(TokenUsageAdvisor.USER_ID_KEY, userId))
                .call()
                .content();

        KnowledgeChatResult result = new KnowledgeChatResult();
        result.setAnswer(answer);
        result.setReferencedChunks(toChunks(reranked));
        return result;
    }

    // ==================== 流水线各阶段 ====================

    /**
     * 1. 语义改写：LLM 将口语化问题改写为检索友好查询（失败回退原文）
     */
    private String rewriteQuestion(String question) {
        String instruction = """
                你是一个检索查询改写专家。请把用户的问题改写成更适合向量检索的查询语句：
                - 去除口语化表达，提取核心语义与关键实体
                - 补充必要上下文，使查询自包含、可独立检索
                - 保持原有含义不变
                只输出改写后的查询，不要任何解释或前缀。

                原问题：%s
                """.formatted(question);
        String rewritten = callLlm(instruction);
        if (!StringUtils.hasText(rewritten)) {
            log.warn("[KnowledgeChat] 查询改写失败，回退原始问题");
            return question;
        }
        log.info("[KnowledgeChat] 查询改写：{} -> {}", truncate(question, 30), truncate(rewritten, 30));
        return rewritten.trim();
    }

    /**
     * 3. RRF 融合多路召回 + 粗筛：按 RRF 分数去重排序，保留前 topN
     */
    private List<Document> rrfFusion(List<Document> listA, List<Document> listB, int topN) {
        Map<String, DocScore> scoreMap = new LinkedHashMap<>();
        accumulateRrf(listA, scoreMap);
        accumulateRrf(listB, scoreMap);
        return scoreMap.values().stream()
                .sorted(Comparator.comparingDouble((DocScore ds) -> ds.score).reversed())
                .limit(topN)
                .map(ds -> ds.document)
                .collect(Collectors.toList());
    }

    private void accumulateRrf(List<Document> docs, Map<String, DocScore> scoreMap) {
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            double increment = 1.0 / (RRF_K + i + 1);   // rank 从 1 开始
            scoreMap.computeIfAbsent(doc.getId(), id -> new DocScore(doc)).score += increment;
        }
    }

    /**
     * 4. Rerank：LLM 按与问题的相关性对候选片段打分，重排后取 topN
     * （如接入专用 Rerank API，可在此处替换实现）
     */
    private List<Document> rerank(String query, List<Document> candidates, int topN) {
        if (candidates.size() <= topN) {
            return candidates;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("请评估以下资料片段与问题的相关性，输出每个片段的编号和相关性分数（0-100，越高越相关）。\n");
        sb.append("只输出格式「编号:分数」，每行一个，不要输出任何其他内容。\n\n");
        sb.append("问题：").append(query).append("\n\n资料片段：\n");
        for (int i = 0; i < candidates.size(); i++) {
            sb.append("【").append(i).append("】").append(truncate(candidates.get(i).getText(), 500)).append("\n\n");
        }
        String resp = callLlm(sb.toString());

        Map<Integer, Double> scores = new HashMap<>();
        if (StringUtils.hasText(resp)) {
            for (String line : resp.split("\n")) {
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                try {
                    int idx = Integer.parseInt(line.substring(0, colon).trim());
                    double s = Double.parseDouble(line.substring(colon + 1).trim());
                    if (idx >= 0 && idx < candidates.size()) {
                        scores.put(idx, s);
                    }
                } catch (NumberFormatException ignored) {
                    // 跳过无法解析的行
                }
            }
        }
        // 未拿到分数的候选排最后，保持原顺序兜底
        return IntStream.range(0, candidates.size())
                .boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> scores.getOrDefault(i, 0.0)).reversed())
                .limit(topN)
                .map(candidates::get)
                .collect(Collectors.toList());
    }

    /**
     * 5. 复评门控：LLM 判断资料是否足以回答问题；LLM 异常时放行，避免误伤
     */
    private GateResult evaluateSufficiency(String query, List<Document> docs) {
        String context = toContext(docs);
        String instruction = """
                请判断以下参考资料是否足以回答用户问题。
                如果资料直接或间接包含答案信息，输出：通过
                如果资料明显不足以回答（内容不相关、缺少关键信息），输出：不足
                不足时另起一行，用一句话说明缺失什么信息。
                只需输出「通过」或「不足」及缺失说明，不要输出其他内容。

                参考资料：
                %s

                用户问题：%s
                """.formatted(context, query);
        String resp = callLlm(instruction);
        if (!StringUtils.hasText(resp)) {
            return new GateResult(true, null);
        }
        String[] lines = resp.split("\n", 2);
        boolean qualified = lines[0].trim().contains("通过");
        if (qualified) {
            return new GateResult(true, null);
        }
        String reason = lines.length > 1 ? lines[1].trim() : null;
        return new GateResult(false, StringUtils.hasText(reason) ? reason : "知识库中未检索到相关内容");
    }

    // ==================== 工具方法 ====================

    /**
     * 无 Advisor 的裸 LLM 调用：用于改写/重排/门控等辅助调用，
     * 避免经过全局 ChatClient 触发语义缓存、会话记忆等 Advisor（污染缓存、误耗 token）
     */
    private String callLlm(String userText) {
        try {
            ChatResponse response = chatModel.call(new Prompt(userText));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return null;
            }
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("[KnowledgeChat] 辅助 LLM 调用失败: {}", e.getMessage());
            return null;
        }
    }

    private String toContext(List<Document> docs) {
        return docs.stream().map(Document::getText).collect(Collectors.joining("\n\n---\n\n"));
    }

    private List<String> toChunks(List<Document> docs) {
        return docs.stream().map(Document::getText).collect(Collectors.toList());
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** RRF 累加容器 */
    private static final class DocScore {
        final Document document;
        double score;

        DocScore(Document document) {
            this.document = document;
        }
    }

    /** 复评门控结果 */
    private record GateResult(boolean qualified, String reason) {
    }
}
