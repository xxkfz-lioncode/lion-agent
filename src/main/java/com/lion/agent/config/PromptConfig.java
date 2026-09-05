package com.lion.agent.config;

import com.lion.agent.service.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 提示词模板统一配置管理
 *
 * <p>集中维护项目全部提示词模板（{@code prompts/*.st}）及其渲染变量，
 * 业务层通过注入本组件获取渲染后的提示词，避免模板路径与变量在各业务类中散落。</p>
 *
 * <p>模板正文实时取自 {@link PromptTemplateService}（数据库优先、缺失回退 classpath 文件，
 * 应用启动时已自动把文件导入 {@code ai_prompt_template} 表）。
 * 因此模板不再以类加载时的静态编译结果固定：在页面修改并保存提示词后，
 * 下一次请求渲染即读取最新内容，无需重启服务。</p>
 *
 * <p>模板清单（文件名 → 渲染变量）：</p>
 * <ul>
 *   <li>system-prompt.st —— 系统提示词（{agentName}）</li>
 *   <li>memory-extract.st —— 长期记忆抽取（{userContent}/{assistantContent}/{jsonExample}）</li>
 *   <li>memory-rewrite.st —— 长期记忆查询改写（{question}）</li>
 *   <li>memory-inject.st —— 长期记忆注入 SystemMessage（{items}）</li>
 *   <li>kb-answer.st —— 知识库回答（{context}/{question}）</li>
 *   <li>kb-rewrite-multi.st —— 知识库多路查询改写（{question}/{count}）</li>
 *   <li>kb-gate.st —— 知识库复评门控（{context}/{query}）</li>
 *   <li>intent-classify.st —— 意图识别（{knowledgeBases}/{selectedKnowledge}/{question}）</li>
 *   <li>summary-compress.st —— 会话摘要压缩（{history}）</li>
 *   <li>summary-merge.st —— 会话摘要合并（{oldSummary}/{history}）</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PromptConfig {

    private final PromptTemplateService promptTemplateService;

    /** 记忆抽取 JSON 数组示例（含花括号，不能直接写进 .st 模板，否则会被 ST4 当作变量占位符解析），通过渲染变量 {jsonExample} 注入模板 */
    private static final String JSON_EXAMPLE = "[{\"content\":\"用户预算是50万\",\"importance\":4}]";

    /** 渲染变量：Agent 角色名（可由 application.yml 的 lion.prompt.agent-name 覆盖） */
    @Value("${lion.prompt.agent-name:Lion Agent}")
    private String agentName;

    // ==================== 系统提示词 ====================

    /** 渲染系统提示词（使用配置的角色名） */
    public String renderSystemPrompt() {
        return renderTemplate("system-prompt.st", Map.of("agentName", agentName));
    }

    // ==================== 长期记忆 ====================

    /** 渲染长期记忆抽取提示词 */
    public String renderMemoryExtract(String userContent, String assistantContent) {
        return renderTemplate("memory-extract.st", Map.of(
                "userContent", userContent,
                "assistantContent", assistantContent == null ? "" : assistantContent,
                "jsonExample", JSON_EXAMPLE));
    }

    /** 渲染长期记忆查询改写提示词 */
    public String renderMemoryRewrite(String question) {
        return renderTemplate("memory-rewrite.st", Map.of("question", question));
    }

    /** 渲染长期记忆注入 SystemMessage（记忆条目自动拼接为「- 内容」列表） */
    public String renderMemoryInjection(List<String> memoryContents) {
        StringBuilder items = new StringBuilder();
        for (String content : memoryContents) {
            items.append("- ").append(content).append('\n');
        }
        return renderTemplate("memory-inject.st", Map.of("items", items.toString())).trim();
    }

    // ==================== 知识库问答 ====================

    /** 渲染知识库回答提示词 */
    public String renderKbAnswer(String context, String question) {
        return renderTemplate("kb-answer.st", Map.of("context", context, "question", question));
    }

    /** 渲染知识库多路查询改写提示词（要求输出 {count} 种等价表达） */
    public String renderKbRewriteMulti(String question, int count) {
        return renderTemplate("kb-rewrite-multi.st",
                Map.of("question", question, "count", String.valueOf(count)));
    }

    /** 渲染知识库复评门控提示词 */
    public String renderKbGate(String context, String query) {
        return renderTemplate("kb-gate.st", Map.of("context", context, "query", query));
    }

    // ==================== 意图识别 ====================

    /** 渲染意图识别提示词（knowledgeBases/selectedKnowledge 为「无」或知识库名称，selectedKnowledge 为「全部知识库」表示未指定） */
    public String renderIntentClassify(String knowledgeBases, String selectedKnowledge, String question) {
        return renderTemplate("intent-classify.st", Map.of(
                "knowledgeBases", knowledgeBases == null || knowledgeBases.isBlank() ? "无" : knowledgeBases,
                "selectedKnowledge", selectedKnowledge == null || selectedKnowledge.isBlank() ? "无" : selectedKnowledge,
                "question", question));
    }

    // ==================== 会话摘要 ====================

    /** 渲染会话摘要压缩提示词（无旧摘要，直接压缩新增对话） */
    public String renderSummaryCompress(String history) {
        return renderTemplate("summary-compress.st", Map.of("history", history));
    }

    /** 渲染会话摘要合并提示词（已有旧摘要 + 新增对话） */
    public String renderSummaryMerge(String oldSummary, String history) {
        return renderTemplate("summary-merge.st", Map.of("oldSummary", oldSummary, "history", history));
    }

    // ==================== 内部工具 ====================

    /**
     * 实时读取模板正文并渲染：每次渲染前向 {@link PromptTemplateService} 获取最新内容，
     * 保证页面保存的修改即时生效（渲染开销毫秒级，相对模型调用可忽略）
     */
    private String renderTemplate(String fileName, Map<String, Object> variables) {
        String content = promptTemplateService.getContent(fileName);
        return new PromptTemplate(content).render(variables);
    }
}
