package com.lion.agent.dify.service.impl;

import cn.hutool.core.util.StrUtil;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.dify.service.DifyAppService;
import com.lion.agent.dify.vo.DifyAppVO;
import com.lion.agent.dify.vo.DifyFlowGraphVO;
import io.github.guoshiqiufeng.dify.core.config.DifyProperties;
import io.github.guoshiqiufeng.dify.server.DifyServer;
import io.github.guoshiqiufeng.dify.server.client.DifyServerClient;
import io.github.guoshiqiufeng.dify.server.dto.request.AppsRequest;
import io.github.guoshiqiufeng.dify.server.dto.response.ApiKeyResponse;
import io.github.guoshiqiufeng.dify.server.dto.response.AppsResponse;
import io.github.guoshiqiufeng.dify.server.dto.response.AppsResponseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dify 应用服务实现 —— 基于 Server API（DifyServer）动态获取，无需在 yml 手工登记
 *
 * <p>数据来源（全部走 Dify 控制台的服务端接口，依赖 yml 里 dify.server.email/password）：
 * <ul>
 *   <li>应用列表：{@link DifyServer#apps(AppsRequest)}（分页拉全量）</li>
 *   <li>应用详情：{@link DifyServer#app(String)}</li>
 *   <li>应用 API Key：{@link DifyServer#getAppApiKey(String)}；一个应用还没有密钥时
 *       调 {@link DifyServer#initAppApiKey(String)} 自动初始化，相当于首次在控制台
 *       点「API 密钥 → 创建」的自动化版本。</li>
 * </ul>
 *
 * <p>appCode 即 Dify 的应用 ID（uuid），前端拿到的应用列表里就带；API Key 只在
 * 后端内存中解析与缓存，永远不返回给前端。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DifyAppServiceImpl implements DifyAppService {

    /** chat 类型的 Dify 应用 mode（聊天助手 / Agent / Chatflow / 高级对话） */
    private static final Set<String> CHAT_MODES = Set.of("chat", "agent-chat", "advanced-chat", "chatflow");

    /**
     * 草稿画布接口候选路径（各 Dify 版本命名不一致，命中第一个含 graph 的响应即可）
     */
    private static final List<String> WORKFLOW_PATHS = List.of(
            "/console/api/apps/{appId}/workflows/draft",
            "/console/api/apps/{appId}/workflow",
            "/console/api/apps/{appId}/workflows");

    private final DifyServer difyServer;
    /** SDK 的控制台客户端（含 login()），用于补调 SDK 未封装的画布接口 */
    private final DifyServerClient difyServerClient;
    private final DifyProperties difyProperties;

    /** appId → apiKey 内存缓存，避免每次对话都调 Server API 取密钥 */
    private final Map<String, String> apiKeyCache = new ConcurrentHashMap<>();

    /** 控制台登录凭证缓存（画布接口直调用） */
    private volatile String consoleToken;

    @Override
    public List<DifyAppVO> listApps() {
        // 分页拉取全部应用（每页 100，直到没有更多）
        List<AppsResponse> all = new ArrayList<>();
        int page = 1;
        while (true) {
            AppsRequest request = new AppsRequest();
            request.setPage(page);
            request.setLimit(100);
            AppsResponseResult result = difyServer.apps(request);
            List<AppsResponse> data = result == null ? null : result.getData();
            if (data == null || data.isEmpty()) {
                break;
            }
            all.addAll(data);
            if (result.getHasMore() == null || !result.getHasMore()) {
                break;
            }
            page++;
        }
        return all.stream().map(this::toVO).toList();
    }

    @Override
    public DifyAppVO getApp(String appCode) {
        AppsResponse app = difyServer.app(appCode);
        if (app == null) {
            throw new BusinessException("Dify 应用不存在：" + appCode);
        }
        return toVO(app);
    }

    @Override
    public String resolveApiKey(String appCode, String type) {
        // 先查缓存
        String cached = appCode == null ? null : apiKeyCache.get(appCode);
        if (cached != null) {
            return cached;
        }

        // 未指定应用编码时无法兜底（应用完全由 Dify 平台动态管理），必须显式指定
        if (appCode == null || appCode.isBlank()) {
            throw new BusinessException("请指定 Dify 应用（appCode）");
        }

        // 类型校验：防止把工作流应用的 Key 拿去对话（反之亦然）
        AppsResponse app = difyServer.app(appCode);
        if (app == null) {
            throw new BusinessException("Dify 应用不存在：" + appCode);
        }
        if (!mapType(app.getMode()).equalsIgnoreCase(type)) {
            throw new BusinessException("应用 " + app.getName() + " 不是 " + type + " 类型");
        }

        // 取密钥：没有则自动初始化一个（等价于控制台里首次创建 API 密钥）
        List<ApiKeyResponse> keys = difyServer.getAppApiKey(appCode);
        if (keys == null || keys.isEmpty()) {
            log.info("[Dify-App] 应用 {} 尚无 API Key，自动初始化", app.getName());
            keys = difyServer.initAppApiKey(appCode);
        }
        if (keys == null || keys.isEmpty() || StrUtil.isBlank(keys.get(0).getToken())) {
            throw new BusinessException("获取应用 API Key 失败：" + app.getName());
        }
        String token = keys.getFirst().getToken();
        apiKeyCache.put(appCode, token);
        return token;
    }

    /**
     * 控制台「草稿画布」直调（SDK 未封装的接口）
     *
     * <p>注意：SDK 的 {@code app(id)} 只返回 workflow 元信息（id/创建人/时间），
     * 不含 graph；画布节点与连线在这个单独的接口里，SDK 2.3.3 未封装，
     * 因此这里用 SDK 的 login() 拿控制台凭证后自行请求。
     */
    private Map<String, Object> fetchWorkflowGraph(String appCode) {
        try {
            // 不同 Dify 版本草稿画布路径不一致，按候选顺序尝试，取第一个含 graph 的响应
            for (String path : WORKFLOW_PATHS) {
                try {
                    ResponseEntity<Map<String, Object>> resp = doGetWorkflow(appCode, path);
                    if (resp.getStatusCode().value() == 401) {
                        consoleToken = null;
                        resp = doGetWorkflow(appCode, path);
                    }
                    if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null
                            && resp.getBody().containsKey("graph")) {
                        log.debug("[Dify-App] 编排画布命中路径 {}", path);
                        return resp.getBody();
                    }
                    log.warn("[Dify-App] 编排画布路径 {} 返回 {}（尝试下一个）", path, resp.getStatusCode().value());
                } catch (org.springframework.web.client.HttpStatusCodeException inner) {
                    log.warn("[Dify-App] 编排画布路径 {} 失败: {}（尝试下一个）", path, inner.getStatusCode().value());
                }
            }
            log.warn("[Dify-App] 所有候选路径均未返回画布：base={} appCode={}（请确认 Dify 版本与控制台路由）",
                    difyProperties.getUrl(), appCode);
            return null;
        } catch (Exception e) {
            log.warn("[Dify-App] 获取编排画布失败: {}", e.getMessage());
            return null;
        }
    }

    private ResponseEntity<Map<String, Object>> doGetWorkflow(String appCode, String path) {
        if (consoleToken == null || consoleToken.isBlank()) {
            consoleToken = difyServerClient.login().getAccessToken();
        }
        String base = difyProperties.getUrl();
        if (base != null && base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return RestClient.create()
                .get()
                .uri(base + path.replace("{appId}", appCode))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + consoleToken)
                .retrieve()
                .toEntity(new ParameterizedTypeReference<Map<String, Object>>() { });
    }

    @SuppressWarnings("unchecked")
    @Override
    public DifyFlowGraphVO graph(String appCode) {
        // 画布数据在草稿工作流接口里；chat/agent-chat 无画布时返回空 graph
        Map<String, Object> workflow = fetchWorkflowGraph(appCode);
        if (workflow == null) {
            // 基础聊天助手（chat / agent-chat）没有画布，返回空图
            return DifyFlowGraphVO.builder().nodes(List.of()).edges(List.of()).build();
        }
        Map<String, Object> graph = (Map<String, Object>) workflow.get("graph");
        if (graph == null) {
            return DifyFlowGraphVO.builder().nodes(List.of()).edges(List.of()).build();
        }

        // 节点：id / 类型与标题在 data 里，坐标在 position 里（ReactFlow 结构）
        List<DifyFlowGraphVO.FlowNode> nodes = new ArrayList<>();
        Object rawNodes = graph.get("nodes");
        if (rawNodes instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> node = (Map<String, Object>) m;
                Map<String, Object> data = (Map<String, Object>) node.getOrDefault("data", Map.of());
                Map<String, Object> pos = (Map<String, Object>) node.getOrDefault("position", Map.of());
                nodes.add(DifyFlowGraphVO.FlowNode.builder()
                        .id(str(node.get("id")))
                        .type(str(data.get("type")))
                        .title(str(data.getOrDefault("title", data.get("type"))))
                        .desc(str(data.get("desc")))
                        .x(dbl(pos.get("x")))
                        .y(dbl(pos.get("y")))
                        .width(dbl(node.getOrDefault("width", 240)))
                        .height(dbl(node.getOrDefault("height", 60)))
                        .build());
            }
        }

        List<DifyFlowGraphVO.FlowEdge> edges = new ArrayList<>();
        Object rawEdges = graph.get("edges");
        if (rawEdges instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> edge = (Map<String, Object>) m;
                edges.add(DifyFlowGraphVO.FlowEdge.builder()
                        .id(str(edge.get("id")))
                        .source(str(edge.get("source")))
                        .target(str(edge.get("target")))
                        .sourceHandle(str(edge.get("sourceHandle")))
                        .targetHandle(str(edge.get("targetHandle")))
                        .build());
            }
        }

        Map<String, Object> viewport = (Map<String, Object>) graph.getOrDefault("viewport", Map.of());
        return DifyFlowGraphVO.builder()
                .nodes(nodes)
                .edges(edges)
                .viewportX(dbl(viewport.get("x")))
                .viewportY(dbl(viewport.get("y")))
                .viewportZoom(dbl(viewport.get("zoom")))
                .build();
    }


    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Double dbl(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Dify 的 mode → 本项目的类型（前端据此路由到对话页或工作流页） */
    private String mapType(String mode) {
        if (mode != null && mode.equalsIgnoreCase("workflow")) {
            return "workflow";
        }
        return CHAT_MODES.contains(mode == null ? "" : mode.toLowerCase()) ? "chat" : "chat";
    }

    private DifyAppVO toVO(AppsResponse app) {
        return DifyAppVO.builder()
                .code(app.getId())
                .name(app.getName())
                .type(mapType(app.getMode()))
                .mode(app.getMode())
                .icon(app.getIcon())
                .iconBackground(app.getIconBackground())
                .description(app.getDescription())
                .build();
    }
}
