<template>
  <div class="manage-page">
    <header class="page-header">
      <h2 class="page-title">知识库管理</h2>
      <div class="header-actions">
        <input
          v-model="keyword"
          class="search-input"
          type="text"
          placeholder="搜索知识库名称/描述"
          @keyup.enter="onSearch"
        >
        <button class="refresh-btn" @click="onSearch">查询</button>
        <button class="create-btn" @click="openDialog()">+ 新建知识库</button>
      </div>
    </header>

    <div class="manage-body">
      <!-- 加载失败：页面内错误态 + 重试，不再用原生 alert -->
      <div v-if="loadError" class="error-state">
        <div class="error-icon">⚠️</div>
        <p class="error-text">知识库列表加载失败</p>
        <p class="error-detail">{{ loadError }}</p>
        <button class="retry-btn" @click="loadList">重新加载</button>
      </div>

      <div v-else-if="knowledgeList.length === 0" class="empty">暂无知识库，点击右上角创建</div>

      <div v-for="kb in knowledgeList" :key="kb.id" class="kb-card">
        <div class="kb-main">
          <div class="kb-icon">📚</div>
          <div class="kb-info">
            <h3 class="kb-name">{{ kb.name }}</h3>
            <p class="kb-desc">{{ kb.description || '暂无描述' }}</p>
            <p class="kb-meta">创建于 {{ kb.createdAt }}</p>
          </div>
        </div>
        <div class="kb-actions">
          <button class="action-btn" @click="openDialog(kb)">编辑</button>
          <button class="action-btn danger" @click="remove(kb.id)">删除</button>
        </div>
      </div>
    </div>

    <PaginationBar
      :page-num="pagination.pageNum"
      :page-size="pagination.pageSize"
      :pages="pagination.pages"
      :total="pagination.total"
      @change="onPageChange"
    />

    <!-- 新建/编辑弹窗 -->
    <div v-if="dialogVisible" class="dialog-mask" @click.self="closeDialog">
      <div class="dialog">
        <h3 class="dialog-title">{{ isEdit ? '编辑知识库' : '新建知识库' }}</h3>
        <div class="dialog-body">
          <div class="form-item">
            <label>名称</label>
            <input v-model="form.name" class="form-input" placeholder="请输入知识库名称">
          </div>
          <div class="form-item">
            <label>描述</label>
            <textarea v-model="form.description" class="form-textarea" placeholder="请输入描述（可选）"></textarea>
          </div>
        </div>
        <div class="dialog-footer">
          <button class="btn-default" @click="closeDialog">取消</button>
          <button class="btn-primary" :disabled="!form.name.trim() || saving" @click="save">
            {{ saving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <ConfirmDialog
      v-model="confirmVisible"
      :title="confirmConfig.title"
      :content="confirmConfig.content"
      :confirm-text="confirmConfig.confirmText"
      :cancel-text="confirmConfig.cancelText"
      :loading="deleting"
      @confirm="onConfirmDelete"
    />

    <!-- 轻量 Toast：替代原生 alert -->
    <transition name="toast-slide">
      <div v-if="toast.visible" class="toast" :class="toast.type">
        <span class="toast-icon">{{ toast.type === 'success' ? '✓' : '⚠' }}</span>
        <span class="toast-msg">{{ toast.msg }}</span>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { listKnowledge, createKnowledge, updateKnowledge, deleteKnowledge } from '../../api/knowledge'
import ConfirmDialog from '../../components/ConfirmDialog.vue'
import PaginationBar from '../../components/PaginationBar.vue'

const knowledgeList = ref([])
const dialogVisible = ref(false)
const saving = ref(false)
const isEdit = ref(false)
const editingId = ref(null)
const form = ref({ name: '', description: '' })
const deleting = ref(false)
const deletingId = ref(null)
const confirmVisible = ref(false)
const confirmConfig = ref({ title: '', content: '', confirmText: '确定', cancelText: '取消' })
const keyword = ref('')
const pagination = ref({ pageNum: 1, pageSize: 10, pages: 1, total: 0 })
const loadError = ref('')
const toast = ref({ visible: false, msg: '', type: 'error' })
let toastTimer = null

onMounted(() => loadList())

/** 轻量提示：3 秒自动消失，替代原生 alert */
function showToast(msg, type = 'error') {
  toast.value = { visible: true, msg, type }
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => {
    toast.value.visible = false
  }, 3000)
}

/** 提取接口错误的可读信息 */
function errMsg(e, fallback) {
  return e?.message || fallback
}

async function loadList() {
  loadError.value = ''
  try {
    const res = await listKnowledge({
      pageNum: pagination.value.pageNum,
      pageSize: pagination.value.pageSize,
      keyword: keyword.value
    })
    knowledgeList.value = res.list || []
    pagination.value = {
      pageNum: Number(res.pageNum) || 1,
      pageSize: Number(res.pageSize) || 10,
      pages: Number(res.pages) || 1,
      total: Number(res.total) || 0
    }
  } catch (e) {
    loadError.value = errMsg(e, '网络异常，请检查后端服务是否启动')
  }
}

function onSearch() {
  pagination.value.pageNum = 1
  loadList()
}

function onPageChange(page) {
  pagination.value.pageNum = page
  loadList()
}

function openDialog(kb) {
  if (kb) {
    isEdit.value = true
    editingId.value = kb.id
    form.value = { name: kb.name, description: kb.description || '' }
  } else {
    isEdit.value = false
    editingId.value = null
    form.value = { name: '', description: '' }
  }
  dialogVisible.value = true
}

function closeDialog() {
  dialogVisible.value = false
}

async function save() {
  saving.value = true
  try {
    if (isEdit.value) {
      await updateKnowledge(editingId.value, form.value)
    } else {
      await createKnowledge(form.value)
    }
    closeDialog()
    await loadList()
    showToast(isEdit.value ? '知识库已更新' : '知识库已创建', 'success')
  } catch (e) {
    showToast('保存失败：' + errMsg(e, '请稍后重试'))
  } finally {
    saving.value = false
  }
}

function remove(id) {
  deletingId.value = id
  confirmConfig.value = {
    title: '删除知识库',
    content: '确定删除该知识库吗？关联的文档将一并删除，此操作不可恢复。',
    confirmText: '删除',
    cancelText: '取消'
  }
  confirmVisible.value = true
}

async function onConfirmDelete() {
  if (!deletingId.value) return
  deleting.value = true
  try {
    await deleteKnowledge(deletingId.value)
    confirmVisible.value = false
    await loadList()
    showToast('知识库已删除', 'success')
  } catch (e) {
    showToast('删除失败：' + errMsg(e, '请稍后重试'))
  } finally {
    deleting.value = false
    deletingId.value = null
  }
}
</script>

<style scoped>
.manage-page {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.page-header {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  border-bottom: 1px solid var(--border);
}

.page-title {
  font-size: 16px;
  font-weight: 600;
}

.create-btn {
  padding: 8px 16px;
  border: none;
  border-radius: 8px;
  background: var(--primary);
  color: #fff;
  font-size: 13px;
  cursor: pointer;
}

.header-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.search-input {
  width: 220px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 13px;
  outline: none;
}

.search-input:focus {
  border-color: var(--primary);
}

.refresh-btn {
  padding: 8px 16px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fff;
  color: var(--text-main);
  font-size: 13px;
  cursor: pointer;
}

.refresh-btn:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.manage-body {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}

.empty {
  text-align: center;
  color: var(--text-sub);
  padding: 60px 0;
}

/* ===== 加载失败错误态 ===== */
.error-state {
  text-align: center;
  padding: 70px 0;
}

.error-icon {
  font-size: 40px;
}

.error-text {
  margin: 14px 0 4px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-main);
}

.error-detail {
  margin: 0 0 18px;
  font-size: 13px;
  color: var(--text-sub);
}

.retry-btn {
  padding: 8px 22px;
  border: 1px solid var(--primary);
  border-radius: 8px;
  background: #fff;
  color: var(--primary);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}

.retry-btn:hover {
  background: var(--primary);
  color: #fff;
}

/* ===== Toast 提示 ===== */
.toast {
  position: fixed;
  top: 24px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 18px;
  border-radius: 8px;
  font-size: 13px;
  color: #fff;
  z-index: 300;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.18);
  max-width: 60vw;
}

