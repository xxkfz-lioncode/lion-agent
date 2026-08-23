<template>
  <div class="memory-page">
    <header class="page-header">
      <div class="header-left">
        <h2 class="page-title">长期记忆</h2>
        <span class="page-desc">跨会话积累的用户画像，对话时自动注入辅助回答</span>
      </div>
      <div class="header-actions">
        <button class="refresh-btn" :disabled="loading" @click="loadAll">
          {{ loading ? '加载中…' : '刷新' }}
        </button>
      </div>
    </header>

    <!-- 概览统计 -->
    <div class="stat-cards">
      <div class="stat-card">
        <div class="stat-icon">🧠</div>
        <div class="stat-info">
          <div class="stat-value">{{ list.length }}</div>
          <div class="stat-label">画像条数</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon">⭐</div>
        <div class="stat-info">
          <div class="stat-value">{{ avgImportance.toFixed(1) }}</div>
          <div class="stat-label">平均重要度</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon">🕐</div>
        <div class="stat-info">
          <div class="stat-value">{{ lastUpdated }}</div>
          <div class="stat-label">最近更新</div>
        </div>
      </div>
    </div>

    <!-- 画像卡片 -->
    <div v-if="list.length > 0" class="memory-list">
      <div v-for="item in list" :key="item.id" class="memory-card">
        <div class="card-head">
          <div class="card-tags">
            <span class="tag tag-profile">用户画像</span>
            <span class="tag tag-imp" :class="impClass(item.importance)">重要度 {{ item.importance }}/5</span>
          </div>
          <div class="card-stars" :title="'重要度 ' + item.importance + '/5'">
            <span v-for="n in 5" :key="n" class="star" :class="{ on: n <= item.importance }">★</span>
          </div>
        </div>
        <p class="card-content">{{ item.content }}</p>
        <div class="card-foot">
          <span class="foot-item">🆔 #{{ item.id }}</span>
          <span class="foot-item">来源会话：{{ item.sourceConversationId || '-' }}</span>
          <span class="foot-item">创建：{{ item.createdAt }}</span>
          <span class="foot-item">更新：{{ item.updatedAt }}</span>
        </div>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-else-if="!loading" class="empty-state">
      <div class="empty-icon">🧠</div>
      <p class="empty-text">暂无长期记忆</p>
      <p class="empty-hint">先去「常规对话」或「知识库问答」聊几句，系统会自动抽取你的偏好与画像</p>
      <button class="refresh-btn" @click="loadAll">再查一次</button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { listMemory } from '../api/memory'

const list = ref([])
const loading = ref(false)

const avgImportance = computed(() => {
  if (list.value.length === 0) return 0
  const sum = list.value.reduce((acc, m) => acc + (m.importance || 0), 0)
  return sum / list.value.length
})

const lastUpdated = computed(() => {
  if (list.value.length === 0) return '-'
  return list.value[0].updatedAt || '-'
})

function impClass(imp) {
  const n = Number(imp) || 0
  if (n >= 5) return 'imp-high'
  if (n >= 4) return 'imp-mid'
  return 'imp-low'
}

async function loadAll() {
  loading.value = true
  try {
    list.value = (await listMemory()) || []
  } catch (e) {
    alert('加载长期记忆失败：' + e.message)
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.memory-page {
  height: 100%;
  overflow-y: auto;
  padding: 20px 24px;
  background: #f7f8fc;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.header-left {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-main);
}

.page-desc {
  font-size: 12px;
  color: var(--text-sub);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.refresh-btn {
  height: 34px;
  padding: 0 16px;
  border: none;
  border-radius: 8px;
  background: #4f66f9;
  color: #fff;
  font-size: 13px;
  cursor: pointer;
  transition: opacity 0.2s;
}

.refresh-btn:hover {
  opacity: 0.85;
}

.refresh-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.stat-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
  margin-bottom: 16px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px;
}

.stat-icon {
  font-size: 26px;
}

.stat-value {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-main);
  font-variant-numeric: tabular-nums;
}

.stat-label {
  font-size: 12px;
  color: var(--text-sub);
  margin-top: 2px;
}

.memory-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.memory-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 18px 20px;
  box-shadow: 0 1px 3px rgba(30, 34, 53, 0.04);
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.card-tags {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 12px;
}

.tag-profile {
  background: #eef1ff;
  color: #4f66f9;
}

.tag-imp {
  background: #fff3e0;
  color: #e67e22;
}

.imp-high {
  background: #ffe8e8;
  color: #e5484d;
}

.imp-mid {
  background: #fff3e0;
  color: #e67e22;
}

.imp-low {
  background: #f3f4f8;
  color: #6b7280;
}

.card-stars {
  display: flex;
  gap: 2px;
}

.star {
  color: #d9dce6;
  font-size: 15px;
}

.star.on {
  color: #f5a623;
}

.card-content {
  font-size: 15px;
  line-height: 1.7;
  color: var(--text-main);
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0 0 14px;
  padding: 12px 14px;
  background: #fafbfe;
  border-radius: 8px;
  border-left: 3px solid #4f66f9;
}

.card-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  font-size: 12px;
  color: var(--text-sub);
  font-variant-numeric: tabular-nums;
}

.foot-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.empty-state {
  background: #fff;
  border: 1px dashed var(--border);
  border-radius: 12px;
  padding: 60px 20px;
  text-align: center;
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 12px;
}

.empty-text {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-main);
  margin: 0 0 6px;
}

.empty-hint {
  font-size: 13px;
  color: var(--text-sub);
  margin: 0 0 20px;
}
</style>
