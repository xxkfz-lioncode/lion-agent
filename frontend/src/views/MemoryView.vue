<template>
  <div class="manage-page">
    <header class="page-header">
      <div class="header-title">
        <h2 class="page-title">长期记忆</h2>
        <p class="page-sub">
          跨会话积累的用户画像，对话时自动注入辅助回答；画像由系统在对话后异步抽取整合，每用户保留一条。
        </p>
      </div>
      <button class="btn-primary" :disabled="loading" @click="loadAll">
        {{ loading ? '加载中…' : '⟳ 刷新' }}
      </button>
    </header>

    <div class="manage-body">
      <!-- 工具栏：搜索 + 统计 -->
      <div class="toolbar">
        <input
          v-model="keyword"
          class="search-input"
          placeholder="搜索记忆内容 / 来源会话 ID"
        />
        <span class="count-tip">共 {{ filteredList.length }} 条画像</span>
      </div>

      <!-- 记忆列表（表格） -->
      <div class="table-card">
        <table class="memory-table">
          <thead>
            <tr>
              <th class="col-id">ID</th>
              <th class="col-content">记忆内容</th>
              <th class="col-importance">重要度</th>
              <th class="col-source">来源会话</th>
              <th class="col-time">创建时间</th>
              <th class="col-time">更新时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading">
              <td colspan="6" class="empty-cell">加载中...</td>
            </tr>
            <tr v-else-if="filteredList.length === 0 && keyword">
              <td colspan="6" class="empty-cell">没有匹配「{{ keyword }}」的记忆</td>
            </tr>
            <tr v-else-if="list.length === 0">
              <td colspan="6" class="empty-cell">
                暂无长期记忆，先去「常规对话」或「知识库问答」聊几句，系统会自动抽取你的偏好与画像
              </td>
            </tr>
            <tr v-for="item in filteredList" :key="item.id">
              <td class="col-id">#{{ item.id }}</td>
              <td class="col-content">
                <span class="content-text" :title="item.content">{{ item.content }}</span>
              </td>
              <td class="col-importance">
                <span class="imp-cell">
                  <span class="imp-badge" :class="impClass(item.importance)">
                    {{ item.importance }}/5
                  </span>
                  <span class="imp-stars" :title="'重要度 ' + item.importance + '/5'">
                    <span v-for="n in 5" :key="n" class="star" :class="{ on: n <= item.importance }">★</span>
                  </span>
                </span>
              </td>
              <td class="col-source">{{ item.sourceConversationId || '-' }}</td>
              <td class="col-time">{{ formatTime(item.createdAt) }}</td>
              <td class="col-time">{{ formatTime(item.updatedAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { listMemory } from '../api/memory'

const list = ref([])
const loading = ref(false)
const keyword = ref('')

const filteredList = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return list.value
  return list.value.filter(
    (m) =>
      (m.content || '').toLowerCase().includes(kw) ||
      String(m.sourceConversationId || '').includes(kw)
  )
})

function impClass(imp) {
  const n = Number(imp) || 0
  if (n >= 5) return 'imp-high'
  if (n >= 4) return 'imp-mid'
  return 'imp-low'
}

function formatTime(time) {
  if (!time) return '-'
  return String(time).replace('T', ' ').slice(0, 19)
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
.manage-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  background: #f7f8fc;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 16px 24px;
  gap: 16px;
}

.header-title {
  flex: 1;
  min-width: 0;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-main);
  margin: 0;
}

.page-sub {
  font-size: 12px;
  color: var(--text-sub);
  margin: 6px 0 0;
}

.btn-primary {
  height: 34px;
  padding: 0 16px;
  border: none;
  border-radius: 8px;
  background: #4f66f9;
  color: #fff;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
  transition: opacity 0.2s;
}

.btn-primary:hover {
  opacity: 0.85;
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.manage-body {
  flex: 1;
  overflow-y: auto;
  padding: 0 24px 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* 工具栏：搜索 + 统计 */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.search-input {
  width: 280px;
  height: 34px;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 13px;
  color: var(--text-main);
  background: #fff;
  outline: none;
  transition: border-color 0.2s;
}

.search-input:focus {
  border-color: #4f66f9;
}

.count-tip {
  font-size: 12px;
  color: #8a91ad;
}

/* 表格卡片 */
.table-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  overflow: auto;
}

.memory-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.memory-table thead th {
  text-align: left;
  font-size: 12px;
  font-weight: 600;
  color: #8a91ad;
  background: #f7f8fc;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}

.memory-table tbody td {
  padding: 12px 14px;
  border-bottom: 1px solid #f0f1f6;
  vertical-align: middle;
}

.memory-table tbody tr:last-child td {
  border-bottom: none;
}

.memory-table tbody tr:hover {
  background: #fafbff;
}

.empty-cell {
  text-align: center;
  color: #a0a6bd;
  font-size: 13px;
  padding: 48px 0 !important;
}

/* ID 列 */
.col-id {
  width: 64px;
  color: #8a91ad;
  font-family: Consolas, monospace;
  white-space: nowrap;
}

/* 内容列 */
.col-content {
  max-width: 520px;
}

.content-text {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
  color: var(--text-main);
  line-height: 1.6;
}

/* 重要度列 */
.col-importance {
  white-space: nowrap;
}

.imp-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.imp-badge {
  font-size: 11px;
  font-weight: 500;
  border-radius: 4px;
  padding: 2px 6px;
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
  background: #f2f3f7;
  color: #8a91ad;
}

.imp-stars {
  display: inline-flex;
  gap: 1px;
}

.star {
  color: #d9dce6;
  font-size: 13px;
}

.star.on {
  color: #f5a623;
}

/* 来源会话列 */
.col-source {
  color: #6b7280;
  font-family: Consolas, monospace;
  white-space: nowrap;
}

/* 时间列 */
.col-time {
  color: #8a91ad;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
</style>
