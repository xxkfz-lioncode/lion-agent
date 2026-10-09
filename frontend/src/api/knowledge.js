import request from './request'

export function listKnowledge(params) {
  return request.get('/knowledge', { params })
}

export function createKnowledge(data) {
  return request.post('/knowledge', data)
}

export function updateKnowledge(id, data) {
  return request.put(`/knowledge/${id}`, data)
}

export function deleteKnowledge(id) {
  return request.delete(`/knowledge/${id}`)
}

export function listDocuments(knowledgeId, params) {
  return request.get(`/knowledge/${knowledgeId}/documents`, { params })
}

export function uploadDocument(knowledgeId, formData) {
  return request.post(`/knowledge/${knowledgeId}/documents`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export function deleteDocument(knowledgeId, docId) {
  return request.delete(`/knowledge/${knowledgeId}/documents/${docId}`)
}

/** 重新上传：用新文件替换原文档并重新处理 */
export function replaceDocument(knowledgeId, docId, formData) {
  return request.put(`/knowledge/${knowledgeId}/documents/${docId}/file`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export function previewDocument(knowledgeId, docId) {
  return request.get(`/knowledge/${knowledgeId}/documents/${docId}/preview`)
}
