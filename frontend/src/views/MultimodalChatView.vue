<template>
  <div class="multimodal-page">
    <!-- 顶部工具栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <span class="page-title">多模态对话</span>
        <span v-if="conversationId" class="conv-tag">会话 #{{ conversationId }}</span>
        <span v-else class="conv-tag new">新会话</span>
      </div>
      <div class="toolbar-right">
        <input
          v-model="imageUrlInput"
          class="url-input"
          type="text"
          placeholder="图片 URL（多个用逗号分隔）"
          @keyup.enter="addImageUrls"
        >
        <button class="tool-btn" :disabled="loading" @click="addImageUrls">添加图片链接</button>
        <button class="tool-btn primary" :disabled="loading" @click="startNewChat">新建会话</button>
      </div>
    </div>

    <!-- 消息列表 -->
    <div ref="messageList" class="message-list">
      <div v-for="msg in messages" :key="msg.id" class="message-item" :class="msg.role">
        <div class="avatar">{{ msg.role === 'user' ? '👤' : '🦁' }}</div>
        <div class="message-body">
          <div v-if="msg.images && msg.images.length" class="msg-images">
            <img
              v-for="(img, i) in msg.images"
              :key="i"
              class="msg-img"
              :src="img"
              :alt="'图片 ' + (i + 1)"
            >
          </div>
          <div class="bubble">
            <div v-if="msg.content" class="text" v-html="renderMarkdown(msg.content)"></div>
            <div v-else-if="msg.loading" class="typing-wrapper">
              <span class="typing-text">正在理解图片与文字…</span>
              <div class="typing-indicator">
                <span class="typing-dot"></span>
                <span class="typing-dot"></span>
                <span class="typing-dot"></span>
              </div>
            </div>
          </div>
          <div v-if="msg.referencedChunks && msg.referencedChunks.length" class="reference-box">
            <div class="reference-title">📎 引用来源（{{ msg.referencedChunks.length }}）</div>
            <div v-for="(chunk, i) in msg.referencedChunks" :key="i" class="reference-item">
              <div class="reference-meta">
                <span class="reference-kb">{{ chunk.knowledgeName || '未知知识库' }}</span>
                <span v-if="chunk.fileName" class="reference-file">{{ chunk.fileName }}</span>
              </div>
              <div class="reference-content">{{ chunk.content }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 待发送图片预览 -->
    <div v-if="pendingImages.length" class="pending-images">
      <div v-for="(img, i) in pendingImages" :key="i" class="pending-img">
        <img :src="img.dataUrl" alt="待发送图片">
        <button class="remove-img" title="移除" @click="pendingImages.splice(i, 1)">×</button>
      </div>
    </div>

    <!-- 输入区 -->
    <div class="input-area">
      <label class="img-upload-btn" title="上传图片（可多选）">
        <input
          type="file"
          accept="image/*"
          multiple
          hidden
          :disabled="loading"
          @change="onSelectFiles"
        >
        📷
      </label>
      <textarea
        v-model="inputText"
        class="chat-input"
        placeholder="输入文字，Enter 发送，Shift+Enter 换行；支持直接粘贴图片"
        :disabled="loading"
        @keydown.enter.exact.prevent="send"
        @paste="onPaste"
      ></textarea>
      <button
        class="send-btn"
        :disabled="loading || (!inputText.trim() && !pendingImages.length)"
        @click="send"
      >
        {{ loading ? '思考中…' : '发送' }}
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { marked } from 'marked'
import { sendMultimodal } from '../api/chat'

const messageList = ref(null)
const inputText = ref('')
const loading = ref(false)
const conversationId = ref(null)
const imageUrlInput = ref('')
const pendingImages = ref([])

const messages = ref([
  { id: 'welcome', role: 'ai', content: '你好！我是多模态助手，支持发送图片 + 文字进行对话。' }
])

let msgIdSeq = 0
function nextMsgId() {
  return `${Date.now()}-${++msgIdSeq}`
}

onMounted(scrollToBottom)

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

function startNewChat() {
  conversationId.value = null
  messages.value = [
    { id: 'welcome', role: 'ai', content: '你好！我是多模态助手，支持发送图片 + 文字进行对话。' }
  ]
  inputText.value = ''
  pendingImages.value = []
  imageUrlInput.value = ''
  scrollToBottom()
}

/** 选择图片文件 */
function onSelectFiles(e) {
  const files = Array.from(e.target.files || [])
  addFiles(files)
  e.target.value = ''
}

/** 粘贴图片 */
function onPaste(e) {
  const items = e.clipboardData?.items || []
  const files = []
  for (const item of items) {
    if (item.type && item.type.startsWith('image/')) {
      const file = item.getAsFile()
      if (file) files.push(file)
    }
  }
  if (files.length) {
    e.preventDefault()
    addFiles(files)
  }
}

/** 将图片文件读取为 dataURL 加入待发送列表 */
function addFiles(files) {
  for (const file of files) {
    const reader = new FileReader()
    reader.onload = () => {
      pendingImages.value.push({ type: 'file', file, dataUrl: reader.result })
    }
    reader.readAsDataURL(file)
  }
}

/** 将图片 URL 加入待发送列表（逗号分隔） */
function addImageUrls() {
  const urls = imageUrlInput.value
    .split(/[,，]/)
    .map((u) => u.trim())
    .filter(Boolean)
  if (!urls.length) return
  urls.forEach((url) => {
    pendingImages.value.push({ type: 'url', url, dataUrl: url })
  })
  imageUrlInput.value = ''
}

async function send() {
  const text = inputText.value.trim()
  const files = pendingImages.value.filter((p) => p.type === 'file').map((p) => p.file)
  const urls = pendingImages.value.filter((p) => p.type === 'url').map((p) => p.url)
  if ((!text && !files.length && !urls.length) || loading.value) return

  const userMsg = {
    id: nextMsgId(),
    role: 'user',
    content: text,
    images: pendingImages.value.map((p) => p.dataUrl)
  }
  messages.value.push(userMsg)
  const aiMsgId = nextMsgId()
  messages.value.push({ id: aiMsgId, role: 'ai', content: '', loading: true })

  inputText.value = ''
  pendingImages.value = []
  loading.value = true
  scrollToBottom()

  try {
    const res = await sendMultimodal({
      message: text,
      conversationId: conversationId.value,
      images: files,
      imageUrls: urls
    })
    conversationId.value = res.conversationId
    const aiMsg = messages.value.find((m) => m.id === aiMsgId)
    if (aiMsg) {
      aiMsg.loading = false
      aiMsg.content = res.reply || '（模型未返回内容）'
      aiMsg.referencedChunks = res.referencedChunks || null
    }
  } catch (e) {
    const aiMsg = messages.value.find((m) => m.id === aiMsgId)
    if (aiMsg) {
      aiMsg.loading = false
      aiMsg.content = `请求失败：${e.message}`
    }
  } finally {
    loading.value = false
    scrollToBottom()
  }
}
</script>

<style scoped>
.multimodal-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fff;
}

