<template>
  <div class="agent-demo">
    <!-- 顶部说明 -->
    <header class="page-head">
      <div class="head-text">
        <h2>Agent 工具演示</h2>
        <p>
          基于社区库 <code>spring-ai-agent-utils</code>。三个示例共用同一套「渐进式披露」机制：
          <b>启动时只挂载轻量目录 → 模型按需点名 → 真正的内容才进入上下文</b>。
        </p>
      </div>
      <div class="head-badge">spring-ai-agent-utils 0.12.0</div>
    </header>

    <!-- 示例切换（配置驱动，新增示例只需往 demos 里加一项） -->
    <div class="tab-switch">
      <button
        v-for="d in demos"
        :key="d.key"
        class="switch-btn"
        :class="{ active: active === d.key }"
        @click="active = d.key"
      >
        <span class="switch-icon">{{ d.icon }}</span>
        <span class="switch-text">
          <b>{{ d.title }}</b>
          <em>{{ d.desc }}</em>
        </span>
      </button>
    </div>

    <section class="demo-body">
      <!-- ==================== 左：输入区（通用） ==================== -->
      <div class="panel input-panel">
        <div class="panel-title">① 输入任务</div>
        <textarea
          v-model="inputs[current.key]"
          class="task-input"
          :placeholder="current.placeholder"
        />
        <div class="chip-row">
          <span class="chip-label">快速试用</span>
          <button
            v-for="p in current.presets"
            :key="p"
            class="chip"
            :title="p"
            @click="inputs[current.key] = p"
          >{{ p }}</button>
        </div>

        <!-- 各示例专属的"目录"展示：就是启动时低成本挂载的那份清单 -->
        <template v-if="current.catalog">
          <div class="catalog-title">{{ current.catalog.title }}</div>
          <div class="catalog-list">
            <div v-for="c in current.catalog.items" :key="c.name" class="catalog-card">
              <div class="cg-head">
                <span class="cg-avatar">{{ c.icon }}</span>
                <span class="cg-name">{{ c.name }}</span>
              </div>
              <div class="cg-desc">{{ c.desc }}</div>
            </div>
          </div>
        </template>

        <!-- 可选：探测远程服务端（只有配置了 probe 的示例才显示） -->
        <template v-if="current.probe">
          <button class="probe-btn" :disabled="probeLoading" @click="probeRemote">
            <span v-if="!probeLoading">🔌 {{ current.probeLabel }}</span>
            <span v-else>探测中…</span>
          </button>
          <div v-if="probeText" class="probe-result" :class="probeOk ? 'ok' : 'bad'">{{ probeText }}</div>
        </template>

        <button class="run-btn" :disabled="rt.loading" @click="run(current.key)">
          <span v-if="!rt.loading">🚀 运行（{{ current.btnText }}）</span>
          <span v-else class="running">⏳ {{ current.runningText }}… {{ rt.elapsed }}s</span>
        </button>
      </div>

      <!-- ==================== 右：结果区（按示例分别渲染） ==================== -->
      <div class="panel result-panel">
        <div class="panel-title">
          ② 执行结果
          <span v-if="rt.elapsed && !rt.loading" class="title-meta">耗时约 {{ rt.elapsed }}s</span>
        </div>

        <div v-if="!hasResult && !rt.loading" class="empty-state">
          <div class="empty-icon">{{ current.icon }}</div>
          <div>{{ current.emptyText }}</div>
        </div>

        <div v-if="rt.loading" class="loading-state">
          <div class="spinner"></div>
          <div>{{ current.runningText }}…</div>
        </div>

        <!-- 机制说明：点明"目录 → 点名 → 回流"这条链路 -->
        <div v-if="hasResult" class="result-scroll">
          <div class="principle-card">
            <div class="principle-title">机制</div>
            <div class="principle-text">{{ current.principle }}</div>
          </div>

          <!-- ---------- TodoWrite ---------- -->
          <template v-if="current.key === 'todo' && results.todo">
            <div class="progress-card">
              <div class="progress-top">
                <span>任务进度</span>
                <b>{{ doneCount }}/{{ results.todo.items.length }}</b>
              </div>
              <div class="progress-track">
                <div class="progress-fill" :style="{ width: progressPercent + '%' }"></div>
              </div>
            </div>

            <ul class="todo-list">
              <li v-for="(item, i) in results.todo.items" :key="i" class="todo-item" :class="item.status">
                <span class="todo-mark">{{ markOf(item.status) }}</span>
                <div class="todo-text">
                  <div class="todo-content">{{ item.content }}</div>
                  <div v-if="item.activeForm && item.status !== 'completed'" class="todo-active">
                    {{ item.activeForm }}
                  </div>
                </div>
                <span class="todo-status">{{ statusText(item.status) }}</span>
              </li>
            </ul>

            <div class="answer-card">
              <div class="answer-title">🤖 最终回答</div>
              <div class="answer-text">{{ results.todo.answer }}</div>
            </div>
          </template>

          <!-- ---------- TaskTool ---------- -->
          <template v-else-if="current.key === 'task' && results.task">
            <div class="flow-card">
              <div class="flow-step">用户任务</div>
              <div class="flow-arrow">↓</div>
              <div class="flow-step">主 Agent 判断并调用 Task 工具</div>
              <div class="flow-arrow">↓</div>
              <div class="flow-step highlight">子 Agent 独立执行并返回</div>
              <div class="flow-arrow">↓</div>
              <div class="flow-step">结果回流，主 Agent 汇总转述</div>
            </div>

            <div class="answer-card">
              <div class="answer-title">🤖 主 Agent 汇总回答</div>
              <div class="answer-text">{{ results.task }}</div>
            </div>
          </template>

          <!-- ---------- SkillsTool ---------- -->
          <template v-else-if="current.key === 'skill' && results.skill">
            <div class="flow-card">
              <div class="flow-step">启动时：只挂载 Skill 名 + 一句话描述</div>
              <div class="flow-arrow">↓</div>
              <div class="flow-step">模型判断需求命中哪个 Skill</div>
              <div class="flow-arrow">↓</div>
              <div class="flow-step highlight">调 Skill 工具 → 该 SKILL.md 正文注入对话</div>
              <div class="flow-arrow">↓</div>
              <div class="flow-step">按说明书的章节结构回答</div>
            </div>

            <div class="answer-card">
              <div class="answer-title">🤖 模型回答</div>
              <div class="answer-text">{{ results.skill }}</div>
            </div>

            <div class="tip-card">
              <b>怎么看"省没省 token"</b>
              <p>
                命中时日志会出现
                <code>[AI-TOOL] 工具返回: Skill -&gt; …</code>
                并带上整份正文；不命中时这条根本不会出现，两份说明书一个 token 都没进上下文。
                用预设里的「写个冒泡排序」对照跑一次即可。
              </p>
            </div>
          </template>

          <!-- ---------- A2A ---------- -->
          <template v-else-if="current.key === 'a2a' && results.a2a">
            <div class="flow-card">
              <div class="flow-step">构建 Task 工具：本地子 Agent + 远程 A2A Agent</div>
              <div class="flow-arrow">↓</div>
              <div class="flow-step">解析远程：GET /.well-known/agent-card.json</div>
              <div class="flow-arrow">↓</div>
              <div class="flow-step">模型从目录里挑一个（本地或远程）</div>
              <div class="flow-arrow">↓</div>
              <div class="flow-step highlight">远程走 JSON-RPC message/send，返回 Task</div>
              <div class="flow-arrow">↓</div>
              <div class="flow-step">结果回流，主 Agent 汇总转述</div>
            </div>

            <div class="answer-card">
              <div class="answer-title">🤖 主 Agent 汇总回答</div>
              <div class="answer-text">{{ results.a2a }}</div>
            </div>

            <div class="tip-card">
              <b>远程服务怎么起</b>
              <p>
                远程 Agent 是另一个进程 <code>lion-a2a-server</code>（端口 9999），
                在 <code>lion-agent/lion-agent/a2a-server</code> 下执行
                <code>mvn spring-boot:run</code>。
                没起也能跑本示例——后端会自动降级为仅本地子 Agent，回答开头会带上降级提示。
              </p>
            </div>
          </template>
        </div>
      </div>
    </section>

    <!-- 错误提示 -->
    <div v-if="errorMsg" class="error-bar">
      <span>⚠️ {{ errorMsg }}</span>
      <button class="error-close" @click="errorMsg = ''">✕</button>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onUnmounted } from 'vue'
