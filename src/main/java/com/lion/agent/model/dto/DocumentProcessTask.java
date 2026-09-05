package com.lion.agent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库文档异步处理任务
 * <p>
 * 由上传接口作为生产者推入 Redis 队列，后台消费者取出后执行解析/切分/向量化。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentProcessTask {

    /** 文档 ID（knowledge_document.id） */
    private Long docId;

    /** 所属知识库 ID */
    private Long knowledgeId;

    /** 文件存储路径 */
    private String filePath;

    /** 切分方式（token/recursive/paragraph/sentence/line/semantic） */
    private String splitter;

    /** 已重试次数 */
    private Integer retryCount = 0;
}
