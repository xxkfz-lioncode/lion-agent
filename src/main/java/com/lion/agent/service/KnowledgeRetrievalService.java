package com.lion.agent.service;

import com.lion.agent.common.enums.VectorType;
import com.lion.agent.config.PromptConfig;
import com.lion.agent.entity.KnowledgeBase;
import com.lion.agent.service.retriever.ChunkPos;
import com.lion.agent.service.retriever.MilvusChunkReader;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库检索服务（高级 RAG 流水线）
 * <p>
 * 检索链路：多路召回 → RRF 融合 + 粗筛 → Rerank → 窗口扩容 + 临近拼接 → 合并后复评 → 复评门控
 * <pre>
 * 1. 多路召回：MultiRouteRetriever 并行执行向量语义、BM25 关键词、问题改写三路召回（每路 TopK=20）
 * 2. RRF 融合 + 粗筛：多路结果按 Reciprocal Rank Fusion 分数去重排序，保留前 10
 * 3. Rerank：DashScope 专用 Rerank 模型按 query 相关性对候选片段重排，取前 5（分数回写 metadata）
 * 4. 窗口扩容 + 临近拼接：命中块向前后扩 WINDOW_RADIUS 段，同文档相邻窗口合并成完整片段（small-to-big）
 * 5. 合并后复评：对拼接片段重新打分——向量+关键词双路命中获得证据加成（CombMNZ 思想）
 * 6. 复评门控：分数阈值预检 + LLM 判断资料是否足以回答；不足则降级返回，不再浪费一次主模型调用
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
    /** 窗口扩容半径：命中块向前后各扩多少分片（small-to-big 上下文补全） */
    private static final int WINDOW_RADIUS = 1;
    /** 合并后复评：双路（向量+关键词）命中时的证据加成系数，封顶 1.0 */
    private static final double DUAL_PATH_BONUS = 1.2;
    /** 复评门控最低分数：合并后最高证据分数低于该值直接拒答（分数阈值法），不浪费 LLM */
    private static final double GATE_MIN_SCORE = 0.2;

    private final KnowledgeBaseService knowledgeBaseService;
    /** 多路召回组合器：自动收集全部 {@link Retriever} 实现并 RRF 融合 */
    private final MultiRouteRetriever multiRouteRetriever;
    /** 无 Advisor 的裸模型：用于重排/门控等辅助调用，避免污染语义缓存与会话记忆 */
    private final ChatModel chatModel;
    /** 提示词模板统一配置（回答/改写/重排/门控等模板集中维护） */
    private final PromptConfig promptConfig;
    /** DashScope Rerank API 客户端：用专用排序模型替代 LLM 打分重排 */
    private final DashScopeRerankUtils dashScopeRerankUtils;
    /** Milvus 分片原文读取器：按位置取回相邻分片，供窗口扩容 */
    private final MilvusChunkReader milvusChunkReader;

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

        // 2. Rerank：DashScope 专用 Rerank 模型按 query 相关性重排，取前 5（分数回写 metadata）
        List<Document> reranked = dashScopeRerankUtils.rerank(question, fused, RERANK_TOP_N);

        // 3. 窗口扩容 + 临近拼接（small-to-big）：命中块前后扩窗，相邻窗口合并成完整片段
        List<PendingSpan> pending = expandChunkWindows(reranked);

        // 4. 合并后复评：对拼接片段重新打分（双路命中加成），供门控评估证据强度
        List<ExpandedSpan> expanded = reScoreSpans(pending);

        // 5. 复评门控：分数阈值预检 + LLM 判断，不足则降级返回
        GateResult gate = evaluateSufficiency(question, expanded);
        if (!gate.qualified()) {
            log.info("[Retrieval] 门控拦截：资料不足以回答 knowledgeId={} reason={}",
                    knowledgeId, gate.reason());
            return new RetrievalResult(false, null, gate.reason(), toChunksFromSpans(expanded, kbNameMap));
        }

        // 门控通过：返回拼接好的上下文（含扩窗后的完整片段）
        String context = expanded.stream()
                .map(ExpandedSpan::text)
                .collect(Collectors.joining("\n\n---\n\n"));
        return new RetrievalResult(true, context, null, toChunksFromSpans(expanded, kbNameMap));
    }

    // ==================== 流水线各阶段 ====================

    /**
     * 解析检索过滤条件：
     * <ul>
     *   <li>指定 knowledgeId → 校验归属，返回 {@code type == 'kb' && knowledgeId == X}</li>
     *   <li>未指定 → 查询用户全部知识库 ID，返回
     *       {@code type == 'kb' && (knowledgeId == 1 || knowledgeId == 2 || ...)}</li>
     *   <li>用户无任何知识库 → 返回 null（调用方直接判为不可检索）</li>
     * </ul>
     * 统一带 {@code type == 'kb'}：只召回知识库分片，自动排除同集合内的
     * 工具索引/技能索引/QA 缓存/长期记忆等其他类型数据。
     */
    private String resolveFilter(Long userId, Long knowledgeId) {
        String scope;
        if (knowledgeId != null) {
            knowledgeBaseService.getById(knowledgeId, userId);
            scope = "knowledgeId == " + knowledgeId;
        } else {
            List<KnowledgeBase> kbs = knowledgeBaseService.listAllByUser(userId);
            if (kbs.isEmpty()) {
                return null;
            }
            // 只有 1 个知识库时直接走等值过滤，与前端「指定知识库」行为一致
            if (kbs.size() == 1) {
                scope = "knowledgeId == " + kbs.get(0).getId();
            } else {
                // 多个知识库时避免使用 in 操作符：Spring AI/Milvus 对 in 的解析在
                // 单元素列表/数值类型/JSON 字段等场景下不稳定，统一用 OR 条件兼容
                // 语义检索与 BM25 直接查询。
                scope = kbs.stream()
                        .map(kb -> "knowledgeId == " + kb.getId())
                        .collect(Collectors.joining(" || ", "(", ")"));
            }
        }
        return "type == '" + VectorType.KB.getValue() + "' && " + scope;
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
     * 复评门控：先做分数阈值预检（合并后复评的证据分数，低于阈值直接拒答，省一次 LLM），
     * 再交给 LLM 判断资料是否足以回答问题；LLM 异常时放行，避免误伤。
     *
     * <p>临时状态：已去掉大模型判断逻辑（仅保留分数阈值预检，通过即放行），
     * 方便单独验证检索/复评链路；原 LLM 逻辑保留在注释中，便于恢复。</p>
     */
    private GateResult evaluateSufficiency(String query, List<ExpandedSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return new GateResult(false, "知识库中未检索到相关内容");
        }
        // 分数阈值法：复评后排名第一（精排顺序）的片段证据分数过低 → 检索质量不佳
        double topScore = spans.get(0).score();
        if (topScore < GATE_MIN_SCORE) {
            return new GateResult(false, "检索结果相关度不足（评分 " + String.format("%.2f", topScore) + "）");
        }
        // ===== 临时：跳过 LLM 判断，分数阈值通过即放行 =====
        return new GateResult(true, null);
        // ===== 原 LLM 判断逻辑（临时注释，恢复时取消注释即可） =====
        // String context = spans.stream()
        //         .map(ExpandedSpan::text)
        //         .collect(Collectors.joining("\n\n---\n\n"));
        // String instruction = promptConfig.renderKbGate(context, query);
        // String resp = callLlm(instruction);
        // if (!StringUtils.hasText(resp)) {
        //     return new GateResult(true, null);
        // }
        // String[] lines = resp.split("\n", 2);
        // boolean qualified = lines[0].trim().contains("通过");
        // if (qualified) {
        //     return new GateResult(true, null);
        // }
        // String reason = lines.length > 1 ? lines[1].trim() : null;
        // return new GateResult(false, StringUtils.hasText(reason) ? reason : "知识库中未检索到相关内容");
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

    /**
     * 把复评后的片段转成引用来源（用片段首个命中块的所属知识库/文件名回填）。
     */
    private List<ChunkSource> toChunksFromSpans(List<ExpandedSpan> spans, Map<Long, String> kbNameMap) {
        return spans.stream()
                .map(span -> {
                    Document first = span.hits().isEmpty() ? null : span.hits().get(0);
                    Long kbId = first == null ? null : parseKnowledgeId(first);
                    String fileName = first != null && first.getMetadata() != null
                            ? String.valueOf(first.getMetadata().getOrDefault("fileName", "未知文件"))
                            : "未知文件";
                    return new ChunkSource(
                            kbId,
                            kbNameMap.getOrDefault(kbId, "未知知识库"),
                            fileName,
                            span.text()
                    );
                })
                .collect(Collectors.toList());
    }

    // ==================== 窗口扩容 + 临近拼接（small-to-big） ====================

    /**
     * 从文档元数据解析分片位置 (documentId, chunkIndex)；缺失时返回 null（旧数据降级为自成一段）。
     */
    private ChunkPos parseChunkPos(Document doc) {
        if (doc.getMetadata() == null) {
            return null;
        }
        Object docId = doc.getMetadata().get("documentId");
        Object index = doc.getMetadata().get("chunkIndex");
        if (!(docId instanceof Number) || !(index instanceof Number)) {
            return null;
        }
        return new ChunkPos(((Number) docId).longValue(), ((Number) index).intValue());
    }

    /**
     * 窗口扩容 + 临近拼接（small-to-big）：只管几何——扩容、取邻居、按位置拼成完整片段。
     *
     * <p>解决切分边界把一句话/一个答案切成两半的问题：问「报销标准」，答案恰好骑在
     * chunk 3 和 chunk 4 边界上时，只给命中块会丢失另一半上下文。</p>
     *
     * <p>做法（利用 metadata 里的 documentId/chunkIndex 定位）：</p>
     * <ol>
     *   <li>解析精排后命中块的 (文档号, 序号)</li>
     *   <li>每个命中向前后各扩 {@link #WINDOW_RADIUS} 段，由 {@link MilvusChunkReader} 批量取回原文</li>
     *   <li>同文档里窗口相邻/重叠的命中拼成一条（临近窗口拼接），段内按序号升序拼原文；
     *       每段带上被吸收的命中名单（hits），证据留给复评方法算分</li>
     * </ol>
     *
     * <p>容错：位置解析不出来（旧数据无 chunkIndex）就把该块自成一段，不伤主链路。</p>
     */
    private List<PendingSpan> expandChunkWindows(List<Document> topDocs) {
        Map<ChunkPos, Document> hits = new LinkedHashMap<>();
        Set<ChunkPos> targets = new HashSet<>();
        for (Document doc : topDocs) {
            ChunkPos pos = parseChunkPos(doc);
            if (pos == null) {
                continue;
            }
            hits.put(pos, doc);
            for (int offset = -WINDOW_RADIUS; offset <= WINDOW_RADIUS; offset++) {
                int idx = pos.index() + offset;
                if (idx >= 0) {
                    targets.add(new ChunkPos(pos.documentId(), idx));
                }
            }
        }
        if (hits.isEmpty()) {
            // 位置解析不出：每个命中自成一段（吸收名单就是它自己，分数交给复评统一算）
            return topDocs.stream().map(doc -> new PendingSpan(doc.getText(), List.of(doc))).toList();
        }

        Map<ChunkPos, String> contentByPos = milvusChunkReader.selectByPositions(targets);

        // 临近窗口拼接：同文档里窗口相邻/重叠的命中并成一段（如命中 2 和 3 → 拼成 1~4 一整段），
        // span 先后顺序由它在精排结果里的排名决定（LinkedHashMap 遍历顺序即 rerank 顺序）
        List<PendingSpan> spans = new ArrayList<>();
        Set<ChunkPos> consumed = new HashSet<>();
        for (ChunkPos hit : hits.keySet()) {
            if (!consumed.add(hit)) {
                continue; // 已被前面的 span 吸收
            }
            long docId = hit.documentId();
            int start = hit.index();
            int end = hit.index();
            // 两个命中的窗口相邻/重叠 ⟺ 序号距离 ≤ 2×半径；逐步吸收直到收敛（传递性合并）
            boolean grew = true;
            while (grew) {
                grew = false;
                for (ChunkPos other : hits.keySet()) {
                    if (consumed.contains(other) || other.documentId() != docId) {
                        continue;
                    }
                    if (other.index() >= start - 2 * WINDOW_RADIUS && other.index() <= end + 2 * WINDOW_RADIUS) {
                        start = Math.min(start, other.index());
                        end = Math.max(end, other.index());
                        consumed.add(other);
                        grew = true;
                    }
                }
            }
            // span 定稿：窗口内真实存在的段按序号升序拼接（越界的邻居查不到就跳过），
            // 窗口遍历范围 ⊇ 吸收区间，被吸收的命中在这里收齐名单
            StringBuilder text = new StringBuilder();
            List<Document> absorbed = new ArrayList<>();
            for (int idx = Math.max(0, start - WINDOW_RADIUS); idx <= end + WINDOW_RADIUS; idx++) {
                String content = contentByPos.get(new ChunkPos(docId, idx));
                if (content != null) {
                    text.append(content);
                }
                Document hitDoc = hits.get(new ChunkPos(docId, idx));
                if (hitDoc != null) {
                    absorbed.add(hitDoc);
                }
            }
            if (text.length() == 0) {
                // Milvus 降级未取到窗口原文：用命中块原文兜底，保证 span 不为空
                text.append(absorbed.stream().map(Document::getText).collect(Collectors.joining("\n")));
            }
            spans.add(new PendingSpan(text.toString(), absorbed));
        }
        log.info("[Retrieval] 窗口扩容+临近拼接：{} 条命中 → {} 段（含相邻段）", hits.size(), spans.size());
        return spans;
    }

    // ==================== 合并后复评（CombMNZ 思想） ====================

    /**
     * 对拼接好的 span 重新打分：合并前的单个命中可能只被向量路命中，但合并后的 span 里
     * 可能同时含向量命中和关键词（倒排）命中——「语义像 + 字面像」双重佐证，比单路可信得多。
     * 落地为双路命中加成系数（启发式，可调）：
     * <pre>score = maxRerank × (双路命中 ? DUAL_PATH_BONUS : 1.0)，封顶 1.0</pre>
     * max 取命中里的最高分：答案主体在哪个命中，span 的证据强度就由谁代表。
     */
    private List<ExpandedSpan> reScoreSpans(List<PendingSpan> pending) {
        List<ExpandedSpan> scored = new ArrayList<>(pending.size());
        for (PendingSpan span : pending) {
            double maxRerank = 0;
            boolean vectorSourced = false;
            boolean keywordSourced = false;
            for (Document hit : span.hits()) {
                maxRerank = Math.max(maxRerank, rerankScoreOf(hit));
                vectorSourced |= Boolean.TRUE.equals(
                        hit.getMetadata() != null ? hit.getMetadata().get("src_vector") : null);
                keywordSourced |= Boolean.TRUE.equals(
                        hit.getMetadata() != null ? hit.getMetadata().get("src_keyword") : null);
            }
            boolean dualHit = vectorSourced && keywordSourced;
            double score = dualHit ? Math.min(maxRerank * DUAL_PATH_BONUS, 1.0) : maxRerank;
            scored.add(new ExpandedSpan(span.text(), score, dualHit, span.hits()));
        }
        log.info("[Retrieval] 合并后复评：{} 段，双路命中加成 {} 段（CombMNZ）",
                scored.size(), scored.stream().filter(ExpandedSpan::dualHit).count());
        return scored;
    }

    /** 读取命中块的 Rerank 相关性分数；未回写分数（API 降级）时视为中性 1.0，不误伤门控 */
    private double rerankScoreOf(Document doc) {
        if (doc.getMetadata() == null) {
            return 1.0;
        }
        Object score = doc.getMetadata().get("rerankScore");
        return score instanceof Number n ? n.doubleValue() : 1.0;
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

    /** 窗口拼接中间态：拼接后的文本 + 被吸收的命中块（证据留给复评算分） */
    private record PendingSpan(String text, List<Document> hits) {
    }

    /** 合并后复评结果：拼接片段 + 证据分数 + 是否双路命中 + 命中块名单（供引用来源回填） */
    private record ExpandedSpan(String text, double score, boolean dualHit, List<Document> hits) {
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
