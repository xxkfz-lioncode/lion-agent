<template>
  <teleport to="body">
    <transition name="fade">
      <div v-if="visible" class="profile-mask" @click.self="handleClose">
        <div class="profile-panel">
          <!-- 标题 -->
          <div class="panel-header">
            <span class="panel-title">个人设置</span>
            <button class="panel-close" title="关闭" @click="handleClose">×</button>
          </div>

          <!-- Tab 切换 -->
          <div class="tab-nav">
            <div
              class="tab-btn"
              :class="{ active: activeTab === 'profile' }"
              @click="activeTab = 'profile'"
            >基本资料</div>
            <div
              class="tab-btn"
              :class="{ active: activeTab === 'password' }"
              @click="activeTab = 'password'"
            >修改密码</div>
          </div>

          <!-- 基本资料 -->
          <div v-if="activeTab === 'profile'" class="panel-body">
            <div class="avatar-row">
              <div class="avatar-preview">
                <img v-if="previewAvatar" :src="previewAvatar" alt="头像" />
                <span v-else class="avatar-letter">{{ avatarLetter }}</span>
              </div>
              <div class="avatar-tip">头像将显示在导航栏与页面中</div>
            </div>

            <div class="form-item">
              <label class="form-label">用户名</label>
              <input class="form-input" :value="user?.username" disabled />
            </div>

            <div class="form-item">
              <label class="form-label">昵称</label>
              <input
                v-model="profileForm.nickname"
                class="form-input"
                placeholder="请输入昵称（1-32 个字符）"
                maxlength="32"
              />
            </div>

            <div class="form-item">
              <label class="form-label">头像地址</label>
              <input
                v-model="profileForm.avatar"
                class="form-input"
                placeholder="https://example.com/avatar.png，留空表示清除"
                maxlength="255"
              />
            </div>
          </div>

          <!-- 修改密码 -->
          <div v-else class="panel-body">
            <div class="form-item">
              <label class="form-label">原密码</label>
              <input
                v-model="passwordForm.oldPassword"
                type="password"
                class="form-input"
                placeholder="请输入原密码"
              />
            </div>
            <div class="form-item">
              <label class="form-label">新密码</label>
              <input
                v-model="passwordForm.newPassword"
                type="password"
                class="form-input"
                placeholder="请输入新密码（6-32 个字符）"
                maxlength="32"
              />
            </div>
            <div class="form-item">
              <label class="form-label">确认新密码</label>
              <input
                v-model="passwordForm.confirmPassword"
                type="password"
                class="form-input"
                placeholder="请再次输入新密码"
                maxlength="32"
              />
            </div>
            <div class="pwd-tip">修改成功后需使用新密码重新登录</div>
          </div>

          <!-- 错误提示 -->
          <div v-if="errorMsg" class="error-msg">{{ errorMsg }}</div>

          <!-- 底部按钮 -->
          <div class="panel-footer">
            <button class="btn-cancel" @click="handleClose">取消</button>
            <button class="btn-save" :disabled="saving" @click="handleSave">
              {{ saving ? '保存中...' : '保存' }}
            </button>
          </div>
        </div>
      </div>
    </transition>
  </teleport>
</template>

<script setup>
import { ref, reactive, watch, computed } from 'vue'
import { updateProfile, updatePassword } from '../api/auth'

const props = defineProps({
  visible: { type: Boolean, default: false },
  user: { type: Object, default: null },
  initialTab: { type: String, default: 'profile' }
})

const emit = defineEmits(['close', 'saved', 'password-changed'])

const activeTab = ref('profile')
const saving = ref(false)
const errorMsg = ref('')

const profileForm = reactive({ nickname: '', avatar: '' })
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

// 打开弹窗时用最新用户数据初始化表单
watch(
  () => props.visible,
  (val) => {
    if (val) {
      activeTab.value = props.initialTab === 'password' ? 'password' : 'profile'
      profileForm.nickname = props.user?.nickname || props.user?.username || ''
      profileForm.avatar = props.user?.avatar || ''
      passwordForm.oldPassword = ''
      passwordForm.newPassword = ''
      passwordForm.confirmPassword = ''
      errorMsg.value = ''
    }
  }
)

// 头像预览：合法 URL 才展示
const previewAvatar = computed(() => {
  const url = (profileForm.avatar || '').trim()
  if (!url) return ''
  try {
    const u = new URL(url)
    return u.protocol === 'http:' || u.protocol === 'https:' ? url : ''
  } catch {
    return ''
  }
})

const avatarLetter = computed(() => {
  const name = props.user?.nickname || props.user?.username || 'U'
  return name.charAt(0).toUpperCase()
})

