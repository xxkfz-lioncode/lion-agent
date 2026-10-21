package com.lion.agent.dify.service;

import com.lion.agent.dify.vo.DifyAppVO;
import com.lion.agent.dify.vo.DifyFlowGraphVO;

import java.util.List;

/**
 * Dify 应用服务：读取配置中心登记的 Dify 应用，供前端「应用市场」展示与路由跳转
 */
public interface DifyAppService {

    /**
     * 全部应用（不含密钥）
     */
    List<DifyAppVO> listApps();

    /**
     * 单个应用详情
     */
    DifyAppVO getApp(String appCode);

    /**
     * 按应用编码解析出 API Key（后端内部使用，不对外暴露）
     *
     * @param appCode 应用编码；为空时取该类型下第一个应用
     * @param type    chat / workflow
     */
    String resolveApiKey(String appCode, String type);

    /**
     * 应用编排流程图（仅 Chatflow / Workflow 有值）
     *
     * <p>数据来源：Dify 控制台应用详情的 {@code workflow.graph}，后端归一化后返回坐标与连线。
     * 基础聊天助手（chat / agent-chat）没有画布，返回空节点列表。
     */
    DifyFlowGraphVO graph(String appCode);
}
