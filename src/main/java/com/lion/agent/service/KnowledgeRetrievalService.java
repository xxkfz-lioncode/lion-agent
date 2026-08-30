package com.lion.agent.service;

import com.lion.agent.config.PromptConfig;
import com.lion.agent.entity.KnowledgeBase;
import com.lion.agent.utils.DashScopeRerankUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库检索服务（高级 RAG 流水线）
 * <p>
 * 检索链路：语义改写 → 扩容多路检索 → RRF 融合 + 粗筛 → Rerank → 复评门控
 * <pre>
 * 1. 语义改写：LLM 将口语化问题改写为检索友好查询（失败回退原文）
 * 2. 扩容多路检索：原始问题 + 改写后问题各召回 TopK=20，扩大候选池（recall 扩容）
 * 3. RRF 融合 + 粗筛：两路结果按 Reciprocal Rank Fusion 分数去重排序，保留前 10
 * 4. Rerank：DashScope 专用 Rerank 模型按 query 相关性对候选片段重排，取前 5
 * 5. 复评门控：LLM 判断资料是否足以回答；不足则降级返回，不再浪费一次主模型调用
 * </pre>
 * 说明：改写/重排/门控使用无 Advisor 的裸 {@link ChatModel} 调用，
 * 避免经过全局 ChatClient 触发语义缓存、会话记忆等 Advisor（污染缓存、误耗 token）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

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
    /** 无 Advisor 的裸模型：用于改写/重排/门控等辅助调用，避免污染语义缓存与会话记忆 */
    private final ChatModel chatModel;
    /** 提示词模板统一配置（回答/改写/重排/门控等模板集中维护） */
    private final PromptConfig promptConfig;
    /** DashScope Rerank API 客户端：用专用排序模型替代 LLM 打分重排 */
    private final DashScopeRerankUtils dashScopeRerankUtils;

    /**
     * 知识库检索流水线
     *
     * @param userId      当前用户 ID
     * @param question    用户问题
     * @param knowledgeId 指定的知识库 ID；为空则检索该用户全部知识库
     * @return 检索结果（qualified=门控是否通过、context=拼接上下文、reason=门控未通过原因、chunks=引用片段）
     */
    public RetrievalResult retrieve(Long userId, String question, Long knowledgeId) {
        // 解析检索范围：指定知识库 → 校验归属后单库检索；未指定 → 全部知识库
        String filter = resolveFilter(userId, knowledgeId);
        if (filter == null) {
            // 用户没有任何知识库，无法检索
            return new RetrievalResult(false, null, "当前用户没有任何知识库", List.of());
        }

        // 1. 语义改写：LLM 将口语化问题改写为检索友好查询（失败回退原文）
        String rewritten = rewriteQuestion(question);

        // 2. 扩容多路检索：原始问题 + 改写后问题各召回 TopK=20，扩大候选池
        List<Document> rawDocs = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(RECALL_TOP_K).filterExpression(filter).build());
        List<Document> rewriteDocs = vectorStore.similaritySearch(
                SearchRequest.builder().query(rewritten).topK(RECALL_TOP_K).filterExpression(filter).build());

        // 3. RRF 融合 + 粗筛：两路结果按 RRF 分数去重排序，保留前 10
        List<Document> fused = rrfFusion(rawDocs, rewriteDocs, FUSED_TOP_N);
        log.info("[Retrieval] 召回统计：原始 {} 条 / 改写 {} 条 / 融合后 {} 条",
                rawDocs.size(), rewriteDocs.size(), fused.size());

        // 4. Rerank：DashScope 专用 Rerank 模型按 query 相关性重排，取前 5
        List<Document> reranked = dashScopeRerankUtils.rerank(rewritten, fused, RERANK_TOP_N);

        // 5. 复评门控：LLM 判断资料是否足以回答问题，不足则降级返回
        GateResult gate = evaluateSufficiency(rewritten, reranked);
        if (!gate.qualified()) {
            log.info("[Retrieval] 门控拦截：资料不足以回答 knowledgeId={} reason={}",
                    knowledgeId, gate.reason());
            return new RetrievalResult(false, null, gate.reason(), toChunks(reranked));
        }

        // 门控通过：返回拼接好的上下文
        String context = toContext(reranked);
        return new RetrievalResult(true, context, null, toChunks(reranked));
    }

    // ==================== 流水线各阶段 ====================

    /**
     * 解析检索过滤条件：
     * <ul>
     *   <li>指定 knowledgeId → 校验归属，返回 {@code knowledgeId == X}</li>
     *   <li>未指定 → 查询用户全部知识库 ID，返回 {@code knowledgeId in (1,2,3)}</li>
     *   <li>用户无任何知识库 → 返回 null（调用方直接判为不可检索）</li>
     * </ul>
     */
    private String resolveFilter(Long userId, Long knowledgeId) {
        if (knowledgeId != null) {
            knowledgeBaseService.getById(knowledgeId, userId);
            return "knowledgeId == " + knowledgeId;
        }
        List<KnowledgeBase> kbs = knowledgeBaseService.listAllByUser(userId);
        if (kbs.isEmpty()) {
            return null;
        }
        String ids = kbs.stream().map(kb -> kb.getId().toString()).collect(Collectors.joining(","));
        return "knowledgeId in (" + ids + ")";
    }

    /**
     * 1. 语义改写：LLM 将口语化问题改写为检索友好查询（失败回退原文）
     */
    private String rewriteQuestion(String question) {
        String instruction = promptConfig.renderKbRewrite(question);
        String rewritten = callLlm(instruction);
        if (!StringUtils.hasText(rewritten)) {
            log.warn("[Retrieval] 查询改写失败，回退原始问题");
            return question;
        }
        log.info("[Retrieval] 查询改写：{} -> {}", truncate(question, 30), truncate(rewritten, 30));
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
     * 5. 复评门控：LLM 判断资料是否足以回答问题；LLM 异常时放行，避免误伤
     */
    private GateResult evaluateSufficiency(String query, List<Document> docs) {
        String context = toContext(docs);
        String instruction = promptConfig.renderKbGate(context, query);
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

    /** 无 Advisor 的裸 LLM 调用：用于改写/重排/门控等辅助调用，失败返回 null */
    private String callLlm(String userText) {
        try {
            ChatResponse response = chatModel.call(new Prompt(userText));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return null;
            }
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("[Retrieval] 辅助 LLM 调用失败: {}", e.getMessage());
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

    /**
     * 检索结果
     *
     * @param qualified 门控是否通过（false 表示资料不足以回答）
     * @param context   门控通过时拼接好的上下文（供答案模板渲染）；未通过为 null
     * @param reason    门控未通过时的缺失信息；通过为 null
     * @param chunks    引用片段（无论门控是否通过均返回，便于前端展示来源）
     */
    public record RetrievalResult(boolean qualified, String context, String reason, List<String> chunks) {
    }
}
