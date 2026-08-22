package com.lion.agent.service;

import com.lion.agent.common.PageResult;
import com.lion.agent.dto.KnowledgeBaseRequest;
import com.lion.agent.entity.KnowledgeBase;

public interface KnowledgeBaseService {

    PageResult<KnowledgeBase> listByUser(Long userId, int pageNum, int pageSize, String keyword);

    KnowledgeBase create(Long userId, KnowledgeBaseRequest request);

    KnowledgeBase update(Long id, Long userId, KnowledgeBaseRequest request);

    void delete(Long id, Long userId);

    KnowledgeBase getById(Long id, Long userId);
}
