package com.lion.agent.service;

import com.lion.agent.model.dto.PromptTemplateRequest;
import com.lion.agent.model.vo.PromptTemplateVo;

import java.util.List;

/**
 * 提示词模板管理
 *
 * <p>模板数据以 {@code ai_prompt_template} 表为准（应用启动时会自动将
 * {@code classpath:prompts/*.st} 中的模板导入数据库，保证表内默认有数据），
 * 查询/渲染时先取数据库，缺失时回退读取 classpath 文件。</p>
 */
public interface PromptTemplateService {

    /**
     * 列表：返回数据库中全部提示词模板（按文件名排序）
     */
    List<PromptTemplateVo> list();

    /**
     * 按模板名（不含 .st）查询单个模板；数据库缺失时回退读取 classpath 文件并自动补写数据库
     */
    PromptTemplateVo getByName(String name);

    /**
     * 获取模板正文（供 PromptConfig 渲染使用）：数据库优先，缺失时回退文件并自动补写数据库
     *
     * @param name 模板名（不含 .st，如 system-prompt）
     */
    String getContent(String name);

    /**
     * 更新数据库中的模板内容，保存后下一次请求即使用最新内容
     */
    PromptTemplateVo update(String name, PromptTemplateRequest request);

    /**
     * 将 classpath:prompts/*.st 文件内容全量同步到数据库（新增 + 覆盖），用于代码改模板后的同步/重置
     */
    void refreshFromFiles();
}
