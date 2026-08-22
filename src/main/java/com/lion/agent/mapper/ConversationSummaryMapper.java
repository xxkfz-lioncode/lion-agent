package com.lion.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lion.agent.entity.ConversationSummary;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话摘要 Mapper
 */
@Mapper
public interface ConversationSummaryMapper extends BaseMapper<ConversationSummary> {
}
