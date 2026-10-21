<template>
  <div class="pagination-bar" :class="{ compact }">
    <!-- 上一页 -->
    <button
      class="nav-btn"
      :disabled="pageNum <= 1"
      title="上一页"
      @click="goPage(pageNum - 1)"
    >
      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
        <polyline points="15 18 9 12 15 6" />
      </svg>
    </button>

    <!-- 页码（普通模式：数字胶囊；窄边栏模式：n / pages） -->
    <template v-if="!compact">
      <button
        v-for="p in pageList"
        :key="p"
        class="page-num"
        :class="{ active: p === pageNum, dots: p === ELLIPSIS }"
        :disabled="p === ELLIPSIS"
        @click="goPage(p)"
      >{{ p }}</button>
    </template>
    <span v-else class="page-info">{{ pageNum }} / {{ pages || 1 }}</span>

    <!-- 下一页 -->
    <button
      class="nav-btn"
      :disabled="pageNum >= pages"
      title="下一页"
      @click="goPage(pageNum + 1)"
    >
      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
        <polyline points="9 18 15 12 9 6" />
      </svg>
    </button>

    <!-- 总数（窄边栏模式省略，避免挤压换行） -->
    <span v-if="!compact" class="total-info">共 {{ total }} 条</span>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  pageNum: { type: Number, default: 1 },
  pageSize: { type: Number, default: 10 },
  pages: { type: Number, default: 1 },
  total: { type: Number, default: 0 },
  /** 窄面板模式：只显示 ‹ n/pages ›，适合侧边栏 */
  compact: { type: Boolean, default: false }
})

const emit = defineEmits(['change'])

const ELLIPSIS = '…'

function goPage(page) {
  if (page === ELLIPSIS || page < 1 || page > props.pages || page === props.pageNum) return
  emit('change', page)
}

/** 页码序列：当前页 ±1，首尾常驻，间隔用省略号（总页数 ≤ 7 时全展示） */
const pageList = computed(() => {
  const total = props.pages || 1
  const cur = props.pageNum
  if (total <= 7) {
    return Array.from({ length: total }, (_, i) => i + 1)
  }
  const list = [1]
  if (cur > 3) list.push(ELLIPSIS)
  for (let i = Math.max(2, cur - 1); i <= Math.min(total - 1, cur + 1); i++) {
    list.push(i)
  }
  if (cur < total - 2) list.push(ELLIPSIS)
  list.push(total)
  return list
})
</script>

<style scoped>
.pagination-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 12px 16px;
  font-size: 13px;
  color: var(--text-sub);
  user-select: none;
}

/* 箭头导航按钮：圆形 */
.nav-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--text-sub);
  cursor: pointer;
  transition: all 0.15s;
}

.nav-btn:hover:not(:disabled) {
  background: #eef1ff;
  color: var(--primary);
}

.nav-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

/* 页码胶囊 */
.page-num {
  min-width: 28px;
  height: 28px;
  padding: 0 6px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-main);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
  transition: all 0.15s;
}

.page-num:hover:not(:disabled):not(.active) {
  background: #eef1ff;
  color: var(--primary);
}

.page-num.active {
  background: var(--primary);
  color: #fff;
  font-weight: 600;
}

.page-num.dots {
  cursor: default;
  color: #c0c4d0;
}

/* 当前页 / 总页数（窄边栏模式） */
.page-info {
  min-width: 56px;
  text-align: center;
  color: var(--text-main);
  font-variant-numeric: tabular-nums;
}

.total-info {
  margin-left: 8px;
  white-space: nowrap;
}

/* 窄边栏模式：更紧凑 */
.pagination-bar.compact {
  gap: 4px;
  padding: 8px 10px;
}
</style>
