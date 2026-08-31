package com.lion.agent.service.retriever;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多路召回组合器：Spring 启动时自动收集全部 {@link Retriever} Bean，
 * 对每条路执行召回后用 RRF（Reciprocal Rank Fusion）融合去重排序。
 *
 * <p>RRF 原理：不依赖各路的分数可比性，只用排名位置计算
 * {@code score = Σ 1/(k + rank)}（k=60 为平滑常数），天然适用于
 * 向量分、BM25 分、改写去重结果等不同量纲的召回。</p>
 */
@Slf4j
@Component
public class MultiRouteRetriever {

    /** RRF 平滑常数 */
    private static final double RRF_K = 60.0;

    private final List<Retriever> retrievers;

    public MultiRouteRetriever(List<Retriever> retrievers) {
        this.retrievers = retrievers;
        log.info("多路召回器注册 {} 路：{}", retrievers.size(),
                retrievers.stream().map(Retriever::name).toList());
    }

    /**
     * 多路召回并 RRF 融合
     *
     * @param query            检索问题
     * @param routeTopK        每路召回上限
     * @param filterExpression Milvus 标量过滤表达式，可为空
     * @param fuseTopN         融合后保留条数
     * @return 相关度降序的文档列表（最多 fuseTopN 条）
     */
    public List<Document> retrieve(String query, int routeTopK, String filterExpression, int fuseTopN) {
        List<List<Document>> routes = new ArrayList<>(retrievers.size());
        for (Retriever retriever : retrievers) {
            List<Document> docs = retriever.retrieve(query, routeTopK, filterExpression);
            log.info("[Retrieval] 召回路 [{}]：{} 条", retriever.name(), docs.size());
            if (!docs.isEmpty()) {
                routes.add(docs);
            }
        }
        if (routes.isEmpty()) {
            return List.of();
        }

        // RRF
        return rrfFusion(routes, fuseTopN);
    }

    /**
     * RRF 融合：多路排名按 {@code Σ 1/(k + rank)} 累加，按分数降序取前 topN
     */
    private List<Document> rrfFusion(List<List<Document>> routes, int topN) {
        Map<String, ScoredDocument> scoreMap = new LinkedHashMap<>();
        for (List<Document> route : routes) {
            for (int i = 0; i < route.size(); i++) {
                Document doc = route.get(i);
                if (doc == null || doc.getId() == null) {
                    continue;
                }
                double increment = 1.0 / (RRF_K + i + 1);
                ScoredDocument existing = scoreMap.get(doc.getId());
                if (existing == null) {
                    scoreMap.put(doc.getId(), new ScoredDocument(doc, increment));
                } else {
                    existing.score += increment;
                    // 同一分片被多路同时命中：合并来源标记，供合并后复评识别「双路命中」
                    mergeSourceFlags(existing.document, doc);
                }
            }
        }
        return scoreMap.values().stream()
                .sorted(Comparator.comparingDouble((ScoredDocument s) -> s.score).reversed())
                .limit(topN)
                .map(s -> s.document)
                .toList();
    }

    /**
     * 把 source 中的来源标记（src_vector/src_keyword）合并到 target 元数据：
     * 多路召回命中同一分片时，先到达的路会创建实例，后到达的路只累加分数，
     * 若不同步标记，复评会漏判「双路命中」。
     */
    private void mergeSourceFlags(Document target, Document source) {
        if (target.getMetadata() == null || source.getMetadata() == null) {
            return;
        }
        if (Boolean.TRUE.equals(source.getMetadata().get("src_vector"))) {
            target.getMetadata().put("src_vector", true);
        }
        if (Boolean.TRUE.equals(source.getMetadata().get("src_keyword"))) {
            target.getMetadata().put("src_keyword", true);
        }
    }

    /** 内部：文档 + 累计 RRF 分数 */
    private static class ScoredDocument {
        private final Document document;
        private double score;

        ScoredDocument(Document document, double score) {
            this.document = document;
            this.score = score;
        }
    }
}
