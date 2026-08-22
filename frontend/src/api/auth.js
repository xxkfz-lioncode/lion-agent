import request from './request'

/** 注册 */
export function register(data) {
  return request.post('/auth/register', data)
}

/** 登录 */
export function login(data) {
  return request.post('/auth/login', data)
}

/** 退出登录 */
export function logout() {
  return request.post('/auth/logout')
}

/** 获取当前用户 */
export function getMe() {
  return request.get('/auth/me')
}
