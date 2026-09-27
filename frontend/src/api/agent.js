import request from './request'

/* ==================== spring-ai-agent-utils 示例 ==================== */

// 这两个接口是阻塞式的：模型要跑多轮工具调用（TodoWrite 反复更新清单 / TaskTool 派活给子 Agent），
// 常规 30~60 秒打不住，单独放宽到 5 分钟（per-request timeout 会覆盖 axios 实例的全局 60s）
const DEMO_TIMEOUT = 300000

/**
 * TodoWrite 任务清单示例：让模型显式拆解并推进任务
 * @param {string} task 任务描述，留空用后端默认任务
 * @returns {Promise<{task: string, answer: string, items: Array<{content: string, status: string, activeForm: string}>, updateCount: number}>}
 */
export function runTodoPlan(task) {
  return request.get('/agent/demo/plan', { params: { task }, timeout: DEMO_TIMEOUT })
}

/**
 * TaskTool 子 Agent 派活示例：主 Agent 把任务交给 translator / summarizer 执行
 * @param {string} task 任务描述，留空用后端默认任务
 * @returns {Promise<string>} 主 Agent 汇总后的最终回答
 */
export function runTaskAgent(task) {
  return request.get('/agent/demo/task', { params: { task }, timeout: DEMO_TIMEOUT })
}

/**
 * SkillsTool 示例：模型判断需求命中哪个 Skill 后，加载该 SKILL.md 的完整正文再回答
 * @param {string} task 用户问题，留空用后端默认问题
 * @returns {Promise<string>} 模型最终回答
 */
export function runSkillAgent(task) {
  return request.get('/agent/demo/skill', { params: { task }, timeout: DEMO_TIMEOUT })
}

/**
 * A2A 子 Agent 示例：本地子 Agent 与远程 A2A Agent 一起注册，由模型自主选择
 * @param {string} task 用户任务，留空用后端默认任务
 * @returns {Promise<string>} 主 Agent 汇总后的最终回答
 */
export function runA2aAgent(task) {
  return request.get('/agent/demo/a2a', { params: { task }, timeout: DEMO_TIMEOUT })
}

/**
 * 探测远程 A2A 服务端的 AgentCard（请求 /.well-known/agent-card.json）
 * @returns {Promise<string>} 名片摘要或失败原因
 */
export function probeA2aCard() {
  return request.get('/agent/demo/a2a/card', { timeout: 30000 })
}
