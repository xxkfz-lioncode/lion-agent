<template>
  <div class="pagination-bar">
    <button
      class="page-btn"
      :disabled="pageNum <= 1"
      @click="goPage(pageNum - 1)"
    >上一页</button>
    <span class="page-info">第 {{ pageNum }} / {{ pages || 1 }} 页</span>
    <button
      class="page-btn"
      :disabled="pageNum >= pages"
      @click="goPage(pageNum + 1)"
    >下一页</button>
    <span class="total-info">共 {{ total }} 条，每页 {{ pageSize }} 条</span>
  </div>
</template>

<script setup>
const props = defineProps({
  pageNum: { type: Number, default: 1 },
  pageSize: { type: Number, default: 10 },
  pages: { type: Number, default: 1 },
  total: { type: Number, default: 0 }
})

const emit = defineEmits(['change'])

function goPage(page) {
  if (page < 1 || page > props.pages || page === props.pageNum) return
  emit('change', page)
}
</script>

<style scoped>
.pagination-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 24px;
  border-top: 1px solid var(--border);
  font-size: 13px;
  color: var(--text-sub);
}

.page-btn {
  padding: 6px 14px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
  color: var(--text-main);
}

.page-btn:hover:not(:disabled) {
  border-color: var(--primary);
  color: var(--primary);
}

.page-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.page-info {
  color: var(--text-main);
}

.total-info {
  margin-left: 8px;
}
</style>