function handleClose() {
  if (saving.value) return
  emit('close')
}

async function handleSave() {
  errorMsg.value = ''
  if (activeTab.value === 'profile') {
    await saveProfile()
  } else {
    await savePassword()
  }
}

async function saveProfile() {
  const nickname = profileForm.nickname.trim()
  if (!nickname) {
    errorMsg.value = '昵称不能为空'
    return
  }
  saving.value = true
  try {
    // avatar 空串表示清除头像，需传给后端
    const data = { nickname, avatar: profileForm.avatar.trim() }
    const updated = await updateProfile(data)
    emit('saved', updated)
    emit('close')
  } catch (e) {
    errorMsg.value = e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function savePassword() {
  const { oldPassword, newPassword, confirmPassword } = passwordForm
  if (!oldPassword) {
    errorMsg.value = '请输入原密码'
    return
  }
  if (!newPassword) {
    errorMsg.value = '请输入新密码'
    return
  }
  if (newPassword.length < 6 || newPassword.length > 32) {
    errorMsg.value = '新密码长度需在 6-32 之间'
    return
  }
  if (newPassword !== confirmPassword) {
    errorMsg.value = '两次输入的新密码不一致'
    return
  }
  saving.value = true
  try {
    await updatePassword({ oldPassword, newPassword })
    emit('password-changed')
  } catch (e) {
    errorMsg.value = e.message || '修改失败'
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.profile-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.profile-panel {
  width: 460px;
  max-width: calc(100vw - 32px);
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.18);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 80px);
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border, #eceff5);
}

.panel-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-main, #1a1f36);
}

.panel-close {
  width: 26px;
  height: 26px;
  border: none;
  background: transparent;
  border-radius: 6px;
  font-size: 20px;
  line-height: 1;
  color: #999;
  cursor: pointer;
  transition: all 0.2s;
}

.panel-close:hover {
  background: #f0f1f5;
  color: #333;
}

.tab-nav {
  display: flex;
  gap: 4px;
  padding: 12px 20px 0;
}

.tab-btn {
  padding: 6px 14px;
  border-radius: 8px 8px 0 0;
  font-size: 14px;
  color: #666;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.2s;
}

.tab-btn:hover {
  color: #4f66f9;
}

.tab-btn.active {
  color: #4f66f9;
  font-weight: 600;
  border-bottom-color: #4f66f9;
}

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 18px 20px;
}

.avatar-row {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 16px;
}

.avatar-preview {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: linear-gradient(135deg, #4f66f9, #7b8cff);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  flex-shrink: 0;
}

.avatar-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-letter {
  color: #fff;
  font-size: 26px;
  font-weight: 600;
}

.avatar-tip {
  font-size: 13px;
  color: var(--text-sub, #6b7390);
}

.form-item {
  margin-bottom: 16px;
}

.form-label {
  display: block;
  font-size: 13px;
  color: var(--text-sub, #6b7390);
  margin-bottom: 6px;
}

.form-input {
  width: 100%;
  box-sizing: border-box;
  height: 36px;
  padding: 0 12px;
  border: 1px solid var(--border, #e2e6ef);
  border-radius: 8px;
  font-size: 14px;
  color: var(--text-main, #1a1f36);
  background: #fafbfd;
  transition: all 0.2s;
}

.form-input:focus {
  outline: none;
  border-color: #4f66f9;
  background: #fff;
  box-shadow: 0 0 0 3px rgba(79, 102, 249, 0.12);
}

.form-input:disabled {
  background: #f0f1f5;
  color: #999;
  cursor: not-allowed;
}

.pwd-tip {
  font-size: 12px;
  color: #e6a23c;
  background: #fdf6ec;
  border-radius: 6px;
  padding: 8px 12px;
}

.error-msg {
  margin: 0 20px 12px;
  font-size: 13px;
  color: #f56c6c;
  background: #fef0f0;
  border-radius: 6px;
  padding: 8px 12px;
}

.panel-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 20px;
  border-top: 1px solid var(--border, #eceff5);
}

.btn-cancel,
.btn-save {
  height: 34px;
  padding: 0 20px;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  border: none;
  transition: all 0.2s;
}

.btn-cancel {
  background: #f0f1f5;
  color: #555;
}

.btn-cancel:hover {
  background: #e4e6ec;
}

.btn-save {
  background: #4f66f9;
  color: #fff;
}

.btn-save:hover {
  background: #3e55e8;
}

.btn-save:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.fade-enter-active .profile-panel,
.fade-leave-active .profile-panel {
  transition: transform 0.2s ease;
}

.fade-enter-from .profile-panel,
.fade-leave-to .profile-panel {
  transform: scale(0.95);
}
</style>
