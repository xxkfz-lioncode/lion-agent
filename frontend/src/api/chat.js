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
 * @param {Function} [params.onStart] 收到 start 事件（含 conversationId）
 * @param {Function} [params.onMessage] 收到内容片段 { content }
 * @param {Function} [params.onDone] 收到 done 事件（含 assistantMessageId）
 */
export async function streamChat({ conversationId, message, onStart, onMessage, onDone }) {
  const baseURL = import.meta.env.VITE_API_BASE_URL || '/api'
  const token = localStorage.getItem('token') || ''
  const res = await fetch(`${baseURL}/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: token
    },
    body: JSON.stringify({ conversationId, message })
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
