<template>
  <div class="guide-page">
    <h2 class="page-title">Spring AI 知识体系：一条消息的完整旅程</h2>
    <p class="page-sub">
      不按功能模块切分，按「一条用户消息从进来到出去」的实际顺序组织。
      主链路 5 个阶段 + 2 条支线（离线的知识入库、贯穿全程的观测治理），共 {{ total }} 个知识点，括号内为代码位置。
    </p>

    <p class="flow">
      启动装配 → 构建 ChatClient（挂 Advisor 链）→ 请求拦截（敏感词 → 记忆注入 → 缓存 → 路由 → 选工具）
      → 模型推理 ⇄ 工具循环（RAG / 子 Agent / A2A）→ 响应回流（记忆回写 / 用量落库）
      <br />
      支线：知识入库（离线 ETL）｜观测治理（贯穿全程）
    </p>

    <!-- 阶段 0 -->
    <div id="stage0" class="group">
      <h3 class="group-title">阶段 0 · 启动装配：进程起来之前，这些东西必须就位</h3>
      <ul class="item-list">
        <li><b>密钥外置 .env</b>：optional:file:.env[.properties]，优先级：环境变量 > .env > yml <span class="loc">（application.yml:14-15）</span></li>
        <li><b>OpenAI 兼容接入千问</b>：spring-ai-starter-model-openai 指向 DashScope /compatible-mode/v1 <span class="loc">（application-dev.yml:22-31）</span></li>
        <li><b>ChatModel 配置</b>：qwen3.8-max，temperature 0.1 <span class="loc">（application-dev.yml:26-27）</span></li>
        <li><b>Embedding 模型</b>：text-embedding-v3（1024 维）；配置位置写错会回退 text-embedding-ada-002 <span class="loc">（application-dev.yml:29-31）</span></li>
        <li><b>多模态模型</b>：qwen-vl-max，复用同一端点仅切模型名 <span class="loc">（application.yml:224-228）</span></li>
        <li><b>Rerank 专用模型</b>：DashScope 原生 qwen3-vl-rerank（非 Spring AI 模块，自写 RestClient）<span class="loc">（application.yml:153-161）</span></li>
        <li><b>JDBC ChatMemory 建表</b>：官方 JDBC 仓库，initialize-schema: always 自动建表 <span class="loc">（application-dev.yml:36-39）</span></li>
        <li><b>Milvus 5 库隔离</b>：knowledge / tool / skill / qa-cache / memory 按 type 标量字段隔离 <span class="loc">（application.yml:143-262）</span></li>
        <li><b>懒加载建表</b>：非 Spring Bean 手动调 afterPropertiesSet()，提供 get/getOrNull/reset 三级降级 <span class="loc">（LazyMilvusVectorStoreUtils.java:59-105）</span></li>
        <li><b>MCP 服务端</b>：webmvc starter，端点 /mcp，protocol STATELESS + annotation-scanner <span class="loc">（application.yml:26-35）</span></li>
        <li><b>MCP 客户端静态配置</b>：streamable-http + initialized: false 懒初始化 <span class="loc">（application.yml:36-52）</span></li>
        <li><b>Prompt 模板集中管理</b>：12 个 .st 模板，DB 优先、缺失回退 classpath，页面改完即时生效 <span class="loc">（resources/prompts/、PromptConfig.java:131-134）</span></li>
      </ul>
    </div>

    <!-- 阶段 1 -->
    <div id="stage1" class="group">
      <h3 class="group-title">阶段 1 · 构建 ChatClient：一个 Client，按 order 挂 8 个 Advisor</h3>
      <ul class="item-list">
        <li><b>ChatClient.Builder 注入</b>：自动配置的 Builder 构造全局 ChatClient，挂全套 Advisor <span class="loc">（AiConfig.java:195-240）</span></li>
        <li><b>defaultOptions</b>：OpenAiChatOptions 指定模型与温度 <span class="loc">（AiConfig.java:178-189）</span></li>
        <li><b>defaultSystem</b>：多模态 Client 用 PromptTemplate 渲染系统提示词 <span class="loc">（AiConfig.java:191）</span></li>
        <li><b>不使用 defaultTools</b>：工具改为每次请求动态注册（为阶段 2 的三层筛选埋伏笔）<span class="loc">（ChatServiceImpl.java:395）</span></li>
        <li><b>defaultAdvisors</b>：按 order 升序挂载 8 个 Advisor <span class="loc">（AiConfig.java:238）</span></li>
        <li><b>order 设计原理</b>：业务 Advisor 的 order 必须小于工具 order，否则被圈进工具循环 <span class="loc">（AiConfig.java:49-74）</span></li>
        <li><b>9 个 order 常量</b>：MIN+50（敏感词）→ MAX-50（工具日志）全链路顺序 <span class="loc">（AiConfig.java:76-111）</span></li>
        <li><b>多模态独立 Client</b>：隔离重 Advisor，仅挂敏感词 + Token + 日志 <span class="loc">（AiConfig.java:171-193）</span></li>
        <li><b>裸 ChatModel</b>：意图识别/技能执行/记忆抽取用裸模型，避免递归触发 Advisor <span class="loc">（IntentRecognitionService.java:40）</span></li>
        <li><b>测试专用 Client</b>：ChatClient.builder(chatModel).build()，不挂任何 Advisor <span class="loc">（ModelConfigServiceImpl.java:44）</span></li>
      </ul>
    </div>

    <!-- 阶段 2 -->
    <div id="stage2" class="group">
      <h3 class="group-title">阶段 2 · 请求拦截：消息进来的第一毫秒，Advisor 链从小 order 往大走</h3>
      <ul class="item-list">
        <li><b>SensitiveWordAdvisor</b>：order 最小先执行，命中敏感词直接短路返回，不走模型 <span class="loc">（SensitiveWordAdvisor.java:105-124）</span></li>
        <li><b>敏感词动态词库</b>：30s TTL 缓存 + 写操作即时失效，Advisor 每次拿最新词库 <span class="loc">（SensitiveWordServiceImpl.java:38-48）</span></li>
        <li><b>MessageWindowChatMemory</b>：maxMessages(500)，每会话最多保留 500 条 <span class="loc">（AiConfig.java:293-300）</span></li>
        <li><b>会话隔离</b>：param(ChatMemory.CONVERSATION_ID, conversationId) 按会话隔离 <span class="loc">（ChatServiceImpl.java:389-393）</span></li>
        <li><b>ReadLimitChatMemory</b>：装饰器限制只读最近 30 条，SYSTEM 消息永不截断 <span class="loc">（ReadLimitChatMemory.java:27-49）</span></li>
        <li><b>上下文 Key 常量</b>：与 Spring AI 内置 key 对齐（chat_memory_conversation_id / user_id）<span class="loc">（AdvisorConstants.java:20-27）</span></li>
        <li><b>MessageChatMemoryAdvisor</b>：官方内置 Advisor，到这里把历史对话注入 prompt <span class="loc">（AiConfig.java:216）</span></li>
        <li><b>ConversationSummaryAdvisor</b>：历史太长时注入压缩摘要（version 游标增量压缩）<span class="loc">（ConversationSummaryAdvisor.java:172-215）</span></li>
        <li><b>LongTermMemoryAdvisor</b>：注入跨会话记忆画像（SystemMessage 插到第一个用户消息之前）<span class="loc">（LongTermMemoryAdvisor.java:143-182）</span></li>
        <li><b>记忆查询改写</b>：口语化 query → 关键词短句，提升记忆向量召回率 <span class="loc">（LongTermMemoryAdvisor.java:244-264）</span></li>
        <li><b>QaCacheAdvisor</b>：语义缓存命中即短路，直接返回缓存答案，不进模型 <span class="loc">（QaCacheAdvisor.java:83-153）</span></li>
        <li><b>语义缓存配置</b>：独立 collection，userId 隔离，相似度阈值 0.78 <span class="loc">（QaCacheService.java:48-68）</span></li>
        <li><b>意图识别</b>：LLM 分流 KB 链路 / 一般对话链路，决定后面要不要走 RAG <span class="loc">（IntentRecognitionService.java:52-74）</span></li>
        <li><b>三层工具筛选</b>：常驻工具 ∪ 权限过滤 ∪ 向量 Top3 ∪ 自定义技能，动态注册成本次请求的工具集 <span class="loc">（ToolRegistryService.java:328-367）</span></li>
        <li><b>@ToolPermission 权限码</b>：权限编进 filterExpression，先过滤后排序 <span class="loc">（ToolRegistryService.java:373-409）</span></li>
        <li><b>ToolSearch 工具搜索</b>：工具太多时建向量索引（regex / lucene / vector），max-results 5 <span class="loc">（application.yml:62-77）</span></li>
        <li><b>ToolSearch Builder 修复</b>：手动声明 Builder Bean，抢占自动配置的注册顺序 <span class="loc">（AiConfig.java:263-287）</span></li>
        <li><b>索引淘汰策略</b>：TTL 2 小时 + LRU 200 个会话组合淘汰 <span class="loc">（AiConfig.java:275-280）</span></li>
        <li><b>MCP 客户端动态管理</b>：McpSyncClient 连接池 + 事件驱动重建工具索引 <span class="loc">（McpServerServiceImpl.java:258-344）</span></li>
        <li><b>SSE 传输已废弃</b>：MCP 统一改用 HttpClientStreamableHttpTransport <span class="loc">（McpServerServiceImpl.java:291-295）</span></li>
        <li><b>Milvus 不可用降级</b>：向量库挂了，工具筛选退化为「常驻 + 权限内全量注册」<span class="loc">（ToolRegistryService.java:357-365）</span></li>
      </ul>
    </div>

    <!-- 阶段 3 -->
    <div id="stage3" class="group">
      <h3 class="group-title">阶段 3 · 推理与工具循环：模型干活，工具结果喂回来再推理，直到不再调工具</h3>
      <ul class="item-list">
        <li><b>@Tool 注解</b>：声明式工具，UserTools / DateTools / TimeLimiterTools / StarFortuneTools <span class="loc">（tools/base/）</span></li>
        <li><b>MethodToolCallback</b>：编程式注册，反射拿 Method + 手写 inputSchema <span class="loc">（CustomToolsConfig.java:71-91）</span></li>
        <li><b>FunctionToolCallback</b>：函数式注册，inputType 自动生成 JSON Schema <span class="loc">（CustomToolsConfig.java:113-118）</span></li>
        <li><b>inputSchema 必填校验</b>：Spring AI 2.0 启动校验，缺失抛 IllegalStateException <span class="loc">（CustomToolsConfig.java:29-31）</span></li>
        <li><b>ToolCallbackBuilder</b>：极简 ToolCallback 构造器，统一出入参日志 <span class="loc">（tools/builder/ToolCallbackBuilder.java:34-81）</span></li>
        <li><b>RAG 入口</b>：KB 链路走自研检索流水线，不依赖官方 QuestionAnswerAdvisor <span class="loc">（ChatServiceImpl.java:303-323）</span></li>
        <li><b>多路召回</b>：向量 + BM25 + 查询改写，各 TopK=20 <span class="loc">（MultiRouteRetriever.java:45-60）</span></li>
        <li><b>RRF 融合</b>：score = Σ 1/(60+rank)，跨量纲可直接相加 <span class="loc">（MultiRouteRetriever.java:65-89）</span></li>
        <li><b>BM25 关键词召回</b>：k1=1.5 / b=0.75，中文单字切分，纯内存不依赖 Milvus <span class="loc">（Bm25Retriever.java:33-70）</span></li>
        <li><b>查询改写</b>：LLM 生成 3 种等价表达分别召回去重 <span class="loc">（QueryRewriteRetriever.java:51-72）</span></li>
        <li><b>DashScope Rerank</b>：qwen3-vl-rerank 精排 Top5，@CircuitBreaker 熔断降级 <span class="loc">（DashScopeRerankUtils.java:61-115）</span></li>
        <li><b>窗口扩容 small-to-big</b>：命中块前后扩 radius=1 并拼接上下文 <span class="loc">（KnowledgeRetrievalService.java:103-106）</span></li>
        <li><b>复评与门控</b>：双路命中 ×1.2 加成，阈值 0.2 门控，不达标降级一般对话 <span class="loc">（KnowledgeRetrievalService.java:56-57, 177-188）</span></li>
        <li><b>TodoWrite</b>：复杂任务先显式拆解成清单，再逐条推进 <span class="loc">（AgentUtilsDemoService.java:57-77）</span></li>
        <li><b>TaskTool 子 Agent</b>：主 Agent 派活，子 Agent 独立上下文（新 ChatClient）<span class="loc">（TaskToolDemoService.java:53-59）</span></li>
        <li><b>TaskOutputTool</b>：按 task_id 取后台任务结果，与 TaskTool 共用同一 Repository <span class="loc">（TaskToolDemoService.java:61-63）</span></li>
        <li><b>run_in_background</b>：后台异步执行，仓库内 CompletableFuture + 线程池 <span class="loc">（DefaultTaskRepository）</span></li>
        <li><b>TodoWriteToolAdapter</b>：修复库工具入参多包一层的反序列化问题 <span class="loc">（agent/tool/TodoWriteToolAdapter.java）</span></li>
        <li><b>A2A 远程 Agent（客户端）</b>：AgentCard 发现 + JSON-RPC message/send，失败自动降级本地子 Agent <span class="loc">（A2aDemoService.java:96-113）</span></li>
        <li><b>A2A 服务端</b>：独立进程 a2a-server(9999)：真实千问模型 + 天气 @Tool <span class="loc">（a2a-server/controller/A2aServerController.java）</span></li>
        <li><b>AgentCard 类型安全构造</b>：用 SDK 的 AgentCard.Builder 构造，避免手写 JSON 漏字段 <span class="loc">（a2a-server/config/A2aAgentCardConfig.java）</span></li>
        <li><b>SkillsTool 渐进披露</b>：目录里只放 name+description，命中才加载 SKILL.md 正文 <span class="loc">（SkillsToolDemoService.java:75-77）</span></li>
        <li><b>自定义技能</b>：提示词模板动态转 ToolCallback，用裸模型执行防递归 <span class="loc">（skill/SkillToolRegistry.java:40-55）</span></li>
      </ul>
    </div>

    <!-- 阶段 4 -->
    <div id="stage4" class="group">
      <h3 class="group-title">阶段 4 · 响应回流：工具循环结束，结果沿 Advisor 链往回走，各自收尾</h3>
      <ul class="item-list">
        <li><b>TokenUsageAdvisor</b>：位于工具循环外，统计含全部轮次的累计用量并落库 <span class="loc">（TokenUsageAdvisor.java:62-118）</span></li>
        <li><b>ChatLoggerAdvisor 双实例</b>：order 0 打完整 prompt；order MAX-50 打 [AI-TOOL] 工具轮次与最终回复 <span class="loc">（ChatLoggerAdvisor.java:124-146）</span></li>
        <li><b>CallAdvisor + StreamAdvisor</b>：每个自定义 Advisor 都实现双接口，为流式改造留口子 <span class="loc">（advisor/ 各实现类）</span></li>
        <li><b>QaCacheAdvisor 回写</b>：响应阶段把问答对写回语义缓存，下次命中 <span class="loc">（QaCacheAdvisor.java:83-153）</span></li>
        <li><b>记忆回写</b>：MessageChatMemoryAdvisor 自动把本轮对话写入 JDBC 记忆 <span class="loc">（AiConfig.java:216）</span></li>
        <li><b>长期记忆异步抽取</b>：@Async 抽取 → 合并为单条画像 → MySQL + Milvus 双写 <span class="loc">（MemoryServiceImpl.java:107-150）</span></li>
        <li><b>会话摘要压缩</b>：version 递增 + last_message_id 游标增量压缩 <span class="loc">（ConversationSummaryAdvisor.java:172-215）</span></li>
        <li><b>清空记忆</b>：chatMemory.clear(conversationId) <span class="loc">（ChatServiceImpl.java:230, 257）</span></li>
        <li><b>全局结构化输出</b>：AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT <span class="loc">（ChatConfig.java:14-19）</span></li>
        <li><b>单次结构化输出</b>：.entity(Class, EntityParamSpec::useProviderStructuredOutput) <span class="loc">（StructTestController.java:63）</span></li>
        <li><b>Map 输出转换</b>：.entity(new ParameterizedTypeReference&lt;Map&lt;String,Object&gt;&gt;(){}) <span class="loc">（StructTestController.java:70-74）</span></li>
        <li><b>Record 包装 List</b>：OpenAI 不支持顶层 JSON 数组，需用 record 包一层 <span class="loc">（MemoryExtractor.java:113）</span></li>
        <li><b>多模态 media()</b>：userSpec.media(MimeType, Resource/URL)，支持上传文件与远程 URL <span class="loc">（ChatServiceImpl.java:420-433）</span></li>
        <li><b>图片落盘与回显</b>：upload/multimodal/yyyy/MM/dd/，历史回显转 markdown 图片 <span class="loc">（ChatServiceImpl.java:500-529）</span></li>
        <li><b>SSE 伪流式 ⚠️</b>：同步 call() 后一次性推送；真流式需同步调整 Advisor 流式分支（前置条件已就绪）<span class="loc">（ChatServiceImpl.java:143-191）</span></li>
      </ul>
    </div>

    <!-- 支线 A -->
    <div id="lineA" class="group">
      <h3 class="group-title">支线 A · 知识入库（离线）：上传那一刻发生的事，与对话链路平行</h3>
      <ul class="item-list">
        <li><b>TikaDocumentReader</b>：PDF / Word / MD / TXT 统一解析 <span class="loc">（KnowledgeDocumentServiceImpl.java:26）</span></li>
        <li><b>PagePdfDocumentReader</b>：PDF 按页读取 + 页边距裁剪页眉页脚 <span class="loc">（PageSplitterStrategy.java:74-80）</span></li>
        <li><b>7 种切分策略</b>：TOKEN(默认)/PAGE/PARAGRAPH/LINE/SENTENCE/RECURSIVE/SEMANTIC 策略注册表 <span class="loc">（rag/splitter/）</span></li>
        <li><b>TokenTextSplitter</b>：800 token/块，小于 50 不参与向量化 <span class="loc">（TokenSplitterStrategy.java:36-40）</span></li>
        <li><b>语义切分</b>：用 embedding 算相邻句相似度，在语义断裂点切分 <span class="loc">（SemanticSplitterStrategy.java:46-60）</span></li>
        <li><b>分批写入 10 条</b>：绕过 DashScope 单批上限 <span class="loc">（KnowledgeDocumentServiceImpl.java:185-193）</span></li>
        <li><b>异步 ETL 队列</b>：上传即返回，解析/切分/向量化推 Redis 队列由消费者执行 <span class="loc">（KnowledgeDocumentServiceImpl.java:126-127）</span></li>
      </ul>
    </div>

    <!-- 支线 B -->
    <div id="lineB" class="group">
      <h3 class="group-title">支线 B · 观测与治理（横切）：上面每个阶段都被它照着</h3>
      <ul class="item-list">
        <li><b>OTel Tracing</b>：spring-boot-starter-opentelemetry + OTLP 导出 <span class="loc">（application.yml:287-303）</span></li>
        <li><b>Langfuse OTLP 端点</b>：/api/public/otel/v1/traces + Basic Auth + x-langfuse-ingestion-version: 4 <span class="loc">（application.yml:294-303）</span></li>
        <li><b>全量采样</b>：management.tracing.sampling.probability: 1.0 <span class="loc">（application.yml:283-286）</span></li>
        <li><b>关闭 OTLP metrics</b>：避免无 Collector 时不停刷 Connection refused <span class="loc">（application.yml:308-311）</span></li>
        <li><b>Spring AI 日志观测</b>：log-prompt / log-completion 开关 <span class="loc">（application-dev.yml:32-35）</span></li>
        <li><b>Langfuse 原生上报</b>：补报 OTel 覆盖不到的事件与评分，攒批 flush，无 Key 自动禁用 <span class="loc">（LangfuseIngestClientUtils.java:30-66）</span></li>
        <li><b>Actuator</b>：暴露全部端点，health 显示明细 <span class="loc">（application.yml:274-282）</span></li>
        <li><b>Resilience4j 熔断</b>：5 个实例覆盖 aiService / 工具 / DB / Rerank <span class="loc">（application.yml:318-389）</span></li>
        <li><b>评估器 ❌</b>：未接入 RelevancyEvaluator / FactCheckingEvaluator（可拓展）<span class="loc">（—）</span></li>
        <li><b>异步线程池</b>：tokenUsage + memory 两个池，CallerRuns 兜底不丢数据 <span class="loc">（AsyncConfig.java:32-77）</span></li>
        <li><b>PromptTemplate 渲染</b>：new PromptTemplate(content).render(Map) <span class="loc">（PromptConfig.java:133）</span></li>
      </ul>
    </div>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

