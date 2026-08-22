<template>
  <div class="chat-layout">
    <!-- 全局轻提示 -->
    <Transition name="toast">
      <div v-if="toastVisible" class="toast" :class="toastType">
        <span class="toast-icon">{{ toastType === 'success' ? '✓' : '✕' }}</span>
        <span class="toast-text">{{ toastMessage }}</span>
      </div>
    </Transition>

    <!-- 左侧会话列表 -->
    <aside class="conversation-list">
      <div class="conversation-header">
        <span>历史对话</span>
        <div class="conversation-header-actions">
          <button class="clear-all-btn" title="清空所有会话" @click="handleClearAll">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="3 6 5 6 21 6"></polyline>
              <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
              <line x1="10" y1="11" x2="10" y2="17"></line>
              <line x1="14" y1="11" x2="14" y2="17"></line>
            </svg>
          </button>
          <button class="new-conversation-btn" title="新建会话" @click="startNewChat">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="12" y1="5" x2="12" y2="19"></line>
              <line x1="5" y1="12" x2="19" y2="12"></line>
            </svg>
          </button>
        </div>
      </div>
      <div class="conversation-search">
        <input
          v-model="convKeyword"
          type="text"
          class="search-input"
          placeholder="搜索会话"
          @keyup.enter="onSearchConversations"
        >
      </div>
      <div class="conversation-items">
        <div
          v-for="conv in conversations"
          :key="conv.id"
          class="conversation-item"
          :class="{ active: conv.id === conversationId }"
          @click="selectConversation(conv.id)"
        >
          <span class="conversation-title">{{ conv.title || '新对话' }}</span>
          <div class="conversation-actions">
            <button class="icon-btn" title="重命名" @click.stop="handleRename(conv)">✏️</button>
            <button class="icon-btn" title="删除" @click.stop="handleDelete(conv.id)">🗑️</button>
          </div>
        </div>
      </div>
      <PaginationBar
        v-if="convPagination.pages > 1 || convPagination.total > 0"
        :page-num="convPagination.pageNum"
        :page-size="convPagination.pageSize"
        :pages="convPagination.pages"
        :total="convPagination.total"
        @change="onConvPageChange"
      />
    </aside>

    <!-- 右侧聊天区 -->
    <div class="chat-page">
      <!-- 消息列表 -->
      <div ref="messageList" class="message-list">
        <div
          v-for="msg in currentMessages"
          :key="msg.id"
          class="message-item"
          :class="msg.role === 'user' ? 'user' : 'ai'"
        >
          <div class="message-avatar">{{ msg.role === 'user' ? '👤' : '🦁' }}</div>
          <div class="message-bubble">
            <div v-if="msg.content" class="message-text" v-html="renderMarkdown(msg.content)"></div>
            <div v-else-if="loading" class="typing-wrapper">
              <div class="typing-text">{{ thinkingHint }}</div>
              <div class="typing-indicator">
                <span class="typing-dot"></span>
                <span class="typing-dot"></span>
                <span class="typing-dot"></span>
              </div>
              <div v-if="thinkingElapsed > 0" class="typing-elapsed">已思考 {{ thinkingElapsed }}s</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="input-area">
        <textarea
          v-model="inputText"
          class="chat-input"
          placeholder="输入消息，Enter 发送，Shift+Enter 换行"
          :disabled="loading"
          @keydown.enter.exact.prevent="send"
        ></textarea>
        <button class="send-btn" :disabled="loading || !inputText.trim()" @click="send">
          发送
        </button>
      </div>
    </div>

    <ConfirmDialog
      v-model="confirmVisible"
      :title="confirmConfig.title"
      :content="confirmConfig.content"
      :confirm-text="confirmConfig.confirmText"
      :cancel-text="confirmConfig.cancelText"
      :loading="deletingConv"
      @confirm="onConfirmDialog"
    />

    <InputDialog
      v-model="renameVisible"
      title="修改会话标题"
      :value="renameValue"
      placeholder="请输入新标题"
      :maxlength="128"
      confirm-text="保存"
      :loading="renameLoading"
      @confirm="onConfirmRename"
    />
  </div>
</template>