import { runTodoPlan, runTaskAgent, runSkillAgent, runA2aAgent, probeA2aCard } from '../../api/agent'

/**
 * 示例配置表。后续新增示例：在这里加一项 + 在结果区加一段 <template v-else-if>。
 *
 * 字段说明：
 *  - key / icon / title / desc  页签展示
 *  - presets / placeholder      输入框预设与提示
 *  - catalog                    启动时低成本挂载到上下文的"目录"（子 Agent 名片 / Skill 描述）
 *  - principle                  结果区顶部的一句话机制说明
 *  - run                        对应的接口函数
 */
const demos = [
  {
    key: 'todo',
    icon: '✅',
    title: 'TodoWrite 任务清单',
    desc: '模型自己拆解任务并逐步推进',
    btnText: 'TodoWrite',
    runningText: '模型正在拆解并推进任务',
    emptyText: '运行后，这里展示模型拆解的任务清单与推进过程',
    placeholder: '描述一个包含多个步骤的任务，模型会先拆解成清单再逐步推进',
    presets: [
      '为在线商城增加优惠券功能，输出数据模型、接口、测试点和发布注意事项',
      '帮我规划一次系统上线：准备、灰度、验证、回滚',
      '拆解“把用户模块重构为独立服务”的实施步骤'
    ],
    principle: '工具定义常驻上下文（几十 token）；模型每推进一步就调一次 TodoWrite，' +
      '把新状态回流进对话，前端据此渲染进度。',
    run: runTodoPlan
  },
  {
    key: 'task',
    icon: '🧩',
    title: 'TaskTool 子 Agent',
    desc: '主 Agent 派活给子 Agent 执行',
    btnText: 'TaskTool 派活',
    runningText: '主 Agent 正在派活给子 Agent',
    emptyText: '运行后，这里展示主 Agent 派活后汇总的最终回答',
    placeholder: '描述一个任务，主 Agent 会判断该交给哪个子 Agent 执行',
    presets: [
      '请把这句话翻译成英文：我们计划在下半年上线新的优惠券系统。',
      '帮我总结一下这段话：知识库 RAG 的流程是上传、切分、向量化、多路召回、重排、生成。',
      '把“欢迎使用 Lion Agent 智能问答系统”翻译成英文'
    ],
    catalog: {
      title: '可派活的子 Agent（启动时的目录）',
      items: [
        { name: 'translator', icon: '🌐', desc: '中英翻译专家，只输出译文' },
        { name: 'summarizer', icon: '📄', desc: '摘要专家，压缩成 3 句话内' }
      ]
    },
    principle: '子 Agent 的 name + description 挂在 Task 工具描述里；命中后另起一个独立 ChatClient 跑完整对话，' +
      '结果作为工具返回值回流，主 Agent 再生成一次得到最终回答。',
    run: runTaskAgent
  },
  {
    key: 'skill',
    icon: '📚',
    title: 'SkillsTool 说明书',
    desc: '按需加载 SKILL.md 正文',
    btnText: 'SkillsTool',
    runningText: '模型正在判断是否加载 Skill',
    emptyText: '运行后，这里展示模型加载说明书后给出的回答',
    placeholder: '描述一个需求，模型会判断是否命中某个 Skill 并加载它的完整说明',
    presets: [
      '帮我梳理一下发版前要检查什么',
      '看下这条SQL有没有问题：SELECT * FROM orders WHERE DATE(create_time)=CURDATE()',
      '用 Java 写个冒泡排序（对照：不该命中任何 Skill）'
    ],
    catalog: {
      title: '可用 Skill（启动时的目录，只有名字+描述）',
      items: [
        { name: 'release-checklist', icon: '🚀', desc: '发版前检查清单，含回滚方案' },
        { name: 'sql-review', icon: '🔍', desc: 'SQL 评审规范：索引 / 执行计划 / 写法 / 安全' }
      ]
    },
    principle: '启动时只挂载 Skill 名 + 一句话描述；命中后 Skill 工具把整份 SKILL.md 正文作为返回值注入对话，' +
      '不命中则正文永不进上下文——纯知识也能按需付费。',
    run: runSkillAgent
  },
  {
    key: 'a2a',
    icon: '🌐',
    title: 'A2A 远程子 Agent',
    desc: '本地子 Agent 与远程 Agent 一起派活',
    btnText: 'A2A 派活',
    runningText: '模型正在选择本地或远程 Agent',
    emptyText: '运行后，这里展示模型派活后的汇总回答（远程不可达时会自动降级为本地）',
    placeholder: '描述一个任务，模型会在本地子 Agent 与远程 A2A Agent 之间自主选择',
    presets: [
      '帮我查一下广州天气怎么样',
      '请把这句话翻译成英文：我们计划在下半年上线新的优惠券系统。',
      '帮我总结一下这段话：A2A 是 Agent 之间互相派活的开放协议，与 MCP 的“调工具”不同。'
    ],
    catalog: {
      title: '可派活的子 Agent（本地 + 远程）',
      items: [
        { name: 'translator', icon: '🌏', desc: '本地：中英翻译专家' },
        { name: 'summarizer', icon: '📄', desc: '本地：摘要专家' },
        { name: '远程小助手', icon: '🛰️', desc: '远程 A2A：http://localhost:9999，查天气 / 翻译 / 摘要' }
      ]
    },
    probe: probeA2aCard,
    probeLabel: '探测远程 A2A 服务端',
    principle: '远程 Agent 与本地子 Agent 共用同一套 Definition / Resolver / Executor 接口：解析时去拉 ' +
      '/.well-known/agent-card.json 发现名片，执行时走 JSON-RPC 的 message/send，结果同样作为工具返回值回流。',
    run: runA2aAgent
  }
]