.toast.error {
  background: #d9483f;
}

.toast.success {
  background: #2ea44f;
}

.toast-icon {
  font-weight: 700;
}

.toast-msg {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.toast-slide-enter-active,
.toast-slide-leave-active {
  transition: all 0.25s ease;
}

.toast-slide-enter-from,
.toast-slide-leave-to {
  opacity: 0;
  transform: translate(-50%, -12px);
}

.kb-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 20px 24px;
  margin-bottom: 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.kb-main {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.kb-icon {
  font-size: 32px;
}

.kb-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.kb-name {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.kb-desc {
  margin: 0;
  font-size: 13px;
  color: var(--text-sub);
}

.kb-meta {
  margin: 4px 0 0;
  font-size: 12px;
  color: #aaa;
}

.kb-actions {
  display: flex;
  gap: 10px;
}

.action-btn {
  padding: 6px 14px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: #fff;
  font-size: 13px;
  cursor: pointer;
}

.action-btn.danger {
  color: var(--danger);
  border-color: #ffd0d0;
}

.dialog-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.dialog {
  width: 460px;
  background: #fff;
  border-radius: 12px;
  padding: 24px;
}

.dialog-title {
  margin: 0 0 20px;
  font-size: 16px;
}

.form-item {
  margin-bottom: 16px;
}

.form-item label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  margin-bottom: 6px;
}

.form-input,
.form-textarea {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 14px;
  box-sizing: border-box;
}

.form-textarea {
  min-height: 80px;
  resize: vertical;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 8px;
}

.btn-default,
.btn-primary {
  padding: 8px 18px;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
}

.btn-default {
  border: 1px solid var(--border);
  background: #fff;
}

.btn-primary {
  border: none;
  background: var(--primary);
  color: #fff;
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