<script setup>
import { ref, nextTick, onMounted, onBeforeUnmount, watch, computed } from 'vue'
import { useRoute } from 'vue-router'
import { marked } from 'marked'
import { streamChat, listMessages, listConversations, deleteConversation, clearAllConversations, renameConversation } from '../api/chat'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import InputDialog from '../components/InputDialog.vue'
import PaginationBar from '../components/PaginationBar.vue'

const route = useRoute()
const messageList = ref(null)
const inputText = ref('')
const loading = ref(false)
const currentMessages = ref([
  { id: 'welcome', role: 'ai', content: '你好！有什么我可以帮你的吗？' }
])

const conversationId = ref(route.query.id ? Number(route.query.id) : null)
const conversations = ref([])
const deletingConv = ref(false)
const deletingConvId = ref(null)
const confirmVisible = ref(false)
const confirmType = ref('delete')
const confirmConfig = ref({ title: '', content: '', confirmText: '确定', cancelText: '取消' })

// 轻量级全局提示
const toastVisible = ref(false)
const toastMessage = ref('')
const toastType = ref('success')
let toastTimer = null

const thinkingElapsed = ref(0)
let thinkingTimer = null
const thinkingHints = [
  '正在理解你的问题…',
  '正在查找相关信息…',
  '正在整理思路…',
  '正在生成回答…'
]
const thinkingHint = computed(() => {
  const idx = Math.min(Math.floor(thinkingElapsed.value / 2), thinkingHints.length - 1)
  return thinkingHints[idx]
})

const renameVisible = ref(false)
const renamingConv = ref(null)
const renameValue = ref('')
const renameLoading = ref(false)

const convKeyword = ref('')
const convPagination = ref({ pageNum: 1, pageSize: 20, pages: 1, total: 0 })

watch(() => route.query.id, (id) => {
  if (id) {
    conversationId.value = Number(id)
    loadMessages(conversationId.value)
  }
})

onMounted(async () => {
  await loadConversations()
  if (conversationId.value) {
    await loadMessages(conversationId.value)
  }
})

async function loadConversations() {
  try {
    const res = await listConversations({
      pageNum: convPagination.value.pageNum,
      pageSize: convPagination.value.pageSize,
      keyword: convKeyword.value
    })
    conversations.value = res.list || []
    convPagination.value = {
      pageNum: Number(res.pageNum) || 1,
      pageSize: Number(res.pageSize) || 20,
      pages: Number(res.pages) || 1,
      total: Number(res.total) || 0
    }
  } catch (e) {
    console.error('加载会话列表失败', e)
  }
}

function onSearchConversations() {
  convPagination.value.pageNum = 1
  loadConversations()
}

function onConvPageChange(page) {
  convPagination.value.pageNum = page
  loadConversations()
}

async function loadMessages(id) {
  try {
    const res = await listMessages(id)
    if (res && res.list && res.list.length > 0) {
      currentMessages.value = res.list.map(item => ({
        id: item.id,
        role: item.role === 'user' ? 'user' : 'ai',
        content: item.content
      }))
    } else {
      currentMessages.value = [
        { id: 'welcome', role: 'ai', content: '你好！有什么我可以帮你的吗？' }
      ]
    }
  } catch (e) {
    console.error('加载消息失败', e)
  }
}

function selectConversation(id) {
  conversationId.value = id
  loadMessages(id)
}

function handleDelete(id) {
  confirmType.value = 'delete'
  deletingConvId.value = id
  confirmConfig.value = {
    title: '删除会话',
    content: '确定删除该会话吗？删除后该会话的所有消息将一并清空，且无法恢复。',
    confirmText: '删除',
    cancelText: '取消'
  }
  confirmVisible.value = true
}

function handleClearAll() {
  confirmType.value = 'clear'
  deletingConvId.value = null
  confirmConfig.value = {
    title: '清空所有会话',
    content: '确定清空所有会话吗？该操作将删除所有历史会话及消息，且无法恢复。',
    confirmText: '清空',
    cancelText: '取消'
  }
  confirmVisible.value = true
}

async function onConfirmDialog() {
  if (confirmType.value === 'clear') {
    await onConfirmClearAll()
  } else {
    await onConfirmDeleteConv()
  }
}

