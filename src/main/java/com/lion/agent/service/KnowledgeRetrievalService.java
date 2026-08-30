package com.lion.agent.service;

import com.lion.agent.config.PromptConfig;
import com.lion.agent.entity.KnowledgeBase;
import com.lion.agent.service.retriever.MultiRouteRetriever;
import com.lion.agent.utils.DashScopeRerankUtils;
import com.lion.agent.vo.ChunkSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 知识库检索服务（高级 RAG 流水线）
 * <p>
 * 检索链路：多路召回（语义/BM25/问题改写） → RRF 融合 + 粗筛 → Rerank → 复评门控
 * <pre>
 * 1. 多路召回：MultiRouteRetriever 并行执行向量语义、BM25 关键词、问题改写三路召回（每路 TopK=20）
 * 2. RRF 融合 + 粗筛：多路结果按 Reciprocal Rank Fusion 分数去重排序，保留前 10
 * 3. Rerank：DashScope 专用 Rerank 模型按 query 相关性对候选片段重排，取前 5
 * 4. 复评门控：LLM 判断资料是否足以回答；不足则降级返回，不再浪费一次主模型调用
 * </pre>
 * 说明：召回策略按 {@link Retriever} 接口可插拔（新增一路 = 新增实现类），
 * 重排/门控使用无 Advisor 的裸 {@link ChatModel} 调用，
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

    private final KnowledgeBaseService knowledgeBaseService;
    /** 多路召回组合器：自动收集全部 {@link Retriever} 实现并 RRF 融合 */
    private final MultiRouteRetriever multiRouteRetriever;
    /** 无 Advisor 的裸模型：用于重排/门控等辅助调用，避免污染语义缓存与会话记忆 */
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

        // 预加载用户全部知识库名称，供引用来源回填
        Map<Long, String> kbNameMap = loadKnowledgeBaseNameMap(userId);

        // 1. 多路召回：语义/BM25/问题改写各召回 TopK=20（每路自身兜底，单路故障不影响整体）
        List<Document> fused = multiRouteRetriever.retrieve(question, RECALL_TOP_K, filter, FUSED_TOP_N);
        if (fused.isEmpty()) {
            log.info("[Retrieval] 多路召回均无结果 knowledgeId={}", knowledgeId);
            return new RetrievalResult(false, null, "知识库中未检索到相关内容", List.of());
        }

        // 2. Rerank：DashScope 专用 Rerank 模型按 query 相关性重排，取前 5
        List<Document> reranked = dashScopeRerankUtils.rerank(question, fused, RERANK_TOP_N);

        // 3. 复评门控：LLM 判断资料是否足以回答问题，不足则降级返回
        GateResult gate = evaluateSufficiency(question, reranked);
        if (!gate.qualified()) {
            log.info("[Retrieval] 门控拦截：资料不足以回答 knowledgeId={} reason={}",
                    knowledgeId, gate.reason());
            return new RetrievalResult(false, null, gate.reason(), toChunks(reranked, kbNameMap));
        }

        // 门控通过：返回拼接好的上下文
        String context = toContext(reranked);
        return new RetrievalResult(true, context, null, toChunks(reranked, kbNameMap));
    }

    // ==================== 流水线各阶段 ====================

    /**
     * 解析检索过滤条件：
     * <ul>
     *   <li>指定 knowledgeId → 校验归属，返回 {@code knowledgeId == X}</li>
     *   <li>未指定 → 查询用户全部知识库 ID，返回 {@code (knowledgeId == 1 || knowledgeId == 2 || ...)}</li>
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
        // 只有 1 个知识库时直接走等值过滤，与前端「指定知识库」行为一致
        if (kbs.size() == 1) {
            return "knowledgeId == " + kbs.get(0).getId();
        }
        // 多个知识库时避免使用 in 操作符：Spring AI/Milvus 对 in 的解析在
        // 单元素列表/数值类型/JSON 字段等场景下不稳定，统一用 OR 条件兼容
        // 语义检索与 BM25 直接查询。
        return kbs.stream()
                .map(kb -> "knowledgeId == " + kb.getId())
                .collect(Collectors.joining(" || ", "(", ")"));
    }

    /**
     * 加载当前用户全部知识库 ID→名称映射，用于给引用来源回填所属知识库。
     */
    private Map<Long, String> loadKnowledgeBaseNameMap(Long userId) {
        return knowledgeBaseService.listAllByUser(userId).stream()
                .collect(Collectors.toMap(KnowledgeBase::getId, KnowledgeBase::getName,
                        (a, b) -> a, java.util.HashMap::new));
    }

    /**
     * 4. 复评门控：LLM 判断资料是否足以回答问题；LLM 异常时放行，避免误伤。
     *    候选片段为空时直接判不合格，避免用空上下文误导 LLM 输出
     */
    private GateResult evaluateSufficiency(String query, List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return new GateResult(false, "知识库中未检索到相关内容");
        }
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

    /** 无 Advisor 的裸 LLM 调用：用于重排/门控等辅助调用，失败返回 null */
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

    private List<ChunkSource> toChunks(List<Document> docs, Map<Long, String> kbNameMap) {
        return docs.stream()
                .map(doc -> {
                    Long kbId = parseKnowledgeId(doc);
                    String fileName = doc.getMetadata() != null ? String.valueOf(doc.getMetadata().getOrDefault("fileName", "未知文件")) : "未知文件";
                    return new ChunkSource(
                            kbId,
                            kbNameMap.getOrDefault(kbId, "未知知识库"),
                            fileName,
                            doc.getText()
                    );
                })
                .collect(Collectors.toList());
    }

    /**
     * 从文档元数据解析 knowledgeId；解析失败时返回 null。
     */
    private Long parseKnowledgeId(Document doc) {
        if (doc.getMetadata() == null) {
            return null;
        }
        Object value = doc.getMetadata().get("knowledgeId");
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
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
     * @param chunks    引用片段来源（无论门控是否通过均返回，便于前端展示来源）
     */
    public record RetrievalResult(boolean qualified, String context, String reason, List<ChunkSource> chunks) {
    }
}
