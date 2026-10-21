<template>
  <div class="app-market">
    <div class="market-head">
      <div>
        <div class="market-title">Dify 应用</div>
        <div class="market-sub">已接入 {{ apps.length }} 个应用，点击进入即可使用</div>
      </div>
      <div class="market-tools">
        <input v-model="keyword" class="market-search" placeholder="搜索应用名称" />
        <button class="btn-refresh" :disabled="refreshing" title="从 Dify 重新拉取应用列表" @click="loadApps">
          <span class="refresh-icon" :class="{ spinning: refreshing }">⟳</span>
          {{ refreshing ? '刷新中…' : '刷新' }}
        </button>
      </div>
    </div>

    <div class="app-grid">
      <div
        v-for="app in filteredApps"
        :key="app.code"
        class="app-card"
        @click="enterApp(app)"
      >
        <div class="card-top">
          <div
            class="app-icon"
            :style="app.iconBackground ? { background: app.iconBackground } : {}"
          >{{ app.icon || (app.type === 'workflow' ? '🔀' : '💬') }}</div>
          <span class="type-tag" :class="app.type">{{ app.type === 'workflow' ? '工作流' : '对话' }}</span>
        </div>
        <div class="app-name">{{ app.name || app.code }}</div>
        <div class="app-desc">{{ app.description || '暂无描述' }}</div>
        <div class="app-foot">
          <span class="app-code">{{ app.mode || app.type }}</span>
          <span class="enter-btn">{{ app.type === 'workflow' ? '运行 →' : '开始对话 →' }}</span>
        </div>
      </div>

      <div v-if="!filteredApps.length" class="market-empty">
        {{ refreshing ? '正在从 Dify 拉取应用…' : '暂无匹配的应用（在 Dify 工作室创建应用后点「刷新」即可同步）' }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listDifyApps } from '../../api/dify'

const router = useRouter()
const apps = ref([])
const keyword = ref('')
const refreshing = ref(false)

const filteredApps = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return apps.value
  return apps.value.filter(
    (a) => (a.name || '').toLowerCase().includes(kw) || (a.description || '').toLowerCase().includes(kw)
  )
})

function enterApp(app) {
  if (app.type === 'workflow') {
    router.push(`/dify/workflow/${app.code}`)
  } else {
    router.push(`/dify/chat/${app.code}`)
  }
}

async function loadApps() {
  refreshing.value = true
  try {
    apps.value = await listDifyApps()
  } finally {
    refreshing.value = false
  }
}

onMounted(loadApps)
</script>

<style scoped>
.app-market {
  height: 100%;
  overflow-y: auto;
  padding: 20px 24px;
  background: #f7f8fc;
}

.market-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}

.market-title {
  font-size: 19px;
  font-weight: 700;
  color: var(--text-main);
}

.market-sub {
  font-size: 13px;
  color: var(--text-sub);
  margin-top: 4px;
}

.market-search {
  width: 240px;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 13px;
  outline: none;
  background: #fff;
}

.market-search:focus {
  border-color: #4f66f9;
}

.market-tools {
  display: flex;
  align-items: center;
  gap: 10px;
}

.btn-refresh {
  display: flex;
  align-items: center;
  gap: 5px;
  border: 1px solid var(--border);
  background: #fff;
  color: var(--text-main);
  font-size: 13px;
  padding: 8px 14px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-refresh:hover:not(:disabled) {
  border-color: #4f66f9;
  color: #4f66f9;
}

.btn-refresh:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.refresh-icon {
  display: inline-block;
  font-size: 15px;
  line-height: 1;
}

.refresh-icon.spinning {
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.app-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 16px;
}

.app-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  flex-direction: column;
  min-height: 150px;
}

.app-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 8px 22px rgba(79, 102, 249, 0.14);
  border-color: #4f66f9;
}

.card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.app-icon {
  width: 42px;
  height: 42px;
  border-radius: 10px;
  background: linear-gradient(135deg, #eef1ff, #e3e8ff);
  font-size: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.type-tag {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  background: #eef1ff;
  color: #4f66f9;
}

.type-tag.workflow {
  background: #eafaf1;
  color: #1f9d55;
}

.app-name {
  margin-top: 12px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-main);
}

.app-desc {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-sub);
  line-height: 1.6;
  flex: 1;
}

.app-foot {
  margin-top: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
}

.app-code {
  color: #9aa0b4;
  font-family: Consolas, monospace;
}

.enter-btn {
  color: #4f66f9;
  font-weight: 500;
}

.market-empty {
  grid-column: 1 / -1;
  text-align: center;
  color: var(--text-sub);
  font-size: 13px;
  padding: 30px 0;
}
</style>
