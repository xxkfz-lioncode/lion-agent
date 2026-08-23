<template>
  <div class="app-layout">
    <!-- 左侧导航栏 -->
    <aside v-if="showSidebar" class="nav-sidebar" :class="{ collapsed: isCollapsed }">
      <div class="nav-header">
        <span class="brand-icon">🦁</span>
        <span v-show="!isCollapsed" class="brand-title">Lion Agent</span>
        <button class="collapse-btn" :class="{ collapsed: isCollapsed }" @click="toggleCollapse">
          <span v-if="!isCollapsed">◀</span>
          <span v-else>▶</span>
        </button>
      </div>

      <nav class="nav-menu">
        <template v-for="item in menuTree" :key="item.key || item.path">
          <!-- 无子菜单 -->
          <router-link
            v-if="!item.children"
            :to="item.path"
            class="nav-item"
            :class="{ active: $route.path === item.path }"
            :title="item.title"
          >
            <span class="nav-icon">{{ item.icon }}</span>
            <span v-show="!isCollapsed" class="nav-title">{{ item.title }}</span>
          </router-link>

          <!-- 有子菜单的分组 -->
          <div v-else class="nav-group">
            <div
              class="group-title"
              :class="{ active: isGroupActive(item) }"
              @click="toggleGroup(item.key)"
            >
              <span class="nav-icon">{{ item.icon }}</span>
              <span v-show="!isCollapsed" class="nav-title">{{ item.title }}</span>
              <span v-show="!isCollapsed" class="group-arrow" :class="{ open: openedGroups.includes(item.key) }">▼</span>
            </div>
            <transition
              @enter="enter"
              @leave="leave"
            >
              <div v-show="!isCollapsed && openedGroups.includes(item.key)" class="sub-menu">
                <router-link
                  v-for="sub in item.children"
                  :key="sub.path"
                  :to="sub.path"
                  class="nav-item sub-item"
                  :class="{ active: $route.path === sub.path }"
                  :title="sub.title"
                >
                  <span class="sub-dot"></span>
                  <span class="nav-title">{{ sub.title }}</span>
                </router-link>
              </div>
            </transition>
          </div>
        </template>
      </nav>

      <div class="nav-footer">
        <div class="user-info">
          <div class="user-avatar">
            {{ (user?.nickname || user?.username || 'U').charAt(0).toUpperCase() }}
          </div>
          <span v-show="!isCollapsed" class="user-name">{{ user?.nickname || user?.username }}</span>
        </div>
        <button v-show="!isCollapsed" class="logout-btn" @click="handleLogout">退出</button>
        <button v-show="isCollapsed" class="logout-btn icon-only" title="退出" @click="handleLogout">
          🚪
        </button>
      </div>
    </aside>

    <!-- 右侧内容区 -->
    <main class="main-content" :class="{ full: !showSidebar }">
      <header v-if="showSidebar" class="top-bar">
        <div class="top-bar-left">
          <span class="page-title">{{ pageTitle }}</span>
        </div>
        <div class="top-bar-right">
          <div class="top-user">
            <div class="top-avatar">
              {{ (user?.nickname || user?.username || 'U').charAt(0).toUpperCase() }}
            </div>
            <span class="top-username">{{ user?.nickname || user?.username }}</span>
          </div>
          <span class="top-time">🕐 {{ currentTime }}</span>
        </div>
      </header>
      <div class="page-content">
        <router-view />
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, watch, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { logout } from './api/auth'

const router = useRouter()
const route = useRoute()
const user = ref(JSON.parse(localStorage.getItem('user') || 'null'))

const showSidebar = computed(() => !route.meta.public)

const currentTime = ref('')
let timer = null

function pad(n) {
  return String(n).padStart(2, '0')
}