// 支持从首页卡片带 hash 跳转，进入后滚动到对应章节
onMounted(() => {
  if (route.hash) {
    const el = document.getElementById(route.hash.slice(1))
    if (el) el.scrollIntoView({ behavior: 'smooth' })
  }
})

/** 按一条消息的生命周期组织（阶段 0-4 + 支线 A/B），与左侧菜单树无关 */
const groups = [
  { key: 'stage0', title: '启动装配', count: 12 },
  { key: 'stage1', title: '构建 ChatClient', count: 10 },
  { key: 'stage2', title: '请求拦截', count: 21 },
  { key: 'stage3', title: '推理与工具循环', count: 23 },
  { key: 'stage4', title: '响应回流', count: 15 },
  { key: 'lineA', title: '知识入库（离线）', count: 7 },
  { key: 'lineB', title: '观测与治理（横切）', count: 11 }
]
const total = groups.reduce((s, g) => s + g.count, 0)
</script>

<style scoped>
.guide-page {
  height: 100%;
  overflow-y: auto;
  padding: 20px 24px;
  background: #f7f8fc;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-main);
}

.page-sub {
  margin: 6px 0 14px;
  font-size: 13px;
  color: var(--text-sub);
}

.flow {
  margin: 0 0 20px;
  padding: 10px 14px;
  background: #eef1ff;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.9;
  color: #3a4a9f;
}

.group {
  margin-bottom: 22px;
}

.group-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-main);
  margin-bottom: 8px;
}

.item-list {
  margin: 0;
  padding-left: 22px;
  font-size: 13px;
  color: var(--text-main);
  line-height: 2;
}

.item-list b {
  font-weight: 600;
}

.loc {
  color: var(--text-sub);
  font-size: 12px;
}
</style>
