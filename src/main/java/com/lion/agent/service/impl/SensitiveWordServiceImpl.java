package com.lion.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.common.result.PageResult;
import com.lion.agent.mapper.SensitiveWordMapper;
import com.lion.agent.pojo.dto.SensitiveWordBatchRequest;
import com.lion.agent.pojo.dto.SensitiveWordRequest;
import com.lion.agent.pojo.entity.SensitiveWordEntity;
import com.lion.agent.pojo.vo.SensitiveWordCheckVo;
import com.lion.agent.service.SensitiveWordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 敏感词服务实现
 *
 * <p>词表读多写少，且 Advisor 每次对话都要读取，因此启用词表做本地缓存：
 * 缓存带 TTL（兜底），写操作（增删改 / 启停 / 导入）后立即失效，保证页面改完即生效。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveWordServiceImpl implements SensitiveWordService {

    /** 词表缓存兜底 TTL：即使写操作失效通知遗漏，也能在 30 秒内自愈 */
    private static final long CACHE_TTL_MILLIS = 30_000L;

    private static final String DEFAULT_CATEGORY = "custom";

    private final SensitiveWordMapper sensitiveWordMapper;

    /** 启用词表缓存：null 表示未加载/已失效 */
    private volatile List<String> activeWordsCache;

    /** 缓存加载时间 */
    private volatile long cacheLoadedAt;

    @Override
    public PageResult<SensitiveWordEntity> list(int pageNum, int pageSize, String keyword, Boolean enabled) {
        LambdaQueryWrapper<SensitiveWordEntity> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.like(SensitiveWordEntity::getWord, keyword.trim());
        }
        if (enabled != null) {
            wrapper.eq(SensitiveWordEntity::getEnabled, enabled);
        }
        wrapper.orderByDesc(SensitiveWordEntity::getCreatedAt);
        Page<SensitiveWordEntity> page = new Page<>(pageNum, pageSize);
        Page<SensitiveWordEntity> result = sensitiveWordMapper.selectPage(page, wrapper);
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    @Override
    @Transactional
    public SensitiveWordEntity create(SensitiveWordRequest request) {
        String word = normalizeWord(request.getWord());
        assertWordNotExists(word, null);
        SensitiveWordEntity entity = new SensitiveWordEntity();
        entity.setWord(word);
        entity.setCategory(resolveCategory(request.getCategory()));
        entity.setEnabled(request.getEnabled() == null ? Boolean.TRUE : request.getEnabled());
        entity.setRemark(request.getRemark());
        sensitiveWordMapper.insert(entity);
        invalidateCache();
        return entity;
    }

    @Override
    @Transactional
    public SensitiveWordEntity update(Long id, SensitiveWordRequest request) {
        SensitiveWordEntity entity = requireEntity(id);
        String word = normalizeWord(request.getWord());
        assertWordNotExists(word, id);
        entity.setWord(word);
        entity.setCategory(resolveCategory(request.getCategory()));
        if (request.getEnabled() != null) {
            entity.setEnabled(request.getEnabled());
        }
        entity.setRemark(request.getRemark());
        sensitiveWordMapper.updateById(entity);
        invalidateCache();
        return entity;
    }

    @Override
    @Transactional
    public void delete(Long id) {
        SensitiveWordEntity entity = requireEntity(id);
        sensitiveWordMapper.deleteById(entity.getId());
        invalidateCache();
    }

    @Override
    @Transactional
    public SensitiveWordEntity toggle(Long id, boolean enabled) {
        SensitiveWordEntity entity = requireEntity(id);
        entity.setEnabled(enabled);
        sensitiveWordMapper.updateById(entity);
        invalidateCache();
        log.info("[SensitiveWord] {} 敏感词 id={} word={}", enabled ? "启用" : "停用", id, entity.getWord());
        return entity;
    }

    @Override
    @Transactional
    public int batchImport(SensitiveWordBatchRequest request) {
        List<String> input = request.getWords() == null ? List.of() : request.getWords();
        // 去空 + 去重（保持顺序，便于页面按导入顺序核对）
        Set<String> distinct = new LinkedHashSet<>();
        for (String w : input) {
            if (!StringUtils.hasText(w)) {
                continue;
            }
            String word = w.trim();
            if (word.length() > 128) {
                word = word.substring(0, 128);
            }
            distinct.add(word);
        }
        if (distinct.isEmpty()) {
            return 0;
        }

        // 已存在的词直接跳过（大小写不敏感，与匹配规则一致）
        Set<String> existed = sensitiveWordMapper.selectList(null)
                .stream()
                .map(e -> e.getWord() == null ? "" : e.getWord().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        List<SensitiveWordEntity> toInsert = new ArrayList<>();
        for (String word : distinct) {
            if (existed.contains(word.toLowerCase(Locale.ROOT))) {
                continue;
            }
            SensitiveWordEntity entity = new SensitiveWordEntity();
            entity.setWord(word);
            entity.setCategory(resolveCategory(request.getCategory()));
            entity.setEnabled(request.getEnabled() == null ? Boolean.TRUE : request.getEnabled());
            entity.setRemark("批量导入");
            toInsert.add(entity);
            existed.add(word.toLowerCase(Locale.ROOT));
        }
        for (SensitiveWordEntity entity : toInsert) {
            sensitiveWordMapper.insert(entity);
        }
        invalidateCache();
        log.info("[SensitiveWord] 批量导入：提交 {} 个，实际新增 {} 个", distinct.size(), toInsert.size());
        return toInsert.size();
    }

    @Override
    public SensitiveWordCheckVo check(String text) {
        List<String> hits = match(text);
        if (hits.isEmpty()) {
            return SensitiveWordCheckVo.miss();
        }
        SensitiveWordCheckVo vo = new SensitiveWordCheckVo();
        vo.setHit(true);
        vo.setHitWords(hits);
        vo.setRejectMessage(null);
        return vo;
    }

    @Override
    public List<String> match(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<String> words = activeWords();
        if (words.isEmpty()) {
            return List.of();
        }
        String lowerText = text.toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        for (String word : words) {
            if (word != null && !word.isEmpty() && lowerText.contains(word.toLowerCase(Locale.ROOT))) {
                hits.add(word);
            }
        }
        return hits;
    }

    @Override
    public List<String> activeWords() {
        List<String> cached = this.activeWordsCache;
        if (cached != null && System.currentTimeMillis() - this.cacheLoadedAt < CACHE_TTL_MILLIS) {
            return cached;
        }
        synchronized (this) {
            cached = this.activeWordsCache;
            if (cached != null && System.currentTimeMillis() - this.cacheLoadedAt < CACHE_TTL_MILLIS) {
                return cached;
            }
            List<String> loaded = sensitiveWordMapper.selectList(
                            new LambdaQueryWrapper<SensitiveWordEntity>()
                                    .eq(SensitiveWordEntity::getEnabled, true))
                    .stream()
                    .map(SensitiveWordEntity::getWord)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .toList();
            this.activeWordsCache = loaded;
            this.cacheLoadedAt = System.currentTimeMillis();
            log.debug("[SensitiveWord] 词表缓存已加载，启用词 {} 个", loaded.size());
            return loaded;
        }
    }

    @Override
    public void refreshCache() {
        invalidateCache();
        int size = activeWords().size();
        log.info("[SensitiveWord] 手动刷新词表缓存，启用词 {} 个", size);
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /** 写操作后失效缓存：下一轮对话即按新词表拦截 */
    private void invalidateCache() {
        synchronized (this) {
            this.activeWordsCache = null;
            this.cacheLoadedAt = 0L;
        }
    }

    private SensitiveWordEntity requireEntity(Long id) {
        SensitiveWordEntity entity = sensitiveWordMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("敏感词不存在");
        }
        return entity;
    }

    private String normalizeWord(String word) {
        if (!StringUtils.hasText(word)) {
            throw new BusinessException("敏感词不能为空");
        }
        String trimmed = word.trim();
        if (trimmed.length() > 128) {
            throw new BusinessException("敏感词长度不能超过 128 个字符");
        }
        return trimmed;
    }

    /** 唯一性校验：update 时排除自身 */
    private void assertWordNotExists(String word, Long excludeId) {
        LambdaQueryWrapper<SensitiveWordEntity> wrapper = new LambdaQueryWrapper<SensitiveWordEntity>()
                .eq(SensitiveWordEntity::getWord, word);
        if (excludeId != null) {
            wrapper.ne(SensitiveWordEntity::getId, excludeId);
        }
        if (sensitiveWordMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("敏感词已存在：" + word);
        }
    }

    private String resolveCategory(String category) {
        return StringUtils.hasText(category) ? category.trim() : DEFAULT_CATEGORY;
    }
}
