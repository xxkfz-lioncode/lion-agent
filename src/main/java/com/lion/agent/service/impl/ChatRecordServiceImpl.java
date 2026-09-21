package com.lion.agent.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.common.result.PageResult;
import com.lion.agent.mapper.ChatMessageMapper;
import com.lion.agent.mapper.ConversationMapper;
import com.lion.agent.pojo.entity.ChatMessage;
import com.lion.agent.pojo.entity.Conversation;
import com.lion.agent.pojo.vo.ChatRecordStatsVo;
import com.lion.agent.pojo.vo.ConversationRecordVo;
import com.lion.agent.service.ChatRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 会话记录查询实现（只读，不做任何写操作）
 *
 * <p>数据范围：始终限定为当前登录用户的会话，避免越权查看他人记录。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRecordServiceImpl implements ChatRecordService {

    private final ConversationMapper conversationMapper;

    private final ChatMessageMapper chatMessageMapper;

    @Override
    public PageResult<ConversationRecordVo> listConversations(int pageNum, int pageSize, String keyword) {
        long userId = StpUtil.getLoginIdAsLong();

        LambdaQueryWrapper<Conversation> wrapper = Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getUserId, userId);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            // 命中消息内容的会话：先按内容查出会话 ID，再与「标题命中」合并
            Set<Long> hitIds = searchConversationIdsByContent(kw);
            wrapper.and(w -> {
                w.like(Conversation::getTitle, kw);
                if (!hitIds.isEmpty()) {
                    w.or().in(Conversation::getId, hitIds);
                }
            });
        }
        wrapper.orderByDesc(Conversation::getUpdatedAt);

        Page<Conversation> page = new Page<>(pageNum, pageSize);
        Page<Conversation> result = conversationMapper.selectPage(page, wrapper);
        List<Conversation> records = result.getRecords();
        if (records.isEmpty()) {
            return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), Collections.emptyList());
        }

        // 批量聚合消息数，避免 N+1
        Map<Long, MessageStat> stats = countMessages(records.stream().map(Conversation::getId).toList());
        List<ConversationRecordVo> vos = records.stream().map(c -> {
            ConversationRecordVo vo = new ConversationRecordVo();
            vo.setId(c.getId());
            vo.setTitle(c.getTitle());
            vo.setCreatedAt(c.getCreatedAt());
            vo.setUpdatedAt(c.getUpdatedAt());
            MessageStat stat = stats.get(c.getId());
            vo.setMessageCount(stat == null ? 0L : stat.count);
            vo.setUserMessageCount(stat == null ? 0L : stat.userCount);
            vo.setAssistantMessageCount(stat == null ? 0L : stat.assistantCount);
            vo.setLastMessageAt(stat == null ? null : stat.lastMessageAt);
            return vo;
        }).toList();

        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), vos);
    }

    @Override
    public PageResult<ChatMessage> listMessages(Long conversationId, int pageNum, int pageSize,
                                                String role, String keyword, boolean asc) {
        long userId = StpUtil.getLoginIdAsLong();
        checkOwner(conversationId, userId);

        LambdaQueryWrapper<ChatMessage> wrapper = Wrappers.<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getConversationId, conversationId);
        if (StringUtils.hasText(role)) {
            wrapper.eq(ChatMessage::getRole, role.trim());
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.like(ChatMessage::getContent, keyword.trim());
        }
        // 默认正序：按消息产生顺序阅读（最早在前）；asc=false 时倒序，先看最新
        if (asc) {
            wrapper.orderByAsc(ChatMessage::getId);
        } else {
            wrapper.orderByDesc(ChatMessage::getId);
        }

        Page<ChatMessage> page = new Page<>(pageNum, pageSize);
        Page<ChatMessage> result = chatMessageMapper.selectPage(page, wrapper);
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    @Override
    public ChatRecordStatsVo stats() {
        long userId = StpUtil.getLoginIdAsLong();
        List<Long> conversationIds = conversationMapper.selectList(
                        Wrappers.<Conversation>lambdaQuery().eq(Conversation::getUserId, userId))
                .stream()
                .map(Conversation::getId)
                .toList();

        ChatRecordStatsVo vo = new ChatRecordStatsVo();
        vo.setConversationCount((long) conversationIds.size());
        if (conversationIds.isEmpty()) {
            vo.setMessageCount(0L);
            vo.setUserMessageCount(0L);
            vo.setAssistantMessageCount(0L);
            return vo;
        }

        // 一次分组查询同时得出总数 / 用户提问数 / AI 回复数
        List<Map<String, Object>> rows = chatMessageMapper.selectMaps(
                Wrappers.<ChatMessage>query()
                        .select("role, count(1) as cnt")
                        .in("conversation_id", conversationIds)
                        .groupBy("role"));
        long total = 0L;
        long userCount = 0L;
        long assistantCount = 0L;
        for (Map<String, Object> row : rows) {
            long cnt = toLong(row.get("cnt"));
            total += cnt;
            String role = String.valueOf(row.get("role"));
            if ("user".equals(role)) {
                userCount = cnt;
            } else if ("assistant".equals(role)) {
                assistantCount = cnt;
            }
        }
        vo.setMessageCount(total);
        vo.setUserMessageCount(userCount);
        vo.setAssistantMessageCount(assistantCount);
        return vo;
    }

    // ==================== 私有方法 ====================

    /**
     * 按消息内容模糊搜索命中的会话 ID（只返回 ID，避免把正文全部捞到内存）
     */
    private Set<Long> searchConversationIdsByContent(String keyword) {
        List<Object> ids = chatMessageMapper.selectObjs(
                Wrappers.<ChatMessage>query()
                        .select("distinct conversation_id")
                        .like("content", keyword));
        if (ids == null || ids.isEmpty()) {
            return Collections.emptySet();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .map(id -> Long.parseLong(id.toString()))
                .collect(Collectors.toSet());
    }

    /**
     * 批量统计各会话的消息条数（含用户/AI 分项）与最后一条消息时间。
     * 按 conversation_id + role 分组一次查出，Java 侧聚合成每会话一条统计。
     */
    private Map<Long, MessageStat> countMessages(List<Long> conversationIds) {
        List<Map<String, Object>> rows = chatMessageMapper.selectMaps(
                Wrappers.<ChatMessage>query()
                        .select("conversation_id, role, count(1) as cnt, max(created_at) as last_at")
                        .in("conversation_id", conversationIds)
                        .groupBy("conversation_id, role"));
        Map<Long, MessageStat> stats = new HashMap<>();
        for (Map<String, Object> row : rows) {
            long conversationId = Long.parseLong(String.valueOf(row.get("conversation_id")));
            MessageStat stat = stats.computeIfAbsent(conversationId, k -> new MessageStat());
            long cnt = toLong(row.get("cnt"));
            stat.count += cnt;
            String role = String.valueOf(row.get("role"));
            if ("user".equals(role)) {
                stat.userCount = cnt;
            } else if ("assistant".equals(role)) {
                stat.assistantCount = cnt;
            }
            Object lastAt = row.get("last_at");
            if (lastAt instanceof LocalDateTime time) {
                // 每个 role 分组各有一条 max(created_at)，取较大者
                if (stat.lastMessageAt == null || time.isAfter(stat.lastMessageAt)) {
                    stat.lastMessageAt = time;
                }
            }
        }
        return stats;
    }

    /**
     * 校验会话归属，越权/不存在直接抛业务异常
     */
    private void checkOwner(Long conversationId, long userId) {
        Long count = conversationMapper.selectCount(
                Wrappers.<Conversation>lambdaQuery()
                        .eq(Conversation::getId, conversationId)
                        .eq(Conversation::getUserId, userId));
        if (count == null || count == 0) {
            throw new BusinessException("会话不存在或无权访问");
        }
    }

    private long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    /**
     * 会话维度的消息聚合结果
     */
    private static class MessageStat {

        private long count;

        private long userCount;

        private long assistantCount;

        private LocalDateTime lastMessageAt;
    }
}