const active = ref('todo')
const current = computed(() => demos.find(d => d.key === active.value))

const errorMsg = ref('')

/** 每个示例各自的输入文本 */
const inputs = reactive(Object.fromEntries(demos.map(d => [d.key, d.presets[0]])))

/** 每个示例各自的结果（切页签不丢） */
const results = reactive(Object.fromEntries(demos.map(d => [d.key, null])))

/** 运行态：同一时刻只有一个示例在跑 */
const rt = reactive({ loading: false, elapsed: 0 })

const hasResult = computed(() => {
  const r = results[active.value]
  return r !== null && r !== undefined && r !== ''
})

/* ==================== 远程服务端探测（仅配置了 probe 的示例） ==================== */

const probeLoading = ref(false)
const probeText = ref('')
const probeOk = ref(false)

// 切换示例时清掉上一个示例的探测结果与报错，避免串味
watch(active, () => {
  probeText.value = ''
  errorMsg.value = ''
})

async function probeRemote() {
  const demo = current.value
  if (!demo.probe || probeLoading.value) return

  probeLoading.value = true
  probeText.value = ''
  try {
    probeText.value = await demo.probe()
    probeOk.value = !probeText.value.includes('❌')
  } catch (e) {
    probeOk.value = false
    probeText.value = '❌ 探测失败：' + (e.message || '未知错误')
  } finally {
    probeLoading.value = false
  }
}