async function onConfirmDeleteConv() {
  if (!deletingConvId.value) return
  deletingConv.value = true
  try {
    await deleteConversation(deletingConvId.value)
    if (conversationId.value === deletingConvId.value) {
      startNewChat()
    }
    confirmVisible.value = false
    await loadConversations()
    showToast('删除成功', 'success')
  } catch (e) {
    console.error('删除会话失败', e)
    showToast(e.message || '删除会话失败，请稍后重试', 'error')
  } finally {
    deletingConv.value = false
    deletingConvId.value = null
  }
}

async function onConfirmClearAll() {
  if (conversations.value.length === 0) {
    confirmVisible.value = false
    showToast('当前没有会话可清空', 'error')
    return
  }
  deletingConv.value = true
  try {
    await clearAllConversations()
    confirmVisible.value = false
    // 若当前正查看某个历史会话，新建空会话
    startNewChat()
    await loadConversations()
    showToast('已清空所有会话', 'success')
  } catch (e) {
    console.error('清空所有会话失败', e)
    showToast(e.message || '清空会话失败，请稍后重试', 'error')
  } finally {
    deletingConv.value = false
  }
}

function showToast(message, type = 'success') {
  toastMessage.value = message
  toastType.value = type
  toastVisible.value = true
  if (toastTimer) clearTimeout(toastTimer)
  toastTimer = setTimeout(() => {
    toastVisible.value = false
  }, 2500)
}

function handleRename(conv) {
  renamingConv.value = conv
  renameValue.value = conv.title || '新对话'
  renameVisible.value = true
}

async function onConfirmRename(title) {
  if (!renamingConv.value) return
  renameLoading.value = true
  try {
    await renameConversation(renamingConv.value.id, title)
    renameVisible.value = false
    await loadConversations()
  } catch (e) {
    console.error('重命名会话失败', e)
  } finally {
    renameLoading.value = false
    renamingConv.value = null
  }
}

function startNewChat() {
  conversationId.value = null
  currentMessages.value = [
    { id: 'welcome', role: 'ai', content: '你好！有什么我可以帮你的吗？' }
  ]
  inputText.value = ''
}

function renderMarkdown(text) {
  if (!text) return ''
  return marked.parse(text)
}

function scrollToBottom() {
  nextTick(() => {
    const el = messageList.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function startThinkingTimer() {
  thinkingElapsed.value = 0
  stopThinkingTimer()
  thinkingTimer = setInterval(() => {
    thinkingElapsed.value++
  }, 1000)
}

function stopThinkingTimer() {
  if (thinkingTimer) {
    clearInterval(thinkingTimer)
    thinkingTimer = null
  }
}

onBeforeUnmount(stopThinkingTimer)

async function send() {
  const content = inputText.value.trim()
  if (!content || loading.value) return

  currentMessages.value.push({ id: Date.now(), role: 'user', content })
  const aiMsgId = Date.now() + 1
  currentMessages.value.push({ id: aiMsgId, role: 'ai', content: '' })
  inputText.value = ''
  loading.value = true
  startThinkingTimer()
  scrollToBottom()

  try {
    await streamChat({
      conversationId: conversationId.value,
      message: content,
      onStart: (payload) => {
        conversationId.value = payload.conversationId
      },
      onMessage: (payload) => {
        const msg = currentMessages.value.find(m => m.id === aiMsgId)
        if (msg) msg.content += payload.content || ''
        scrollToBottom()
      },
      onDone: async () => {
        const msg = currentMessages.value.find(m => m.id === aiMsgId)
        if (msg && !msg.content) {
          msg.content = '抱歉，我没有获取到回复，请稍后再试。'
        }
        await loadConversations()
      }
    })
  } catch (e) {
    const msg = currentMessages.value.find(m => m.id === aiMsgId)
    if (msg) {
      msg.content = `抱歉，服务暂时不可用（${e.message}）`
    }
  } finally {
    loading.value = false
    stopThinkingTimer()
    scrollToBottom()
  }
}
</script>

<style scoped>
.chat-layout {
  height: 100%;
  display: flex;
  overflow: hidden;
}

.conversation-list {
  width: 220px;
  min-width: 220px;
  display: flex;
  flex-direction: column;
  background: #f8f9fb;
  border-right: 1px solid var(--border);
}

.conversation-header {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  border-bottom: 1px solid var(--border);
  font-size: 14px;
  font-weight: 600;
  color: var(--text-main);
}

.conversation-header-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.clear-all-btn,
.new-conversation-btn {
  width: 28px;
  height: 28px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s ease;
}

.new-conversation-btn {
  color: var(--primary);
}

.clear-all-btn {
  color: var(--text-weak);
}

.new-conversation-btn:hover,
.clear-all-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 3px 8px rgba(0, 0, 0, 0.08);
}

.new-conversation-btn:hover {
  color: #fff;
  background: var(--primary);
  border-color: var(--primary);
}

.clear-all-btn:hover {
  color: #fff;
  background: #ff4d4f;
  border-color: #ff4d4f;
}

.conversation-search {
  padding: 8px 12px;
  border-bottom: 1px solid var(--border);
}

.conversation-search .search-input {
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 13px;
  outline: none;
  box-sizing: border-box;
}

.conversation-search .search-input:focus {
  border-color: var(--primary);
}

.conversation-items {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.conversation-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  margin-bottom: 4px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  color: var(--text-main);
}

.conversation-item:hover,
.conversation-item.active {
  background: #eef1ff;
  color: var(--primary);
}

.conversation-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  margin-right: 6px;
}

.conversation-actions {
  display: none;
  gap: 4px;
}

.conversation-item:hover .conversation-actions {
  display: flex;
}

.icon-btn {
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  padding: 2px;
  opacity: 0.7;
}

.icon-btn:hover {
  opacity: 1;
}

.chat-page {
  flex: 1;
  min-width: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #fff;
}

.chat-toolbar {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 0 24px;
  border-bottom: 1px solid var(--border);
}

.chat-header {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  border-bottom: 1px solid var(--border);
}

.header-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-main);
}

