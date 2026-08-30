package com.lion.agent.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lion.agent.common.PageResult;
import com.lion.agent.dto.SkillRequest;
import com.lion.agent.entity.Skill;
import com.lion.agent.exception.BusinessException;
import com.lion.agent.mapper.SkillMapper;
import com.lion.agent.service.SkillService;
import com.lion.agent.service.SkillToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 自定义技能服务实现：技能 CRUD、导出 Markdown 与试跑。
 *
 * <p>所有写操作（增删改）在事务提交后调用 {@link SkillToolRegistry#rebuild()}
 * 重建工具注册表，使技能变更即时生效。</p>
 */
@Service
@RequiredArgsConstructor
public class SkillServiceImpl implements SkillService {

    private final SkillMapper skillMapper;
    private final SkillToolRegistry skillToolRegistry;

    /**
     * 分页查询技能列表：按 userId 精确过滤（含全局技能），
     * 支持 name/description 关键字模糊搜索，按创建时间倒序
     */
    @Override
    public PageResult<Skill> listByUser(Long userId, int pageNum, int pageSize, String keyword) {
        LambdaQueryWrapper<Skill> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Skill::getUserId, userId);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(Skill::getName, keyword)
                    .or()
                    .like(Skill::getDescription, keyword));
        }
        wrapper.orderByDesc(Skill::getCreatedAt);
        Page<Skill> page = new Page<>(pageNum, pageSize);
        Page<Skill> result = skillMapper.selectPage(page, wrapper);
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    /**
     * 创建技能：组装实体写入 ai_skill 表，成功后重建工具注册表；
     * 事务回滚时 rebuild 的异常仅记录日志，不影响主流程
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Skill create(Long userId, SkillRequest request) {
        Skill skill = toEntity(request);
        skill.setUserId(userId);
        skillMapper.insert(skill);
        skillToolRegistry.rebuild();
        return skill;
    }

    /**
     * 更新技能：先校验归属与存在性，再覆盖可编辑字段（status 为空则保持原值），
     * 更新成功后重建工具注册表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Skill update(Long id, Long userId, SkillRequest request) {
        Skill skill = getById(id, userId);
        skill.setName(request.getName().trim());
        skill.setDescription(request.getDescription());
        skill.setPromptTemplate(request.getPromptTemplate());
        skill.setParameters(toParametersJson(request.getParameters()));
        if (request.getStatus() != null) {
            skill.setStatus(request.getStatus());
        }
        skillMapper.updateById(skill);
        skillToolRegistry.rebuild();
        return skill;
    }

    /**
     * 逻辑删除技能：校验归属后删除（MyBatis-Plus 逻辑删除置 deleted=1），
     * 并重建工具注册表移除该技能对应的 ToolCallback
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long userId) {
        Skill skill = getById(id, userId);
        skillMapper.deleteById(skill.getId());
        skillToolRegistry.rebuild();
    }

    /**
     * 按 ID + userId 查询技能（userId 校验归属，防止越权访问他人技能），
     * 查询不到抛 {@link BusinessException}
     */
    @Override
    public Skill getById(Long id, Long userId) {
        LambdaQueryWrapper<Skill> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Skill::getId, id)
                .eq(Skill::getUserId, userId);
        Skill skill = skillMapper.selectOne(wrapper);
        if (skill == null) {
            throw new BusinessException("技能不存在");
        }
        return skill;
    }

    /**
     * 导出 Markdown：组装 YAML frontmatter（name + 转义后的 description）与模板正文，
     * description 含冒号/井号/引号时加双引号包裹保证 YAML 可解析
     */
    @Override
    public String exportMarkdown(Long id, Long userId) {
        Skill skill = getById(id, userId);
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(skill.getName()).append('\n');
        sb.append("description: ").append(yamlEscape(skill.getDescription())).append('\n');
        sb.append("---\n\n");
        sb.append(skill.getPromptTemplate() == null ? "" : skill.getPromptTemplate());
        return sb.toString();
    }

    /**
     * 试跑：委托 {@link SkillToolRegistry#renderAndRun} 完成
     * 参数替换（缺省用默认值）+ 裸模型调用，args 为 null 时按空 Map 处理
     */
    @Override
    public Map<String, String> test(Long id, Long userId, Map<String, Object> args) {
        Skill skill = getById(id, userId);
        return skillToolRegistry.renderAndRun(skill, args == null ? Map.of() : args);
    }

    /**
     * 将请求 DTO 组装为技能实体：name 去除首尾空白，
     * status 为空默认启用（1）
     */
    private Skill toEntity(SkillRequest request) {
        Skill skill = new Skill();
        skill.setName(request.getName().trim());
        skill.setDescription(request.getDescription());
        skill.setPromptTemplate(request.getPromptTemplate());
        skill.setParameters(toParametersJson(request.getParameters()));
        skill.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        return skill;
    }

    /**
     * 参数列表序列化为 JSON 字符串入库（ai_skill.parameters），
     * 无参数时存 NULL，便于前端以 JSON 数组解析
     */
    private String toParametersJson(List<SkillRequest.SkillParam> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        return JSONUtil.toJsonStr(params);
    }

    /**
     * YAML frontmatter 转义：description 含冒号/井号/引号等敏感字符时加引号包裹，
     * 保证导出的 md 文件 frontmatter 可被标准 YAML 解析
     */
    private String yamlEscape(String text) {
        if (text == null) {
            return "";
        }
        if (text.contains(":") || text.contains("#") || text.contains("\"")) {
            return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return text;
    }
}
