package com.lion.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lion.agent.common.PageResult;
import com.lion.agent.dto.KnowledgeBaseRequest;
import com.lion.agent.entity.KnowledgeBase;
import com.lion.agent.exception.BusinessException;
import com.lion.agent.mapper.KnowledgeBaseMapper;
import com.lion.agent.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    public PageResult<KnowledgeBase> listByUser(Long userId, int pageNum, int pageSize, String keyword) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeBase::getUserId, userId);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(KnowledgeBase::getName, keyword)
                    .or()
                    .like(KnowledgeBase::getDescription, keyword));
        }
        wrapper.orderByDesc(KnowledgeBase::getCreatedAt);
        Page<KnowledgeBase> page = new Page<>(pageNum, pageSize);
        Page<KnowledgeBase> result = knowledgeBaseMapper.selectPage(page, wrapper);
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBase create(Long userId, KnowledgeBaseRequest request) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setUserId(userId);
        kb.setName(request.getName().trim());
        kb.setDescription(request.getDescription());
        knowledgeBaseMapper.insert(kb);
        return kb;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBase update(Long id, Long userId, KnowledgeBaseRequest request) {
        KnowledgeBase kb = getById(id, userId);
        kb.setName(request.getName().trim());
        kb.setDescription(request.getDescription());
        knowledgeBaseMapper.updateById(kb);
        return kb;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long userId) {
        KnowledgeBase kb = getById(id, userId);
        knowledgeBaseMapper.deleteById(kb.getId());
    }

    @Override
    public KnowledgeBase getById(Long id, Long userId) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getUserId, userId);
        KnowledgeBase kb = knowledgeBaseMapper.selectOne(wrapper);
        if (kb == null) {
            throw new BusinessException("知识库不存在");
        }
        return kb;
    }
}
