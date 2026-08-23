package com.lion.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户长期记忆实体（ai_memory）
 *
 * <p>由 {@code MemoryServiceImpl} 在每次对话/知识库问答完成后，异步调用 LLM 抽取
 * 用户持久性事实与偏好后写入；向量副本存 Milvus collection lion_agent_memory，
 * 下次对话时经 {@code LongTermMemoryAdvisor} 检索注入。</p>
 */
@Data
@TableName("ai_memory")
public class AiMemory {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户 ID */
    private Long userId;

    /** 记忆类型：fact-事实 preference-偏好 */
    private String memoryType;

    /** 记忆内容（如：用户预算是 50 万） */
    private String content;

    /** 重要性 1-5（LLM 抽取时打分，越高越重要） */
    private Integer importance;

    /** 来源会话 ID（知识库问答为 NULL） */
    private Long sourceConversationId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 逻辑删除：0-未删除 1-已删除 */
    @TableLogic
    private Integer deleted;
}
