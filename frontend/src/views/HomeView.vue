<template>
  <div class="home-page">
    <div class="page-head">
      <h2 class="page-title">Spring AI 知识体系</h2>
      <p class="page-sub">
        按「一条用户消息的生命周期」组织 · 主链路 5 个阶段 + 2 条支线 · 共 {{ total }} 个知识点
      </p>
    </div>

    <div class="flow">
      <div class="flow-main">
        <span v-for="(s, i) in mainFlow" :key="s" class="flow-step">
          <span v-if="i > 0" class="flow-arrow">→</span>{{ s }}
        </span>
      </div>
      <div class="flow-sub">支线：知识入库（离线 ETL）｜观测治理（贯穿全程）</div>
    </div>

    <div class="stage-grid">
      <div v-for="g in stages" :key="g.key" class="stage-card" @click="go(g.key)">
        <div class="stage-head">
          <span class="stage-icon">{{ g.icon }}</span>
          <span class="stage-name">{{ g.title }}</span>
          <span class="stage-count">{{ g.count }}</span>
        </div>
        <p class="stage-desc">{{ g.desc }}</p>
        <div class="stage-tags">
          <span v-for="t in g.tags" :key="t" class="tag">{{ t }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()

const mainFlow = ['启动装配', '构建 ChatClient', '请求拦截', '推理 ⇄ 工具循环', '响应回流']

// 与 /spring-ai/guide 大纲页一一对应，key 即锚点 id
const stages = [
  {
    key: 'stage0', icon: '📦', title: '阶段 0 · 启动装配', count: 12,
    desc: '进程启动前必须就位的组件：四类模型、记忆表、向量库、MCP 两端、Prompt 模板',
    tags: ['千问接入', 'Embedding', 'Rerank', 'Milvus 5 库', 'MCP', 'Prompt 模板']
  },
  {
    key: 'stage1', icon: '🧩', title: '阶段 1 · 构建 ChatClient', count: 10,
    desc: '一个 Client 按 order 挂 8 个 Advisor；业务拦截必须在工具循环之外',
    tags: ['defaultOptions', 'order 设计', '裸 ChatModel', '多模态独立 Client']
  },
  {
    key: 'stage2', icon: '🛂', title: '阶段 2 · 请求拦截', count: 21,
    desc: '消息进来的第一毫秒：敏感词短路 → 记忆/摘要/画像注入 → 缓存 → 路由 → 动态选工具',
    tags: ['敏感词', '会话记忆', '语义缓存', '意图路由', '三层工具筛选', 'ToolSearch']
  },
  {
    key: 'stage3', icon: '⚙️', title: '阶段 3 · 推理与工具循环', count: 23,
    desc: '模型干活的核心圈：RAG 检索流水线、子 Agent、A2A 远程协作、自定义技能',
    tags: ['@Tool', '多路召回+RRF', 'Rerank', 'TaskTool', 'A2A', 'SkillsTool']
  },
  {
    key: 'stage4', icon: '↩️', title: '阶段 4 · 响应回流', count: 15,
    desc: '结果沿 Advisor 链往回走，各归各位：落库、回写、异步抽取、结构化输出',
    tags: ['Token 落库', '记忆回写', '长期记忆抽取', '结构化输出', '多模态', '流式现状']
  },
  {
    key: 'lineA', icon: '📚', title: '支线 A · 知识入库（离线）', count: 7,
    desc: '与对话链路平行的离线 ETL：上传即返回，解析切分向量化走队列',
    tags: ['Tika', '7 种切分', '分批向量化', '异步队列']
  },
  {
    key: 'lineB', icon: '🔭', title: '支线 B · 观测与治理（横切）', count: 11,
    desc: '贯穿所有阶段的照妖镜：全链路追踪、用量、熔断、线程池',
    tags: ['OTel→Langfuse', 'Actuator', 'Resilience4j', '线程池']
  }
]

const total = stages.reduce((s, g) => s + g.count, 0)

function go(key) {
  router.push({ path: '/spring-ai/guide', hash: '#' + key })
}
</script>

<style scoped>
.home-page {
  height: 100%;
  overflow-y: auto;
  padding: 24px 28px;
  background: #f7f8fc;
}

.page-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-main);
}

.page-sub {
  margin: 6px 0 16px;
  font-size: 13px;
  color: var(--text-sub);
}

.flow {
  background: linear-gradient(120deg, #232a4d 0%, #2f3a6e 60%, #4f66f9 100%);
  border-radius: 12px;
  padding: 16px 20px;
  margin-bottom: 20px;
  color: #fff;
}

.flow-main {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 0;
  font-size: 14px;
  font-weight: 600;
}

.flow-arrow {
  margin: 0 10px;
  opacity: 0.6;
}

.flow-sub {
  margin-top: 8px;
  font-size: 12px;
  opacity: 0.75;
}

.stage-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 14px;
}

.stage-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px 18px;
  cursor: pointer;
  transition: all 0.2s;
}

.stage-card:hover {
  border-color: #4f66f9;
  box-shadow: 0 6px 18px rgba(79, 102, 249, 0.12);
  transform: translateY(-2px);
}

.stage-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.stage-icon {
  font-size: 18px;
}

.stage-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-main);
  flex: 1;
}

.stage-count {
  font-size: 12px;
  color: #4f66f9;
  background: #eef1ff;
  border-radius: 10px;
  padding: 1px 10px;
}

.stage-desc {
  margin: 10px 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-sub);
}

.stage-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tag {
  font-size: 11px;
  color: #5a6285;
  background: #f2f4fa;
  border-radius: 4px;
  padding: 2px 8px;
}
</style>