/* ==================== TodoWrite 结果计算 ==================== */

const doneCount = computed(() =>
  (results.todo?.items || []).filter(i => i.status === 'completed').length
)
const progressPercent = computed(() => {
  const total = results.todo?.items?.length || 0
  return total === 0 ? 0 : Math.round((doneCount.value / total) * 100)
})

function markOf(status) {
  if (status === 'completed') return '✓'
  if (status === 'in_progress') return '▶'
  return '○'
}

function statusText(status) {
  if (status === 'completed') return '已完成'
  if (status === 'in_progress') return '进行中'
  return '待开始'
}

/* ==================== 计时器 ==================== */

let timer = null
function startTimer() {
  rt.elapsed = 0
  timer = setInterval(() => {
    rt.elapsed++
  }, 1000)
}
function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}
onUnmounted(stopTimer)

/* ==================== 执行（通用） ==================== */

async function run(key) {
  const demo = demos.find(d => d.key === key)
  if (rt.loading) return

  errorMsg.value = ''
  rt.loading = true
  rt.elapsed = 0
  results[key] = null
  startTimer()

  try {
    results[key] = await demo.run(inputs[key])
  } catch (e) {
    errorMsg.value = e.message || '运行失败，请确认后端已启动且模型 Key 已配置'
  } finally {
    rt.loading = false
    stopTimer()
  }
}
</script>

