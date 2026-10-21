<template>
  <div class="dify-chat">
    <!-- 顶部：当前应用 -->
    <header class="app-bar">
      <button class="btn-back" @click="goBack">← 应用列表</button>
      <div class="app-meta">
        <span class="app-icon">{{ app.icon || '💬' }}</span>
        <span class="app-name">{{ app.name || appCode }}</span>
        <span class="app-desc">{{ app.description }}</span>
      </div>
      <!-- 画布类应用（advanced-chat / workflow）才展示编排图入口 -->
      <button v-if="hasCanvas" class="btn-flow" @click="toggleGraph">
        {{ showGraph ? '隐藏流程' : '编排流程' }}
      </button>
    </header>

    <div class="chat-body">
      <!-- 最右：编排流程面板（Chatflow / Workflow 才有画布） -->
      <aside v-if="showGraph" class="graph-side">
        <div class="side-head">
          <span class="side-title">编排流程</span>
          <span v-if="stage" class="stage-tag">{{ STAGE_LABEL[stage] || stage }}</span>
          <button class="btn-new" @click="showGraph = false">收起 ✕</button>
        </div>
        <div class="graph-inner">
          <div v-if="graphLoading" class="graph-tip">加载中…</div>
          <div v-else-if="graphError" class="graph-tip error">{{ graphError }}</div>
          <DifyFlowGraph v-else :nodes="graph.nodes || []" :edges="graph.edges || []" :stage="stage" />
        </div>
      </aside>

      <!-- 左：会话列表 -->
      <aside class="conv-side">
        <div class="side-head">
          <span class="side-title">会话</span>
          <button class="btn-new" @click="startNewConversation">＋ 新会话</button>
        </div>

        <div class="conv-list">
          <div
            v-for="c in conversations"
            :key="c.id"
            class="conv-item"
            :class="{ active: c.id === conversationId }"
            @click="selectConversation(c.id)"
          >
            <div class="conv-name">{{ c.name || '未命名会话' }}</div>
            <div class="conv-time">{{ formatTime(c.updatedAt || c.createdAt) }}</div>
            <div class="conv-ops">
              <span title="重命名" @click.stop="handleRename(c)">✏️</span>
              <span title="删除" @click.stop="handleDelete(c.id)">🗑️</span>
            </div>
          </div>
          <div v-if="!conversations.length" class="side-empty">暂无会话</div>
          <button v-if="hasMore" class="btn-more" @click="loadConversations(true)">加载更多</button>
        </div>
      </aside>

      <!-- 右：对话区 -->
      <section class="chat-main">
        <div ref="listRef" class="msg-list">
          <div v-for="(m, idx) in messages" :key="idx" class="msg" :class="m.role">
            <div class="avatar">{{ m.role === 'user' ? '我' : 'AI' }}</div>
            <div class="bubble">
              <div class="content">{{ m.content }}<span v-if="m.streaming" class="caret" /></div>
              <div v-if="m.role === 'assistant' && m.messageId && !m.streaming" class="msg-ops">
                <span :class="{ on: m.rated === 'like' }" title="点赞" @click="handleFeedback(m, 'like')">👍</span>
                <span :class="{ on: m.rated === 'dislike' }" title="点踩" @click="handleFeedback(m, 'dislike')">👎</span>
              </div>
            </div>
          </div>
          <div v-if="!messages.length" class="chat-empty">
            <div class="empty-icon">{{ app.icon || '🤖' }}</div>
            <div class="empty-text">和「{{ app.name || appCode }}」对话吧</div>
          </div>
        </div>

        <div v-if="suggested.length" class="suggested">
          <span v-for="s in suggested" :key="s" class="chip" @click="sendMessage(s)">{{ s }}</span>
        </div>

        <div class="composer">
          <textarea
            v-model="input"
            class="input"
            placeholder="输入消息，Enter 发送，Shift+Enter 换行"
            @keydown.enter.exact.prevent="sendMessage()"
          />
          <button class="btn-send" :disabled="loading || !input.trim()" @click="sendMessage()">
            {{ loading ? '生成中…' : '发送' }}
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DifyFlowGraph from './DifyFlowGraph.vue'
import {
  getDifyApp,
  getDifyAppGraph,
  listDifyConversations,
  listDifyMessages,
  streamChat,
  getSuggested,
  sendFeedback,
  renameDifyConversation,
  deleteDifyConversation
} from '../../api/dify'

const route = useRoute()
const router = useRouter()

const appCode = ref(route.params.appCode || '')
const app = ref({})
const conversations = ref([])
const conversationId = ref('')
const lastId = ref('')
const hasMore = ref(false)
const messages = ref([])
const suggested = ref([])
const input = ref('')
const loading = ref(false)
const listRef = ref(null)

/* ===== 编排流程面板 ===== */
const showGraph = ref(false)
const graphLoading = ref(false)
const graphError = ref('')
const graph = ref({ nodes: [], edges: [] })
const stage = ref('') // 当前运行阶段（node_started / workflow_started …）

