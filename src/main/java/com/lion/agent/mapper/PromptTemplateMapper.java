package com.lion.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lion.agent.entity.PromptTemplateEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 提示词模板 Mapper
 */
@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplateEntity> {
}
