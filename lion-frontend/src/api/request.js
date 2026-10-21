import axios from 'axios'
import router from '../router'

/**
 * axios 实例封装
 * - 自动携带 Authorization token
 * - 统一处理后端 Result 结构
 * - 401 自动跳转登录页
 */
const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 60000
})

// 请求拦截器：携带 token
request.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = token
  }
  return config
})

// 响应拦截器：解包 Result
request.interceptors.response.use(
  (response) => {
    const res = response.data
    // 非标准 Result 结构（如文件流）直接返回
    if (res === null || typeof res !== 'object' || !('code' in res)) {
      return res
    }
    if (res.code === 200) {
      return res.data
    }
    // 未登录：清除 token 并跳转登录
    if (res.code === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      router.push('/login')
    }
    return Promise.reject(new Error(res.message || '请求失败'))
  },
  (error) => {
    if (error.response && error.response.status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      router.push('/login')
    }
    const message =
      error.response?.data?.message || error.message || '网络异常，请稍后重试'
    return Promise.reject(new Error(message))
  }
)

export default request
