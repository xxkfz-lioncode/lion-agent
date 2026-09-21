<template>
  <div class="record-page">
    <!-- 顶部：标题 + 搜索 + 刷新 -->
    <header class="page-header">
      <h2 class="page-title">会话记录</h2>
      <div class="header-actions">
        <input
          v-model="convKeyword"
          class="search-input"
          type="text"
          placeholder="搜索会话标题 / 消息内容"
          @keyup.enter="onConvSearch"
        >
        <button class="refresh-btn" @click="onConvSearch">查询</button>
        <button class="action-btn" @click="loadAll">刷新</button>
      </div>
    </header>

    <!-- 统计条 -->
    <div class="stat-bar">
      <div class="stat-item">
        <span class="stat-value">{{ stats.conversationCount || 0 }}</span>
        <span class="stat-label">会话数</span>
      </div>
      <div class="stat-item">
        <span class="stat-value">{{ stats.messageCount || 0 }}</span>
        <span class="stat-label">消息总数</span>
      </div>
      <div class="stat-item">
        <span class="stat-value user">{{ stats.userMessageCount || 0 }}</span>
        <span class="stat-label">用户提问</span>
      </div>
      <div class="stat-item">
        <span class="stat-value ai">{{ stats.assistantMessageCount || 0 }}</span>
        <span class="stat-label">AI 回复</span>
      </div>
    </div>

    <div class="record-body">
      <!-- 左：会话列表 -->
      <aside class="conv-panel">
        <div class="panel-title">会话列表（{{ convPagination.total }}）</div>

        <div class="conv-list">
          <div
            v-for="c in conversations"
            :key="c.id"
            class="conv-item"
            :class="{ active: current && current.id === c.id }"
            @click="selectConversation(c)"
          >
            <div class="conv-main">
              <div class="conv-name" :title="c.title">{{ c.title || '新对话' }}</div>
              <div class="conv-sub">
                <span class="conv-count user" title="用户提问条数">我 {{ c.userMessageCount || 0 }}</span>
                <span class="conv-count ai" title="AI 回复条数">AI {{ c.assistantMessageCount || 0 }}</span>
                <span class="conv-time">{{ formatTime(c.lastMessageAt || c.updatedAt) }}</span>
              </div>
            </div>
            <div class="conv-id">#{{ c.id }}</div>
          </div>

          <div v-if="conversations.length === 0 && !convLoading" class="panel-empty">
            暂无会话记录
          </div>
        </div>

        <PaginationBar
          v-if="convPagination.total > 0"
          compact
          :page-num="convPagination.pageNum"
          :page-size="convPagination.pageSize"
          :pages="convPagination.pages"
          :total="convPagination.total"
          @change="onConvPageChange"
        />
      </aside>

      <!-- 右：消息明细 -->
      <section class="msg-panel">
        <template v-if="current">
          <div class="msg-header">
            <div class="msg-title" :title="current.title">{{ current.title || '新对话' }}</div>
            <div class="msg-filters">
              <select v-model="roleFilter" class="filter-select" @change="onMsgFilterChange">
                <option value="">全部角色</option>
                <option value="user">仅用户提问</option>
                <option value="assistant">仅 AI 回复</option>
              </select>
              <input
                v-model="msgKeyword"
                class="search-input"
                type="text"
                placeholder="消息内容关键字"
                @keyup.enter="onMsgFilterChange"
              >
              <button class="action-btn" title="点击切换排序" @click="toggleOrder">
                {{ asc ? '时间正序 ↑' : '时间倒序 ↓' }}
              </button>
            </div>
          </div>

          <div class="msg-list">
            <div
              v-for="m in messages"
              :key="m.id"
              class="msg-row"
              :class="m.role === 'user' ? 'row-user' : 'row-ai'"
            >
              <div class="avatar" :class="m.role === 'user' ? 'avatar-user' : 'avatar-ai'">
                {{ m.role === 'user' ? '我' : 'AI' }}
              </div>
              <div class="bubble-wrap">
                <div class="bubble-meta">
                  <span class="role-name">{{ m.role === 'user' ? '用户输入' : 'AI 回复' }}</span>
                  <span class="msg-time">{{ formatTime(m.createdAt) }}</span>
                  <button class="copy-btn" @click="copyContent(m.content)">复制</button>
                </div>
                <div class="bubble" :class="m.role === 'user' ? 'bubble-user' : 'bubble-ai'">
                  <div class="bubble-text" v-html="highlight(m.content, msgKeyword)"></div>
                </div>
              </div>
            </div>

            <div v-if="messages.length === 0 && !msgLoading" class="panel-empty">
              该会话暂无消息
            </div>
          </div>

          <PaginationBar
            v-if="msgPagination.total > 0"
            :page-num="msgPagination.pageNum"
            :page-size="msgPagination.pageSize"
            :pages="msgPagination.pages"
            :total="msgPagination.total"
            @change="onMsgPageChange"
          />
        </template>

        <div v-else class="panel-empty large">从左侧选择一个会话，查看用户提问与 AI 回复</div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import PaginationBar from '../../components/PaginationBar.vue'
