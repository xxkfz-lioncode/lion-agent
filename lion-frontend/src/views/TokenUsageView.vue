<template>
  <div class="usage-page">
    <header class="page-header">
      <h2 class="page-title">Token 用量统计</h2>
      <div class="header-actions">
        <select v-model="chatType" class="filter-select" @change="onFilterChange">
          <option value="">全部类型</option>
          <option value="chat">常规对话</option>
          <option value="kb">知识库问答</option>
        </select>
        <button class="refresh-btn" @click="loadAll">刷新</button>
      </div>
    </header>

    <!-- 统计卡片 -->
    <div class="stat-cards">
      <div class="stat-card">
        <div class="stat-icon">📞</div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.todayCalls }}</div>
          <div class="stat-label">今日调用次数</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon">🪙</div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.todayTokens }}</div>
          <div class="stat-label">今日 Token 数</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon">📊</div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.totalCalls }}</div>
          <div class="stat-label">总调用次数</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon">🔥</div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.totalTokens }}</div>
          <div class="stat-label">总 Token 数</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon">⏱️</div>
        <div class="stat-info">
          <div class="stat-value">{{ Number(stats.avgCostMs || 0).toFixed(0) }}ms</div>
          <div class="stat-label">平均耗时</div>
        </div>
      </div>
    </div>

    <!-- 用量明细表 -->
    <div class="table-card">
      <table class="data-table">
        <thead>
          <tr>
            <th>时间</th>
            <th>类型</th>
            <th>调用方式</th>
            <th>模型</th>
            <th>输入 Token</th>
            <th>输出 Token</th>
            <th>总 Token</th>
            <th>耗时</th>
            <th>会话</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in list" :key="row.id">
            <td class="cell-time">{{ row.createdAtStr || row.createdAt }}</td>
            <td>
              <span class="tag" :class="row.chatType === 'kb' ? 'tag-kb' : 'tag-chat'">
                {{ row.chatType === 'kb' ? '知识库问答' : '常规对话' }}
              </span>
            </td>
            <td>
              <span class="tag" :class="row.callType === 'stream' ? 'tag-stream' : 'tag-sync'">
                {{ row.callType === 'stream' ? '流式' : '同步' }}
              </span>
            </td>
            <td class="cell-model">{{ row.model || '-' }}</td>
            <td>{{ row.promptTokens }}</td>
            <td>{{ row.completionTokens }}</td>
            <td class="cell-total">{{ row.totalTokens }}</td>
            <td>{{ row.costMs }}ms</td>
            <td class="cell-title" :title="row.conversationTitle || ''">
              {{ row.conversationTitle || '-' }}
            </td>
          </tr>
          <tr v-if="list.length === 0">
            <td colspan="9" class="empty-cell">暂无用量记录，去对话或知识库问答试试吧</td>
          </tr>
        </tbody>
      </table>

      <PaginationBar
        :page-num="pagination.pageNum"
        :page-size="pagination.pageSize"
        :pages="pagination.pages"
        :total="pagination.total"
        @change="onPageChange"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { listTokenUsage, getTokenUsageStats } from '../api/token-usage'
import PaginationBar from '../components/PaginationBar.vue'

const list = ref([])
const stats = ref({ todayCalls: 0, todayTokens: 0, totalCalls: 0, totalTokens: 0, avgCostMs: 0 })
const chatType = ref('')
const pagination = ref({ pageNum: 1, pageSize: 10, pages: 1, total: 0 })

onMounted(() => loadAll())

async function loadStats() {
  try {
    stats.value = await getTokenUsageStats()
  } catch (e) {
    // 统计失败不影响列表
  }
}

async function loadList() {
  try {
    const res = await listTokenUsage({
      pageNum: pagination.value.pageNum,
      pageSize: pagination.value.pageSize,
      chatType: chatType.value
    })
    list.value = res.list || []
    pagination.value = {
      pageNum: Number(res.pageNum) || 1,
      pageSize: Number(res.pageSize) || 10,
      pages: Number(res.pages) || 1,
      total: Number(res.total) || 0
    }
  } catch (e) {
    alert('加载失败')
  }
}

function loadAll() {
  loadStats()
  loadList()
}

function onFilterChange() {
  pagination.value.pageNum = 1
  loadAll()
}

function onPageChange({ pageNum, pageSize }) {
  pagination.value.pageNum = pageNum
  pagination.value.pageSize = pageSize
  loadList()
}
</script>

<style scoped>
.usage-page {
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

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-main);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.filter-select {
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fff;
  font-size: 13px;
  color: var(--text-main);
  outline: none;
  cursor: pointer;
}

.filter-select:focus {
  border-color: #4f66f9;
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

.stat-cards {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
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

.table-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.data-table th {
  text-align: left;
  padding: 10px 12px;
  color: var(--text-sub);
  font-weight: 500;
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}

.data-table td {
  padding: 10px 12px;
  color: var(--text-main);
  border-bottom: 1px solid #f0f1f6;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.data-table tr:last-child td {
  border-bottom: none;
}

.data-table tbody tr:hover {
  background: #f8f9ff;
}

.cell-time {
  color: var(--text-sub);
}

.cell-model {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.cell-total {
  font-weight: 600;
}

.cell-title {
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 12px;
}

.tag-chat {
  background: #eef1ff;
  color: #4f66f9;
}

.tag-kb {
  background: #e8f7ef;
  color: #22a06b;
}

.tag-sync {
  background: #f3f4f8;
  color: #6b7280;
}

.tag-stream {
  background: #fff3e0;
  color: #e67e22;
}

.empty-cell {
  text-align: center;
  padding: 40px 0;
  color: var(--text-sub);
}
</style>
