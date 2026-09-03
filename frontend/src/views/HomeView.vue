<template>
  <div class="home-page">
    <!-- 欢迎横幅 -->
    <div class="welcome-banner">
      <div class="banner-left">
        <div class="banner-greeting">{{ greeting }}，{{ displayName }}</div>
        <div class="banner-sub">{{ nowText }} · 欢迎使用 Lion Agent 智能体平台</div>
        <div class="banner-actions">
          <button class="primary-btn" @click="go('/chat')">💬 开始对话</button>
          <button class="ghost-btn" @click="go('/knowledge/manage')">📚 管理知识库</button>
        </div>
      </div>
      <div class="banner-logo">🦁</div>
    </div>

    <!-- 快捷入口 -->
    <div class="section-title">
      <span>快捷入口</span>
      <span class="section-hint">点击卡片进入对应模块</span>
    </div>
    <div class="quick-grid">
      <div v-for="m in modules" :key="m.path" class="quick-card" @click="go(m.path)">
        <div class="quick-icon">{{ m.icon }}</div>
        <div class="quick-info">
          <div class="quick-name">{{ m.title }}</div>
          <div class="quick-desc">{{ m.desc }}</div>
        </div>
        <div class="quick-arrow">›</div>
      </div>
    </div>

    <!-- 平台能力 -->
    <div class="section-title">
      <span>平台能力</span>
    </div>
    <div class="feature-row">
      <div v-for="f in features" :key="f.title" class="feature-card">
        <div class="feature-icon">{{ f.icon }}</div>
        <div class="feature-title">{{ f.title }}</div>
        <div class="feature-desc">{{ f.desc }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const user = JSON.parse(localStorage.getItem('user') || '{}')

const displayName = user.nickname || user.username || '用户'
const greeting = ref('你好')
const nowText = ref('')
let timer = null

const modules = [
  { path: '/chat', icon: '💬', title: '对话', desc: '与 Agent 进行自然语言对话' },
  { path: '/chat/multimodal', icon: '🖼️', title: '多模态对话', desc: '支持图片等多媒体输入' },
  { path: '/knowledge/manage', icon: '📚', title: '知识库管理', desc: '查看与维护知识库文档' },
  { path: '/knowledge/upload', icon: '📤', title: '知识库上传', desc: '上传文档构建领域知识' },
  { path: '/memory', icon: '🧠', title: '长期记忆', desc: '查看与管理 Agent 长期记忆' },
  { path: '/skill/manage', icon: '🧩', title: '技能管理', desc: '配置与编排 Agent 技能' },
  { path: '/usage', icon: '📊', title: '用量统计', desc: '查看 Token 消耗与调用情况' }
]

const features = [
  { icon: '🧠', title: 'RAG 检索增强', desc: '基于知识库的精准问答，支持多轮引用溯源' },
  { icon: '🛠️', title: '技能编排', desc: '向量检索、Http 请求等工具一键配置与调用' },
  { icon: '🔭', title: '全链路可观测', desc: 'Langfuse 追踪每次调用，Token 与成本清晰可见' }
]

function pad(n) {
  return String(n).padStart(2, '0')
}

function updateTime() {
  const d = new Date()
  const h = d.getHours()
  if (h < 6) greeting.value = '夜深了'
  else if (h < 12) greeting.value = '上午好'
  else if (h < 14) greeting.value = '中午好'
  else if (h < 18) greeting.value = '下午好'
  else greeting.value = '晚上好'

  const week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
  nowText.value =
    `${d.getFullYear()}年${pad(d.getMonth() + 1)}月${pad(d.getDate())}日 星期${week} ` +
    `${pad(h)}:${pad(d.getMinutes())}`
}

function go(path) {
  router.push(path)
}

onMounted(() => {
  updateTime()
  timer = setInterval(updateTime, 30000)
})

onUnmounted(() => {
  clearInterval(timer)
})
</script>

<style scoped>
.home-page {
  height: 100%;
  overflow-y: auto;
  padding: 20px 24px;
  background: #f7f8fc;
}

.welcome-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: linear-gradient(120deg, #232a4d 0%, #2f3a6e 55%, #4f66f9 100%);
  border-radius: 16px;
  padding: 28px 32px;
  color: #fff;
  margin-bottom: 24px;
  position: relative;
  overflow: hidden;
}

.banner-greeting {
  font-size: 24px;
  font-weight: 700;
  letter-spacing: 1px;
}

.banner-sub {
  margin-top: 8px;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.75);
}

.banner-actions {
  margin-top: 18px;
  display: flex;
  gap: 12px;
}

.primary-btn {
  border: none;
  background: #fff;
  color: #4f66f9;
  font-size: 14px;
  font-weight: 600;
  padding: 9px 20px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.primary-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
}

.ghost-btn {
  border: 1px solid rgba(255, 255, 255, 0.5);
  background: transparent;
  color: #fff;
  font-size: 14px;
  padding: 9px 20px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.ghost-btn:hover {
  background: rgba(255, 255, 255, 0.15);
}

.banner-logo {
  font-size: 72px;
  opacity: 0.35;
  margin-right: 20px;
  user-select: none;
}

.section-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 14px;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-main);
}

.section-hint {
  font-size: 12px;
  font-weight: 400;
  color: var(--text-sub);
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
  margin-bottom: 24px;
}

.quick-card {
  display: flex;
  align-items: center;
  gap: 14px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 18px 20px;
  cursor: pointer;
  transition: all 0.2s;
}

.quick-card:hover {
  border-color: #4f66f9;
  box-shadow: 0 6px 18px rgba(79, 102, 249, 0.1);
  transform: translateY(-2px);
}

.quick-icon {
  width: 46px;
  height: 46px;
  border-radius: 12px;
  background: #eef1ff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  flex-shrink: 0;
}

.quick-info {
  flex: 1;
  min-width: 0;
}

.quick-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-main);
}

.quick-desc {
  font-size: 12px;
  color: var(--text-sub);
  margin-top: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quick-arrow {
  font-size: 22px;
  color: #c3c7d8;
  flex-shrink: 0;
}

.feature-row {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
}

.feature-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 20px;
}

.feature-icon {
  font-size: 28px;
}

.feature-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-main);
  margin-top: 10px;
}

.feature-desc {
  font-size: 13px;
  color: var(--text-sub);
  margin-top: 6px;
  line-height: 1.6;
}
</style>
