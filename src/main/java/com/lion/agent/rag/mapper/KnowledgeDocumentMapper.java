package com.lion.agent.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lion.agent.rag.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {
}
