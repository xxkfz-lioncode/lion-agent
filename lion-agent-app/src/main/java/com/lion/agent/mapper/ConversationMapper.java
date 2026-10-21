package com.lion.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lion.agent.pojo.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 会话 Mapper
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    /**
     * 刷新会话：更新时间必刷（会话列表按此倒序），标题仅在仍是默认值时用首条用户消息覆盖。
     *
     * <p>合并为一条 SQL：原实现是「select 会话 → select 首条消息 → update」三次往返，
     * 这里用子查询 + 条件赋值一次完成，标题已自定义时保持不变。
     */
    @Update("UPDATE chat_conversation c SET " +
            "  c.updated_at = NOW(), " +
            "  c.title = IF(c.title IS NULL OR c.title = '' OR c.title = '新对话', " +
            "       COALESCE((SELECT LEFT(m.content, 20) FROM chat_message m " +
            "                WHERE m.conversation_id = c.id AND m.role = 'user' AND m.deleted = 0 " +
            "                ORDER BY m.id ASC LIMIT 1), c.title), " +
            "       c.title) " +
            "WHERE c.id = #{conversationId}")
    int touchAndRefreshTitle(@Param("conversationId") Long conversationId);
}
