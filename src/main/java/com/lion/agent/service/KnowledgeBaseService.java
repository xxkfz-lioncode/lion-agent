package com.lion.agent.service;

import com.lion.agent.common.PageResult;
import com.lion.agent.dto.KnowledgeBaseRequest;
import com.lion.agent.entity.KnowledgeBase;

import java.util.List;

public interface KnowledgeBaseService {

    PageResult<KnowledgeBase> listByUser(Long userId, int pageNum, int pageSize, String keyword);

    /**
     * 查询用户全部知识库（不分页，按创建时间倒序）。
     * 用于意图识别（判断用户有哪些知识库）与未指定知识库时的全局检索。
     */
    List<KnowledgeBase> listAllByUser(Long userId);

    KnowledgeBase create(Long userId, KnowledgeBaseRequest request);

    KnowledgeBase update(Long id, Long userId, KnowledgeBaseRequest request);

    void delete(Long id, Long userId);

    KnowledgeBase getById(Long id, Long userId);
}
