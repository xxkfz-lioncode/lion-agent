package com.lion.agent.service;

import cn.dev33.satoken.stp.StpInterface;
import com.lion.agent.tools.DateTools;
import com.lion.agent.tools.StarFortuneTools;
import com.lion.agent.tools.ToolPermission;
import com.lion.agent.tools.TimeLimiterTools;
import com.lion.agent.tools.UserTools;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 工具注册与筛选中心（常驻 + 权限 + 向量预筛 三层筛选）
 *
 * <p>解决的问题：工具全量注册时，每个工具的 name + description + 参数 Schema 都随请求发给模型——
 * 工具一多，token 固定开销线性膨胀，且候选越多模型选得越不准（误选/犹豫/绕开工具硬答）。
 *
 * <p>筛选分三层，逐层收敛：
 * <ol>
 *   <li><b>常驻工具</b>：UserTools 这类高频低成本工具，不走检索永远注册
 *       ——向量召回靠语义相似，"现在系统里有多少用户"和工具描述未必对得上，高频万金油兜底。</li>
 *   <li><b>权限过滤</b>：{@code @ToolPermission} 标注的工具（如查敏感数据），无权限直接排除出候选池
 *       ——权限是安全约束不是相关性问题，不能交给向量相似度决定。
 *       当前项目未实现 {@code StpInterface}，默认全部公开；接入权限体系后自动生效。</li>
 *   <li><b>向量预筛</b>：用户 query 与工具描述算相似度，top-K 召回（RAG of tools）
 *       ——向量负责召回（全量→K 个），function calling 负责精选（K 个→调哪个），
 *         预筛成本仅一次 embedding（毫秒级）。</li>
 * </ol>
 *
 * <p>索引与本体分离（与知识库文档共用一个 Milvus collection，靠 metadata type 隔离）：
 * Milvus 里只存"目录索引"（工具名 + 描述 + 权限码），工具实现还是 Spring 容器里的 Bean；
 * 两边通过工具名关联。加新工具 = 写工具类 + 登记进 RETRIEVABLE_TOOLS / ALWAYS_ON_TOOLS，
 * 启动自动重建索引。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRegistryService {

    /** 向量索引的 metadata type 标记：与知识库文档向量（knowledgeId 过滤）共用 collection，靠 type 隔离 */
    private static final String INDEX_TYPE = "tool_index";

    /** 向量检索每次召回的工具数：K 取小值，宁缺勿滥——漏召回有常驻工具兜底，多召回则每个都占 token */
    private static final int TOP_K = 3;

    /** MilvusVectorStore.add 内部会对整批 embedding，DashScope 单次上限 10 条 */
    private static final int ADD_BATCH_SIZE = 10;

    /**
     * 常驻工具：不参与检索、永远注册
     * UserTools（查用户数/状态）这类高频低成本工具，走检索省的那点 token 抵不上漏召回的代价
     */
    private static final List<Class<?>> ALWAYS_ON_TOOLS = List.of(UserTools.class, DateTools.class, TimeLimiterTools.class);

    /** 可检索工具池：参与向量预筛的工具（新工具写完类在这里登记一行，索引和筛选全自动） */
    private static final List<Class<?>> RETRIEVABLE_TOOLS = List.of(StarFortuneTools.class);

    private final VectorStore vectorStore;
    private final ApplicationContext applicationContext;

    /** 权限接口（可选依赖）：当前项目未实现 StpInterface 时为 null，全部工具公开；接入后自动按权限码过滤 */
    private final ObjectProvider<StpInterface> stpInterfaceProvider;

    /** 工具名 -> callback：方法级索引（一个工具类可含多个 @Tool 方法，各自独立召回） */
    private final Map<String, ToolCallback> callbackIndex = new LinkedHashMap<>();

    /** 工具名 -> 所需权限码（无标注存空串）：降级路径下做内存权限过滤用 */
    private final Map<String, String> permissionIndex = new HashMap<>();

    /** 常驻工具的 callbacks，启动时构建后不变 */
    private final List<ToolCallback> alwaysOnCallbacks = new ArrayList<>();

    private final ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider;
    private final CircuitBreakerRegistry circuitBreakerRegistry;



    /**
     * 启动时构建本地索引 + 全量重建向量索引
     *
     * 用 ApplicationReadyEvent 而不是 @PostConstruct：
     * 这里要调 embedding API 向量化工具描述（网络调用），放在容器就绪后执行，
     * 不阻塞 Bean 初始化；失败只影响向量检索（自动降级为常驻+全量注册），不影响启动。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        // 1. 常驻工具：生成 callback 直接持有，不建向量索引（不参与检索）
        for (Class<?> toolClass : ALWAYS_ON_TOOLS) {
            alwaysOnCallbacks.addAll(Arrays.asList(ToolCallbacks.from(applicationContext.getBean(toolClass))));
        }
        log.info("常驻工具注册 {} 个（不走检索）", alwaysOnCallbacks.size());

        // 2. 可检索工具：反射生成方法级 callback，构建本地索引 + 向量索引文档
        List<Document> indexDocs = new ArrayList<>();
        for (Class<?> toolClass : RETRIEVABLE_TOOLS) {
            String permission = resolvePermission(toolClass);
            for (ToolCallback cb : ToolCallbacks.from(applicationContext.getBean(toolClass))) {
                String toolName = cb.getToolDefinition().name();
                callbackIndex.put(toolName, cb);
                permissionIndex.put(toolName, permission);
                indexDocs.add(toIndexDocument(toolName, cb, permission));
            }
        }

        ToolCallbackProvider mcpProvider = mcpToolCallbackProvider.getIfAvailable();
        if (mcpProvider == null) {
            log.info("MCP 客户端未启用（MCP_ENABLED=false），不接入远程工具");
        } else {
            ToolCallback[] mcpCallbacks = mcpProvider.getToolCallbacks();
            for (ToolCallback raw : mcpCallbacks) {
                String toolName = raw.getToolDefinition().name();
                // MCP 工具本地无 @ToolPermission，默认公开（permission=''）；要收紧需在配置层统一赋权
                callbackIndex.put(toolName, raw);
                permissionIndex.put(toolName, "");
                indexDocs.add(toIndexDocument(toolName, raw, ""));
            }
            log.info("MCP 远程工具接入 {} 个（熔断包装 + 向量索引）", mcpCallbacks.length);
        }

        log.info("可检索工具索引 {} 个（方法级）：{}", callbackIndex.size(), callbackIndex.keySet());

        // 3. 全量重建向量索引：先按 type 清旧再分批写新（覆盖语义，不留脏数据）
        try {
            vectorStore.delete("type == '" + INDEX_TYPE + "'");
            for (int i = 0; i < indexDocs.size(); i += ADD_BATCH_SIZE) {
                vectorStore.add(indexDocs.subList(i, Math.min(i + ADD_BATCH_SIZE, indexDocs.size())));
            }
            log.info("工具向量索引重建完成，共 {} 条", indexDocs.size());
        } catch (Exception e) {
            // 降级：索引建不上（Milvus 挂了/embedding 超时），selectTools 检索时同样会失败并走全量降级
            log.warn("工具向量索引构建失败（检索将降级为常驻+全量注册）：{}", e.getMessage());
        }


    }

    /**
     * 为本次请求挑选工具：常驻 ∪ 权限过滤后的向量 top-K
     *
     * 返回 List 而不是数组：避免 .tools() 重载解析歧义——
     * ToolCallback[] 会命中已弃用的 toolCallbacks(ToolCallback...) 重载，
     * List 作为单个 Object 传入 tools(Object...) 后由 2.0 的统一分发规则迭代注册。
     *
     * @param query  用户问题（embedding 后与工具描述算相似度）
     * @param userId 当前用户（按其权限码过滤候选池；null 视为匿名）
     */
    public List<ToolCallback> selectTools(String query, Long userId) {
        List<ToolCallback> selected = new ArrayList<>(alwaysOnCallbacks);
        try {
            // 权限过滤编进 filterExpression：权限条件和向量召回一次查询完成，
            // 先过滤再排序，不会出现"top-K 里一半被权限筛掉导致候选不足"的问题
            List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(TOP_K)
                    .filterExpression(buildPermissionFilter(userId))
                    .build());
            for (Document hit : hits) {
                // 检索回来的 doc ID 还原成工具名，回本地索引取 callback；
                // 取不到 = 索引残留（工具已删但旧向量还在），跳过即可
                ToolCallback cb = callbackIndex.get(toolNameOf(hit.getId()));
                if (cb != null) {
                    selected.add(cb);
                }
            }
            log.info("工具筛选：常驻 {} + 检索命中 {}（query 相关工具：{}）",
                    alwaysOnCallbacks.size(), selected.size() - alwaysOnCallbacks.size(),
                    hits.stream().map(d -> toolNameOf(d.getId())).collect(Collectors.toList()));
        } catch (Exception e) {
            // 降级：Milvus 不可用时返回"常驻 + 权限内的全部可检索工具"
            // 筛选是优化不是功能，挂了最多多花点 token，不能因此让 Agent 没工具可用
            log.warn("工具向量检索失败，降级为常驻+权限内全量注册：{}", e.getMessage());
            selected.addAll(callbackIndex.entrySet().stream()
                    .filter(e1 -> hasPermission(userId, permissionIndex.get(e1.getKey())))
                    .map(Map.Entry::getValue)
                    .toList());
        }
        return selected;
    }

    /**
     * 权限过滤表达式：type 限定工具索引 + 权限条件
     * 当前项目未实现 StpInterface 时不做权限收敛（所有工具公开）。
     */
    private String buildPermissionFilter(Long userId) {
        String base = "type == '" + INDEX_TYPE + "'";
        StpInterface stpInterface = stpInterfaceProvider.getIfAvailable();
        if (stpInterface == null || userId == null) {
            return base;
        }
        List<String> perms = stpInterface.getPermissionList(userId, "login");
        if (perms == null || perms.isEmpty()) {
            return base;
        }
        String permCondition = perms.stream()
                .map(p -> "permission == '" + p + "'")
                .collect(Collectors.joining(" || "));
        return base + " && (permission == '' || " + permCondition + ")";
    }

    /** 降级路径的内存权限判断：与 buildPermissionFilter 同一套规则，只是过滤时机从 Milvus 换成了 Java */
    private boolean hasPermission(Long userId, String requiredPermission) {
        if (requiredPermission == null || requiredPermission.isEmpty()) {
            return true;
        }
        if (userId == null) {
            return false;
        }
        StpInterface stpInterface = stpInterfaceProvider.getIfAvailable();
        if (stpInterface == null) {
            return true;
        }
        List<String> perms = stpInterface.getPermissionList(userId, "login");
        return perms != null && perms.contains(requiredPermission);
    }

    /** 读工具类上的 @ToolPermission（没标注 = 公开工具，权限码存空串保证 metadata 字段恒存在） */
    private String resolvePermission(Class<?> toolClass) {
        ToolPermission annotation = toolClass.getAnnotation(ToolPermission.class);
        return annotation != null ? annotation.value() : "";
    }

    /** 工具描述 → 向量索引文档：text 是检索语料，metadata 携带权限码参与标量过滤 */
    private Document toIndexDocument(String toolName, ToolCallback callback, String permission) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", INDEX_TYPE);
        metadata.put("permission", permission);
        // 检索语料 = 工具名 + 描述：名字放前面是给向量的一份额外语义锚点（如 "queryStarFortune" 本身就含语义）
        String text = toolName + "：" + callback.getToolDefinition().description();
        return Document.builder()
                .id(docId(toolName))
                .text(text)
                .metadata(metadata)
                .build();
    }

    private String docId(String toolName) {
        return "tool-index-" + toolName;
    }

    private String toolNameOf(String docId) {
        return docId.substring("tool-index-".length());
    }
}