/** 画布类应用：Chatflow（advanced-chat）与工作流才有编排图 */
const hasCanvas = computed(() => ['advanced-chat', 'chatflow', 'workflow'].includes(app.value?.mode))



async function loadGraph() {
  graphLoading.value = true
  graphError.value = ''
  try {
    graph.value = await getDifyAppGraph(appCode.value)
  } catch (e) {
    graphError.value = e?.message || '编排图加载失败'
  } finally {
    graphLoading.value = false
  }
}

function toggleGraph() {
  if (showGraph.value) {
    showGraph.value = false
    return
  }
  showGraph.value = true
  if (!graph.value.nodes.length && !graphLoading.value) loadGraph()
}

/** Dify 编排事件 → 中文阶段名 */
const STAGE_LABEL = {
  workflow_started: '流程开始',
  node_started: '节点执行中',
  node_finished: '节点完成',
  workflow_finished: '流程结束',
  message: '生成回复',
  message_end: '回复完成',
  tts_message: '语音合成',
  error: '执行出错'
}

function goBack() {
  router.push('/dify/apps')
}

function scrollBottom() {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(Number(ts) * 1000)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

async function loadApp() {
  try {
    app.value = await getDifyApp(appCode.value)
  } catch {
    app.value = { code: appCode.value, name: appCode.value }
  }
}

async function loadConversations(append = false) {
  const res = await listDifyConversations({
    app: appCode.value,
    lastId: append ? lastId.value : '',
    limit: 20
  })
  const data = res?.data || []
  hasMore.value = Boolean(res?.hasMore)
  conversations.value = append ? [...conversations.value, ...data] : data
  if (data.length) {
    lastId.value = data[data.length - 1].id
    if (!append && !conversationId.value) {
      conversationId.value = data[0].id
      await loadMessages(data[0].id)
    }
  }
}

async function loadMessages(id) {
  const res = await listDifyMessages({ app: appCode.value, conversationId: id, limit: 20 })
  const list = [...(res?.data || [])].reverse()
  const pairs = []
  list.forEach((item) => {
    if (item.query) pairs.push({ role: 'user', content: item.query })
    if (item.answer) pairs.push({ role: 'assistant', content: item.answer, messageId: item.id })
  })
  messages.value = pairs
  suggested.value = []
  scrollBottom()
}

function selectConversation(id) {
  conversationId.value = id
  loadMessages(id)
}

function startNewConversation() {
  conversationId.value = ''
  messages.value = []
  suggested.value = []
  input.value = ''
}

async function handleRename(conv) {
  const name = window.prompt('输入新的会话名称', conv.name || '')
  if (!name) return
  await renameDifyConversation({ appCode: appCode.value, conversationId: conv.id, name })
  await loadConversations()
}

async function handleDelete(id) {
  if (!window.confirm('确认删除该会话？')) return
  await deleteDifyConversation(id, appCode.value)
  if (conversationId.value === id) startNewConversation()
  await loadConversations()
}

async function handleFeedback(msg, rating) {
  await sendFeedback({ appCode: appCode.value, messageId: msg.messageId, rating })
  msg.rated = rating
}

async function sendMessage(text) {
  const content = (text || input.value || '').trim()
  if (!content || loading.value) return

  loading.value = true
  input.value = ''
  suggested.value = []
  messages.value.push({ role: 'user', content })
  const assistant = { role: 'assistant', content: '', streaming: true }
  messages.value.push(assistant)
  scrollBottom()

  try {
    await streamChat({
      appCode: appCode.value,
      content,
      conversationId: conversationId.value || undefined,
      onMessage: (payload) => {
        assistant.content += payload.content || ''
        scrollBottom()
      },
      onStage: (payload) => {
        stage.value = payload.event || ''
      },
      onDone: async (payload) => {
        assistant.streaming = false
        stage.value = ''
        assistant.messageId = payload.messageId
        if (payload.conversationId) {
          const isNew = !conversationId.value
          conversationId.value = payload.conversationId
          if (isNew) await loadConversations()
        }
        if (payload.messageId) {
          try {
            suggested.value = await getSuggested(payload.messageId, appCode.value)
          } catch {
            suggested.value = []
          }
        }
        scrollBottom()
      }
    })
  } catch (e) {
    assistant.content = '调用失败：' + (e.message || e)
  } finally {
    assistant.streaming = false
    loading.value = false
    scrollBottom()
  }
}

// 切换应用时重置会话上下文
watch(
  () => route.params.appCode,
  async (code) => {
    appCode.value = code || ''
    startNewConversation()
    conversations.value = []
    lastId.value = ''
    await loadApp()
    await loadConversations()
  }
)

onMounted(async () => {
  await loadApp()
  await loadConversations()
})
</script>

<style scoped>
.dify-chat {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #f7f8fc;
}

/* ===== 顶部应用条 ===== */
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
  color: var(--text-main);
}