.new-chat-btn {
  padding: 8px 16px;
  border: 1px solid var(--primary);
  border-radius: 8px;
  background: #fff;
  color: var(--primary);
  font-size: 13px;
  cursor: pointer;
}

.new-chat-btn:hover {
  background: #f2f5ff;
}

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}

.message-item {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.message-item.user {
  flex-direction: row-reverse;
}

.message-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: #eef1ff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 18px;
}

.message-bubble {
  max-width: 70%;
  padding: 12px 16px;
  border-radius: 12px;
  background: #f5f6fa;
  color: var(--text-main);
  line-height: 1.6;
}

.message-item.user .message-bubble {
  background: var(--primary);
  color: #fff;
}

.message-text :deep(p) {
  margin: 0 0 8px;
}

.message-text :deep(p:last-child) {
  margin-bottom: 0;
}

.typing-wrapper {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 4px 0;
}

.typing-text {
  font-size: 14px;
  color: var(--text-main);
}

.typing-indicator {
  display: flex;
  align-items: center;
  gap: 4px;
}

.typing-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--primary);
  animation: typing 1.4s infinite both;
}

.typing-dot:nth-child(2) {
  animation-delay: 0.2s;
}

.typing-dot:nth-child(3) {
  animation-delay: 0.4s;
}

.typing-elapsed {
  font-size: 12px;
  color: var(--text-sub);
}

@keyframes typing {
  0%, 80%, 100% {
    transform: scale(0.6);
    opacity: 0.4;
  }
  40% {
    transform: scale(1);
    opacity: 1;
  }
}

.input-area {
  display: flex;
  gap: 12px;
  padding: 16px 24px 24px;
  border-top: 1px solid var(--border);
}

.chat-input {
  flex: 1;
  min-height: 52px;
  max-height: 140px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: 12px;
  resize: none;
  font-size: 14px;
  line-height: 1.5;
}

.chat-input:focus {
  outline: none;
  border-color: var(--primary);
}

.send-btn {
  padding: 0 22px;
  border: none;
  border-radius: 12px;
  background: var(--primary);
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}

.send-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 轻量级全局提示 */
.toast {
  position: fixed;
  top: 24px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 1000;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 20px;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
  font-size: 14px;
  color: var(--text-main);
  pointer-events: none;
}

.toast.success {
  border-left: 4px solid #52c41a;
}

.toast.error {
  border-left: 4px solid #ff4d4f;
}

.toast-icon {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 12px;
  color: #fff;
}

.toast.success .toast-icon {
  background: #52c41a;
}

.toast.error .toast-icon {
  background: #ff4d4f;
}

.toast-enter-active,
.toast-leave-active {
  transition: all 0.25s ease;
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(-12px);
}
</style>
