package com.lion.agent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档实体
 */
@Data
@TableName("knowledge_document")
public class KnowledgeDocument {

    /**
     * 文档 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属知识库 ID
     */
    private Long knowledgeId;

    /**
     * 文件名称
     */
    private String fileName;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 文件类型（扩展名，如 pdf、docx）
     */
    private String fileType;

    /**
     * 文件存储路径（相对或绝对路径，如 upload/2026/08/xxx.pdf）
     */
    private String filePath;

    /**
     * 上传状态：0-失败 1-成功 2-处理中
     */
    private Integer status;

    /**
     * 失败原因（失败时记录）
     */
    private String failReason;

    /**
     * 文档切分方式（token/recursive/paragraph/sentence/line/semantic）
     */
    private String splitter;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