import {
  listRecordConversations,
  listRecordMessages,
  getRecordStats
} from '../../api/chat-record'

const stats = ref({})
const conversations = ref([])
const messages = ref([])
const current = ref(null)

const convKeyword = ref('')
const convLoading = ref(false)
const convPagination = ref({ pageNum: 1, pageSize: 20, total: 0, pages: 0 })

const roleFilter = ref('')
const msgKeyword = ref('')
// 默认正序：按消息产生顺序阅读（最早的在前），点按钮可切倒序先看最新
const asc = ref(true)
const msgLoading = ref(false)
const msgPagination = ref({ pageNum: 1, pageSize: 20, total: 0, pages: 0 })

onMounted(() => loadAll())

function loadAll() {
  loadStats()
  loadConversations()
}

async function loadStats() {
  try {
    stats.value = await getRecordStats()
  } catch (e) {
    // 统计失败不影响列表展示
  }
}

async function loadConversations() {
  convLoading.value = true
  try {
    const params = { pageNum: convPagination.value.pageNum, pageSize: convPagination.value.pageSize }
    if (convKeyword.value.trim()) params.keyword = convKeyword.value.trim()
    const res = await listRecordConversations(params)
    conversations.value = res?.list || []
    convPagination.value = {
      pageNum: res?.pageNum || convPagination.value.pageNum,
      pageSize: res?.pageSize || convPagination.value.pageSize,
      total: res?.total || 0,
      pages: res?.pages || 0
    }
    // 未选中会话时默认打开第一条，减少一次点击
    if (!current.value && conversations.value.length > 0) {
      selectConversation(conversations.value[0])
    } else if (current.value && !conversations.value.some(c => c.id === current.value.id)) {
      // 搜索后当前会话不在结果中：切到第一条或清空
      current.value = conversations.value.length > 0 ? conversations.value[0] : null
      if (current.value) loadMessages()
      else messages.value = []
    }
  } catch (e) {
    alert(e.message || '加载会话列表失败')
  } finally {
    convLoading.value = false
  }
}

async function loadMessages() {
  if (!current.value) return
  msgLoading.value = true
  try {
    const params = {
      pageNum: msgPagination.value.pageNum,
      pageSize: msgPagination.value.pageSize,
      asc: asc.value
    }
    if (roleFilter.value) params.role = roleFilter.value
    if (msgKeyword.value.trim()) params.keyword = msgKeyword.value.trim()
    const res = await listRecordMessages(current.value.id, params)
    messages.value = res?.list || []
    msgPagination.value = {
      pageNum: res?.pageNum || msgPagination.value.pageNum,
      pageSize: res?.pageSize || msgPagination.value.pageSize,
      total: res?.total || 0,
      pages: res?.pages || 0
    }
  } catch (e) {
    alert(e.message || '加载消息失败')
  } finally {
    msgLoading.value = false
  }
}

function selectConversation(c) {
  if (current.value && current.value.id === c.id) return
  current.value = c
  msgPagination.value = { ...msgPagination.value, pageNum: 1 }
  loadMessages()
}

function onConvSearch() {
  convPagination.value.pageNum = 1
  loadConversations()
}

function onConvPageChange(page) {
  convPagination.value.pageNum = page
  loadConversations()
}

function onMsgFilterChange() {
  msgPagination.value.pageNum = 1
  loadMessages()
}

function onMsgPageChange(page) {
  msgPagination.value.pageNum = page
  loadMessages()
}

function toggleOrder() {
  asc.value = !asc.value
  msgPagination.value.pageNum = 1
  loadMessages()
}

async function copyContent(text) {
  try {
    await navigator.clipboard.writeText(text || '')
  } catch (e) {
    alert('复制失败，请手动选择文本')
  }
}

function formatTime(value) {
  if (!value) return '-'
  const s = String(value).replace('T', ' ')
  return s.length >= 16 ? s.slice(5, 16) : s
}

