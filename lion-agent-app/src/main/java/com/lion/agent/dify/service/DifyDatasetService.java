package com.lion.agent.dify.service;

import com.lion.agent.dify.dto.DifyDatasetCreateDTO;
import com.lion.agent.dify.dto.DifyDocTextDTO;
import io.github.guoshiqiufeng.dify.core.pojo.DifyPageResult;
import io.github.guoshiqiufeng.dify.dataset.dto.response.DatasetResponse;
import io.github.guoshiqiufeng.dify.dataset.dto.response.DocumentCreateResponse;
import io.github.guoshiqiufeng.dify.dataset.dto.response.DocumentInfo;
import org.springframework.web.multipart.MultipartFile;

/**
 * Dify 知识库服务（对接 dify-spring-boot4-starter 的 DifyDataset）
 */
public interface DifyDatasetService {

    /**
     * 知识库分页列表
     */
    DifyPageResult<DatasetResponse> page(String keyword, Integer page, Integer limit);

    /**
     * 创建知识库
     */
    DatasetResponse create(DifyDatasetCreateDTO dto);

    /**
     * 删除知识库（连同其下所有文档）
     */
    void delete(String datasetId);

    /**
     * 知识库文档分页列表
     */
    DifyPageResult<DocumentInfo> documents(String datasetId, String keyword, Integer page, Integer limit);

    /**
     * 以纯文本新建文档
     */
    DocumentCreateResponse createDocumentByText(String datasetId, DifyDocTextDTO dto);

    /**
     * 上传文件新建文档（txt/markdown/pdf/word 等由 Dify 自动解析）
     */
    DocumentCreateResponse createDocumentByFile(String datasetId, MultipartFile file);

    /**
     * 删除文档
     */
    void deleteDocument(String datasetId, String documentId);
}
