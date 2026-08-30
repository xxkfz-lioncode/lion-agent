package com.lion.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 自定义技能（Skill）
 *
 * <p>页面维护的提示词型技能：后台存 {@code prompt_template} 模板与参数定义，
 * 运行期经 {@code com.lion.agent.service.SkillToolRegistry} 动态构建为
 * {@code org.springframework.ai.tool.ToolCallback}（参数替换 {@code {{param}}}
 * 占位符后调用 LLM），随对话按需注册给模型。</p>
 */
@Data
@TableName("ai_skill")
public class Skill {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 ID（0=全局技能，当前实现为用户私有，按 userId 隔离） */
    private Long userId;

    /** 技能名（工具名，模型可见，字母数字下划线，与内置工具不重名） */
    private String name;

    /** 技能描述（模型判断何时调用的依据，同时是向量检索语料） */
    private String description;

    /** 提示词模板，{{param}} 占位符运行时替换 */
    private String promptTemplate;

    /** 参数定义 JSON 数组：[{"name","type","description","required","defaultValue"}] */
    private String parameters;

    /** 状态：0-禁用 1-启用（禁用技能不构建工具、不参与检索） */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