/* 顶部工具栏 */
.toolbar {
  height: 56px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 20px;
  border-bottom: 1px solid var(--border);
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-main);
  white-space: nowrap;
}

.conv-tag {
  font-size: 12px;
  color: var(--primary);
  background: #eef1ff;
  padding: 3px 10px;
  border-radius: 20px;
  white-space: nowrap;
}

.conv-tag.new {
  color: var(--text-weak);
  background: #f2f3f7;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.url-input {
  width: 240px;
  padding: 7px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 13px;
  outline: none;
}

.url-input:focus {
  border-color: var(--primary);
}

.tool-btn {
  padding: 7px 14px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fff;
  color: var(--text-main);
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.2s;
}

.tool-btn:hover:not(:disabled) {
  border-color: var(--primary);
  color: var(--primary);
}

.tool-btn.primary {
  background: linear-gradient(135deg, #4f66f9, #7b8cff);
  border: none;
  color: #fff;
}

.tool-btn.primary:hover:not(:disabled) {
  opacity: 0.9;
  color: #fff;
}

.tool-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 消息区 */
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

.avatar {
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

.message-body {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  max-width: 72%;
}

.message-item.user .message-body {
  align-items: flex-end;
}

.msg-images {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  max-width: 100%;
}

.msg-img {
  max-width: 220px;
  max-height: 220px;
  border-radius: 10px;
  border: 1px solid var(--border);
  object-fit: contain;
}

.bubble {
  max-width: 100%;
  padding: 12px 16px;
  border-radius: 12px;
  background: #f5f6fa;
  color: var(--text-main);
  line-height: 1.6;
}

.message-item.user .bubble {
  background: var(--primary);
  color: #fff;
}

.text :deep(p) {
  margin: 0 0 8px;
}

.text :deep(p:last-child) {
  margin-bottom: 0;
}

.typing-wrapper {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.typing-text {
  font-size: 13px;
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

.reference-box {
  margin-top: 4px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-left: 3px solid var(--primary);
  border-radius: 6px;
  background: #fafbfc;
  max-width: 100%;
}

.reference-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-main);
  margin-bottom: 6px;
}

.reference-item {
  padding: 6px 8px;
  border-radius: 4px;
  background: #fff;
  border: 1px solid var(--border);
  margin-bottom: 6px;
}

.reference-item:last-child {
  margin-bottom: 0;
}

.reference-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.reference-kb {
  font-size: 11px;
  font-weight: 600;
  color: var(--primary);
  background: rgba(66, 133, 244, 0.1);
  padding: 1px 6px;
  border-radius: 4px;
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reference-file {
  font-size: 11px;
  color: var(--text-weak);
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reference-content {
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-weak);
  word-break: break-all;
  white-space: pre-wrap;
}

/* 待发送图片预览 */
.pending-images {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding: 12px 20px 0;
  flex-shrink: 0;
}

.pending-img {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid var(--border);
}

.pending-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.remove-img {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 20px;
  height: 20px;
  border: none;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.remove-img:hover {
  background: rgba(244, 67, 54, 0.85);
}

/* 输入区 */
.input-area {
  display: flex;
  gap: 10px;
  padding: 14px 20px 20px;
  border-top: 1px solid var(--border);
  flex-shrink: 0;
}

.img-upload-btn {
  width: 48px;
  height: 48px;
  flex-shrink: 0;
  border: 1px dashed var(--border);
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  cursor: pointer;
  background: #fafbfc;
  transition: all 0.2s;
}

.img-upload-btn:hover {
  border-color: var(--primary);
  background: #eef1ff;
}

.chat-input {
  flex: 1;
  min-height: 48px;
  max-height: 140px;
  padding: 13px 16px;
  border: 1px solid var(--border);
  border-radius: 12px;
  resize: none;
  font-size: 14px;
  line-height: 1.5;
  font-family: inherit;
}

.chat-input:focus {
  outline: none;
  border-color: var(--primary);
}

.chat-input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.send-btn {
  padding: 0 24px;
  border: none;
  border-radius: 12px;
  background: var(--primary);
  color: #fff;
  font-size: 14px;
  cursor: pointer;
  flex-shrink: 0;
}

.send-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
