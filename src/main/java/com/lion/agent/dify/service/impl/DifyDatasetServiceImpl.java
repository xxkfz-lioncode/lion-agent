package com.lion.agent.dify.service.impl;


import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.dify.dto.DifyDatasetCreateDTO;
import com.lion.agent.dify.dto.DifyDocTextDTO;
import com.lion.agent.dify.service.DifyDatasetService;
import io.github.guoshiqiufeng.dify.core.config.DifyProperties;
import io.github.guoshiqiufeng.dify.core.exception.DifyClientException;
import io.github.guoshiqiufeng.dify.core.pojo.DifyFile;
import io.github.guoshiqiufeng.dify.core.pojo.DifyPageResult;
import io.github.guoshiqiufeng.dify.dataset.DifyDataset;
import io.github.guoshiqiufeng.dify.dataset.dto.request.DatasetCreateRequest;
import io.github.guoshiqiufeng.dify.dataset.dto.request.DatasetPageDocumentRequest;
import io.github.guoshiqiufeng.dify.dataset.dto.request.DatasetPageRequest;
import io.github.guoshiqiufeng.dify.dataset.dto.request.DocumentCreateByFileRequest;
import io.github.guoshiqiufeng.dify.dataset.dto.request.DocumentCreateByTextRequest;
import io.github.guoshiqiufeng.dify.dataset.dto.request.document.ProcessRule;
import io.github.guoshiqiufeng.dify.dataset.dto.response.DatasetResponse;
import io.github.guoshiqiufeng.dify.dataset.dto.response.DocumentCreateResponse;
import io.github.guoshiqiufeng.dify.dataset.dto.response.DocumentInfo;
import io.github.guoshiqiufeng.dify.dataset.enums.IndexingTechniqueEnum;
import io.github.guoshiqiufeng.dify.dataset.enums.document.ModeEnum;
import io.github.guoshiqiufeng.dify.server.DifyServer;
import io.github.guoshiqiufeng.dify.server.dto.response.DatasetApiKeyResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;

