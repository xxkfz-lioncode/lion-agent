<template>
  <div class="app-layout">
    <!-- 左侧导航栏 -->
    <aside v-if="showSidebar" class="nav-sidebar" :class="{ collapsed: isCollapsed }">
      <div class="nav-header">
        <span class="brand-icon">🦁</span>
        <span v-show="!isCollapsed" class="brand-title">Lion Agent</span>
      </div>

      <nav class="nav-menu">
        <template v-for="item in menuTree">
          <!-- 无子菜单 -->
          <router-link
            v-if="!item.children"
            :key="item.key || item.path"
            :to="item.path"
            class="nav-item"
            :class="{ active: $route.path === item.path }"
            :title="item.title"
          >
            <span class="nav-icon">{{ item.icon }}</span>
            <span v-show="!isCollapsed" class="nav-title">{{ item.title }}</span>
          </router-link>

          <!-- 有子菜单的分组 -->
          <div v-else :key="item.key" class="nav-group">
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
        <div class="user-info" title="个人设置" @click="openProfile('profile')">
          <div class="user-avatar">
            <img v-if="avatarUrl" :src="avatarUrl" alt="头像" />
            <span v-else>{{ avatarLetter }}</span>
          </div>
          <span v-show="!isCollapsed" class="user-name">{{ displayName }}</span>
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
          <button class="sidebar-toggle" title="折叠/展开菜单" @click="toggleCollapse">
            ☰
          </button>
          <div class="breadcrumb">
            <span class="breadcrumb-root" @click="goHome">Lion Agent</span>
            <span
              v-for="item in breadcrumbItems"
              :key="item.key"
              class="breadcrumb-item"
              :class="{ sep: item.type === 'sep', active: item.type === 'crumb' && item.active }"
              @click="item.type === 'crumb' && item.path && router.push(item.path)"
            >{{ item.type === 'sep' ? '/' : item.title }}</span>
          </div>
        </div>
        <div class="top-bar-right">
          <div class="user-dropdown">
            <div class="top-user" @click="userMenuOpen = !userMenuOpen">
              <div class="top-avatar">
                <img v-if="avatarUrl" :src="avatarUrl" alt="头像" />
                <span v-else>{{ avatarLetter }}</span>
              </div>
              <span class="top-username">{{ displayName }}</span>
              <span class="dropdown-caret" :class="{ open: userMenuOpen }">▼</span>
            </div>
            <transition name="dropdown">
              <div v-if="userMenuOpen" class="user-menu">
                <div class="user-menu-header">
                  <div class="menu-avatar">
                    <img v-if="avatarUrl" :src="avatarUrl" alt="头像" />
                    <span v-else>{{ avatarLetter }}</span>
                  </div>
                  <div class="menu-user-text">
                    <div class="menu-name">{{ displayName }}</div>
                    <div class="menu-username">{{ user?.username }}</div>
                  </div>
                </div>
                <div class="menu-divider"></div>
                <div class="user-menu-item" @click="openProfile('profile')">👤 个人资料</div>
                <div class="user-menu-item" @click="openProfile('password')">🔒 修改密码</div>
                <div class="menu-divider"></div>
                <div class="user-menu-item danger" @click="handleLogout">🚪 退出登录</div>
              </div>
            </transition>
          </div>
          <span class="top-time">🕐 {{ currentTime }}</span>
        </div>
      </header>

      <!-- 顶部标签页导航 -->
      <div v-if="showSidebar" class="tab-bar">
        <div class="tabs-scroll">
          <div
            v-for="tab in tabs"
            :key="tab.path"
            class="tab-item"
            :class="{ active: activeTab === tab.path }"
            @click.middle="closeTab(tab.path)"
            @contextmenu.prevent="showContextMenu($event, tab.path)"
            @click="switchTab(tab.path)"
          >
            <span class="tab-title">{{ tab.title }}</span>
            <span
              v-if="tab.path !== homePath"
              class="tab-close"
              @click.stop="closeTab(tab.path)"
            >×</span>
          </div>
        </div>
      </div>

      <div class="page-content">
        <router-view />
      </div>
    </main>

    <!-- 标签右键菜单 -->
    <div v-if="contextMenu.show" class="menu-backdrop" @click="closeContextMenu"></div>
    <div
      v-if="contextMenu.show"
      class="tab-context-menu"
      :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
    >
      <div class="menu-item" @click="closeTargetTab">关闭</div>
      <div class="menu-item" @click="closeOthers">关闭其他</div>
      <div class="menu-item" @click="closeRightTabs">关闭右侧</div>
      <div class="menu-item" @click="closeLeftTabs">关闭左侧</div>
      <div class="menu-divider"></div>
      <div class="menu-item" @click="closeAllTabs">关闭所有</div>
    </div>

    <!-- 个人设置弹窗 -->
    <UserProfileModal
      :visible="profileModalVisible"
      :user="user"
      :initial-tab="profileModalTab"
      @close="closeProfileModal"
      @saved="onProfileSaved"
      @password-changed="onPasswordChanged"
    />
  </div>
