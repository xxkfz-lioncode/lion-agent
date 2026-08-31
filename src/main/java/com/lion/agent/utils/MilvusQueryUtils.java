package com.lion.agent.utils;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.QueryResults;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Milvus 原生客户端查询工具类。
 *
 * <p>封装 {@link MilvusServiceClient} 的常用查询操作：全量/条件查询（超上限自动 offset 翻页）、
 * 列式 gRPC 结果 → 按行 {@link QueryResultsWrapper.RowRecord} 转换、metadata JSON 解析、
 * 行记录转 Spring AI {@link Document}。</p>
 *
 * <p>客户端通过 {@link ObjectProvider} 懒获取，Milvus 不可用或查询失败时所有方法安全返回空结果、
 * 不抛异常，供检索等旁路链路优雅降级。</p>
 */
@Slf4j
@Component
public class MilvusQueryUtils {

    /** Milvus 单次查询条数上限（服务端默认 max limit = 16384） */
    public static final long DEFAULT_QUERY_LIMIT = 16384L;

    private final ObjectProvider<MilvusServiceClient> clientProvider;

    public MilvusQueryUtils(ObjectProvider<MilvusServiceClient> clientProvider) {
        this.clientProvider = clientProvider;
    }

    /** 获取客户端；不可用时返回 empty（不抛异常） */
    public Optional<MilvusServiceClient> getClient() {
        MilvusServiceClient client = clientProvider.getIfAvailable();
        return client == null ? Optional.empty() : Optional.of(client);
    }

    /** Milvus 客户端是否可用 */
    public boolean isAvailable() {
        return getClient().isPresent();
    }

    /**
     * 全量查询指定字段，结果超过单次上限时自动 offset 翻页。
     * 等价于 {@code queryAll(collectionName, outFields, null)}，即不过滤全量拉取。
     *
     * @param collectionName 集合名
     * @param outFields      需要返回的字段，如 {@code List.of("doc_id", "content", "metadata")}
     * @return 全部行记录；Milvus 不可用或失败时返回空列表
     */
    public List<QueryResultsWrapper.RowRecord> queryAll(String collectionName, List<String> outFields) {
        return queryAll(collectionName, outFields, null);
    }

    /**
     * 带过滤条件全量查询，结果超过单次上限时自动 offset 翻页（expr 全程生效）。
     * 注意：数据量极大时 offset 翻页受服务端 max_offset 限制，超限部分会缺失（仅告警不报错）。
     *
     * @param collectionName 集合名
     * @param outFields      需要返回的字段
     * @param expr           过滤表达式，可为 null/空（等效全量）。
     *                       metadata 以 JSON 存储，按字段过滤写法如
     *                       {@code "metadata['knowledgeId'] == 5"}、
     *                       {@code "metadata['knowledgeId'] in [1, 2, 3]"}
     * @return 符合条件的行记录；Milvus 不可用或失败时返回空列表
     */
    public List<QueryResultsWrapper.RowRecord> queryAll(String collectionName, List<String> outFields, String expr) {
        List<QueryResultsWrapper.RowRecord> all = new ArrayList<>();
        long offset = 0;
        while (true) {
            List<QueryResultsWrapper.RowRecord> page = query(collectionName, outFields, expr, DEFAULT_QUERY_LIMIT, offset);
            if (page.isEmpty()) {
                break;
            }
            all.addAll(page);
            if (page.size() < DEFAULT_QUERY_LIMIT) {
                break;
            }
            offset += DEFAULT_QUERY_LIMIT;
        }
        return all;
    }

    /**
     * 带过滤表达式查询（expr 为空时等效全量），限量返回。
     *
     * @param collectionName 集合名
     * @param outFields      需要返回的字段
     * @param expr           过滤表达式，如 {@code "documentId == 1001"}，可为 null/空
     * @param limit          单次查询条数上限（≤ 16384）
     * @return 行记录列表；Milvus 不可用或失败时返回空列表
     */
    public List<QueryResultsWrapper.RowRecord> query(String collectionName, List<String> outFields,
                                                     String expr, long limit) {
        return query(collectionName, outFields, expr, limit, 0L);
    }

    /** 内部实现：带偏移量的单次查询 */
    private List<QueryResultsWrapper.RowRecord> query(String collectionName, List<String> outFields,
                                                      String expr, long limit, long offset) {
        Optional<MilvusServiceClient> clientOpt = getClient();
        if (clientOpt.isEmpty()) {
            log.warn("[MilvusQueryUtils] Milvus 客户端不可用，跳过查询 collection={}", collectionName);
            return List.of();
        }
        try {
            QueryParam.Builder builder = QueryParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withOutFields(outFields)
                    .withLimit(limit);
            if (expr != null && !expr.isBlank()) {
                builder.withExpr(expr);
            }
            if (offset > 0) {
                builder.withOffset(offset);
            }
            QueryResults results = clientOpt.get().query(builder.build()).getData();
            if (results == null) {
                return List.of();
            }
            // gRPC QueryResults 是列式 protobuf，包一层 QueryResultsWrapper 才能按行读取
            return new QueryResultsWrapper(results).getRowRecords();
        } catch (Exception e) {
            log.warn("[MilvusQueryUtils] 查询失败 collection={}, expr={}: {}", collectionName, expr, e.getMessage());
            return List.of();
        }
    }

    /**
     * 行记录转 Document（约定字段：doc_id 主键、content 原文、metadata JSON 字符串）。
     * 缺字段或内容为空的记录返回 null。
     */
    public Document toDocument(QueryResultsWrapper.RowRecord row) {
        if (row == null) {
            return null;
        }
        Object id = row.get("doc_id");
        Object content = row.get("content");
        if (id == null || content == null) {
            return null;
        }
        String text = String.valueOf(content);
        if (text.isBlank()) {
            return null;
        }
        return new Document(String.valueOf(id), text, parseMetadata(row.get("metadata")));
    }

    /** 批量行记录转 Document，自动跳过空行/缺字段行 */
    public List<Document> toDocuments(List<QueryResultsWrapper.RowRecord> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Document> docs = new ArrayList<>(rows.size());
        for (QueryResultsWrapper.RowRecord row : rows) {
            Document doc = toDocument(row);
            if (doc != null) {
                docs.add(doc);
            }
        }
        return docs;
    }

    /** 解析 Milvus metadata 字段（JSON 字符串），解析失败返回空 Map */
    public static Map<String, Object> parseMetadata(Object metadataObj) {
        if (metadataObj == null) {
            return Map.of();
        }
        try {
            JSONObject json = JSONUtil.parseObj(String.valueOf(metadataObj));
            Map<String, Object> map = new HashMap<>();
            json.forEach(map::put);
            return map;
        } catch (Exception e) {
            log.debug("[MilvusQueryUtils] metadata 解析失败: {}", e.getMessage());
            return Map.of();
        }
    }
}
