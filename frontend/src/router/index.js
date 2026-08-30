import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import ChatView from '../views/ChatView.vue'
import KnowledgeUpload from '../views/knowledge/UploadView.vue'
import KnowledgeManage from '../views/knowledge/ManageView.vue'
import TokenUsageView from '../views/TokenUsageView.vue'
import MemoryView from '../views/MemoryView.vue'
import SkillManage from '../views/skill/ManageView.vue'

const routes = [
  { path: '/login', name: 'login', component: LoginView, meta: { public: true, title: '登录' } },
  { path: '/', redirect: '/chat' },
  { path: '/chat', name: 'chat', component: ChatView, meta: { title: '对话' } },
  { path: '/knowledge/manage', name: 'knowledge-manage', component: KnowledgeManage, meta: { title: '知识库管理' } },
  { path: '/knowledge/upload', name: 'knowledge-upload', component: KnowledgeUpload, meta: { title: '知识库上传' } },
  { path: '/memory', name: 'memory', component: MemoryView, meta: { title: '长期记忆' } },
  { path: '/skill/manage', name: 'skill-manage', component: SkillManage, meta: { title: '技能管理' } },
  { path: '/usage', name: 'usage', component: TokenUsageView, meta: { title: '用量统计' } }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  if (to.meta.public) {
    next()
    return
  }
  const token = localStorage.getItem('token')
  if (!token) {
    next('/login')
  } else {
    next()
  }
})

export default router