</template>

<script setup>
import { ref, watch, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { logout } from './api/auth'
import UserProfileModal from './components/UserProfileModal.vue'

const router = useRouter()
const route = useRoute()
const user = ref(JSON.parse(localStorage.getItem('user') || 'null'))

// ==================== 个人设置 ====================

const userMenuOpen = ref(false)
const profileModalVisible = ref(false)
const profileModalTab = ref('profile')

const displayName = computed(() => user.value?.nickname || user.value?.username || '')
const avatarLetter = computed(() => (displayName.value || 'U').charAt(0).toUpperCase())
const avatarUrl = computed(() => {
  const url = (user.value?.avatar || '').trim()
  if (!url) return ''
  try {
    const u = new URL(url)
    return u.protocol === 'http:' || u.protocol === 'https:' ? url : ''
  } catch {
    return ''
  }
})

function openProfile(tab = 'profile') {
  profileModalTab.value = tab
  profileModalVisible.value = true
  userMenuOpen.value = false
}

function closeProfileModal() {
  profileModalVisible.value = false
}

function onProfileSaved(updated) {
  // 用后端返回的最新用户刷新本地存储与界面
  user.value = updated
  localStorage.setItem('user', JSON.stringify(updated))
}

function onPasswordChanged() {
  profileModalVisible.value = false
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  router.push('/login')
}

function onGlobalClick(e) {
  const el = document.querySelector('.user-dropdown')
  if (el && !el.contains(e.target)) {
    userMenuOpen.value = false
  }
}

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
  document.addEventListener('click', onGlobalClick)
})

onUnmounted(() => {
  clearInterval(timer)
  document.removeEventListener('click', onGlobalClick)
})

const pageTitle = computed(() => route.meta.title || findMenuTitle(route.path))

const breadcrumbItems = computed(() => {
  const crumbs = []
  for (const item of menuTree) {
    if (item.path === route.path) {
      crumbs.push({ title: item.title, path: item.path })
      break
    }
    if (item.children) {
      const child = item.children.find(c => c.path === route.path)
      if (child) {
        crumbs.push({ title: item.title, path: item.children[0].path })
        crumbs.push({ title: child.title, path: child.path })
        break
      }
    }
  }
  if (crumbs.length === 0) {
    crumbs.push({ title: route.meta.title || '当前页' })
  }

  const items = []
  crumbs.forEach((crumb, idx) => {
    if (idx > 0) items.push({ type: 'sep', key: `sep-${idx}` })
    items.push({
      type: 'crumb',
      key: `crumb-${idx}`,
      title: crumb.title,
      path: crumb.path,
      active: idx === crumbs.length - 1
    })
  })
  return items
})

function goHome() {
  router.push('/home')
}

const isCollapsed = ref(false)
const openedGroups = ref(['knowledge'])

