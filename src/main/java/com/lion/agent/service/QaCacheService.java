package com.lion.agent.service;

import com.lion.agent.common.enums.VectorType;
import com.lion.agent.common.utils.LazyMilvusVectorStoreUtils;
import io.milvus.client.MilvusServiceClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 语义缓存
 *
 * <p>核心思路：每次问答完成后把「问题 + 回答」写入向量库（独立 Milvus collection
 * lion_agent_qa_cache，与知识库 lion_agent_knowledge 物理隔离）；新问题进来先做向量检索，
 * 相似度达到阈值即视为与历史问题重复，直接复用历史回答，跳过模型调用——省 token、秒回、
 * 答案与历史一致。</p>
 *
 * <p>重要：这里不把 VectorStore 注册为 Spring Bean，而是内部直接持有独立的
 * {@link MilvusVectorStore} 实例（懒加载：首次使用时才构建 + 建表）。否则会顶掉 Spring AI
 * 自动配置的默认 VectorStore（指向知识库 collection），导致知识库 RAG / 工具索引全部错位。</p>
 *
 * <p>降级策略：Milvus 不可用 / 检索异常时仅记录日志并返回 null，主流程不受影响；
 * 初始化失败会在下次使用时自动重试。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaCacheService {

    /** Milvus 建表时 doc_id 为 VarChar 主键，max_length=36，因此 doc id 必须 ≤ 36 字符 */
    private static final int MAX_DOC_ID_LENGTH = 36;

    private final MilvusServiceClient milvusClient;
    private final EmbeddingModel embeddingModel;

    /** 缓存开关 */
    @Value("${lion.qa-cache.enabled:true}")
    private boolean enabled;

    /** 语义缓存专用 collection 名（与知识库隔离） */
    @Value("${lion.qa-cache.collection-name:lion_agent_qa_cache}")
    private String collectionName;

    /** 向量维度，必须与 embedding 模型输出一致（text-embedding-v3 默认 1024） */
    @Value("${lion.qa-cache.embedding-dimension:1024}")
    private int embeddingDimension;

    /**
     * 命中相似度阈值：text-embedding-v3 向量未归一化，短句相似度偏低（完全相同文本实测约 0.83），
     * 阈值 0.75-0.80 较合理；过高（0.9+）会导致永远命中不了缓存
     */
    @Value("${lion.qa-cache.threshold:0.78}")
    private double threshold;

    /** 每次检索取 Top-N 条候选（取最高相似度一条） */
    @Value("${lion.qa-cache.top-k:1}")
    private int topK;

    // ==================== 写入侧闸门：只缓存"适合缓存"的问答 ====================
    // 语义缓存的前提是"同一个问题在任何时刻的答案都基本一致"。时效类（当前时间/天气/股价）、
    // 依赖上下文的追问（"再详细点""那第二个呢"）、拒答/兜底话术都不满足该前提，缓存了只会
    // 让后续相似问题拿到过期或错误的答案，因此写入前统一拦截（拦截只损失一次缓存收益，不影响主流程）。

    /** 允许缓存的会话类型（逗号分隔）：chat=一般对话、kb=知识库问答；留空或 all 表示不限制 */
    @Value("${lion.qa-cache.cacheable-chat-types:chat}")
    private String cacheableChatTypes;

    /**
     * 不适合缓存的问题关键词（时效 / 实时 / 易变数据），逗号分隔，命中任意一个即不缓存。
     * 例：「当前时间」「现在的天气」这类问题每次答案都不同，缓存只会造成"永远返回旧值"。
     */
    @Value("${lion.qa-cache.deny-question-keywords:当前时间,现在时间,现在几点,几点了,现在几号,今天几号,今天星期,星期几,周几,今天日期,当前日期,今天是,今天,今晚,明天,昨天,今年,本月,实时,此刻,当前,最新,最近,刚刚,天气,气温,温度,预报,下雨,台风,股价,汇率,油价,金价,余额,积分,库存,余量,剩余,倒计时,进度,在线人数,排队,新闻,热搜,热榜}")
    private String denyQuestionKeywords;

    /** 以这些词开头的问题依赖上文（代词 / 序数 / 承接），独立缓存后复用会张冠李戴，不缓存 */
    @Value("${lion.qa-cache.deny-reference-prefixes:它,他,她,这,那,再,还,其,上面,下面,接着,继续,然后,另外,其他,还有,以及,第一个,第二个,第三个,前一个,后一个,上一个,下一个,最后一个}")
    private String denyReferencePrefixes;

    /** 出现在任意位置即说明依赖上文，不缓存 */
    @Value("${lion.qa-cache.deny-reference-keywords:刚才,刚刚,上一句,上一条,上一个问题,前一个回答,前面说的,你刚才,上面说的,你上面,换个说法,再详细,详细点,具体点,展开说说,举个例子,继续说,还有吗}")
    private String denyReferenceKeywords;

    /** 不适合缓存的回答关键词（拒答 / 兜底话术），命中则不缓存，避免"抱歉"被复用 */
    @Value("${lion.qa-cache.deny-answer-keywords:抱歉,无法回答,不能回答,我不知道,我不清楚,我不确定,暂无相关信息,未找到,没有找到,找不到,稍后再试,请稍后,无法获取,暂时无法,未能获取}")
    private String denyAnswerKeywords;

    /** 回答中携带"具体时刻"（如 2026-08-23 10:33:06）说明答案天生带时效性，不缓存 */
    @Value("${lion.qa-cache.deny-answer-timestamp:true}")
    private boolean denyAnswerTimestamp;

    /** 过短（如"你好""在吗"）或过长（多半是渲染后的长 prompt）的问题不缓存 */
    @Value("${lion.qa-cache.min-question-length:4}")
    private int minQuestionLength;

    @Value("${lion.qa-cache.max-question-length:500}")
    private int maxQuestionLength;

    /** 过短的回答复用价值低（多为"好的""收到"），不缓存 */
    @Value("${lion.qa-cache.min-answer-length:10}")
    private int minAnswerLength;

    /** 时间戳：2026-08-23 / 2026-08-23 10:33 / 2026/08/23 10:33:06 */
    private static final Pattern TIMESTAMP_PATTERN =
            Pattern.compile("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}([ T]\\d{1,2}:\\d{2}(:\\d{2})?)?");

    private Set<String> cacheableChatTypeSet;
    private Set<String> denyQuestionKeywordSet;
    private Set<String> denyReferencePrefixSet;
    private Set<String> denyReferenceKeywordSet;
    private Set<String> denyAnswerKeywordSet;

    /** 把逗号分隔的 yml 配置切成集合（@Value 注入完成后执行，业务方法直接用集合） */
    @PostConstruct
    public void initCacheRules() {
        this.cacheableChatTypeSet = splitToSet(cacheableChatTypes);
        this.denyQuestionKeywordSet = splitToSet(denyQuestionKeywords);
        this.denyReferencePrefixSet = splitToSet(denyReferencePrefixes);
        this.denyReferenceKeywordSet = splitToSet(denyReferenceKeywords);
        this.denyAnswerKeywordSet = splitToSet(denyAnswerKeywords);
    }

    /** 语义缓存专用向量存储持有器（懒加载建库，独立 collection，非 Spring Bean） */
    private volatile LazyMilvusVectorStoreUtils cacheStore;

    /**
     * 懒加载获取缓存向量库：首次调用（或上次失败后）才构建并建表。
     *
     * <p>为什么需要手动建表：Spring AI 2.x 的 {@link MilvusVectorStore} 建表
     * （createCollection + createIndex + loadCollection）发生在 {@code afterPropertiesSet()}
     * 中，这是 Spring bean 生命周期回调，只有容器管理的 bean 才会被自动调用；而本类刻意不把
     * 它注册成 Bean（否则会顶掉自动配置的默认 VectorStore、破坏知识库 RAG），所以必须手动触发。
     * 懒加载 + 建表 + 失败降级/重试统一收敛在 {@link LazyMilvusVectorStoreUtils}，此处仅在首次访问时
     * 组装参数（@Value 注入完成后才可用）；业务方法无需任何前置初始化步骤，失败也会在下次
     * 调用时自动自愈。</p>
     */
    private MilvusVectorStore store() {
        LazyMilvusVectorStoreUtils holder = cacheStore;
        if (holder == null) {
            synchronized (this) {
                holder = cacheStore;
                if (holder == null) {
                    holder = new LazyMilvusVectorStoreUtils(milvusClient, embeddingModel,
                            collectionName, embeddingDimension, "语义缓存");
                    cacheStore = holder;
                }
            }
        }
        return holder.getOrNull();
    }

    /** 命中结果：answer 为历史回答全文，askedAt 为提问时间描述（如"3个月前"） */
    public record Hit(String answer, String askedAt) {
    }

    /**
     * 检索语义缓存：query 与历史问题相似度达到阈值则返回命中结果，否则返回 null。
     * 按 userId 隔离，不同用户的缓存互不可见。
     */
    public Hit search(Long userId, String query) {
        if (!enabled || !StringUtils.hasText(query)) {
            return null;
        }
        String filter = "type == '" + VectorType.QA_CACHE.getValue() + "' && userId == '" + userId + "'";
        try {
            List<Document> docs = store().similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(threshold)
                    .filterExpression(filter)
                    .build());
            log.info("语义缓存检索 query={} filter={} 召回 {} 条", truncate(query, 30), filter, docs.size());
            if (docs.isEmpty()) {
                return null;
            }
            // 打印召回结果，方便观察相似度分布
            for (Document doc : docs) {
                log.info("语义缓存召回候选 id={} question={} metadataKeys={}", doc.getId(),
                        truncate((String) doc.getMetadata().get("question"), 30),
                        doc.getMetadata().keySet());
            }
            Map<String, Object> metadata = docs.get(0).getMetadata();
            String answer = (String) metadata.get("answer");
            if (!StringUtils.hasText(answer)) {
                return null;
            }
            Object epoch = metadata.get("createdAtEpoch");
            long askedAtEpoch = epoch instanceof Number ? ((Number) epoch).longValue() : 0L;
            return new Hit(answer, formatAskedAt(askedAtEpoch));
        } catch (Exception e) {
            // 降级：Milvus 不可用时跳过缓存，走正常模型调用
            cacheStore.reset(); // 检索失败大概率是 collection 丢失 / Milvus 重启，下次自动重建
            log.warn("语义缓存检索失败（跳过缓存）", e);
            return null;
        }
    }


    /**
     * 判断一条问答是否"适合缓存"（写入侧闸门）。
     *
     * <p>语义缓存的前提是：同一个问题在任何时刻、任何上下文下，答案都基本一致。
     * 因此以下几类一律不缓存（漏缓存只是多花一次 token，误缓存则会给出过期/错误答案）：
     * <ul>
     *   <li>会话类型不在白名单（默认只缓存 chat；kb 答案随知识库更新变化，可按需放开）；</li>
     *   <li>问题含时效 / 实时关键词：当前时间、现在几点、天气、股价、余额…；</li>
     *   <li>问题依赖上下文：以「它/这/再/第一个」开头，或含「刚才/再详细点/换个说法」；</li>
     *   <li>问题过短（"你好"）或过长（多半是渲染后的 KB prompt）；</li>
     *   <li>回答过短、含拒答话术（"抱歉，我无法回答"）或含具体时刻。</li>
     * </ul>
     *
     * @param chatType 会话类型（chat / kb），为空表示不限制
     * @return true 表示可写入缓存
     */
    public boolean isCacheable(String chatType, String question, String answer) {
        if (!enabled || !StringUtils.hasText(question) || !StringUtils.hasText(answer)) {
            return false;
        }
        String q = question.trim();
        String a = answer.trim();

        if (!cacheableChatTypeSet.isEmpty() && StringUtils.hasText(chatType)
                && !cacheableChatTypeSet.contains(chatType.trim().toLowerCase(Locale.ROOT))) {
            log.info("语义缓存跳过：会话类型不在白名单 chatType={}", chatType);
            return false;
        }
        if (q.length() < minQuestionLength || q.length() > maxQuestionLength) {
            log.info("语义缓存跳过：问题长度 {} 不在 [{},{}] 区间 question={}", q.length(),
                    minQuestionLength, maxQuestionLength, truncate(q, 30));
            return false;
        }
        if (a.length() < minAnswerLength) {
            log.info("语义缓存跳过：回答过短 length={}", a.length());
            return false;
        }
        String timeHit = firstHit(denyQuestionKeywordSet, q);
        if (timeHit != null) {
            log.info("语义缓存跳过：问题含时效/实时关键词「{}」question={}", timeHit, truncate(q, 30));
            return false;
        }
        String prefixHit = firstPrefixHit(denyReferencePrefixSet, q);
        if (prefixHit != null) {
            log.info("语义缓存跳过：问题以指代词「{}」开头，依赖上下文 question={}", prefixHit, truncate(q, 30));
            return false;
        }
        String refHit = firstHit(denyReferenceKeywordSet, q);
        if (refHit != null) {
            log.info("语义缓存跳过：问题含上下文依赖词「{}」question={}", refHit, truncate(q, 30));
            return false;
        }
        String answerHit = firstHit(denyAnswerKeywordSet, a);
        if (answerHit != null) {
            log.info("语义缓存跳过：回答含拒答/兜底话术「{}」", answerHit);
            return false;
        }
        if (denyAnswerTimestamp && TIMESTAMP_PATTERN.matcher(a).find()) {
            log.info("语义缓存跳过：回答含具体时刻，天然带时效性");
            return false;
        }
        return true;
    }

    /**
     * 写入一条问答缓存（Milvus 异常仅告警，不影响主流程）。
     * <p>写入前先过 {@link #isCacheable(String, String, String)} 闸门，只缓存适合缓存的问答。</p>
     *
     * <p>doc id 用去横线的 UUID（32 字符），必须 ≤36（Milvus VarChar 主键 max_length）。
     * userId / conversationId 等业务信息全部放 metadata，检索与清理靠 filterExpression
     * （type + userId）定位，不依赖 doc id 可读性。</p>
     */
    public void cache(Long userId, Long conversationId, String chatType, String question, String answer) {
        if (!isCacheable(chatType, question, answer)) {
            return;
        }
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("type", VectorType.QA_CACHE.getValue());
            // userId 存字符串，与 filterExpression 中的字符串比较保持一致
            metadata.put("userId", String.valueOf(userId));
            metadata.put("conversationId", conversationId);
            // 会话类型：便于后续按来源做统计 / 定向清理（默认只缓存 chat）
            metadata.put("chatType", chatType == null ? "" : chatType);
            metadata.put("question", question);
            metadata.put("answer", answer);
            metadata.put("createdAtEpoch", System.currentTimeMillis());
            String docId = UUID.randomUUID().toString().replace("-", "");
            if (docId.length() > MAX_DOC_ID_LENGTH) {
                docId = docId.substring(0, MAX_DOC_ID_LENGTH);
            }
            Document doc = Document.builder()
                    .id(docId)
                    .text(question)
                    .metadata(metadata)
                    .build();
            store().add(List.of(doc));
            log.info("已写入语义缓存 userId={} docId={} question={}", userId, docId, truncate(question, 30));
        } catch (Exception e) {
            // 打完整堆栈，方便定位（doc_id 超长 / 维度不匹配 / embedding 失败等）
            cacheStore.reset(); // 例如 collection 被删 / Milvus 重启，下次写入时重新建表
            log.error("写入语义缓存失败 userId={} question={}", userId, truncate(question, 30), e);
        }
    }

    /**
     * 清理某个用户的所有语义缓存（如用户注销 / 数据重置时调用）
     */
    public void clear(Long userId) {
        try {
            store().delete("type == '" + VectorType.QA_CACHE.getValue() + "' && userId == '" + userId + "'");
            log.info("已清理语义缓存 userId={}", userId);
        } catch (Exception e) {
            cacheStore.reset();
            log.warn("清理语义缓存失败", e);
        }
    }

    /** epoch 毫秒 -> "今天 / N天前 / N个月前 / N年前" */
    private String formatAskedAt(long epoch) {
        long days = (System.currentTimeMillis() - epoch) / (24 * 3600 * 1000L);
        if (days <= 0) {
            return "今天";
        }
        if (days < 30) {
            return days + "天前";
        }
        if (days < 365) {
            return (days / 30) + "个月前";
        }
        return (days / 365) + "年前";
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** "a, b, c" -> {a,b,c}；留空 / all 视为不限制（返回空集合） */
    private Set<String> splitToSet(String raw) {
        Set<String> set = new HashSet<>();
        if (raw == null) {
            return set;
        }
        for (String item : raw.split("[,\\s]+")) {
            String v = item.trim().toLowerCase(Locale.ROOT);
            if (!v.isEmpty() && !"all".equals(v)) {
                set.add(v);
            }
        }
        return set;
    }

    /** 返回 text 中命中的第一个关键词（中文无需分词，直接包含匹配）；未命中返回 null */
    private String firstHit(Set<String> keywords, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return keyword;
            }
        }
        return null;
    }

    /** 返回 text 命中的开头词；未命中返回 null */
    private String firstPrefixHit(Set<String> prefixes, String text) {
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }
}
