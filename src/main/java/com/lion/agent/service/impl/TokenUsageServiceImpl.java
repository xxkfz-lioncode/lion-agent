package com.lion.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lion.agent.common.PageResult;
import com.lion.agent.entity.TokenUsage;
import com.lion.agent.mapper.ConversationMapper;
import com.lion.agent.mapper.TokenUsageMapper;
import com.lion.agent.service.TokenUsageService;
import com.lion.agent.vo.TokenUsageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Token 用量统计服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageServiceImpl implements TokenUsageService {

    private final TokenUsageMapper tokenUsageMapper;
    private final ConversationMapper conversationMapper;

    /** 时间格式化器（yyyy-MM-dd HH:mm:ss） */
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 记录一次调用用量（异步落库，由专用线程池执行，不阻塞主调用链路；
     * 内部兜底 try-catch，落库失败仅告警）
     */
    @Async("tokenUsageExecutor")
    @Override
    public void record(TokenUsage usage) {
        if (usage == null) {
            return;
        }
        try {
            tokenUsageMapper.insert(usage);
        } catch (Exception e) {
            // 统计落库失败不能影响主调用链路，仅告警
            log.warn("Token 用量落库失败: {}", e.getMessage());
        }
    }

    @Override
    public PageResult<TokenUsageVO> page(Long userId, int pageNum, int pageSize, String chatType) {
        QueryWrapper<TokenUsage> wrapper = Wrappers.<TokenUsage>query()
                .eq("user_id", userId);
        if (StringUtils.hasText(chatType)) {
            wrapper.eq("chat_type", chatType);
        }
        wrapper.orderByDesc("id");
        Page<TokenUsage> page = tokenUsageMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);

        // 批量查询会话标题，避免 N+1
        Set<Long> convIds = page.getRecords().stream()
                .map(TokenUsage::getConversationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> titles = new HashMap<>();
        if (!convIds.isEmpty()) {
            conversationMapper.selectBatchIds(convIds)
                    .forEach(c -> titles.put(c.getId(), c.getTitle()));
        }
        List<TokenUsageVO> vos = page.getRecords().stream().map(u -> {
            TokenUsageVO vo = new TokenUsageVO();
            BeanUtils.copyProperties(u, vo);
            vo.setConversationTitle(u.getConversationId() == null ? null : titles.get(u.getConversationId()));
            if (u.getCreatedAt() != null) {
                vo.setCreatedAtStr(u.getCreatedAt().format(DATE_TIME_FORMATTER));
            }
            return vo;
        }).toList();
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), vos);
    }

    @Override
    public Map<String, Object> statistics(Long userId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        Map<String, Object> total = aggregate(userId, null);
        stats.put("totalCalls", number(total.get("calls")));
        stats.put("totalTokens", number(total.get("tokens")));
        Map<String, Object> today = aggregate(userId, LocalDate.now().atStartOfDay());
        stats.put("todayCalls", number(today.get("calls")));
        stats.put("todayTokens", number(today.get("tokens")));
        Map<String, Object> avg = aggregateAvgCost(userId);
        stats.put("avgCostMs", number(avg.get("avg_cost")));
        return stats;
    }

    private Map<String, Object> aggregate(Long userId, LocalDateTime from) {
        QueryWrapper<TokenUsage> qw = Wrappers.<TokenUsage>query()
                .select("COUNT(*) AS calls", "COALESCE(SUM(total_tokens), 0) AS tokens")
                .eq("user_id", userId);
        if (from != null) {
            qw.ge("created_at", from);
        }
        List<Map<String, Object>> list = tokenUsageMapper.selectMaps(qw);
        return list.isEmpty() ? Map.of("calls", 0L, "tokens", 0L) : list.get(0);
    }

    private Map<String, Object> aggregateAvgCost(Long userId) {
        QueryWrapper<TokenUsage> qw = Wrappers.<TokenUsage>query()
                .select("COALESCE(AVG(cost_ms), 0) AS avg_cost")
                .eq("user_id", userId);
        List<Map<String, Object>> list = tokenUsageMapper.selectMaps(qw);
        return list.isEmpty() ? Map.of("avg_cost", 0d) : list.get(0);
    }

    private long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
