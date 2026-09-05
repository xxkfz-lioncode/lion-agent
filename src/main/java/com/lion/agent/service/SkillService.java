package com.lion.agent.service;

import com.lion.agent.common.PageResult;
import com.lion.agent.model.dto.SkillRequest;
import com.lion.agent.model.entity.Skill;

import java.util.Map;

/**
 * 自定义技能服务：技能（Skill）的 CRUD、导出 Markdown 与试跑。
 *
 * <p>技能本质是一段可复用的提示词模板 + 参数定义：
 * 写库（增删改）成功后通过 {@link SkillToolRegistry#rebuild()} 重建工具注册表，
 * 使页面上的变更即时反映到对话工具池（每轮请求动态注入 ToolCallback），无需重启服务。</p>
 *
 * <p>技能按用户隔离：{@code userId=0} 为全局技能（对所有用户可见），
 * 其他 userId 为该用户私有技能，查询与增删改均校验归属。</p>
 */
public interface SkillService {

    /**
     * 分页查询指定用户的技能列表（支持按名称/描述关键字模糊搜索，按创建时间倒序）
     *
     * @param userId   技能归属用户 ID，为 0 时表示全局技能
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @param keyword  搜索关键字（匹配 name 或 description），为空则不过滤
     * @return 分页结果（含总条数、当前页记录）
     */
    PageResult<Skill> listByUser(Long userId, int pageNum, int pageSize, String keyword);

    /**
     * 创建技能：校验请求参数，写入 ai_skill 表并重建工具注册表
     *
     * @param userId  技能归属用户 ID（由登录态注入）
     * @param request 技能信息（name/description/prompt_template/parameters/status）
     * @return 落库后的完整技能实体（含自增 id 与审计字段）
     */
    Skill create(Long userId, SkillRequest request);

    /**
     * 更新技能：仅允许更新本人（或全局）的技能，更新后重建工具注册表
     *
     * @param id      技能主键 ID
     * @param userId  当前操作用户 ID，用于校验技能归属
     * @param request 新的技能信息（name/description/prompt_template/parameters/status）
     * @return 更新后的完整技能实体
     */
    Skill update(Long id, Long userId, SkillRequest request);

    /**
     * 逻辑删除技能：校验归属后标记 deleted，并重建工具注册表
     *
     * @param id     技能主键 ID
     * @param userId 当前操作用户 ID，用于校验技能归属
     */
    void delete(Long id, Long userId);

    /**
     * 按 ID 查询单个技能，带归属校验，不存在或越权时抛 {@code BusinessException}
     *
     * @param id     技能主键 ID
     * @param userId 当前操作用户 ID，用于校验技能归属
     * @return 技能实体
     */
    Skill getById(Long id, Long userId);

    /**
     * 导出技能为 Markdown 文件内容：
     * YAML frontmatter（name、description，description 含敏感字符时自动转义）+ 模板正文，
     * 前端预览后可直接下载为 {@code <name>.md}
     *
     * @param id     技能主键 ID
     * @param userId 当前操作用户 ID，用于校验技能归属
     * @return 标准 md 文本（以 {@code ---} frontmatter 开头）
     */
    String exportMarkdown(Long id, Long userId);

    /**
     * 试跑技能：将前端传入的参数替换进模板（缺省用参数默认值，残留占位符给出错误提示），
     * 调用底层模型生成结果，返回替换后的 prompt 与模型输出（不注入任何工具，避免递归调用）
     *
     * @param id     技能主键 ID
     * @param userId 当前操作用户 ID，用于校验技能归属
     * @param args   参数名 → 参数值的映射，可为 null（等价空 Map，全部使用默认值）
     * @return {renderedPrompt: 替换后的完整提示词, output: 模型输出}
     */
    Map<String, String> test(Long id, Long userId, Map<String, Object> args);
}