<style scoped>
.agent-demo {
  height: 100%;
  overflow-y: auto;
  padding: 20px 24px 32px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ==================== 顶部说明 ==================== */

.page-head {
  background: linear-gradient(120deg, #4f66f9 0%, #7b8cff 100%);
  border-radius: 12px;
  padding: 18px 22px;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  box-shadow: 0 6px 18px rgba(79, 102, 249, 0.18);
}

.head-text h2 {
  margin: 0 0 6px;
  font-size: 18px;
  font-weight: 700;
}

.head-text p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  opacity: 0.92;
}

.head-text code {
  background: rgba(255, 255, 255, 0.18);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 12px;
}

.head-badge {
  flex-shrink: 0;
  background: rgba(255, 255, 255, 0.16);
  border: 1px solid rgba(255, 255, 255, 0.28);
  padding: 6px 12px;
  border-radius: 999px;
  font-size: 12px;
  white-space: nowrap;
}

/* ==================== 示例切换 ==================== */

.tab-switch {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.switch-btn {
  flex: 1;
  min-width: 240px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 10px;
  cursor: pointer;
  text-align: left;
  transition: all 0.2s;
}

.switch-btn:hover {
  border-color: #b9c2ff;
  transform: translateY(-1px);
}

.switch-btn.active {
  border-color: #4f66f9;
  background: #f4f6ff;
  box-shadow: 0 3px 10px rgba(79, 102, 249, 0.12);
}

.switch-icon {
  font-size: 20px;
}

.switch-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.switch-text b {
  font-size: 14px;
  color: var(--text-main);
}

.switch-text em {
  font-style: normal;
  font-size: 12px;
  color: var(--text-sub);
}

/* ==================== 主体两栏 ==================== */

.demo-body {
  display: flex;
  gap: 16px;
  align-items: stretch;
  flex: 1;
  min-height: 460px;
}

.panel {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.input-panel {
  width: 380px;
  min-width: 320px;
}

.result-panel {
  flex: 1;
  min-width: 0;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-main);
  display: flex;
  align-items: center;
  gap: 10px;
}

.title-meta {
  font-size: 12px;
  font-weight: 400;
  color: var(--text-sub);
}

.task-input {
  min-height: 120px;
  resize: none;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-main);
  outline: none;
  transition: border-color 0.2s;
}

.task-input:focus {
  border-color: #4f66f9;
}

.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.chip-label {
  font-size: 12px;
  color: var(--text-sub);
}

.chip {
  border: 1px dashed #c3c9e6;
  background: #fafbff;
  color: #5a6488;
  font-size: 12px;
  padding: 5px 10px;
  border-radius: 999px;
  cursor: pointer;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: all 0.2s;
}

.chip:hover {
  border-style: solid;
  border-color: #4f66f9;
  color: #4f66f9;
  background: #f2f4ff;
}

.run-btn {
  border: none;
  background: linear-gradient(120deg, #4f66f9, #7b8cff);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  padding: 11px 16px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.run-btn:hover:not(:disabled) {
  box-shadow: 0 4px 14px rgba(79, 102, 249, 0.32);
}

.run-btn:disabled {
  opacity: 0.75;
  cursor: not-allowed;
}

.running {
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}

/* ==================== 目录卡片（子 Agent / Skill 共用） ==================== */

.catalog-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-main);
  margin-top: 2px;
}

.catalog-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.catalog-card {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px 12px;
  background: #fafbff;
}

.cg-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.cg-avatar {
  font-size: 16px;
}

.cg-name {
  font-size: 13px;
  font-weight: 600;
  color: #4f66f9;
  font-family: Consolas, monospace;
}

.cg-desc {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-sub);
  line-height: 1.6;
}

/* ==================== 结果区 ==================== */

.result-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-right: 4px;
}

.empty-state,
.loading-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--text-sub);
  font-size: 13px;
}

.empty-icon {
  font-size: 34px;
  opacity: 0.55;
}

.spinner {
  width: 30px;
  height: 30px;
  border: 3px solid #e5e8f5;
  border-top-color: #4f66f9;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.principle-card {
  border: 1px solid #d5dcff;
  border-left: 3px solid #4f66f9;
  border-radius: 8px;
  padding: 10px 12px;
  background: #f4f6ff;
}

.principle-title {
  font-size: 12px;
  font-weight: 600;
  color: #4f66f9;
  margin-bottom: 4px;
}

