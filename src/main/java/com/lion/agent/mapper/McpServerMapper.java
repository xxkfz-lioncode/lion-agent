package com.lion.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lion.agent.model.entity.McpServerEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * MCP 服务 Mapper
 */
@Mapper
public interface McpServerMapper extends BaseMapper<McpServerEntity> {
}