// 关键字高亮：先转义再替换，避免 XSS
function highlight(text, keyword) {
  const escapeHtml = (s) =>
    String(s).replace(/[&<>"']/g, (c) => ({
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      '"': '&quot;',
      "'": '&#39;'
    }[c]))
  const safe = escapeHtml(text || '')
  const kw = (keyword || '').trim()
  if (!kw) return safe
  const safeKw = escapeHtml(kw).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return safe.replace(new RegExp(safeKw, 'gi'), (m) => `<mark>${m}</mark>`)
}
</script>

<style scoped>
.record-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #f7f8fc;
}

.page-header {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 24px;
  background: #fff;
  border-bottom: 1px solid var(--border);
}

.page-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--text-main);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.search-input,
.filter-select {
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 13px;
  color: var(--text-main);
  outline: none;
}

.search-input {
  width: 200px;
}

.search-input:focus,
.filter-select:focus {
  border-color: var(--primary);
}

.refresh-btn,
.action-btn {
  height: 34px;
  padding: 0 14px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: #fff;
  font-size: 13px;
  color: var(--text-main);
  cursor: pointer;
  transition: all 0.2s;
}

.refresh-btn:hover,
.action-btn:hover {
  border-color: var(--primary);
  color: var(--primary);
}

/* ==================== 统计条 ==================== */

.stat-bar {
  flex-shrink: 0;
  display: flex;
  gap: 14px;
  padding: 12px 24px;
}

.stat-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 14px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 10px;
}

.stat-value {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-main);
  font-variant-numeric: tabular-nums;
}

.stat-value.user {
  color: var(--primary);
}

.stat-value.ai {
  color: #22a06b;
}

.stat-label {
  font-size: 12px;
  color: var(--text-sub);
}

/* ==================== 主体两栏 ==================== */

.record-body {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 14px;
  padding: 0 24px 16px;
}

.conv-panel,
.msg-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  overflow: hidden;
}

.conv-panel {
  width: 300px;
  min-width: 300px;
}

.msg-panel {
  flex: 1;
  min-width: 0;
}

.panel-title {
  flex-shrink: 0;
  padding: 12px 14px;
  font-size: 13px;
  font-weight: 600;
  color: #555;
  border-bottom: 1px solid var(--border);
  background: #fafbff;
}

.conv-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.conv-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 14px;
  border-bottom: 1px solid #f0f1f6;
  cursor: pointer;
  transition: background 0.15s;
}

.conv-item:hover {
  background: #f8f9ff;
}

.conv-item.active {
  background: #eef1ff;
  box-shadow: inset 3px 0 0 var(--primary);
}

.conv-main {
  min-width: 0;
  flex: 1;
}

.conv-name {
  font-size: 13px;
  color: var(--text-main);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-sub {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-sub);
}

.conv-count {
  padding: 1px 6px;
  background: #f0f1f7;
  border-radius: 4px;
  font-weight: 500;
}

.conv-count.user {
  color: var(--primary);
  background: #eef1ff;
}

.conv-count.ai {
  color: #22a06b;
  background: #e8f7f0;
}

.conv-id {
  font-size: 12px;
  color: #c0c4d0;
}

/* ==================== 消息区 ==================== */

.msg-header {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  background: #fafbff;
}

.msg-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-main);
  max-width: 45%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.msg-filters {
  display: flex;
  align-items: center;
  gap: 8px;
}

.msg-filters .search-input {
  width: 160px;
}

.msg-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px;
  background: #f7f8fc;
}

.msg-row {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}

.msg-row.row-user {
  flex-direction: row-reverse;
}

.avatar {
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: #fff;
}

.avatar-user {
  background: linear-gradient(135deg, #4f66f9, #7b8cff);
}

.avatar-ai {
  background: linear-gradient(135deg, #22a06b, #4ec98d);
}

.bubble-wrap {
  min-width: 0;
  max-width: 78%;
}

.bubble-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--text-sub);
}

.row-user .bubble-meta {
  flex-direction: row-reverse;
}

.role-name {
  font-weight: 600;
}

.copy-btn {
  border: none;
  background: transparent;
  color: #9aa0b4;
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}

.copy-btn:hover {
  color: var(--primary);
}

.bubble {
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.7;
  word-break: break-word;
}

.bubble-user {
  background: #eef1ff;
  border: 1px solid #dbe0fb;
  color: #2c3550;
}

.bubble-ai {
  background: #fff;
  border: 1px solid var(--border);
  color: var(--text-main);
}

.bubble-text {
  white-space: pre-wrap;
}

.bubble-text :deep(mark) {
  background: #ffe58f;
  color: inherit;
  padding: 0 1px;
  border-radius: 2px;
}

.panel-empty {
  padding: 32px 0;
  text-align: center;
  color: var(--text-sub);
  font-size: 13px;
}

.panel-empty.large {
  margin: auto;
  padding: 64px 0;
}
</style>
