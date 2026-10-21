package com.lion.agent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.mapper.ModelConfigMapper;
import com.lion.agent.pojo.dto.ModelConfigRequest;
import com.lion.agent.pojo.entity.ModelConfigEntity;
import com.lion.agent.pojo.vo.ModelConfigVo;
import com.lion.agent.pojo.vo.ModelTestVo;
import com.lion.agent.service.ModelConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 模型配置管理实现
 *
 * <p>所有模型共用服务端的单个端点与密钥（{@code spring.ai.openai.base-url} /
 * {@code spring.ai.openai.api-key}），切换模型时不新建客户端，仅按次覆盖
 * {@code ChatOptions.model}，热切换、不重启服务；ai_model_config 表中的
 * base_url / api_key 字段保留但不再参与调用。</p>
 *
 * <p>不再为单条记录维护密钥：端点与密钥由服务端配置统一提供。</p>
 */
@Slf4j
@Service
public class ModelConfigServiceImpl implements ModelConfigService {

    private final ModelConfigMapper modelConfigMapper;

    /** 连通性测试专用 ChatClient：不挂任何 Advisor，链路最简，避免记忆/缓存等副作用 */
    private final ChatClient testChatClient;

    public ModelConfigServiceImpl(ModelConfigMapper modelConfigMapper, ChatModel chatModel) {
        this.modelConfigMapper = modelConfigMapper;
        this.testChatClient = ChatClient.builder(chatModel).build();
    }

    @Override
    public List<ModelConfigVo> list() {
        return modelConfigMapper.selectList(
                        Wrappers.<ModelConfigEntity>lambdaQuery()
                                .orderByAsc(ModelConfigEntity::getModelType)
                                .orderByDesc(ModelConfigEntity::getIsDefault)
                                .orderByAsc(ModelConfigEntity::getId))
                .stream()
                .map(this::toVo)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelConfigVo create(ModelConfigRequest request) {
        checkModelNameUnique(request.getModelName(), null);
        ModelConfigEntity entity = new ModelConfigEntity();
        applyRequest(entity, request);
        if (Boolean.TRUE.equals(request.getIsDefault())) {
            clearDefault(request.getModelType(), null);
        }
        modelConfigMapper.insert(entity);
        return toVo(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelConfigVo update(Long id, ModelConfigRequest request) {
        ModelConfigEntity entity = requireEntity(id);
        checkModelNameUnique(request.getModelName(), id);
        applyRequest(entity, request);
        if (Boolean.TRUE.equals(request.getIsDefault())) {
            clearDefault(request.getModelType(), id);
        }
        modelConfigMapper.updateById(entity);
        return toVo(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        ModelConfigEntity entity = requireEntity(id);
        if (Boolean.TRUE.equals(entity.getIsDefault())) {
            throw new BusinessException("默认模型不可删除，请先将其他模型设为默认");
        }
        modelConfigMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long id) {
        ModelConfigEntity entity = requireEntity(id);
        if (Boolean.FALSE.equals(entity.getEnabled())) {
            throw new BusinessException("已停用的模型不能设为默认，请先启用");
        }
        clearDefault(entity.getModelType(), id);
        entity.setIsDefault(true);
        modelConfigMapper.updateById(entity);
    }

    @Override
    public String getDefaultModelName(String modelType) {
        ModelConfigEntity entity = modelConfigMapper.selectOne(
                Wrappers.<ModelConfigEntity>lambdaQuery()
                        .eq(ModelConfigEntity::getModelType, modelType)
                        .eq(ModelConfigEntity::getEnabled, true)
                        .eq(ModelConfigEntity::getIsDefault, true)
                        .last("LIMIT 1"));
        return entity == null ? null : entity.getModelName();
    }

    @Override
    public ModelTestVo test(Long id) {
        ModelConfigEntity entity = requireEntity(id);
        Instant start = Instant.now();
        try {
            // Spring AI 2.0：必须传厂商专属 Options，否则 OpenAiChatModel.createRequest 强转 OpenAiChatOptions 会抛 ClassCastException
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(entity.getModelName());
            if (entity.getTemperature() != null) {
                options.temperature(entity.getTemperature());
            }
            // 极短消息降低测试成本（DashScope 按 token 计费，此处通常消耗个位数 token）
            String reply = testChatClient.prompt()
                    .options(options)
                    .user("你好")
                    .call()
                    .content();
            long costMs = Duration.between(start, Instant.now()).toMillis();
            String snippet = StringUtils.hasText(reply) && reply.length() > 200
                    ? reply.substring(0, 200) + "..." : reply;
            return new ModelTestVo(entity.getModelName(), snippet, costMs);
        } catch (Exception e) {
            log.error("模型连通性测试失败 | model={}", entity.getModelName(), e);
            throw new BusinessException("模型连接失败：" + e.getMessage());
        }
    }

    // ==================== 私有工具 ====================

    private ModelConfigEntity requireEntity(Long id) {
        ModelConfigEntity entity = modelConfigMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("模型配置不存在");
        }
        return entity;
    }

    /** model_name 唯一性校验（excludeId 用于更新场景排除自身） */
    private void checkModelNameUnique(String modelName, Long excludeId) {
        Long count = modelConfigMapper.selectCount(
                Wrappers.<ModelConfigEntity>lambdaQuery()
                        .eq(ModelConfigEntity::getModelName, modelName)
                        .ne(excludeId != null, ModelConfigEntity::getId, excludeId));
        if (count != null && count > 0) {
            throw new BusinessException("模型名已存在：" + modelName);
        }
    }

    /** 将同类型其他记录的默认标记清空（excludeId 用于保持当前记录不变） */
    private void clearDefault(String modelType, Long excludeId) {
        ModelConfigEntity clear = new ModelConfigEntity();
        clear.setIsDefault(false);
        modelConfigMapper.update(clear,
                Wrappers.<ModelConfigEntity>lambdaUpdate()
                        .eq(ModelConfigEntity::getModelType, modelType)
                        .eq(ModelConfigEntity::getIsDefault, true)
                        .ne(excludeId != null, ModelConfigEntity::getId, excludeId));
    }

    private void applyRequest(ModelConfigEntity entity, ModelConfigRequest request) {
        entity.setDisplayName(request.getDisplayName());
        entity.setModelName(request.getModelName());
        entity.setModelType(request.getModelType());
        entity.setTemperature(request.getTemperature());
        entity.setEnabled(request.getEnabled() == null || request.getEnabled());
        entity.setIsDefault(Boolean.TRUE.equals(request.getIsDefault()));
        entity.setRemark(request.getRemark());
    }

    private ModelConfigVo toVo(ModelConfigEntity entity) {
        ModelConfigVo vo = new ModelConfigVo();
        vo.setId(entity.getId());
        vo.setDisplayName(entity.getDisplayName());
        vo.setModelName(entity.getModelName());
        vo.setModelType(entity.getModelType());
        vo.setTemperature(entity.getTemperature());
        vo.setEnabled(entity.getEnabled());
        vo.setIsDefault(entity.getIsDefault());
        vo.setRemark(entity.getRemark());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }
}
