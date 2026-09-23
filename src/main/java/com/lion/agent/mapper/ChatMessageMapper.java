package com.lion.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lion.agent.pojo.entity.ChatMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

import java.util.List;

/**
 * 聊天消息 Mapper
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /**
     * 批量插入消息（单条 multi-values SQL）：一轮对话的「用户消息 + AI 回复」一次落库，
     * 相比两次 insert 少一次网络往返与事务开销。
     *
     * <p>主键回填依赖 MySQL 驱动的 {@code getGeneratedKeys}（批量插入会按插入顺序返回全部自增 ID），
     * 由 {@code useGeneratedKeys + keyProperty} 写回每个元素的 {@code id}。
     * 调用方需容忍极端情况下 id 为 null（不影响消息落库，仅影响返回给前端的消息 ID）。
     */
    @Insert("<script>" +
            "INSERT INTO chat_message (conversation_id, role, content, created_at) VALUES " +
            "<foreach collection='list' item='m' separator=','>" +
            "(#{m.conversationId}, #{m.role}, #{m.content}, #{m.createdAt})" +
            "</foreach>" +
            "</script>")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertBatch(List<ChatMessage> list);
}
