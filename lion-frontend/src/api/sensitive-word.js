import request from './request'

export function listSensitiveWords(params) {
  return request.get('/sensitive-word', { params })
}

export function createSensitiveWord(data) {
  return request.post('/sensitive-word', data)
}

export function updateSensitiveWord(id, data) {
  return request.put(`/sensitive-word/${id}`, data)
}

export function deleteSensitiveWord(id) {
  return request.delete(`/sensitive-word/${id}`)
}

export function toggleSensitiveWord(id, enabled) {
  return request.post(`/sensitive-word/${id}/toggle`, null, { params: { enabled } })
}

export function batchImportSensitiveWords(words, category = 'custom', enabled = true) {
  return request.post('/sensitive-word/batch-import', { words, category, enabled })
}

export function checkSensitiveWord(text) {
  return request.post('/sensitive-word/check', { text })
}

export function refreshSensitiveWordCache() {
  return request.post('/sensitive-word/cache/refresh')
}
