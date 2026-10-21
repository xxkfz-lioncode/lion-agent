import request from './request'

/** 技能列表（分页） */
export function listSkills(params) {
  return request.get('/skill', { params })
}

/** 创建技能 */
export function createSkill(data) {
  return request.post('/skill', data)
}

/** 修改技能 */
export function updateSkill(id, data) {
  return request.put(`/skill/${id}`, data)
}

/** 删除技能 */
export function deleteSkill(id) {
  return request.delete(`/skill/${id}`)
}

/** 导出技能为 Markdown（返回 md 文本，前端预览/下载） */
export function exportSkill(id) {
  return request.get(`/skill/${id}/export`)
}

/** 试跑技能：填参数看替换后模板与模型输出 */
export function testSkill(id, args) {
  return request.post(`/skill/${id}/test`, args)
}