.app-desc {
  font-size: 12px;
  color: var(--text-sub);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-body {
  flex: 1;
  min-height: 0;
  display: flex;
}

/* ===== 左侧会话 ===== */
.conv-side {
  width: 250px;
  min-width: 250px;
  background: #fff;
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
}

.side-head {
  height: 50px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px;
  border-bottom: 1px solid var(--border);
}

.side-title {
  font-size: 14px;
  font-weight: 600;
}

.btn-new {
  border: none;
  background: #4f66f9;
  color: #fff;
  font-size: 12px;
  padding: 5px 10px;
  border-radius: 6px;
  cursor: pointer;
}

/* ===== 右侧编排流程 ===== */
.btn-flow {
  margin-left: auto;
  border: 1px solid #4f66f9;
  background: #fff;
  color: #4f66f9;
  font-size: 12px;
  padding: 5px 12px;
  border-radius: 6px;
  cursor: pointer;
}

.btn-flow:hover {
  background: #eef1ff;
}

.graph-side {
  width: 420px;
  min-width: 320px;
  background: #fff;
  border-left: 1px solid var(--border);
  display: flex;
  flex-direction: column;
}

.graph-inner {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 12px;
  background: #f8fafc;
}

.graph-tip {
  padding: 24px 0;
  text-align: center;
  color: #94a3b8;
  font-size: 13px;
}

.graph-tip.error {
  color: #ef4444;
}

.stage-tag {
  margin-left: auto;
  margin-right: 8px;
  font-size: 11px;
  color: #4f66f9;
  background: #eef1ff;
  padding: 3px 8px;
  border-radius: 10px;
}

.conv-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.conv-item {
  position: relative;
  padding: 9px 12px;
  border-radius: 8px;
  cursor: pointer;
}

.conv-item:hover {
  background: #f3f5fb;
}

.conv-item.active {
  background: rgba(79, 102, 249, 0.12);
}

.conv-name {
  font-size: 13px;
  color: var(--text-main);
  padding-right: 44px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-time {
  font-size: 11px;
  color: var(--text-sub);
  margin-top: 2px;
}

.conv-ops {
  position: absolute;
  right: 10px;
  top: 7px;
  display: none;
  gap: 6px;
}

.conv-item:hover .conv-ops {
  display: flex;
}

.conv-ops span {
  cursor: pointer;
}

.side-empty,
.btn-more {
  font-size: 12px;
  color: var(--text-sub);
  text-align: center;
  padding: 10px 0;
}

.btn-more {
  width: 100%;
  border: 1px dashed var(--border);
  background: #fff;
  border-radius: 6px;
  cursor: pointer;
}

/* ===== 右侧对话 ===== */
.chat-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: 14px;
  gap: 10px;
}

.msg-list {
  flex: 1;
  overflow-y: auto;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 16px;
}

.msg {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}

.msg.user {
  flex-direction: row-reverse;
}

.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, #4f66f9, #7b8cff);
  color: #fff;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.msg.user .avatar {
  background: linear-gradient(135deg, #36cfc9, #08979c);
}

.bubble {
  max-width: 70%;
  background: #f4f6fb;
  border-radius: 10px;
  padding: 10px 14px;
}

.msg.user .bubble {
  background: #eef1ff;
}

.content {
  font-size: 14px;
  line-height: 1.7;
  color: var(--text-main);
  white-space: pre-wrap;
  word-break: break-word;
}

.caret {
  display: inline-block;
  width: 6px;
  height: 14px;
  background: #4f66f9;
  margin-left: 3px;
  animation: blink 1s infinite;
  vertical-align: text-bottom;
}

@keyframes blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0; }
}

.msg-ops {
  margin-top: 6px;
  display: flex;
  gap: 10px;
  font-size: 13px;
  cursor: pointer;
  opacity: 0.6;
}

.msg-ops span.on {
  opacity: 1;
}

.chat-empty {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--text-sub);
}

.empty-icon {
  font-size: 40px;
}

.empty-text {
  font-size: 14px;
  margin-top: 8px;
}

.suggested {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.chip {
  font-size: 12px;
  color: #4f66f9;
  background: #eef1ff;
  border: 1px solid #d9defa;
  border-radius: 14px;
  padding: 5px 12px;
  cursor: pointer;
}

.chip:hover {
  background: #dfe4ff;
}

.composer {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}

.input {
  flex: 1;
  height: 72px;
  resize: none;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px 12px;
  font-size: 14px;
  outline: none;
}

.input:focus {
  border-color: #4f66f9;
}

.btn-send {
  height: 72px;
  width: 90px;
  border: none;
  border-radius: 8px;
  background: #4f66f9;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}

.btn-send:disabled {
  background: #b9c0e8;
  cursor: not-allowed;
}
</style>
