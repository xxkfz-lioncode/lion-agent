package com.lion.agent.service;

import com.lion.agent.common.PageResult;
import com.lion.agent.entity.KnowledgeDocument;
import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeDocumentService {

    PageResult<KnowledgeDocument> listByKnowledgeId(Long knowledgeId, Long userId, int pageNum, int pageSize, String keyword);

    /**
     * 上传文档（异步）：校验并落盘后立即返回（status=处理中），实际解析/切分/向量化
     * 由后台消费者通过 {@link #processDocument} 异步完成。
     *
     * @param splitter 切分方式：token / recursive / paragraph / sentence / line / semantic
     */
    KnowledgeDocument upload(Long knowledgeId, Long userId, MultipartFile file, String splitter);

    /**
     * 异步处理文档（后台消费者调用）：读取文件 → 解析 → 切分 → 写入向量库 → 更新状态。
     * 失败时内部更新状态为失败并记录原因。
     *
     * @param knowledgeId 知识库 ID
     * @param docId       文档 ID
     * @param filePath    文件存储路径
     * @param splitter    切分方式
     */
    void processDocument(Long knowledgeId, Long docId, String filePath, String splitter);

    void delete(Long knowledgeId, Long docId, Long userId);

    /**
     * 预览文档内容。
     *
     * @param knowledgeId 知识库 ID
     * @param docId       文档 ID
     * @param userId      当前用户 ID
     * @return 文档文本内容（已截断），非文本文件返回空字符串或提示
     */
    String preview(Long knowledgeId, Long docId, Long userId);
}
