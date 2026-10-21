package com.lion.agent.service;

import com.lion.agent.pojo.dto.ModelConfigRequest;
import com.lion.agent.pojo.vo.ModelConfigVo;
import com.lion.agent.pojo.vo.ModelTestVo;

import java.util.List;

/**
 * 模型配置管理
 *
 * <p>维护可切换的大模型列表（{@code ai_model_config} 表）。模型统一走
 * DashScope OpenAI 兼容端点，切换仅改变请求中的 model 名称——对话链路
 * 每次调用前通过 {@link #getDefaultModelName(String)} 取当前默认模型，
 * 以 ChatOptions 覆盖，热切换、无需重启。</p>
 */
public interface ModelConfigService {

    /**
     * 列表：全部模型配置（默认模型排前，按类型分组）
     */
    List<ModelConfigVo> list();

    /**
     * 新增模型配置（model_name 唯一）
     */
    ModelConfigVo create(ModelConfigRequest request);

    /**
     * 更新模型配置
     */
    ModelConfigVo update(Long id, ModelConfigRequest request);

    /**
     * 删除模型配置（默认模型不可删除，需先切换默认）
     */
    void delete(Long id);

    /**
     * 设为该类型的默认模型（同类型其他记录自动取消默认）
     */
    void setDefault(Long id);

    /**
     * 获取指定类型的当前默认模型名
     *
     * @param modelType chat-文本对话 / multimodal-多模态
     * @return 模型名；表中无启用的默认记录时返回 null（调用方回退到 yml 配置）
     */
    String getDefaultModelName(String modelType);

    /**
     * 连通性测试：用该模型发送一条极短消息，返回回复与耗时
     */
    ModelTestVo test(Long id);
}
