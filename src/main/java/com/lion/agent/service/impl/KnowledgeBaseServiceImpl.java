package com.lion.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lion.agent.common.result.PageResult;
import com.lion.agent.pojo.dto.KnowledgeBaseRequest;
import com.lion.agent.pojo.entity.KnowledgeBase;
import com.lion.agent.pojo.entity.KnowledgeDocument;
import com.lion.agent.pojo.vo.KnowledgeBaseVo;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.mapper.KnowledgeBaseMapper;
import com.lion.agent.mapper.KnowledgeDocumentMapper;
import com.lion.agent.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Override
    public PageResult<KnowledgeBaseVo> listByUser(Long userId, int pageNum, int pageSize, String keyword) {
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

        // 对分页后的知识库做一次 group count，避免逐库查询文档数的 N+1 问题
        List<Long> kbIds = result.getRecords().stream().map(KnowledgeBase::getId).toList();
        Map<Long, Long> countMap = countDocumentsByKnowledgeId(kbIds);
        List<KnowledgeBaseVo> vos = result.getRecords().stream()
                .map(kb -> KnowledgeBaseVo.from(kb, countMap.getOrDefault(kb.getId(), 0L)))
                .toList();
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), vos);
    }

    /** 统计各知识库下的文档数量：SELECT knowledge_id, COUNT(*) ... GROUP BY knowledge_id */
    private Map<Long, Long> countDocumentsByKnowledgeId(List<Long> kbIds) {
        if (kbIds.isEmpty()) {
            return Map.of();
        }
        QueryWrapper<KnowledgeDocument> countWrapper = new QueryWrapper<>();
        countWrapper.select("knowledge_id", "COUNT(*) AS cnt")
                .in("knowledge_id", kbIds)
                .groupBy("knowledge_id");
        return knowledgeDocumentMapper.selectMaps(countWrapper).stream()
                .collect(Collectors.toMap(
                        row -> ((Number) row.get("knowledge_id")).longValue(),
                        row -> ((Number) row.get("cnt")).longValue()));
    }

    @Override
    public java.util.List<KnowledgeBase> listAllByUser(Long userId) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeBase::getUserId, userId)
                .orderByDesc(KnowledgeBase::getCreatedAt);
        return knowledgeBaseMapper.selectList(wrapper);
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
