package com.lion.agent.service.retriever;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向量语义召回器：基于 Embedding 余弦相似度召回语义相关的分片。
 *
 * <p>向量库（Milvus）异常时降级返回空列表，不阻断多路检索主流程。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticRetriever implements Retriever {

    private final VectorStore vectorStore;

    @Override
    public List<Document> retrieve(String query, int topK, String filterExpression) {
        try {
            return vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .filterExpression(filterExpression)
                    .build());
        } catch (Exception e) {
            log.warn("[Retrieval] 向量语义召回失败 query={}: {}", truncate(query, 30), e.getMessage());
            return List.of();
        }
    }

    @Override
    public String name() {
        return "向量语义召回";
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