function updateTime() {
  const d = new Date()
  currentTime.value =
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

onMounted(() => {
  updateTime()
  timer = setInterval(updateTime, 1000)
})

onUnmounted(() => {
  clearInterval(timer)
})

const pageTitle = computed(() => route.meta.title || '')

const isCollapsed = ref(false)
const openedGroups = ref(['knowledge'])

const menuTree = [
  { key: 'chat', path: '/chat', title: '常规对话', icon: '💬' },
  {
    key: 'knowledge',
    title: '知识库',
    icon: '📚',
    children: [
      { path: '/knowledge/manage', title: '知识库管理', icon: '🛠️' },
      { path: '/knowledge/upload', title: '知识库上传', icon: '📤' },
      { path: '/knowledge/chat', title: '知识库问答', icon: '🔍' }
    ]
  },
  { key: 'usage', path: '/usage', title: '用量统计', icon: '📊' }
]

function toggleCollapse() {
  isCollapsed.value = !isCollapsed.value
}

function toggleGroup(key) {
  if (isCollapsed.value) {
    isCollapsed.value = false
  }
  const idx = openedGroups.value.indexOf(key)
  if (idx > -1) {
    openedGroups.value.splice(idx, 1)
  } else {
    openedGroups.value.push(key)
  }
}

function isGroupActive(item) {
  if (!item.children) return false
  return item.children.some(sub => route.path === sub.path)
}

watch(route, () => {
  // 自动展开当前所在分组
  menuTree.forEach(item => {
    if (item.children && item.children.some(sub => route.path === sub.path)) {
      if (!openedGroups.value.includes(item.key)) {
        openedGroups.value.push(item.key)
      }
    }
  })
}, { immediate: true })

function enter(el) {
  el.style.height = '0'
  el.style.opacity = '0'
  requestAnimationFrame(() => {
    el.style.transition = 'all 0.25s ease'
    el.style.height = el.scrollHeight + 'px'
    el.style.opacity = '1'
  })
}

function leave(el) {
  el.style.height = el.scrollHeight + 'px'
  el.style.opacity = '1'
  requestAnimationFrame(() => {
    el.style.transition = 'all 0.25s ease'
    el.style.height = '0'
    el.style.opacity = '0'
  })
}

async function handleLogout() {
  try {
    await logout()
  } catch (e) {
    // 忽略退出接口异常
  }
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  router.push('/login')
}
</script>

<style scoped>
.app-layout {
  height: 100%;
  display: flex;
}

.nav-sidebar {
  width: 220px;
  min-width: 220px;
  background: linear-gradient(180deg, #1e2235 0%, #161926 100%);
  color: #b8bed8;
  display: flex;
  flex-direction: column;
  transition: width 0.25s ease;
  position: relative;
}

.nav-sidebar.collapsed {
  width: 64px;
  min-width: 64px;
}

.nav-header {
  height: 64px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 18px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  position: relative;
}

.brand-icon {
  font-size: 26px;
  flex-shrink: 0;
}

.brand-title {
  font-size: 17px;
  font-weight: 700;
  color: #fff;
  white-space: nowrap;
}

.collapse-btn {
  position: absolute;
  right: -12px;
  top: 50%;
  transform: translateY(-50%);
  width: 24px;
  height: 24px;
  border: none;
  border-radius: 50%;
  background: #4f66f9;
  color: #fff;
  font-size: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(79, 102, 249, 0.35);
  z-index: 10;
  transition: right 0.25s ease;
}

.collapse-btn.collapsed {
  right: -10px;
}

.nav-menu {
  flex: 1;
  padding: 16px 10px;
  overflow-y: auto;
  overflow-x: hidden;
}

.nav-item,
.group-title {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 14px;
  margin-bottom: 4px;
  border-radius: 10px;
  color: #b8bed8;
  text-decoration: none;
  font-size: 14px;
  transition: all 0.2s;
  cursor: pointer;
  user-select: none;
}

.nav-item:hover,
.group-title:hover {
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
}

.nav-item.active,
.group-title.active {
  background: rgba(79, 102, 249, 0.18);
  color: #7b8cff;
  font-weight: 600;
}

.group-title {
  justify-content: space-between;
  position: relative;
}

.group-arrow {
  margin-left: auto;
  font-size: 10px;
  color: #8a91ad;
  transition: transform 0.25s ease;
}

.group-arrow.open {
  transform: rotate(180deg);
}

.sub-menu {
  overflow: hidden;
  padding-left: 12px;
}

.nav-item.sub-item {
  padding: 9px 14px 9px 18px;
  font-size: 13px;
  color: #9ba1bd;
}

.nav-item.sub-item:hover {
  color: #fff;
}

.nav-item.sub-item.active {
  color: #7b8cff;
  background: rgba(79, 102, 249, 0.12);
}

.sub-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.5;
  flex-shrink: 0;
}

.nav-icon {
  font-size: 18px;
  width: 22px;
  text-align: center;
  flex-shrink: 0;
}

.nav-title {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.nav-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  overflow: hidden;
}

.user-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, #4f66f9, #7b8cff);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  flex-shrink: 0;
}

.user-name {
  font-size: 13px;
  color: #fff;
  white-space: nowrap;
}

.logout-btn {
  border: none;
  background: rgba(255, 255, 255, 0.08);
  color: #b8bed8;
  font-size: 12px;
  padding: 6px 10px;
  border-radius: 6px;
  cursor: pointer;
}

.logout-btn:hover {
  background: rgba(244, 67, 54, 0.15);
  color: #ff7b7b;
}

.logout-btn.icon-only {
  padding: 4px;
  background: transparent;
  font-size: 16px;
}

.main-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #f7f8fc;
}

.main-content.full {
  width: 100%;
  height: 100%;
}

.top-bar {
  height: 56px;
  min-height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  background: #fff;
  border-bottom: 1px solid var(--border);
}

.top-bar-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-main);
}

.top-bar-right {
  display: flex;
  align-items: center;
  gap: 18px;
}

.top-user {
  display: flex;
  align-items: center;
  gap: 8px;
}

.top-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: linear-gradient(135deg, #4f66f9, #7b8cff);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
}

.top-username {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-main);
}

.top-time {
  font-size: 13px;
  color: var(--text-sub);
  font-variant-numeric: tabular-nums;
}

.page-content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.page-content > * {
  height: 100%;
}
</style>