/**
 * Dify 知识库服务实现
 *
 * <p>Dataset API Key 的获取顺序：
 * ① starter 配置 {@code dify.dataset.api-key}（填了真实 Key 就用）；
 * ② Server API 动态获取。注意：Dify 控制台的密钥「列表」接口返回的是
 * <b>脱敏 token</b>（形如 {@code datas...LVTA}），完整 Key 只在「创建密钥」
 * 接口的响应里返回一次——所以列表里全是打码值时，必须调
 * {@link DifyServer#initDatasetApiKey()} 新建一把拿完整 token。
 *
 * <p>可靠性：
 * SDK 的 delete/deleteDocument 单参重载会回退到 starter 配置的 Key（占位符必 401），
 * 必须显式传 Key；Key 被拒（Access token is invalid）时清缓存重建并重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DifyDatasetServiceImpl implements DifyDatasetService {

    private final DifyDataset difyDataset;
    private final DifyProperties difyProperties;
    private final DifyServer difyServer;

    /** Server API 动态获取的知识库 Key 缓存（配置 Key 不缓存，直接读配置） */
    private volatile String datasetKeyCache;

    /** 视为「未配置」的占位符前缀（含 .env.example 里的模板值，防止照抄模板后被当成真 Key） */
    private static final String[] PLACEHOLDER_PREFIX = {"dataset-aaa", "dataset-your", "dataset-xxx"};

    /** yml 里配置的真实 Key（占位符/空值视为未配置），null 表示走 Server API */
    private String configKey() {
        String k = difyProperties.getDataset().getApiKey();
        if (k == null || k.isBlank()) {
            return null;
        }
        for (String prefix : PLACEHOLDER_PREFIX) {
            if (k.startsWith(prefix)) {
                return null;
            }
        }
        return k;
    }

    /**
     * Dify 列表接口对 token 脱敏的标志：形如 {@code datas...LVTA}，中间含 {@code ...}
     */
    private static boolean isMasked(String token) {
        return token == null || token.isBlank() || token.contains("..");
    }

    private String datasetApiKey() {
        // ① starter 配置优先
        String config = configKey();
        if (config != null) {
            return config;
        }
        // ② Server API 动态获取（缓存，避免每次请求都调 Server API）
        if (datasetKeyCache != null) {
            return datasetKeyCache;
        }
        synchronized (this) {
            if (datasetKeyCache != null) {
                return datasetKeyCache;
            }
            datasetKeyCache = fetchUsableKey();
            log.info("[Dify-Dataset] 使用知识库 Key: {}（来源: Server API）", mask(datasetKeyCache));
            return datasetKeyCache;
        }
    }

    /**
     * 从 Server API 拿一把「未脱敏」的可用 Key：
     * 先查列表，有完整 token 直接用；全是脱敏值则新建一把（创建接口返回完整 token）。
     */
    private String fetchUsableKey() {
        List<DatasetApiKeyResponse> keys = difyServer.getDatasetApiKey();
        if (keys != null) {
            for (DatasetApiKeyResponse k : keys) {
                if (k != null && !isMasked(k.getToken())) {
                    return k.getToken();
                }
            }
            // 列表非空但全是脱敏值：这些 Key 任何调用方都无法使用，清掉再重建，避免堆积废 Key
            for (DatasetApiKeyResponse k : keys) {
                if (k != null && k.getId() != null) {
                    try {
                        difyServer.deleteDatasetApiKey(k.getId());
                        log.info("[Dify-Dataset] 已清理脱敏的废密钥 id={}", k.getId());
                    } catch (Exception ex) {
                        log.warn("[Dify-Dataset] 清理废密钥失败 id={}：{}", k.getId(), ex.getMessage());
                    }
                }
            }
        }
        log.info("[Dify-Dataset] 密钥列表为空或均为脱敏值，创建新密钥获取完整 token");
        List<DatasetApiKeyResponse> created = difyServer.initDatasetApiKey();
        if (created != null) {
            for (DatasetApiKeyResponse k : created) {
                if (k != null && !isMasked(k.getToken())) {
                    return k.getToken();
                }
            }
        }
        throw new BusinessException("获取知识库 API Key 失败：Dify 只返回脱敏 token（请到 Dify 控制台「知识库 → API」复制真实 Key 填入 dify.dataset.api-key）");
    }

    /** Key 被拒时的自愈：清缓存 → 重新获取（必要时新建）→ 重试一次 */
    private <T> T callWithKey(Function<String, T> action) {
        String key = datasetApiKey();
        try {
            return action.apply(key);
        } catch (DifyClientException e) {
            if (!isInvalidTokenError(e) || key.equals(configKey())) {
                throw e;
            }
            log.warn("[Dify-Dataset] 知识库 Key 被拒（{}），清缓存重新获取后重试", e.getMessage());
            datasetKeyCache = null;
            return action.apply(datasetApiKey());
        }
    }

    private static boolean isInvalidTokenError(DifyClientException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        return msg.contains("Access token is invalid") || msg.contains("Unauthorized") || msg.contains("401");
    }

    /** 日志脱敏：仅展示前缀 */
    private static String mask(String key) {
        return key.length() <= 14 ? key : key.substring(0, 14) + "****";
    }

    @Override
    public DifyPageResult<DatasetResponse> page(String keyword, Integer page, Integer limit) {
        return callWithKey(key -> {
            DatasetPageRequest request = new DatasetPageRequest();
            request.setApiKey(key);
            request.setKeyword(keyword);
            request.setPage(page == null ? 1 : page);
            request.setLimit(limit == null ? 20 : limit);
            return difyDataset.page(request);
        });
    }

    @Override
    public DatasetResponse create(DifyDatasetCreateDTO dto) {
        return callWithKey(key -> {
            DatasetCreateRequest request = new DatasetCreateRequest();
            request.setApiKey(key);
            request.setName(dto.getName());
            request.setDescription(dto.getDescription());
            return difyDataset.create(request);
        });
    }

    @Override
    public void delete(String datasetId) {
        callWithKey(key -> {
            // 显式传 Key：单参重载会回退到 starter 配置的 Key（占位符），必然 401
            difyDataset.delete(datasetId, key);
            return null;
        });
    }

    @Override
    public DifyPageResult<DocumentInfo> documents(String datasetId, String keyword, Integer page, Integer limit) {
        return callWithKey(key -> {
            DatasetPageDocumentRequest request = new DatasetPageDocumentRequest();
            request.setApiKey(key);
            request.setDatasetId(datasetId);
            request.setKeyword(keyword);
            request.setPage(page == null ? 1 : page);
            request.setLimit(limit == null ? 20 : limit);
            return difyDataset.pageDocument(request);
        });
    }

    @Override
    public DocumentCreateResponse createDocumentByText(String datasetId, DifyDocTextDTO dto) {
        return callWithKey(key -> {
            DocumentCreateByTextRequest request = new DocumentCreateByTextRequest();
            request.setApiKey(key);
            request.setDatasetId(datasetId);
            request.setName(dto.getName());
            request.setText(dto.getText());
            // 高质量索引（走向量检索）+ 自动分段清洗规则
            request.setIndexingTechnique(IndexingTechniqueEnum.HIGH_QUALITY);
            ProcessRule processRule = new ProcessRule();
            processRule.setMode(ModeEnum.automatic);
            request.setProcessRule(processRule);
            return difyDataset.createDocumentByText(request);
        });
    }

    @Override
    public DocumentCreateResponse createDocumentByFile(String datasetId, MultipartFile file) {
        return callWithKey(key -> {
            DocumentCreateByFileRequest request = new DocumentCreateByFileRequest();
            request.setApiKey(key);
            request.setDatasetId(datasetId);
            // 2.3.3 起 setFile 需要的是 SDK 自带的 DifyFile（文件名 + 类型 + 字节），由 Spring 的 MultipartFile 转换
            try {
                request.setFile(DifyFile.from(file.getInputStream(), file.getOriginalFilename(), file.getContentType()));
            } catch (IOException e) {
                throw new IllegalArgumentException("读取上传文件失败：" + file.getOriginalFilename(), e);
            }
            request.setIndexingTechnique(IndexingTechniqueEnum.HIGH_QUALITY);
            ProcessRule processRule = new ProcessRule();
            processRule.setMode(ModeEnum.automatic);
            request.setProcessRule(processRule);
            return difyDataset.createDocumentByFile(request);
        });
    }

    @Override
    public void deleteDocument(String datasetId, String documentId) {
        callWithKey(key -> {
            // 同 delete：必须显式传 Key
            difyDataset.deleteDocument(datasetId, documentId, key);
            return null;
        });
    }
}
