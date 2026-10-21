package com.lion.agent.dify.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Dify 应用卡片视图（不含任何密钥），供前端「应用市场」展示
 *
 * <p>数据来自 Dify Server API（应用列表实时拉取），code 即 Dify 应用 ID。
 */
@Data
@Builder
public class DifyAppVO {

    /** 应用编码（= Dify 应用 ID，路由参数、接口传参用这个，不是 API Key） */
    private String code;

    /** 应用名称 */
    private String name;

    /** 归一化类型：chat / workflow（决定前端跳对话页还是工作流页） */
    private String type;

    /** Dify 原始 mode：chat / agent-chat / advanced-chat / chatflow / workflow 等 */
    private String mode;

    /** 图标（Dify 侧是 emoji） */
    private String icon;

    /** 图标背景色（如 #FFEAD5） */
    private String iconBackground;

    /** 描述 */
    private String description;
}
