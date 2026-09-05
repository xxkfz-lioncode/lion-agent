package com.lion.agent.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lion.agent.common.enums.VectorType;
import com.lion.agent.model.dto.SkillRequest;
import com.lion.agent.model.entity.Skill;
import com.lion.agent.mapper.SkillMapper;
import com.lion.agent.tools.ToolCallbackBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义技能（Skill）注册与检索中心
 *
 * <p>把页面维护的 {@link Skill}（提示词模板 + 参数定义）动态转化为
 * {@link ToolCallback}：模型选中该工具后，executor 将模型填的参数替换进
 * {@code prompt_template} 的 {@code {{param}}} 占位符，再用<b>无 Advisor 的裸
 * {@link ChatModel}</b>调用一次 LLM，把结果作为工具返回值交回主对话。</p>
 *
 * <p>技能是用户私有的，本地索引按 {@code userId} 分组；向量索引复用知识库的
 * Milvus collection，用 {@code type=skill_index + userId} 标量过滤，天然隔离
 * 不同用户的技能（不会串权限）。增删改由 {@link SkillServiceImpl} 触发
 * {@link #rebuild()} 全量重建，页面变更即时生效。</p>
 *
 * <p><b>递归规避</b>：技能执行时绝不能用全局 ChatClient（每次请求会注册
 * 技能工具本身，技能执行时再调它会再次看到自己，死循环直到 token 耗尽），
 * 所以这里只注入裸 {@link ChatModel}，与 IntentRecognitionService / KnowledgeRetrievalService 同一套做法。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillToolRegistry {

    /** 技能检索 top-K：技能是用户自配的，命中面窄，K 取小值防 token 膨胀 */
    private static final int TOP_K = 3;

    /** MilvusVectorStore.add 内部整批 embedding，DashScope 单次上限 10 条 */
    private static final int ADD_BATCH_SIZE = 10;

    /** 模板存在未替换占位符时的错误前缀（execute 与 renderAndRun 共用判定） */
    private static final String PLACEHOLDER_ERROR_PREFIX = "技能模板存在未替换的占位符";

    private final SkillMapper skillMapper;
    private final VectorStore vectorStore;

    /** 无 Advisor 的裸模型：技能执行/试跑时调用，避免递归 */
    private final ChatModel chatModel;

    /** userId -> (skillName -> callback)：本地索引，rebuild 时全量重建 */
    private final Map<Long, Map<String, ToolCallback>> skillIndex = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        rebuild();
    }

    /**
     * 全量重建：读所有启用技能 → 构建 callback 到本地索引 → 重建向量索引。
     * 技能增删改后由 SkillService 调用，页面变更即时生效。
     */
    public synchronized void rebuild() {
        skillIndex.clear();
        List<Skill> skills = skillMapper.selectList(
                new LambdaQueryWrapper<Skill>().eq(Skill::getStatus, 1));
        List<Document> indexDocs = new ArrayList<>();
        for (Skill skill : skills) {
            skillIndex.computeIfAbsent(skill.getUserId(), k -> new ConcurrentHashMap<>())
                    .put(skill.getName(), buildCallback(skill));
            indexDocs.add(toIndexDocument(skill));
        }
        log.info("技能本地索引加载 {} 条（{} 个用户）", skills.size(), skillIndex.size());
        try {
            vectorStore.delete("type == '" + VectorType.SKILL_INDEX.getValue() + "'");
            for (int i = 0; i < indexDocs.size(); i += ADD_BATCH_SIZE) {
                vectorStore.add(indexDocs.subList(i, Math.min(i + ADD_BATCH_SIZE, indexDocs.size())));
            }
            log.info("技能向量索引重建完成，共 {} 条", indexDocs.size());
        } catch (Exception e) {
            // 降级：索引建不上（Milvus 挂了/embedding 超时），search 检索失败时同样降级为本地全量
            log.warn("技能向量索引构建失败（检索将降级为本地全量）：{}", e.getMessage());
        }
    }

    /**
     * 按 query 检索当前用户的启用技能（供 ToolRegistryService.selectTools 合并进候选池）。
     * 无技能直接返回空，不发起 embedding；向量检索失败降级为当前用户技能全量注册。
     */
    public List<ToolCallback> search(String query, Long userId) {
        Map<String, ToolCallback> userSkills = skillIndex.get(userId);
        if (userSkills == null || userSkills.isEmpty()) {
            return List.of();
        }
        try {
            List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(TOP_K)
                    .filterExpression("type == '" + VectorType.SKILL_INDEX.getValue() + "' && userId == " + userId)
                    .build());
            List<ToolCallback> selected = new ArrayList<>();
            for (Document hit : hits) {
                Object skillName = hit.getMetadata().get("name");
                if (skillName != null) {
                    ToolCallback cb = userSkills.get(skillName.toString());
                    if (cb != null) {
                        selected.add(cb);
                    }
                }
            }
            return selected;
        } catch (Exception e) {
            // 降级：筛选是优化不是功能，挂了最多多注册几个技能占点 token，不能让技能彻底不可用
            log.warn("技能向量检索失败，降级为当前用户技能全量注册：{}", e.getMessage());
            return new ArrayList<>(userSkills.values());
        }
    }

    /**
     * 试跑：按参数替换模板并调用模型，返回替换后的 prompt 与模型输出（页面调模板用）。
     * 不依赖 skillIndex，直接基于传入的 Skill 执行。
     */
    public Map<String, String> renderAndRun(Skill skill, Map<String, Object> args) {
        List<SkillRequest.SkillParam> params = parseParams(skill);
        JSONObject argsObj = JSONUtil.parseObj(args);
        String prompt = renderPrompt(skill.getPromptTemplate(), params, argsObj);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("prompt", prompt);
        result.put("result", callModel(prompt));
        return result;
    }

    /**
     * 把技能构建为 ToolCallback：schema 由参数定义生成，executor 做参数替换 + 裸模型调用。
     * 构造在 rebuild 时完成，运行期复用（回调闭包持有 skill 与参数定义快照）。
     */
    private ToolCallback buildCallback(Skill skill) {
        List<SkillRequest.SkillParam> params = parseParams(skill);
        Map<String, Map<String, String>> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (SkillRequest.SkillParam p : params) {
            properties.put(p.getName(), Map.of(
                    "type", p.getType() == null ? "string" : p.getType(),
                    "description", p.getDescription() == null ? "" : p.getDescription()));
            if (Boolean.TRUE.equals(p.getRequired())) {
                required.add(p.getName());
            }
        }
        return ToolCallbackBuilder.build(
                skill.getName(),
                skill.getDescription(),
                properties,
                required,
                argumentsJson -> callModel(renderPrompt(skill.getPromptTemplate(), params, JSONUtil.parseObj(argumentsJson))));
    }

    /** 裸模型调用：失败返回错误文案而不是抛异常，主对话能拿到失败原因继续/换招 */
    private String callModel(String prompt) {
        if (prompt.startsWith(PLACEHOLDER_ERROR_PREFIX)) {
            return prompt;
        }
        try {
            ChatResponse response = chatModel.call(new Prompt(prompt));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return "技能执行失败：模型无输出";
            }
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("技能执行失败：{}", e.getMessage());
            return "技能执行失败：" + e.getMessage();
        }
    }

    /**
     * 参数替换：按参数名长度降序替换，避免 "{{a}}" 先于 "{{ab}}" 替换导致占位符错位；
     * 缺参/空值用默认值；替换后仍残留 {{xxx}} 说明模板和参数没对上，返回明确错误。
     */
    private String renderPrompt(String template, List<SkillRequest.SkillParam> params, JSONObject args) {
        String prompt = template == null ? "" : template;
        List<SkillRequest.SkillParam> sorted = new ArrayList<>(params);
        sorted.sort(Comparator.comparingInt(p -> -p.getName().length()));
        for (SkillRequest.SkillParam param : sorted) {
            String value = args.getStr(param.getName());
            if (value == null || value.isBlank()) {
                value = param.getDefaultValue() == null ? "" : param.getDefaultValue();
            }
            prompt = prompt.replace("{{" + param.getName() + "}}", value);
        }
        if (prompt.contains("{{")) {
            return PLACEHOLDER_ERROR_PREFIX + "（请检查参数与模板是否匹配）：" + prompt;
        }
        return prompt;
    }

    private List<SkillRequest.SkillParam> parseParams(Skill skill) {
        if (StringUtils.hasText(skill.getParameters())) {
            return JSONUtil.toList(skill.getParameters(), SkillRequest.SkillParam.class);
        }
        return new ArrayList<>();
    }

    /** 技能描述 → 向量索引文档：text 是检索语料（名称放前给向量语义锚点），metadata 携带 userId 参与标量过滤 */
    private Document toIndexDocument(Skill skill) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", VectorType.SKILL_INDEX.getValue());
        metadata.put("userId", skill.getUserId());
        metadata.put("name", skill.getName());
        String text = skill.getName() + "：" + (skill.getDescription() == null ? "" : skill.getDescription());
        return Document.builder()
                .id("skill-index-" + skill.getUserId() + "-" + skill.getName())
                .text(text)
                .metadata(metadata)
                .build();
    }
}
