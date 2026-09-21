import request from './request'

/**
 * 会话记录（只读查询）
 */

// 会话列表：keyword 同时匹配会话标题与消息内容
export function listRecordConversations(params) {
  return request.get('/chat-records/conversations', { params })
}

// 会话内的消息明细：role = user / assistant，asc = false 时按时间倒序
export function listRecordMessages(conversationId, params) {
  return request.get(`/chat-records/conversations/${conversationId}/messages`, { params })
}

// 顶部统计
export function getRecordStats() {
  return request.get('/chat-records/stats')
}
