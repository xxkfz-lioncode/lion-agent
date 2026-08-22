<template>
  <div class="chat-page">
    <header class="chat-header">
      <h2 class="header-title">知识库问答</h2>
      <select v-model="selectedId" class="kb-select">
        <option value="">请选择知识库</option>
        <option v-for="kb in knowledgeList" :key="kb.id" :value="String(kb.id)">
          {{ kb.name }}
        </option>
      </select>
    </header>

    <div class="message-list">
      <div
        v-for="(msg, index) in messages"
        :key="index"
        class="message-item"
        :class="msg.role"
      >
        <div class="message-avatar">{{ msg.role === 'user' ? '👤' : '🦁' }}</div>
        <div class="message-bubble">
          <div class="message-text" v-html="renderMarkdown(msg.content)"></div>
        </div>
      </div>
      <div v-if="loading" class="message-item ai">
        <div class="message-avatar">🦁</div>
        <div class="message-bubble">
          <span class="typing-dot"></span>
          <span class="typing-dot"></span>
          <span class="typing-dot"></span>
        </div>
      </div>
    </div>

    <div class="input-area">
      <textarea
        v-model="inputText"
        class="chat-input"
        placeholder="基于选中的知识库提问，Enter 发送"
        :disabled="loading"
        @keydown.enter.exact.prevent="send"
      ></textarea>
      <button class="send-btn" :disabled="loading || !inputText.trim() || !selectedId" @click="send">
        发送
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { marked } from 'marked'
import { listKnowledge, knowledgeChat } from '../../api/knowledge'

const knowledgeList = ref([])
const selectedId = ref('')
const inputText = ref('')
const loading = ref(false)
const messages = ref([
  { role: 'ai', content: '请选择一个知识库，我将基于库内文档为您解答问题。' }
])

onMounted(() => loadKnowledgeList())

async function loadKnowledgeList() {
  try {
    const res = await listKnowledge()
    knowledgeList.value = res.list || []
  } catch (e) {
    console.error('加载知识库失败', e)
  }
}

function renderMarkdown(text) {
  if (!text) return ''
  return marked.parse(text)
}

async function send() {
  const content = inputText.value.trim()
  if (!content || loading.value || !selectedId.value) return

  messages.value.push({ role: 'user', content })
  inputText.value = ''
  loading.value = true

  try {
    const res = await knowledgeChat({ knowledgeId: selectedId.value, question: content })
    messages.value.push({ role: 'ai', content: res.answer })
  } catch (e) {
    messages.value.push({
      role: 'ai',
      content: '抱歉，知识库问答服务暂时不可用。'
    })
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.chat-page {
  height: 100%;
  display: flex;
  flex-direction: column;
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
}

.kb-select {
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 14px;
  min-width: 180px;
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

.typing-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  margin-right: 4px;
  border-radius: 50%;
  background: var(--text-sub);
  animation: typing 1.4s infinite both;
}

.typing-dot:nth-child(2) {
  animation-delay: 0.2s;
}

.typing-dot:nth-child(3) {
  animation-delay: 0.4s;
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
</style>