const menuTree = [
  { key: 'home', path: '/home', title: '首页', icon: '🏠' },
  { key: 'chat', path: '/chat', title: '对话', icon: '💬' },
  { key: 'multimodal', path: '/chat/multimodal', title: '多模态对话', icon: '🖼️' },
  {
    key: 'knowledge',
    title: '知识库',
    icon: '📚',
    children: [
      { path: '/knowledge/manage', title: '知识库管理', icon: '🛠️' },
      { path: '/knowledge/upload', title: '知识库上传', icon: '📤' }
    ]
  },
  { key: 'memory', path: '/memory', title: '长期记忆', icon: '🧠' },
  { key: 'skill', path: '/skill/manage', title: '技能管理', icon: '🧩' },
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

// ==================== 顶部标签页逻辑 ====================

const homePath = '/home'
const homeTab = { path: homePath, title: '首页' }

const tabs = ref([homeTab])
const activeTab = ref(homePath)
const contextMenu = ref({ show: false, x: 0, y: 0, targetPath: '' })

function findMenuTitle(path) {
  for (const item of menuTree) {
    if (item.path === path) return item.title
    if (item.children) {
      const child = item.children.find(c => c.path === path)
      if (child) return child.title
    }
  }
  return route.meta.title || path
}

function addTab(path) {
  // 忽略重定向占位路由 /，统一回到首页
  if (path === '/') {
    activeTab.value = homePath
    return
  }

  const exists = tabs.value.some(t => t.path === path)
  if (exists) {
    activeTab.value = path
    return
  }
  tabs.value.push({
    path,
    title: findMenuTitle(path)
  })
  activeTab.value = path
}

function switchTab(path) {
  if (route.path !== path) {
    router.push(path)
  }
}

function closeTab(path) {
  if (path === homePath) return
  const idx = tabs.value.findIndex(t => t.path === path)
  if (idx === -1) return
  const isActive = activeTab.value === path
  tabs.value.splice(idx, 1)
  if (isActive) {
    const next = tabs.value[idx] || tabs.value[idx - 1]
    if (next) {
      router.push(next.path)
    } else {
      router.push(homePath)
    }
  }
}

function closeAllTabs() {
  tabs.value = [homeTab]
  activeTab.value = homePath
  closeContextMenu()
  router.push(homePath)
}

function closeOthers() {
  const target = contextMenu.value.targetPath
  tabs.value = [homeTab, ...tabs.value.filter(t => t.path === target && t.path !== homePath)]
  closeContextMenu()
  if (activeTab.value !== target) {
    router.push(target)
  }
}

function closeRightTabs() {
  const target = contextMenu.value.targetPath
  const idx = tabs.value.findIndex(t => t.path === target)
  if (idx === -1) return
  const removed = tabs.value.splice(idx + 1)
  closeContextMenu()
  if (removed.some(t => t.path === activeTab.value)) {
    router.push(target)
  }
}

function closeLeftTabs() {
  const target = contextMenu.value.targetPath
  const idx = tabs.value.findIndex(t => t.path === target)
  if (idx === -1) return
  // 左侧只清非首页标签
  const removed = tabs.value.splice(1, idx - 1)
  closeContextMenu()
  if (removed.some(t => t.path === activeTab.value)) {
    router.push(target)
  }
}

function closeTargetTab() {
  closeTab(contextMenu.value.targetPath)
  closeContextMenu()
}

function showContextMenu(e, path) {
  e.preventDefault()
  const maxX = window.innerWidth - 140
  const maxY = window.innerHeight - 220
  contextMenu.value = {
    show: true,
    x: Math.min(e.clientX, maxX),
    y: Math.min(e.clientY, maxY),
    targetPath: path
  }
}

function closeContextMenu() {
  contextMenu.value.show = false
}

watch(() => route.path, (path) => {
  addTab(path)
}, { immediate: true })
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
  flex: 1;
  min-width: 0;
  padding: 6px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;
}

.user-info:hover {
  background: rgba(255, 255, 255, 0.08);
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
  overflow: hidden;
}

.user-name {
  font-size: 13px;
  color: #fff;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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
  height: 46px;
  min-height: 46px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  background: #fff;
  border-bottom: 1px solid var(--border);
}

.top-bar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  min-width: 0;
}

.sidebar-toggle {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border);
  background: #f7f8fc;
  border-radius: 6px;
  font-size: 15px;
  color: #555;
  cursor: pointer;
  transition: all 0.2s;
}

.sidebar-toggle:hover {
  background: #eef1ff;
  border-color: #4f66f9;
  color: #4f66f9;
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #666;
}

.breadcrumb-root {
  font-weight: 600;
  color: #333;
  cursor: pointer;
}

.breadcrumb-root:hover {
  color: #4f66f9;
}

.breadcrumb-item {
  cursor: pointer;
  transition: color 0.2s;
}

