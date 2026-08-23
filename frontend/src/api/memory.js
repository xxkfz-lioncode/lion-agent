import request from './request'

/**
 * 长期记忆 API
 */

/** 查询当前用户全部长期记忆画像（按更新时间倒序） */
export function listMemory() {
  return request.get('/memory/list')
}
