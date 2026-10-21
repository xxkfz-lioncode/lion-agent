import request from './request'

/**
 * 模型配置管理接口（后端 ModelConfigController，/api/model）
 */

export function listModels() {
  return request.get('/model')
}

export function createModel(data) {
  return request.post('/model', data)
}

export function updateModel(id, data) {
  return request.put(`/model/${id}`, data)
}

export function deleteModel(id) {
  return request.delete(`/model/${id}`)
}

export function setDefaultModel(id) {
  return request.post(`/model/${id}/default`)
}

/** 连通性测试：用该模型发送一条极短消息（走真实 LLM，耗时可能在数秒） */
export function testModel(id) {
  return request.post(`/model/${id}/test`)
}
