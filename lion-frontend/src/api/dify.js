import request from './request'

/* ==================== Dify 应用 ==================== */

/** 应用列表（不含密钥） */
export function listDifyApps() {
  return request.get('/dify/apps')
}

/** 应用详情 */
export function getDifyApp(appCode) {
  return request.get(`/dify/apps/${appCode}`)
}

/** 应用编排流程图（Chatflow / Workflow 画布） */
export function getDifyAppGraph(appCode) {
  return request.get(`/dify/apps/${appCode}/graph`)
}



/* ==================== Dify 对话 ==================== */

/** 发送消息（阻塞） */
export function sendChat(data) {
  return request.post('/dify/chat/send', data)
}

/**
 * 流式发送消息（SSE）
 * @param {Object} params
 * @param {string} [params.appCode] 应用编码
 * @param {string} params.content 提问内容
 * @param {string} [params.conversationId] 会话 ID（为空自动新建）
 * @param {Function} [params.onMessage] 收到增量片段 { content }
 * @param {Function} [params.onStage] 收到编排阶段事件 { event }（如 node_started / workflow_started）
 * @param {Function} [params.onDone] 收到完成事件 { conversationId, messageId, reply }
 */
export async function streamChat({ appCode, content, conversationId, inputs, onMessage, onStage, onDone }) {
  const baseURL = import.meta.env.VITE_API_BASE_URL || '/api'
  const token = localStorage.getItem('token') || ''
  const res = await fetch(`${baseURL}/dify/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: token
    },
    body: JSON.stringify({ appCode, content, conversationId, inputs })
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
    if (eventName === 'done' && onDone) onDone(payload)
    else if (eventName === 'stage' && onStage) onStage(payload)
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

/** 会话列表（游标分页） */
export function listDifyConversations(params) {
  return request.get('/dify/chat/conversations', { params })
}

/** 会话聊天历史（游标分页） */
export function listDifyMessages(params) {
  return request.get('/dify/chat/messages', { params })
}

/** 下一轮建议问题 */
export function getSuggested(messageId, appCode) {
  return request.get('/dify/chat/suggested', { params: { messageId, app: appCode } })
}

/** 消息点赞/点踩 rating: like | dislike | none */
export function sendFeedback(data) {
  return request.post('/dify/chat/feedback', data)
}

/** 会话重命名 */
export function renameDifyConversation(data) {
  return request.post('/dify/chat/conversations/rename', data)
}

/** 删除会话 */
export function deleteDifyConversation(conversationId, appCode) {
  return request.delete(`/dify/chat/conversations/${conversationId}`, { params: { app: appCode } })
}

/** 应用编排参数 */
export function getAppParameters(appCode) {
  return request.get('/dify/chat/parameters', { params: { app: appCode } })
}

/* ==================== Dify 知识库 ==================== */

/** 知识库分页列表 */
export function pageDatasets(params) {
  return request.get('/dify/dataset/page', { params })
}

/** 创建知识库 */
export function createDataset(data) {
  return request.post('/dify/dataset/create', data)
}

/** 删除知识库 */
export function deleteDataset(datasetId) {
  return request.delete(`/dify/dataset/${datasetId}`)
}

/** 文档分页列表 */
export function pageDocuments(datasetId, params) {
  return request.get(`/dify/dataset/${datasetId}/documents`, { params })
}

/** 文本新建文档 */
export function createDocumentByText(datasetId, data) {
  return request.post(`/dify/dataset/${datasetId}/documents/text`, data)
}

/** 上传文件新建文档 */
export function createDocumentByFile(datasetId, file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post(`/dify/dataset/${datasetId}/documents/file`, formData)
}

/** 删除文档 */
export function deleteDocument(datasetId, documentId) {
  return request.delete(`/dify/dataset/${datasetId}/documents/${documentId}`)
}

/* ==================== Dify 工作流 ==================== */

/** 运行工作流（阻塞） */
export function runWorkflow(data) {
  return request.post('/dify/workflow/run', data)
}

/**
 * 流式运行工作流（SSE）
 * @param {Object} params
 * @param {string} [params.appCode] 应用编码
 * @param {Object} params.inputs 输入变量
 * @param {Function} params.onEvent 收到分片 { event, data }
 */
export async function streamWorkflow({ appCode, inputs, onEvent }) {
  const baseURL = import.meta.env.VITE_API_BASE_URL || '/api'
  const token = localStorage.getItem('token') || ''
  const res = await fetch(`${baseURL}/dify/workflow/run/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: token
    },
    body: JSON.stringify({ appCode, inputs })
  })
  if (!res.ok || !res.body) {
    throw new Error(`请求失败（HTTP ${res.status}）`)
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  function handleEvent(raw) {
    let dataStr = ''
    for (const line of raw.split('\n')) {
      const t = line.trim()
      if (t.startsWith('data:')) dataStr += t.slice(5).trim()
    }
    if (!dataStr) return
    try {
      const payload = JSON.parse(dataStr)
      if (onEvent) onEvent(payload)
    } catch {
      /* 忽略非 JSON 分片 */
    }
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
