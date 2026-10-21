import request from './request'

/** 分页查询用量记录（chatType: chat-常规对话 kb-知识库问答，为空查询全部） */
export function listTokenUsage(params) {
  return request.get('/token-usage', { params })
}

/** 汇总统计（总/今日调用次数、总/今日 token、平均耗时） */
export function getTokenUsageStats() {
  return request.get('/token-usage/stats')
}
