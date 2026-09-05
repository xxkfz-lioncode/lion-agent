package com.lion.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lion.agent.model.entity.AiMemory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户长期记忆 Mapper
 */
@Mapper
public interface AiMemoryMapper extends BaseMapper<AiMemory> {
}
