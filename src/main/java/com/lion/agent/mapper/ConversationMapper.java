package com.lion.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lion.agent.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话 Mapper
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
