package com.lion.agent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lion.agent.dto.PromptTemplateRequest;
import com.lion.agent.entity.PromptTemplateEntity;
import com.lion.agent.exception.BusinessException;
import com.lion.agent.mapper.PromptTemplateMapper;
import com.lion.agent.service.PromptTemplateService;
import com.lion.agent.vo.PromptTemplateVo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 提示词模板管理实现
 *
 * <p>启动时（{@link #init()}）自动将 {@code classpath:prompts/*.st} 全部导入
 * {@code ai_prompt_template} 表，使表内默认有数据；此后数据以数据库为准，
 * 若某条被删除则回退读取文件并自动补写。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateServiceImpl implements PromptTemplateService {

    private final PromptTemplateMapper promptTemplateMapper;
    private final ResourceLoader resourceLoader;

    private static final String PROMPTS_CLASSPATH = "classpath:prompts/*.st";

    /** 模板文件名 -> 用途描述（与 prompts 目录保持一致） */
    private static final Map<String, String> DESCRIPTIONS;

    static {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("system-prompt.st", "系统提示词（给模型设定角色与全局约束）");
        map.put("intent-classify.st", "意图识别（判断用户是否咨询知识库）");
        map.put("kb-answer.st", "知识库问答（基于检索上下文生成回答）");
        map.put("kb-gate.st", "知识库门控（复评上下文是否足以回答）");
        map.put("kb-rewrite.st", "知识库查询改写（单路召回）");
        map.put("kb-rewrite-multi.st", "知识库多路查询改写（多路召回）");
        map.put("memory-extract.st", "长期记忆抽取（从对话中提炼事实/偏好）");
        map.put("memory-inject.st", "长期记忆注入（把记忆拼进 SystemMessage）");
        map.put("memory-rewrite.st", "长期记忆查询改写");
        map.put("summary-compress.st", "会话摘要压缩");
        map.put("summary-merge.st", "会话摘要合并");
        DESCRIPTIONS = Collections.unmodifiableMap(map);
    }

    /** 启动时把 classpath 模板文件导入数据库（仅补缺失，不覆盖已存在的库记录） */
    @PostConstruct
    public void init() {
        try {
            refreshFromFiles();
        } catch (Exception e) {
            // 表不存在（未执行 init.sql）等场景不阻塞启动，运行时操作会给出明确报错
            log.warn("初始化提示词模板表失败：{}（请确认已执行 resources/db/init.sql）", e.getMessage());
        }
    }

    @Override
    public List<PromptTemplateVo> list() {
        return promptTemplateMapper.selectList(
                        Wrappers.<PromptTemplateEntity>lambdaQuery()
                                .orderByAsc(PromptTemplateEntity::getFileName))
                .stream()
                .map(this::toVo)
                .collect(Collectors.toList());
    }

    @Override
    public PromptTemplateVo getByName(String name) {
        String fileName = resolveFileName(name);
        PromptTemplateEntity db = selectByFileName(fileName);
        if (db != null) {
            return toVo(db);
        }
        // 数据库缺失：回退文件并自动补写，保证表内数据完整
        String fileContent = readResource(findResource(fileName));
        return toVo(insert(fileName, fileContent));
    }

    @Override
    public String getContent(String name) {
        return getByName(name).getContent();
    }

    @Override
    public PromptTemplateVo update(String name, PromptTemplateRequest request) {
        String fileName = resolveFileName(name);
        // 校验文件存在，避免随意新增无对应文件的模板
        findResource(fileName);

        PromptTemplateEntity db = selectByFileName(fileName);
        if (db == null) {
            db = newEntity(fileName, request.getContent());
            promptTemplateMapper.insert(db);
        } else {
            db.setContent(request.getContent());
            promptTemplateMapper.updateById(db);
        }
        return toVo(db);
    }

    @Override
    public void refreshFromFiles() {
        for (Resource resource : loadResources()) {
            String fileName = resource.getFilename();
            String fileContent = readResource(resource);
            PromptTemplateEntity db = selectByFileName(fileName);
            if (db == null) {
                insert(fileName, fileContent);
            } else {
                db.setName(stem(fileName));
                db.setDescription(DESCRIPTIONS.getOrDefault(fileName, ""));
                db.setContent(fileContent);
                promptTemplateMapper.updateById(db);
            }
        }
    }

    // ==================== 私有工具 ====================

    private PromptTemplateEntity insert(String fileName, String content) {
        PromptTemplateEntity entity = newEntity(fileName, content);
        promptTemplateMapper.insert(entity);
        return entity;
    }

    private PromptTemplateEntity selectByFileName(String fileName) {
        return promptTemplateMapper.selectOne(
                Wrappers.<PromptTemplateEntity>lambdaQuery()
                        .eq(PromptTemplateEntity::getFileName, fileName));
    }

    private PromptTemplateEntity newEntity(String fileName, String content) {
        PromptTemplateEntity entity = new PromptTemplateEntity();
        entity.setName(stem(fileName));
        entity.setFileName(fileName);
        entity.setDescription(DESCRIPTIONS.getOrDefault(fileName, ""));
        entity.setContent(content);
        return entity;
    }

    private PromptTemplateVo toVo(PromptTemplateEntity entity) {
        PromptTemplateVo vo = new PromptTemplateVo();
        vo.setName(entity.getName());
        vo.setFileName(entity.getFileName());
        vo.setDescription(entity.getDescription());
        vo.setContent(entity.getContent());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    private Resource[] loadResources() {
        try {
            return ResourcePatternUtils.getResourcePatternResolver(resourceLoader)
                    .getResources(PROMPTS_CLASSPATH);
        } catch (IOException e) {
            throw new BusinessException("扫描提示词模板文件失败：" + e.getMessage());
        }
    }

    private Resource findResource(String fileName) {
        return Arrays.stream(loadResources())
                .filter(r -> fileName.equals(r.getFilename()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("提示词模板不存在：" + fileName));
    }

    private String readResource(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException("读取提示词模板失败：" + resource.getFilename());
        }
    }

    private String resolveFileName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("模板名称不能为空");
        }
        return name.endsWith(".st") ? name : name + ".st";
    }

    private String stem(String fileName) {
        if (fileName == null || !fileName.endsWith(".st")) {
            return fileName;
        }
        return fileName.substring(0, fileName.length() - 3);
    }
}
