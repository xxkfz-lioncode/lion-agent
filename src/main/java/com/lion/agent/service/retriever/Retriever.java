package com.lion.agent.service.retriever;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 检索召回器接口：一条独立的召回通路。
 *
 * <p>每种召回策略实现一个 Retriever，由 {@link MultiRouteRetriever} 收集全部
 * 实现后并行执行、RRF 融合，实现"多路召回、多源互补"：
 * <ul>
 *   <li>{@link SemanticRetriever} —— 向量语义召回：捕获语义层面相关性</li>
 *   <li>{@link Bm25Retriever} —— BM25 关键词召回：捕获精确关键词匹配</li>
 *   <li>{@link QueryRewriteRetriever} —— 问题改写召回：多种表达覆盖不同角度</li>
 * </ul>
 * 新增召回策略 = 新增一个 {@code @Component} 实现类，自动被组合器收集，无需改动现有代码。
 */
public interface Retriever {

    /**
     * 执行召回：返回按相关度降序排列的文档（至多 topK 条）。
     *
     * <p>实现必须自身兜底：向量库/外部依赖异常时返回空列表（log 告警），
     * 不得把异常抛给组合器，保证单路故障不影响整体检索。</p>
     *
     * @param query            检索问题
     * @param topK             本路召回上限
     * @param filterExpression Milvus 标量过滤表达式（用户隔离/知识库范围），可为空
     * @return 相关度降序的文档列表，可能为空
     */
    List<Document> retrieve(String query, int topK, String filterExpression);

    /**
     * 召回策略名称（用于日志/监控展示）
     */
    String name();
}
