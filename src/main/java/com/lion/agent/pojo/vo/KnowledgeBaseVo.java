package com.lion.agent.pojo.vo;

import com.lion.agent.pojo.entity.KnowledgeBase;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库列表项视图对象：实体基础上补充文档数量等展示字段
 */
@Data
public class KnowledgeBaseVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 知识库 ID */
    private Long id;

    /** 知识库名称 */
    private String name;

    /** 知识库描述 */
    private String description;

    /** 知识库下的文档数量 */
    private Long documentCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

    public static KnowledgeBaseVo from(KnowledgeBase kb, long documentCount) {
        KnowledgeBaseVo vo = new KnowledgeBaseVo();
        vo.setId(kb.getId());
        vo.setName(kb.getName());
        vo.setDescription(kb.getDescription());
        vo.setDocumentCount(documentCount);
        vo.setCreatedAt(kb.getCreatedAt());
        return vo;
    }
}
