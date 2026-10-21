import request from './request'

/** 提示词模板列表 */
export function listPromptTemplates() {
  return request.get('/prompt-template')
}

/** 查看单个提示词模板 */
export function getPromptTemplate(name) {
  return request.get(`/prompt-template/${name}`)
}

/** 将 classpath 文件内容同步到数据库（新增 + 覆盖） */
export function refreshPromptTemplates() {
  return request.post('/prompt-template/refresh')
}

/** 更新数据库中的模板内容，保存后立即生效 */
export function updatePromptTemplate(name, content) {
  return request.put(`/prompt-template/${name}`, { content })
}
