package com.lion.agent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库实体
 */
@Data
@TableName("knowledge_base")
public class KnowledgeBase {

    /**
     * 知识库 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户 ID
     */
    private Long userId;

    /**
     * 知识库名称
     */
    private String name;

    /**
     * 知识库描述
     */
    private String description;

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