.principle-text {
  font-size: 12px;
  line-height: 1.7;
  color: #5a6488;
}

.progress-card {
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px 14px;
  background: #fafbff;
}

.progress-top {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: var(--text-main);
  margin-bottom: 8px;
}

.progress-top b {
  color: #4f66f9;
}

.progress-track {
  height: 8px;
  background: #e9ecf7;
  border-radius: 999px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #4f66f9, #7b8cff);
  border-radius: 999px;
  transition: width 0.4s ease;
}

.todo-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.todo-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fff;
  transition: all 0.2s;
}

.todo-item.completed {
  background: #f6fdf8;
  border-color: #cdeed7;
}

.todo-item.in_progress {
  background: #f5f8ff;
  border-color: #c7d1ff;
}

.todo-mark {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 12px;
  background: #eef0f7;
  color: #8a90a8;
}

.todo-item.completed .todo-mark {
  background: #2fbf71;
  color: #fff;
}

.todo-item.in_progress .todo-mark {
  background: #4f66f9;
  color: #fff;
  animation: pulse 1.2s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.55; }
}

.todo-text {
  flex: 1;
  min-width: 0;
}

.todo-content {
  font-size: 13px;
  color: var(--text-main);
  line-height: 1.6;
  word-break: break-word;
}

.todo-item.completed .todo-content {
  color: #5f6b7d;
}

.todo-active {
  margin-top: 3px;
  font-size: 12px;
  color: #4f66f9;
}

.todo-status {
  flex-shrink: 0;
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 999px;
  background: #f0f2f8;
  color: #8a90a8;
}

.todo-item.completed .todo-status {
  background: #e3f7ea;
  color: #2fbf71;
}

.todo-item.in_progress .todo-status {
  background: #e8edff;
  color: #4f66f9;
}

.answer-card {
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px 14px;
  background: #fff;
}

.answer-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-main);
  margin-bottom: 8px;
}

.answer-text {
  font-size: 13px;
  line-height: 1.8;
  color: #444c60;
  white-space: pre-wrap;
  word-break: break-word;
}

/* ==================== 流程卡片 ==================== */

.flow-card {
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 14px;
  background: #fafbff;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.flow-step {
  font-size: 13px;
  color: var(--text-main);
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 7px 14px;
  min-width: 220px;
  text-align: center;
}

.flow-step.highlight {
  border-color: #4f66f9;
  color: #4f66f9;
  font-weight: 600;
  background: #f2f4ff;
}

.flow-arrow {
  font-size: 13px;
  color: #b6bccf;
}

/* ==================== 探测远程服务端（A2A） ==================== */

.probe-btn {
  border: 1px solid #c3c9e6;
  background: #fff;
  color: #5a6488;
  font-size: 13px;
  padding: 8px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.probe-btn:hover:not(:disabled) {
  border-color: #4f66f9;
  color: #4f66f9;
  background: #f2f4ff;
}

.probe-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.probe-result {
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid var(--border);
}

.probe-result.ok {
  background: #f6fdf8;
  border-color: #cdeed7;
  color: #2f8f5b;
}

.probe-result.bad {
  background: #fff8f8;
  border-color: #ffd9d9;
  color: #c9563f;
}

/* ==================== 提示卡片（Skill） ==================== */

.tip-card {
  border: 1px dashed #c3c9e6;
  border-radius: 10px;
  padding: 12px 14px;
  background: #fafbff;
  font-size: 12px;
  line-height: 1.8;
  color: var(--text-sub);
}

.tip-card b {
  display: block;
  color: var(--text-main);
  font-size: 13px;
  margin-bottom: 4px;
}

.tip-card p {
  margin: 0;
}

.tip-card code {
  background: #eef0f7;
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 12px;
  color: #4f66f9;
}

/* ==================== 错误条 ==================== */

.error-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: #fff4f4;
  border: 1px solid #ffd4d4;
  color: #d94b4b;
  font-size: 13px;
  padding: 10px 14px;
  border-radius: 8px;
}

.error-close {
  border: none;
  background: transparent;
  color: #d94b4b;
  cursor: pointer;
  font-size: 14px;
}

@media (max-width: 1080px) {
  .demo-body {
    flex-direction: column;
  }

  .input-panel {
    width: 100%;
  }
}
</style>
