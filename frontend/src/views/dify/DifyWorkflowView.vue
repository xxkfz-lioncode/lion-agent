<template>
  <div class="dify-workflow">
    <!-- 顶部：当前应用 -->
    <header class="app-bar">
      <button class="btn-back" @click="goBack">← 应用列表</button>
      <div class="app-meta">
        <span class="app-icon">{{ app.icon || '🔀' }}</span>
        <span class="app-name">{{ app.name || appCode }}</span>
        <span class="app-desc">{{ app.description }}</span>
      </div>
    </header>

    <div class="wf-body">
      <!-- 左：输入区 -->
      <section class="input-panel">
        <div class="panel-title">运行参数</div>

        <div class="var-list">
          <div v-for="(item, idx) in inputs" :key="idx" class="var-row">
            <input v-model="item.key" class="var-key" placeholder="变量名（如 query）" />
            <input v-model="item.value" class="var-value" placeholder="变量值" />
            <button class="btn-del" @click="removeVar(idx)">－</button>
          </div>
          <div v-if="!inputs.length" class="var-empty">暂无输入变量，工作流若无输入可直接运行</div>
        </div>

        <button class="btn-add" @click="addVar">＋ 添加变量</button>

        <div class="mode-row">
          <span class="mode-label">执行方式</span>
          <label><input v-model="mode" type="radio" value="blocking" /> 阻塞（一次性返回）</label>
          <label><input v-model="mode" type="radio" value="streaming" /> 流式（实时过程）</label>
        </div>

        <div class="ops">
          <button class="btn-primary" :disabled="loading" @click="run">
            {{ loading ? '执行中…' : '▶ 运行' }}
          </button>
          <button class="btn-outline" @click="reset">清空</button>
        </div>
      </section>

      <!-- 右：结果区 -->
      <section class="result-panel">
        <div class="panel-title">执行结果</div>

        <!-- 流式：事件时间线 -->
        <div v-if="mode === 'streaming'" class="event-list">
          <div v-for="(e, idx) in events" :key="idx" class="event-item">
            <span class="event-badge">{{ e.event }}</span>
            <pre class="event-data">{{ formatJson(e.data) }}</pre>
          </div>
          <div v-if="!events.length" class="result-empty">暂无事件</div>
        </div>

        <!-- 阻塞：最终结果 -->
        <div v-else class="result-box">
          <div v-if="result" class="result-meta">
            <span>workflowRunId：{{ result.workflowRunId || '-' }}</span>
            <span>taskId：{{ result.taskId || '-' }}</span>
          </div>
          <pre v-if="result" class="result-json">{{ formatJson(result.data) }}</pre>
          <div v-else class="result-empty">暂无结果</div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getDifyApp, runWorkflow, streamWorkflow } from '../../api/dify'

const route = useRoute()
const router = useRouter()

const appCode = ref(route.params.appCode || '')
const app = ref({})
const inputs = ref([{ key: '', value: '' }])
const mode = ref('blocking')
const loading = ref(false)
const result = ref(null)
const events = ref([])

function goBack() {
  router.push('/dify/apps')
}

async function loadApp() {
  try {
    app.value = await getDifyApp(appCode.value)
  } catch {
    app.value = { code: appCode.value, name: appCode.value }
  }
}

function addVar() {
  inputs.value.push({ key: '', value: '' })
}

function removeVar(idx) {
  inputs.value.splice(idx, 1)
}

function buildInputs() {
  const map = {}
  inputs.value.forEach((i) => {
    if (i.key && i.key.trim()) map[i.key.trim()] = i.value
  })
  return map
}

function formatJson(data) {
  if (data == null) return ''
  if (typeof data === 'string') return data
  try {
    return JSON.stringify(data, null, 2)
  } catch {
    return String(data)
  }
}

function reset() {
  inputs.value = [{ key: '', value: '' }]
  result.value = null
  events.value = []
}

async function run() {
  const payload = { appCode: appCode.value, inputs: buildInputs() }
  loading.value = true
  result.value = null
  events.value = []

  try {
    if (mode.value === 'blocking') {
      result.value = await runWorkflow(payload)
    } else {
      await streamWorkflow({
        appCode: appCode.value,
        inputs: payload.inputs,
        onEvent: (evt) => events.value.push(evt)
      })
    }
  } catch (e) {
    window.alert('执行失败：' + (e.message || e))
  } finally {
    loading.value = false
  }
}

watch(
  () => route.params.appCode,
  async (code) => {
    appCode.value = code || ''
    reset()
    await loadApp()
  }
)

onMounted(() => {
  loadApp()
})
</script>

<style scoped>
.dify-workflow {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #f7f8fc;
}

.app-bar {
  height: 56px;
  min-height: 56px;
  background: #fff;
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 14px;
}

.btn-back {
  border: 1px solid var(--border);
  background: #fff;
  color: var(--text-main);
  font-size: 13px;
  padding: 6px 12px;
  border-radius: 6px;
  cursor: pointer;
}

.btn-back:hover {
  border-color: #4f66f9;
  color: #4f66f9;
}

.app-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.app-icon {
  font-size: 20px;
}

.app-name {
  font-size: 15px;
  font-weight: 600;
}

.app-desc {
  font-size: 12px;
  color: var(--text-sub);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wf-body {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 12px;
  padding: 14px;
}

.input-panel,
.result-panel {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.input-panel {
  width: 380px;
  min-width: 380px;
  padding: 14px;
}

.result-panel {
  flex: 1;
  min-width: 0;
  padding: 14px;
}

.panel-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 12px;
}

.var-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.var-row {
  display: flex;
  gap: 8px;
}

.var-key,
.var-value {
  flex: 1;
  min-width: 0;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 7px 10px;
  font-size: 13px;
  outline: none;
}

.var-key:focus,
.var-value:focus {
  border-color: #4f66f9;
}

.btn-del {
  width: 30px;
  border: 1px solid var(--border);
  background: #fff;
  border-radius: 6px;
  cursor: pointer;
  color: #d9534f;
}

.var-empty {
  font-size: 12px;
  color: var(--text-sub);
  padding: 12px 0;
}

.btn-add {
  margin-top: 10px;
  border: 1px dashed #4f66f9;
  background: #f6f8ff;
  color: #4f66f9;
  font-size: 13px;
  padding: 7px 12px;
  border-radius: 6px;
  cursor: pointer;
}

.mode-row {
  margin-top: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
}

.mode-label {
  color: var(--text-sub);
}

.ops {
  margin-top: auto;
  display: flex;
  gap: 8px;
}

.btn-primary,
.btn-outline {
  font-size: 13px;
  padding: 8px 14px;
  border-radius: 6px;
  cursor: pointer;
}

.btn-primary {
  border: none;
  background: #4f66f9;
  color: #fff;
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-outline {
  border: 1px solid var(--border);
  background: #fff;
}

/* ===== 结果区 ===== */
.event-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.event-item {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px 12px;
}

.event-badge {
  display: inline-block;
  font-size: 12px;
  color: #4f66f9;
  background: #eef1ff;
  border-radius: 10px;
  padding: 2px 10px;
  margin-bottom: 6px;
}

.event-data,
.result-json {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.result-box {
  flex: 1;
  overflow: auto;
}

.result-meta {
  display: flex;
  gap: 18px;
  font-size: 12px;
  color: var(--text-sub);
  margin-bottom: 8px;
}

.result-json {
  background: #fafbfd;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
}

.result-empty {
  font-size: 13px;
  color: var(--text-sub);
  padding: 20px 0;
  text-align: center;
}
</style>
