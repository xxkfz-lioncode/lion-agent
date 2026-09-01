package com.lion.agent.service.retriever;

import com.lion.agent.common.enums.VectorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BM25 关键词召回器：基于本地内存分片副本（{@link InMemoryChunkStore}）做 BM25 打分排序，
 * 捕获精确关键词匹配。与向量语义召回互补：语义召回擅长"意思相关但用词不同"，
 * BM25 擅长"关键词精确命中"。
 *
 * <p>实现说明：</p>
 * <ul>
 *   <li>分片数据来自本地内存（文档入库时同步、重启后自动种子加载），检索全程不依赖 Milvus 客户端</li>
 *   <li>filter 表达式在内存按 metadata 匹配（支持 {@code knowledgeId == X} 与 {@code (A || B)}）</li>
 *   <li>打分使用 BM25（k1=1.5, b=0.75），中文按单字切分，英文/数字按连续单词切分</li>
 * </ul>
 */
@Slf4j
@Component
public class Bm25Retriever implements Retriever {

    /** BM25 参数：词频饱和控制 */
    private static final double K1 = 1.5;

    /** BM25 参数：文档长度归一化强度（0=不看长度，1=完全归一化） */
    private static final double B = 0.75;

    /** 英文/数字词元 */
    private static final Pattern WORD_PATTERN = Pattern.compile("[a-zA-Z0-9_]{2,}");

    private final InMemoryChunkStore chunkStore;

    public Bm25Retriever(InMemoryChunkStore chunkStore) {
        this.chunkStore = chunkStore;
    }

    @Override
    public List<Document> retrieve(String query, int topK, String filterExpression) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<Document> chunks = chunkStore.all();
        if (chunks.isEmpty()) {
            log.info("[Retrieval] BM25 本地分片为空，跳过关键词召回");
            return List.of();
        }
        // 按 filter 过滤（内存 metadata 匹配，语义与向量库标量过滤一致）
        List<Document> filtered = new ArrayList<>(chunks.size());
        for (Document chunk : chunks) {
            if (matchesFilter(chunk.getMetadata(), filterExpression)) {
                filtered.add(chunk);
            }
        }
        if (filtered.isEmpty()) {
            return List.of();
        }

        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        // 预分词 + 平均文档长度
        Map<String, List<String>> docTerms = new HashMap<>(filtered.size());
        for (Document chunk : filtered) {
            docTerms.put(chunk.getId(), tokenize(chunk.getText()));
        }
        double avgDocLength = docTerms.values().stream()
                .mapToInt(List::size)
                .average().orElse(1.0);

        // BM25 打分
        List<ScoredDocument> scored = new ArrayList<>(filtered.size());
        for (Document chunk : filtered) {
            double score = 0;
            List<String> terms = docTerms.get(chunk.getId());
            for (String term : queryTerms) {
                int df = countDocumentsContaining(docTerms, term);
                if (df == 0) {
                    continue;
                }
                double idf = Math.log(1 + (docTerms.size() - df + 0.5) / (df + 0.5));
                int tf = countTermFrequency(terms, term);
                if (tf == 0) {
                    continue;
                }
                score += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * terms.size() / avgDocLength));
            }
            if (score > 0) {
                scored.add(new ScoredDocument(copyWithSrcFlag(chunk), score));
            }
        }

        scored.sort(Comparator.comparingDouble((ScoredDocument s) -> s.score).reversed());
        List<Document> result = new ArrayList<>(Math.min(topK, scored.size()));
        for (int i = 0; i < scored.size() && i < topK; i++) {
            result.add(scored.get(i).document);
        }
        return result;
    }

    @Override
    public String name() {
        return "BM25关键词召回";
    }

    // ==================== 内存 filter 过滤 ====================

    /**
     * 内存 filter 匹配：表达式按 CNF 解析——
     * {@code &&} 组合的多个条件组必须全部满足（如 {@code type == 'kb' && (knowledgeId == 1 || knowledgeId == 2)}），
     * 每组内 {@code ||} 分支任一满足即可（如 {@code (knowledgeId == 1 || knowledgeId == 2)}）；
     * 空表达式放行所有分片。遇到不支持的操作符按不匹配处理（保守，不误召回越权数据）。
     */
    private boolean matchesFilter(Map<String, Object> metadata, String filterExpression) {
        if (filterExpression == null || filterExpression.isBlank()) {
            return true;
        }
        for (String andPart : filterExpression.split("&&")) {
            if (!matchesOrGroup(metadata, andPart.trim())) {
                return false;
            }
        }
        return true;
    }

    /** OR 条件组匹配：组内任一条件命中即通过 */
    private boolean matchesOrGroup(Map<String, Object> metadata, String group) {
        String inner = group.startsWith("(") && group.endsWith(")")
                ? group.substring(1, group.length() - 1) : group;
        for (String branch : inner.split("\\|\\|")) {
            if (matchesCondition(metadata, branch.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单条件匹配：field == value（value 支持数字与引号包裹的字符串）。
     */
    private boolean matchesCondition(Map<String, Object> metadata, String condition) {
        if (metadata == null || condition.isEmpty()) {
            return false;
        }
        int idx = condition.indexOf("==");
        if (idx < 0) {
            return false;
        }
        String field = condition.substring(0, idx).trim();
        String rawValue = condition.substring(idx + 2).trim();
        Object actual = metadata.get(field);
        if (actual == null) {
            return false;
        }
        if ((rawValue.startsWith("'") && rawValue.endsWith("'"))
                || (rawValue.startsWith("\"") && rawValue.endsWith("\""))) {
            return actual.toString().equals(rawValue.substring(1, rawValue.length() - 1));
        }
        try {
            long expected = Long.parseLong(rawValue);
            return actual instanceof Number && ((Number) actual).longValue() == expected;
        } catch (NumberFormatException e) {
            return actual.toString().equals(rawValue);
        }
    }

    // ==================== 分词与 BM25 统计 ====================

    /**
     * 分词：中文按单字切分（无词典依赖），英文/数字按连续单词切分（转小写）
     */
    private List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        Matcher matcher = WORD_PATTERN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group().toLowerCase());
        }
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                tokens.add(String.valueOf(c));
            }
        }
        return tokens;
    }

    /** 包含某词元的文档数 */
    private int countDocumentsContaining(Map<String, List<String>> docTerms, String term) {
        int count = 0;
        for (List<String> terms : docTerms.values()) {
            if (terms.contains(term)) {
                count++;
            }
        }
        return count;
    }

    /** 词元在文档中的出现次数 */
    private int countTermFrequency(List<String> terms, String term) {
        int count = 0;
        for (String t : terms) {
            if (t.equals(term)) {
                count++;
            }
        }
        return count;
    }

    /** 拷贝分片并打上关键词来源标记（不污染内存共享副本，供复评识别「双路命中」） */
    private Document copyWithSrcFlag(Document chunk) {
        Map<String, Object> meta = chunk.getMetadata() == null ? new HashMap<>() : new HashMap<>(chunk.getMetadata());
        meta.put("src_keyword", true);
        return new Document(chunk.getId(), chunk.getText(), meta);
    }

    /** 打分结果 */
    private record ScoredDocument(Document document, double score) {
    }
}
