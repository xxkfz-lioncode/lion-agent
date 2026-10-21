package com.lion.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lion.agent.pojo.entity.SensitiveWordEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 敏感词 Mapper
 */
@Mapper
public interface SensitiveWordMapper extends BaseMapper<SensitiveWordEntity> {
}
