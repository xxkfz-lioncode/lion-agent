package com.lion.agent.service.retriever;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BM25 关键词召回器：从 Milvus 拉取过滤范围内的全量分片到内存，
 * 按 BM25 算法（k1=1.5, b=0.75）对查询做关键词打分排序，捕获精确关键词匹配。
 *
 * <p>与向量语义召回互补：语义召回擅长"意思相关但用词不同"，BM25 擅长"关键词精确命中"。</p>
 *
 * <p>实现说明：</p>
 * <ul>
 *   <li>Milvus 客户端不可用（未注入 Bean）时禁用本路，返回空列表</li>
 *   <li>全量数据按 filter 缓存（TTL 5 分钟），文档变更后最多延迟 5 分钟生效</li>
 *   <li>Milvus 单次查询上限 16384 条，超出部分请配合分页改造</li>
 * </ul>
 */
@Slf4j
@Component
public class Bm25Retriever implements Retriever {

    /** BM25 参数：词频饱和控制 */
    private static final double K1 = 1.5;

    /** BM25 参数：文档长度归一化强度（0=不看长度，1=完全归一化） */
    private static final double B = 0.75;

    /** 缓存过期时间（毫秒） */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;

    /** Milvus 单次查询上限 */
    private static final int MILVUS_QUERY_LIMIT = 16384;

    /** 英文/数字词元 */
    private static final Pattern WORD_PATTERN = Pattern.compile("[a-zA-Z0-9_]{2,}");

    private final ObjectProvider<MilvusClientV2> milvusClientProvider;
    private final String collectionName;

    /** 按 filter 缓存的 chunk 数据 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** Milvus 客户端是否可用（启动时探测一次，失效后自动降级） */
    private volatile boolean available;

    public Bm25Retriever(ObjectProvider<MilvusClientV2> milvusClientProvider,
                         @Value("${spring.ai.vectorstore.milvus.collection-name:lion_agent_knowledge}") String collectionName) {
        this.milvusClientProvider = milvusClientProvider;
        this.collectionName = collectionName;
        this.available = milvusClientProvider.getIfAvailable() != null;
        if (!available) {
            log.warn("[Retrieval] Milvus 客户端不可用，BM25 关键词召回禁用");
        }
    }

    @Override
    public List<Document> retrieve(String query, int topK, String filterExpression) {
        if (!available || query == null || query.isBlank()) {
            return List.of();
        }
        List<ChunkRecord> chunks = loadChunks(filterExpression);
        if (chunks.isEmpty()) {
            return List.of();
        }

        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        Map<String, List<String>> docTerms = new HashMap<>();
        for (ChunkRecord chunk : chunks) {
            docTerms.put(chunk.id, tokenize(chunk.text));
        }
        double avgDocLength = docTerms.values().stream()
                .mapToInt(List::size)
                .average().orElse(1.0);

        // BM25 打分
        List<ScoredDocument> scored = new ArrayList<>(chunks.size());
        for (ChunkRecord chunk : chunks) {
            double score = 0;
            List<String> terms = docTerms.get(chunk.id);
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
                scored.add(new ScoredDocument(chunk.toDocument(), score));
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

    /**
     * 主动刷新缓存（文档入库/删除后可调用，无需等待 TTL）
     */
    public void refresh() {
        cache.clear();
        log.info("[Retrieval] BM25 缓存已刷新");
    }

    // ==================== 数据加载与缓存 ====================

    /** 从 Milvus 拉取 filter 范围内的全量分片，带 TTL 缓存 */
    private List<ChunkRecord> loadChunks(String filterExpression) {
        String key = filterExpression == null ? "" : filterExpression;
        CacheEntry entry = cache.get(key);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.loadedAt < CACHE_TTL_MS) {
            return entry.chunks;
        }
        try {
            MilvusClientV2 client = milvusClientProvider.getIfAvailable();
            if (client == null) {
                available = false;
                return List.of();
            }
            QueryResp resp = client.query(QueryReq.builder()
                    .collectionName(collectionName)
                    .filter(key.isEmpty() ? null : key)
                    .outputFields(List.of("id", "content", "metadata"))
                    .limit(MILVUS_QUERY_LIMIT)
                    .build());
            List<QueryResp.QueryResult> results = resp.getQueryResults();
            if (results == null) {
                return List.of();
            }
            List<ChunkRecord> chunks = new ArrayList<>(results.size());
            for (QueryResp.QueryResult result : results) {
                Map<String, Object> entity = result.getEntity();
                String id = String.valueOf(entity.get("id"));
                Object contentObj = entity.get("content");
                String content = contentObj == null ? "" : String.valueOf(contentObj);
                if (id == null || id.isBlank() || content.isBlank()) {
                    continue;
                }
                chunks.add(new ChunkRecord(id, content, parseMetadata(entity.get("metadata"))));
            }
            cache.put(key, new CacheEntry(chunks, now));
            log.info("[Retrieval] BM25 加载分片 {} 条（filter={}）", chunks.size(), key.isEmpty() ? "无" : key);
            return chunks;
        } catch (Exception e) {
            log.warn("[Retrieval] BM25 加载分片失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 解析 Milvus metadata 字段（JSON 字符串） */
    private Map<String, Object> parseMetadata(Object metadataObj) {
        if (metadataObj == null) {
            return Map.of();
        }
        try {
            JSONObject json = JSONUtil.parseObj(String.valueOf(metadataObj));
            Map<String, Object> map = new HashMap<>();
            json.forEach((k, v) -> map.put(k, v));
            return map;
        } catch (Exception e) {
            log.debug("[Retrieval] BM25 分片 metadata 解析失败: {}", e.getMessage());
            return Map.of();
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

    // ==================== 内部数据结构 ====================

    /** 分片记录：id + 文本 + 元数据 */
    private record ChunkRecord(String id, String text, Map<String, Object> metadata) {
        Document toDocument() {
            return new Document(id, text, metadata);
        }
    }

    /** 打分结果 */
    private record ScoredDocument(Document document, double score) {
    }

    /** 缓存条目 */
    private record CacheEntry(List<ChunkRecord> chunks, long loadedAt) {
    }
}
