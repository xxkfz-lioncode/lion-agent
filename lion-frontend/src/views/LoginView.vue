<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-logo">
        <div class="logo-icon">🦁</div>
        <h1>Lion Agent</h1>
        <p>智能问答系统</p>
      </div>

      <div class="tabs">
        <button
          :class="{ active: mode === 'login' }"
          @click="mode = 'login'"
        >
          登录
        </button>
        <button
          :class="{ active: mode === 'register' }"
          @click="mode = 'register'"
        >
          注册
        </button>
      </div>

      <form class="form" @submit.prevent="handleSubmit">
        <div class="field">
          <label>用户名</label>
          <input
            v-model.trim="form.username"
            type="text"
            placeholder="请输入用户名"
            autocomplete="username"
          />
        </div>
        <div class="field">
          <label>密码</label>
          <input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
          />
        </div>
        <div v-if="mode === 'register'" class="field">
          <label>昵称（可选）</label>
          <input
            v-model.trim="form.nickname"
            type="text"
            placeholder="请输入昵称"
          />
        </div>

        <p v-if="errorMsg" class="error">{{ errorMsg }}</p>

        <button class="submit-btn" type="submit" :disabled="loading">
          {{ loading ? '处理中...' : mode === 'login' ? '登 录' : '注 册' }}
        </button>
      </form>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { login, register } from '../api/auth'

const router = useRouter()
const mode = ref('login')
const loading = ref(false)
const errorMsg = ref('')

const form = reactive({
  username: '',
  password: '',
  nickname: ''
})

async function handleSubmit() {
  errorMsg.value = ''
  if (!form.username || !form.password) {
    errorMsg.value = '请输入用户名和密码'
    return
  }

  loading.value = true
  try {
    if (mode.value === 'login') {
      const data = await login({ username: form.username, password: form.password })
      localStorage.setItem('token', data.token)
      localStorage.setItem('user', JSON.stringify(data.user))
      router.push('/home')
    } else {
      await register({
        username: form.username,
        password: form.password,
        nickname: form.nickname || undefined
      })
      mode.value = 'login'
      errorMsg.value = '注册成功，请登录'
      form.password = ''
    }
  } catch (e) {
    errorMsg.value = e.message || '操作失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #eef1ff 0%, #f5f6fa 100%);
}

.login-card {
  width: 400px;
  padding: 40px 36px;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 12px 40px rgba(79, 110, 247, 0.12);
}

.login-logo {
  text-align: center;
  margin-bottom: 28px;
}

.logo-icon {
  font-size: 44px;
  margin-bottom: 8px;
}

.login-logo h1 {
  font-size: 24px;
  color: var(--primary);
}

.login-logo p {
  margin-top: 4px;
  font-size: 13px;
  color: var(--text-sub);
}

.tabs {
  display: flex;
  background: #f2f3f8;
  border-radius: 8px;
  padding: 4px;
  margin-bottom: 24px;
}

.tabs button {
  flex: 1;
  padding: 8px 0;
  border: none;
  background: transparent;
  border-radius: 6px;
  font-size: 14px;
  color: var(--text-sub);
  transition: all 0.2s;
}

.tabs button.active {
  background: #fff;
  color: var(--primary);
  font-weight: 600;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.field {
  margin-bottom: 18px;
}

.field label {
  display: block;
  font-size: 13px;
  color: var(--text-sub);
  margin-bottom: 6px;
}

.field input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
}

.field input:focus {
  border-color: var(--primary);
}

.error {
  color: var(--danger);
  font-size: 13px;
  margin-bottom: 12px;
}

.submit-btn {
  width: 100%;
  padding: 11px 0;
  border: none;
  border-radius: 8px;
  background: var(--primary);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  transition: background 0.2s;
}

.submit-btn:hover:not(:disabled) {
  background: var(--primary-dark);
}

.submit-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
