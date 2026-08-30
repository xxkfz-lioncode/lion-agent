package com.lion.agent.splitter;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 语义切分策略：先按句子切，再通过 embedding 计算相邻句相似度，
 * 在语义断裂处（相似度低于阈值）切分，保证块内语义连贯。
 */
@Component
public class SemanticSplitterStrategy implements DocumentSplitterStrategy {

    private final EmbeddingModel embeddingModel;

    /** 相似度阈值，低于该值视为语义断点 */
    private final double similarityThreshold;

    /** 单块最大字符数 */
    private final int maxChunkChars;

    public SemanticSplitterStrategy(
            EmbeddingModel embeddingModel,
            @Value("${lion.splitter.semantic.threshold:0.7}") double similarityThreshold,
            @Value("${lion.splitter.semantic.max-chunk-chars:3000}") int maxChunkChars) {
        this.embeddingModel = embeddingModel;
        this.similarityThreshold = similarityThreshold;
        this.maxChunkChars = maxChunkChars;
    }

    @Override
    public SplitterType type() {
        return SplitterType.SEMANTIC;
    }

    @Override
    public List<Document> split(List<Document> docs) {
        return new SemanticTextSplitter(embeddingModel, similarityThreshold, maxChunkChars).split(docs);
    }

    /**
     * 语义切分：先按句子切，再通过 embedding 计算相邻句相似度，
     * 在语义断裂处（相似度低于阈值）切分，保证块内语义连贯
     */
    private static class SemanticTextSplitter extends TextSplitter {
        private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？!?.；;])\\s*");

        private final EmbeddingModel embeddingModel;
        private final double similarityThreshold;
        private final int maxChunkChars;

        SemanticTextSplitter(EmbeddingModel embeddingModel, double similarityThreshold, int maxChunkChars) {
            this.embeddingModel = embeddingModel;
            this.similarityThreshold = similarityThreshold;
            this.maxChunkChars = maxChunkChars;
        }

        @Override
        protected List<String> splitText(String text) {
            List<String> sentences = Arrays.stream(SENTENCE_SPLIT.split(text))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            if (sentences.isEmpty()) {
                return List.of();
            }
            if (sentences.size() == 1) {
                return sentences;
            }

            // 批量向量化（DashScope embedding 单次批量上限 10 条，分批调用）
            final int batchSize = 10;
            List<float[]> vectors = new ArrayList<>(sentences.size());
            for (int i = 0; i < sentences.size(); i += batchSize) {
                List<String> batch = sentences.subList(i, Math.min(i + batchSize, sentences.size()));
                vectors.addAll(embeddingModel.embed(batch));
            }

            List<String> chunks = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < sentences.size(); i++) {
                // 超长强制切分，避免单块过大
                if (current.length() > 0 && current.length() + sentences.get(i).length() > maxChunkChars) {
                    chunks.add(current.toString().trim());
                    current.setLength(0);
                }
                current.append(sentences.get(i));
                // 与下一句相似度过低 → 语义边界，切分
                if (i + 1 < sentences.size()
                        && cosineSimilarity(vectors.get(i), vectors.get(i + 1)) < similarityThreshold) {
                    chunks.add(current.toString().trim());
                    current.setLength(0);
                }
            }
            if (current.length() > 0) {
                chunks.add(current.toString().trim());
            }
            return chunks;
        }

        private static float cosineSimilarity(float[] a, float[] b) {
            double dot = 0, normA = 0, normB = 0;
            for (int i = 0; i < a.length; i++) {
                dot += a[i] * b[i];
                normA += a[i] * a[i];
                normB += b[i] * b[i];
            }
            if (normA == 0 || normB == 0) {
                return 0f;
            }
            return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
        }
    }
}
