package com.lion.agent.dify.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Dify 应用编排流程图（Chatflow / Workflow 画布）
 *
 * <p>原始数据来自 Dify 控制台应用详情里的 {@code workflow.graph}（ReactFlow 格式），
 * 这里做一层归一化，前端只需关心节点坐标与连线，不用处理 Dify 的私有字段。
 */
@Data
@Builder
public class DifyFlowGraphVO {

    /** 节点列表 */
    private List<FlowNode> nodes;

    /** 连线列表 */
    private List<FlowEdge> edges;

    /** 画布原点与缩放（Dify 的 viewport，可选） */
    private Double viewportX;
    private Double viewportY;
    private Double viewportZoom;

    /**
     * 画布节点
     */
    @Data
    @Builder
    public static class FlowNode {

        /** 节点 ID（Dify 内部 id，连线用） */
        private String id;

        /** 节点类型：start / llm / knowledge-retrieval / code / tool / if-else / answer / end … */
        private String type;

        /** 节点标题（画布上显示的名字） */
        private String title;

        /** 画布坐标 */
        private Double x;
        private Double y;

        /** 节点尺寸（部分版本缺失，前端有默认值） */
        private Double width;
        private Double height;

        /** 节点描述（部分节点有 desc） */
        private String desc;
    }

    /**
     * 画布连线
     */
    @Data
    @Builder
    public static class FlowEdge {

        private String id;
        private String source;
        private String target;
        private String sourceHandle;
        private String targetHandle;
    }
}
