import request from './request'

/** 发送消息（conversationId 为空则自动新建会话） */
export function sendMessage(data) {
  return request.post('/chat/send', data)
}

/**
 * 流式发送消息（SSE）
 * @param {Object} params
 * @param {number|null} params.conversationId 会话 ID
 * @param {string} params.message 用户消息
 * @param {number|null} [params.knowledgeId] 可选：指定知识库（不传则检索用户全部知识库）
 * @param {Function} [params.onStart] 收到 start 事件（含 conversationId）
 * @param {Function} [params.onMessage] 收到内容片段 { content }
 * @param {Function} [params.onDone] 收到 done 事件（含 assistantMessageId、referencedChunks）
 */
export async function streamChat({ conversationId, message, knowledgeId, onStart, onMessage, onDone }) {
  const baseURL = import.meta.env.VITE_API_BASE_URL || '/api'
  const token = localStorage.getItem('token') || ''
  const res = await fetch(`${baseURL}/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: token
    },
    body: JSON.stringify({ conversationId, message, knowledgeId })
  })
  if (!res.ok || !res.body) {
    throw new Error(`请求失败（HTTP ${res.status}）`)
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  function handleEvent(raw) {
    let eventName = 'message'
    let dataStr = ''
    for (const line of raw.split('\n')) {
      const t = line.trim()
      if (t.startsWith('event:')) eventName = t.slice(6).trim()
      else if (t.startsWith('data:')) dataStr += t.slice(5).trim()
    }
    if (!dataStr) return
    let payload
    try {
      payload = JSON.parse(dataStr)
    } catch {
      return
    }
    if (eventName === 'start' && onStart) onStart(payload)
    else if (eventName === 'done' && onDone) onDone(payload)
    else if (onMessage) onMessage(payload)
  }

  // eslint-disable-next-line no-constant-condition
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx
    while ((idx = buffer.indexOf('\n\n')) !== -1) {
      const raw = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      if (raw.trim()) handleEvent(raw)
    }
  }
  if (buffer.trim()) handleEvent(buffer)
}

/**
 * 多模态对话（图片 + 文字，multipart/form-data，非流式）
 * @param {Object} params
 * @param {string} params.message 文本内容
 * @param {number|null} [params.conversationId] 会话 ID（为空自动创建）
 * @param {File[]} [params.images] 图片文件数组
 * @param {string[]} [params.imageUrls] 图片 URL 数组
 * @returns {Promise<{conversationId:number, reply:string, referencedChunks:Array|null}>}
 */
export function sendMultimodal({ message, conversationId, images = [], imageUrls = [] }) {
  const formData = new FormData()
  formData.append('message', message)
  if (conversationId != null) formData.append('conversationId', conversationId)
  images.forEach((file) => formData.append('images', file))
  imageUrls.forEach((url) => formData.append('imageUrls', url))
  return request.post('/chat/multimodal', formData)
}

/** 获取会话列表 */
export function listConversations(params) {
  return request.get('/conversations', { params })
}

/** 获取会话消息记录 */
export function getMessages(conversationId, params) {
  return request.get(`/conversations/${conversationId}/messages`, { params })
}

/** 别名：兼容部分视图中的 listMessages 导入 */
export const listMessages = getMessages

/** 删除会话 */
export function deleteConversation(conversationId) {
  return request.delete(`/conversations/${conversationId}`)
}

/** 清空当前用户的所有会话 */
export function clearAllConversations() {
  return request.delete('/conversations/all')
}

/** 重命名会话 */
export function renameConversation(conversationId, title) {
  return request.put(`/conversations/${conversationId}`, { title })
}
