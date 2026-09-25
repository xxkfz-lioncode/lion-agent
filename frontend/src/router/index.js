import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import HomeView from '../views/HomeView.vue'
import ChatView from '../views/ChatView.vue'
import MultimodalChatView from '../views/MultimodalChatView.vue'
import ChatRecordView from '../views/chat/ChatRecordView.vue'
import KnowledgeUpload from '../views/knowledge/UploadView.vue'
import KnowledgeManage from '../views/knowledge/ManageView.vue'
import TokenUsageView from '../views/TokenUsageView.vue'
import MemoryView from '../views/MemoryView.vue'
import SkillManage from '../views/skill/ManageView.vue'
import PromptManage from '../views/prompt/PromptManageView.vue'
import McpManage from '../views/mcp/McpManageView.vue'
import SwaggerView from '../views/SwaggerView.vue'
import ModelConfigView from '../views/model/ModelConfigView.vue'
import SensitiveWordManage from '../views/sensitive/SensitiveWordManageView.vue'
import DifyAppList from '../views/dify/DifyAppListView.vue'
import DifyChat from '../views/dify/DifyChatView.vue'
import DifyDataset from '../views/dify/DifyDatasetView.vue'
import DifyWorkflow from '../views/dify/DifyWorkflowView.vue'

const routes = [
  { path: '/login', name: 'login', component: LoginView, meta: { public: true, title: '登录' } },
  { path: '/', redirect: '/home' },
  { path: '/home', name: 'home', component: HomeView, meta: { title: '首页' } },
  { path: '/chat', name: 'chat', component: ChatView, meta: { title: '对话' } },
  { path: '/chat/multimodal', name: 'multimodal-chat', component: MultimodalChatView, meta: { title: '多模态对话' } },
  { path: '/chat/records', name: 'chat-records', component: ChatRecordView, meta: { title: '会话记录' } },
  { path: '/knowledge/manage', name: 'knowledge-manage', component: KnowledgeManage, meta: { title: '知识库管理' } },
  { path: '/knowledge/upload', name: 'knowledge-upload', component: KnowledgeUpload, meta: { title: '知识库上传' } },
  { path: '/memory', name: 'memory', component: MemoryView, meta: { title: '长期记忆' } },
  { path: '/skill/manage', name: 'skill-manage', component: SkillManage, meta: { title: '技能管理' } },
  { path: '/prompt/manage', name: 'prompt-manage', component: PromptManage, meta: { title: '提示词模板' } },
  { path: '/mcp/manage', name: 'mcp-manage', component: McpManage, meta: { title: 'MCP 服务管理' } },
  { path: '/model/manage', name: 'model-manage', component: ModelConfigView, meta: { title: '模型配置管理' } },
  { path: '/sensitive/manage', name: 'sensitive-manage', component: SensitiveWordManage, meta: { title: '敏感词管理' } },
  { path: '/dify/apps', name: 'dify-apps', component: DifyAppList, meta: { title: 'Dify 应用' } },
  { path: '/dify/dataset', name: 'dify-dataset', component: DifyDataset, meta: { title: 'Dify 知识库' } },
  // 应用市场里点卡片进入，appCode 决定对接 Dify 的哪个应用
  { path: '/dify/chat/:appCode', name: 'dify-chat', component: DifyChat, meta: { title: 'Dify 对话' } },
  { path: '/dify/workflow/:appCode', name: 'dify-workflow', component: DifyWorkflow, meta: { title: 'Dify 工作流' } },
  { path: '/swagger', name: 'swagger', component: SwaggerView, meta: { title: '接口文档' } },
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