.breadcrumb-item:hover {
  color: #4f66f9;
}

.breadcrumb-item.active {
  color: #333;
  font-weight: 600;
  cursor: default;
}

.breadcrumb-item.sep {
  color: #c3c7d8;
  cursor: default;
}

.breadcrumb-item.sep:hover {
  color: #c3c7d8;
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
  padding: 4px 8px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;
}

.top-user:hover {
  background: #f0f1f5;
}

.top-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: linear-gradient(135deg, #4f66f9, #7b8cff);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  overflow: hidden;
  flex-shrink: 0;
}

.top-avatar img,
.user-avatar img,
.menu-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.top-username {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-main);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.top-time {
  font-size: 13px;
  color: var(--text-sub);
  font-variant-numeric: tabular-nums;
}

/* ==================== 用户下拉菜单 ==================== */

.user-dropdown {
  position: relative;
}

.dropdown-caret {
  font-size: 10px;
  color: #999;
  transition: transform 0.2s;
}

.dropdown-caret.open {
  transform: rotate(180deg);
}

.user-menu {
  position: absolute;
  right: 0;
  top: calc(100% + 6px);
  width: 220px;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.14);
  padding: 6px 0;
  z-index: 1001;
  overflow: hidden;
}

.user-menu-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
}

.menu-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: linear-gradient(135deg, #4f66f9, #7b8cff);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  font-weight: 600;
  overflow: hidden;
  flex-shrink: 0;
}

.menu-user-text {
  min-width: 0;
}

.menu-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-main);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.menu-username {
  font-size: 12px;
  color: var(--text-sub);
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-menu-item {
  padding: 9px 16px;
  font-size: 13px;
  color: #444;
  cursor: pointer;
  transition: all 0.15s;
}

.user-menu-item:hover {
  background: #f5f6fa;
  color: #4f66f9;
}

.user-menu-item.danger {
  color: #e05a5a;
}

.user-menu-item.danger:hover {
  background: #fef0f0;
  color: #e05a5a;
}

.dropdown-enter-active,
.dropdown-leave-active {
  transition: all 0.18s ease;
}

.dropdown-enter-from,
.dropdown-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

/* ==================== 顶部标签页 ==================== */

.tab-bar {
  height: 38px;
  min-height: 38px;
  background: #f7f8fc;
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  padding: 0 12px;
}

.tabs-scroll {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  overflow-x: auto;
  scrollbar-width: thin;
}

.tabs-scroll::-webkit-scrollbar {
  height: 4px;
}

.tabs-scroll::-webkit-scrollbar-thumb {
  background: rgba(0, 0, 0, 0.15);
  border-radius: 2px;
}

.tab-item {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  background: #fff;
  border: 1px solid #e4e6eb;
  border-radius: 5px;
  color: #555;
  font-size: 12px;
  cursor: pointer;
  user-select: none;
  transition: all 0.2s;
  max-width: 160px;
}

.tab-item:hover {
  border-color: #d0d3dc;
  color: #333;
}

.tab-item.active {
  background: #fff;
  color: #4f66f9;
  border-color: #4f66f9;
  font-weight: 500;
  box-shadow: 0 2px 6px rgba(79, 102, 249, 0.1);
}

.tab-title {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tab-close {
  width: 14px;
  height: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 13px;
  line-height: 1;
  color: #888;
  transition: all 0.2s;
}

.tab-close:hover {
  background: rgba(0, 0, 0, 0.08);
  color: #333;
}

.tab-item.active .tab-close:hover {
  background: rgba(79, 102, 249, 0.12);
  color: #4f66f9;
}

/* ==================== 右键菜单 ==================== */

.menu-backdrop {
  position: fixed;
  inset: 0;
  z-index: 998;
}

.tab-context-menu {
  position: fixed;
  z-index: 999;
  min-width: 130px;
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  padding: 4px 0;
  font-size: 13px;
  color: #333;
}

.menu-item {
  padding: 8px 16px;
  cursor: pointer;
  transition: background 0.15s;
}

.menu-item:hover {
  background: #f5f5f5;
  color: #4f66f9;
}

.menu-divider {
  height: 1px;
  background: #eee;
  margin: 4px 0;
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
