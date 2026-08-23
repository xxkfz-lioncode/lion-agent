package com.lion.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lion.agent.entity.TokenUsage;
import org.apache.ibatis.annotations.Mapper;

/**
 * Token 用量统计 Mapper
 */
@Mapper
public interface TokenUsageMapper extends BaseMapper<TokenUsage> {
}
