package com.lion.agent.config;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 提示词模板统一配置管理
 *
 * <p>集中维护项目全部提示词模板（{@code resources/prompts/*.st}）及其渲染变量，
 * 业务层通过注入本组件获取渲染后的提示词，避免模板路径与变量在各业务类中散落。</p>
 *
 * <p>现有模板清单：</p>
 * <ul>
 *   <li>system-prompt.st —— 系统提示词（{agentName}）</li>
 *   <li>memory-extract.st —— 长期记忆抽取（{userContent}/{assistantContent}/{jsonExample}）</li>
 *   <li>memory-rewrite.st —— 长期记忆查询改写（{question}）</li>
 *   <li>memory-inject.st —— 长期记忆注入 SystemMessage（{items}）</li>
 *   <li>kb-answer.st —— 知识库回答（{context}/{question}）</li>
 *   <li>kb-rewrite.st —— 知识库查询改写（{question}）</li>
 *   <li>kb-rerank.st —— 知识库 Rerank 打分（{query}/{chunks}）</li>
 *   <li>kb-gate.st —— 知识库复评门控（{context}/{query}）</li>
 *   <li>summary-compress.st —— 会话摘要压缩（{history}）</li>
 *   <li>summary-merge.st —— 会话摘要合并（{oldSummary}/{history}）</li>
 * </ul>
 */
@Component
public class PromptConfig {

    // ==================== 模板路径 ====================

    /**
     * 系统提示词模板路径（渲染变量：{agentName}）
     */
    private static final String SYSTEM_PROMPT_TEMPLATE_PATH = "prompts/system-prompt.st";
    /**
     * 长期记忆抽取模板路径（渲染变量：{userContent}/{assistantContent}/{jsonExample}）
     */
    private static final String MEMORY_EXTRACT_TEMPLATE_PATH = "prompts/memory-extract.st";
    /**
     * 长期记忆查询改写模板路径（渲染变量：{question}）
     */
    private static final String MEMORY_REWRITE_TEMPLATE_PATH = "prompts/memory-rewrite.st";
    /**
     * 长期记忆注入 SystemMessage 模板路径（渲染变量：{items}）
     */
    private static final String MEMORY_INJECT_TEMPLATE_PATH = "prompts/memory-inject.st";
    /**
     * 知识库回答模板路径（渲染变量：{context}/{question}）
     */
    private static final String KB_ANSWER_TEMPLATE_PATH = "prompts/kb-answer.st";
    /**
     * 知识库多路查询改写模板路径（渲染变量：{question}/{count}），用于多路召回
     */
    private static final String KB_REWRITE_MULTI_TEMPLATE_PATH = "prompts/kb-rewrite-multi.st";
    /**
     * 知识库复评门控模板路径（渲染变量：{context}/{query}）
     */
    private static final String KB_GATE_TEMPLATE_PATH = "prompts/kb-gate.st";
    /**
     * 意图识别模板路径（渲染变量：{knowledgeBases}/{question}）
     */
    private static final String INTENT_CLASSIFY_TEMPLATE_PATH = "prompts/intent-classify.st";
    /**
     * 会话摘要压缩模板路径（渲染变量：{history}）
     */
    private static final String SUMMARY_COMPRESS_TEMPLATE_PATH = "prompts/summary-compress.st";
    /**
     * 会话摘要合并模板路径（渲染变量：{oldSummary}/{history}）
     */
    private static final String SUMMARY_MERGE_TEMPLATE_PATH = "prompts/summary-merge.st";

    // ==================== 模板实例（类加载时初始化一次，不可变、线程安全） ====================

    private static final PromptTemplate SYSTEM_PROMPT_TEMPLATE =
            new PromptTemplate(new ClassPathResource(SYSTEM_PROMPT_TEMPLATE_PATH));
    private static final PromptTemplate MEMORY_EXTRACT_TEMPLATE =
            new PromptTemplate(new ClassPathResource(MEMORY_EXTRACT_TEMPLATE_PATH));
    private static final PromptTemplate MEMORY_REWRITE_TEMPLATE =
            new PromptTemplate(new ClassPathResource(MEMORY_REWRITE_TEMPLATE_PATH));
    private static final PromptTemplate MEMORY_INJECT_TEMPLATE =
            new PromptTemplate(new ClassPathResource(MEMORY_INJECT_TEMPLATE_PATH));
    private static final PromptTemplate KB_ANSWER_TEMPLATE =
            new PromptTemplate(new ClassPathResource(KB_ANSWER_TEMPLATE_PATH));
    private static final PromptTemplate KB_REWRITE_MULTI_TEMPLATE =
            new PromptTemplate(new ClassPathResource(KB_REWRITE_MULTI_TEMPLATE_PATH));
    private static final PromptTemplate KB_GATE_TEMPLATE =
            new PromptTemplate(new ClassPathResource(KB_GATE_TEMPLATE_PATH));
    private static final PromptTemplate INTENT_CLASSIFY_TEMPLATE =
            new PromptTemplate(new ClassPathResource(INTENT_CLASSIFY_TEMPLATE_PATH));
    private static final PromptTemplate SUMMARY_COMPRESS_TEMPLATE =
            new PromptTemplate(new ClassPathResource(SUMMARY_COMPRESS_TEMPLATE_PATH));
    private static final PromptTemplate SUMMARY_MERGE_TEMPLATE =
            new PromptTemplate(new ClassPathResource(SUMMARY_MERGE_TEMPLATE_PATH));

    // ==================== 模板常量 ====================

    /**
     * 记忆抽取 JSON 数组示例（含花括号，不能直接写进 .st 模板，否则会被 ST4 当作变量占位符解析），
     * 通过渲染变量 {jsonExample} 注入模板
     */
    private static final String JSON_EXAMPLE = "[{\"content\":\"用户预算是50万\",\"importance\":4}]";

    /** 渲染变量：Agent 角色名（可由 application.yml 的 lion.prompt.agent-name 覆盖） */
    @Value("${lion.prompt.agent-name:Lion Agent}")
    private String agentName;

    // ==================== 系统提示词 ====================

    /** 渲染系统提示词（使用配置的角色名） */
    public String renderSystemPrompt() {
        return SYSTEM_PROMPT_TEMPLATE.render(Map.of("agentName", agentName));
    }

    // ==================== 长期记忆 ====================

    /** 渲染长期记忆抽取提示词 */
    public String renderMemoryExtract(String userContent, String assistantContent) {
        return MEMORY_EXTRACT_TEMPLATE.render(Map.of(
                "userContent", userContent,
                "assistantContent", assistantContent == null ? "" : assistantContent,
                "jsonExample", JSON_EXAMPLE));
    }

    /** 渲染长期记忆查询改写提示词 */
    public String renderMemoryRewrite(String question) {
        return MEMORY_REWRITE_TEMPLATE.render(Map.of("question", question));
    }

    /** 渲染长期记忆注入 SystemMessage（记忆条目自动拼接为「- 内容」列表） */
    public String renderMemoryInjection(List<String> memoryContents) {
        StringBuilder items = new StringBuilder();
        for (String content : memoryContents) {
            items.append("- ").append(content).append('\n');
        }
        return MEMORY_INJECT_TEMPLATE.render(Map.of("items", items.toString())).trim();
    }

    // ==================== 知识库问答 ====================

    /** 渲染知识库回答提示词 */
    public String renderKbAnswer(String context, String question) {
        return KB_ANSWER_TEMPLATE.render(Map.of("context", context, "question", question));
    }

    /** 渲染知识库多路查询改写提示词（要求输出 {count} 种等价表达） */
    public String renderKbRewriteMulti(String question, int count) {
        return KB_REWRITE_MULTI_TEMPLATE.render(Map.of("question", question, "count", String.valueOf(count)));
    }


    /** 渲染知识库复评门控提示词 */
    public String renderKbGate(String context, String query) {
        return KB_GATE_TEMPLATE.render(Map.of("context", context, "query", query));
    }

    // ==================== 意图识别 ====================

    /** 渲染意图识别提示词（knowledgeBases 为「无」或知识库名称逗号分隔列表） */
    public String renderIntentClassify(String knowledgeBases, String question) {
        return INTENT_CLASSIFY_TEMPLATE.render(Map.of(
                "knowledgeBases", knowledgeBases == null || knowledgeBases.isBlank() ? "无" : knowledgeBases,
                "question", question));
    }

    // ==================== 会话摘要 ====================

    /** 渲染会话摘要压缩提示词（无旧摘要，直接压缩新增对话） */
    public String renderSummaryCompress(String history) {
        return SUMMARY_COMPRESS_TEMPLATE.render(Map.of("history", history));
    }

    /** 渲染会话摘要合并提示词（已有旧摘要 + 新增对话） */
    public String renderSummaryMerge(String oldSummary, String history) {
        return SUMMARY_MERGE_TEMPLATE.render(Map.of("oldSummary", oldSummary, "history", history));
    }
}
