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

export function previewDocument(knowledgeId, docId) {
  return request.get(`/knowledge/${knowledgeId}/documents/${docId}/preview`)
}
