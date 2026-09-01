<template>
  <div class="upload-page">
    <header class="page-header">
      <div class="header-title">
        <h2 class="page-title">已上传文档</h2>
        <select
          v-model="selectedId"
          class="kb-select"
          :disabled="loading"
          @change="onKbChange"
        >
          <option value="" disabled>请选择知识库</option>
          <option v-for="kb in knowledgeList" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
        </select>
      </div>
      <div class="header-actions">
        <input
          v-model="searchName"
          class="search-input"
          type="text"
          placeholder="搜索文档名称"
          @keyup.enter="onSearch"
        >
        <button class="search-btn" @click="onSearch">查询</button>
        <input
          ref="fileInput"
          type="file"
          accept=".txt,.md,.pdf,.doc,.docx"
          hidden
          @change="onFileSelected"
        >
        <button class="upload-btn" @click="selectFile">+ 上传文档</button>
      </div>
    </header>

    <div class="doc-table-wrap">
      <table class="doc-table">
        <thead>
          <tr>
            <th class="col-name">文件名</th>
            <th class="col-size">大小</th>
            <th class="col-type">类型</th>
            <th class="col-status">状态</th>
            <th class="col-splitter">切分方式</th>
            <th class="col-time">上传时间</th>
            <th class="col-actions">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="doc in docList" :key="doc.id">
            <td class="cell-name" :title="doc.fileName">{{ doc.fileName }}</td>
            <td class="cell-size">{{ formatSize(doc.fileSize) }}</td>
            <td class="cell-type">{{ doc.fileType || '-' }}</td>
            <td class="cell-status">
              <span class="status-tag" :class="statusClass(doc.status)">{{ statusLabel(doc.status) }}</span>
            </td>
            <td class="cell-splitter">{{ splitterLabel(doc.splitter) }}</td>
            <td class="cell-time">{{ formatTime(doc.createdAt) }}</td>
            <td class="cell-actions">
              <button class="action-btn primary" title="预览" @click="openPreview(doc)">
                <span class="btn-icon">👁</span>
                <span class="btn-text">预览</span>
              </button>
              <button class="action-btn danger" title="删除" @click="requestDelete(doc)">
                <span class="btn-icon">🗑</span>
                <span class="btn-text">删除</span>
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="docList.length === 0 && !loading" class="empty-state">
        暂无文档，点击右上角「上传文档」添加
      </div>
      <div v-if="loading" class="loading-mask">加载中…</div>
    </div>

    <PaginationBar
      v-if="pagination.total > 0"
      :page-num="pagination.pageNum"
      :page-size="pagination.pageSize"
      :pages="pagination.pages"
      :total="pagination.total"
      @change="onPageChange"
    />

    <!-- 预览弹窗 -->
    <div v-if="previewVisible" class="dialog-mask" @click.self="closePreview">
      <div class="dialog dialog-lg">
        <h3 class="dialog-title">文档预览：{{ previewDoc.fileName }}</h3>
        <div class="dialog-body">
          <div class="preview-meta">
            <div class="meta-row">
              <span class="meta-label">文件大小</span>
              <span class="meta-value">{{ formatSize(previewDoc.fileSize) }}</span>
            </div>
            <div class="meta-row">
              <span class="meta-label">文件类型</span>
              <span class="meta-value">{{ previewDoc.fileType || '-' }}</span>
            </div>
            <div class="meta-row">
              <span class="meta-label">处理状态</span>
              <span class="meta-value">
                <span class="status-tag" :class="statusClass(previewDoc.status)">{{ statusLabel(previewDoc.status) }}</span>
              </span>
            </div>
            <div class="meta-row">
              <span class="meta-label">切分方式</span>
              <span class="meta-value splitter-value">{{ splitterLabel(previewDoc.splitter) }}</span>
            </div>
            <div class="meta-row">
              <span class="meta-label">上传时间</span>
              <span class="meta-value">{{ formatTime(previewDoc.createdAt) }}</span>
            </div>
          </div>
          <div class="preview-content">
            <div class="preview-label">内容预览</div>
            <pre v-if="previewContent" class="preview-pre">{{ previewContent }}</pre>
            <div v-else class="preview-empty">加载中…</div>
          </div>
        </div>
        <div class="dialog-footer">
          <button class="btn-default" @click="closePreview">关闭</button>
        </div>
      </div>
    </div>

    <ConfirmDialog
      v-model="confirmVisible"
      title="删除文档"
      :content="confirmContent"
      confirm-text="删除"
      type="danger"
      @confirm="confirmDelete"
    />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { listDocuments, uploadDocument, deleteDocument, previewDocument, listKnowledge } from '../../api/knowledge'
import ConfirmDialog from '../../components/ConfirmDialog.vue'
import PaginationBar from '../../components/PaginationBar.vue'

const route = useRoute()

const knowledgeList = ref([])
const selectedId = ref('')
const docList = ref([])
const loading = ref(false)
const searchName = ref('')
const pagination = ref({ pageNum: 1, pageSize: 10, pages: 1, total: 0 })

const fileInput = ref(null)
const uploading = ref(false)

const confirmVisible = ref(false)
const confirmContent = ref('')
const pendingDeleteId = ref(null)

const previewVisible = ref(false)
const previewDoc = ref({})
const previewContent = ref('')

const splitterMap = {
  token: 'Token 切分',
  recursive: '递归切分',
  paragraph: '段落切分',
  sentence: '句子切分',
  line: '行切分',
  semantic: '语义切分'
}

onMounted(async () => {
  await loadKnowledgeList()
  const kbId = route.query.knowledgeId
  if (kbId) {
    selectedId.value = String(kbId)
    await loadList()
  }
})

async function loadKnowledgeList() {
  try {
    const res = await listKnowledge({ pageNum: 1, pageSize: 100 })
    knowledgeList.value = res.list || []
    if (!selectedId.value && knowledgeList.value.length) {
      selectedId.value = String(knowledgeList.value[0].id)
      await loadList()
    }
  } catch (e) {
    alert('加载知识库列表失败：' + (e.message || e))
  }
}

function onKbChange() {
  pagination.value.pageNum = 1
  loadList()
}

function currentKbId() {
  return selectedId.value || null
}

function statusLabel(status) {
  switch (status) {
    case 0: return '失败'
    case 1: return '成功'
    case 2: return '处理中'
    default: return '未知'
  }
}

function statusClass(status) {
  switch (status) {
    case 0: return 'status-fail'
    case 1: return 'status-success'
    case 2: return 'status-processing'
    default: return 'status-unknown'
  }
}

function splitterLabel(splitter) {
  return splitterMap[splitter] || splitter || '-'
}

function formatSize(bytes) {
  if (bytes == null) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB'
}

function formatTime(time) {
  if (!time) return '-'
  const date = new Date(time)
  if (isNaN(date.getTime())) return time
  const pad = n => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

async function loadList() {
  const kbId = currentKbId()
  if (!kbId) return
  loading.value = true
  try {
    const res = await listDocuments(kbId, {
      pageNum: pagination.value.pageNum,
      pageSize: pagination.value.pageSize,
      keyword: searchName.value || undefined
    })
    docList.value = res.list || []
    pagination.value = res
  } catch (e) {
    alert('加载文档列表失败：' + (e.message || e))
  } finally {
    loading.value = false
  }
}

function onSearch() {
  pagination.value.pageNum = 1
  loadList()
}

function onPageChange(pageNum, pageSize) {
  pagination.value.pageNum = pageNum
  pagination.value.pageSize = pageSize
  loadList()
}

function selectFile() {
  fileInput.value?.click()
}

async function onFileSelected(e) {
  const file = e.target.files?.[0]
  if (!file) return
  const kbId = currentKbId()
  if (!kbId) {
    alert('请先选择知识库')
    return
  }

  // 读取当前表单中的切分方式（由父组件通过 provide/inject 或 query 维护，默认 token）
  const splitter = localStorage.getItem('last-selected-splitter') || 'token'

  uploading.value = true
  try {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('splitter', splitter)
    await uploadDocument(kbId, formData)
    await loadList()
  } catch (err) {
    alert('上传失败：' + (err.message || err))
  } finally {
    uploading.value = false
    e.target.value = ''
  }
}

function requestDelete(doc) {
  pendingDeleteId.value = doc.id
  confirmContent.value = `确定删除文档「${doc.fileName}」吗？删除后将同步移除向量库中的分片。`
  confirmVisible.value = true
}

async function confirmDelete() {
  const kbId = currentKbId()
  if (!kbId) return
  try {
    await deleteDocument(kbId, pendingDeleteId.value)
    await loadList()
  } catch (e) {
    alert('删除失败：' + (e.message || e))
  } finally {
    pendingDeleteId.value = null
    confirmVisible.value = false
  }
}

function openPreview(doc) {
  previewDoc.value = doc
  previewContent.value = ''
  previewVisible.value = true
  loadPreviewContent(doc.id)
}

async function loadPreviewContent(docId) {
  const kbId = currentKbId()
  if (!kbId) return
  try {
    previewContent.value = await previewDocument(kbId, docId)
  } catch (e) {
    previewContent.value = '预览失败：' + (e.message || e)
  }
}

function closePreview() {
  previewVisible.value = false
  previewDoc.value = {}
  previewContent.value = ''
}
</script>

<style scoped>
.upload-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
  flex-shrink: 0;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-main);
  margin: 0;
}

.header-title {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.kb-select {
  height: 34px;
  padding: 0 30px 0 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 13px;
  color: var(--text-main);
  background: #fff;
  outline: none;
  cursor: pointer;
  max-width: 220px;
}

.kb-select:focus {
  border-color: #4f66f9;
}

.kb-select:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
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
  border-color: #4f66f9;
}

.search-btn,
.upload-btn {
  border: none;
  border-radius: 8px;
  padding: 8px 16px;
  font-size: 13px;
  cursor: pointer;
}

.search-btn {
  background: #eef0ff;
  color: #4f66f9;
}

.upload-btn {
  background: linear-gradient(135deg, #4f66f9, #7b8cff);
  color: #fff;
}

.doc-table-wrap {
  flex: 1;
  overflow: auto;
  padding: 0 24px 16px;
  position: relative;
}

.doc-table {
  width: 100%;
  border-collapse: collapse;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  overflow: hidden;
  font-size: 13px;
}

.doc-table th,
.doc-table td {
  padding: 12px 16px;
  text-align: left;
  border-bottom: 1px solid var(--border);
  vertical-align: middle;
}

.doc-table thead {
  background: #f7f8fc;
}

.doc-table th {
  font-weight: 600;
  color: var(--text-main);
  white-space: nowrap;
}

.doc-table tbody tr:last-child td {
  border-bottom: none;
}

.doc-table tbody tr:hover {
  background: #fafbff;
}

.col-name { width: auto; min-width: 180px; }
.col-size { width: 90px; }
.col-type { width: 120px; }
.col-status { width: 90px; }
.col-splitter { width: 110px; }
.col-time { width: 150px; }
.col-actions { width: 150px; white-space: nowrap; }

.cell-name {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-size,
.cell-type,
.cell-splitter,
.cell-time {
  color: var(--text-sub);
}

.status-tag {
  display: inline-block;
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 20px;
  font-weight: 500;
}

.status-success {
  background: #e6f7ec;
  color: #2e9e5b;
}

.status-fail {
  background: #fff0f0;
  color: #f44336;
}

.status-processing {
  background: #fff8e6;
  color: #f5a623;
}

.status-unknown {
  background: #f2f3f7;
  color: #8a91ad;
}

.cell-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.action-btn {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border: none;
  border-radius: 6px;
  padding: 5px 10px;
  font-size: 12px;
  line-height: 1;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
  background: #f2f3f7;
  color: var(--text-main);
  white-space: nowrap;
}

.action-btn:hover {
  background: #eef0ff;
  color: #4f66f9;
  transform: translateY(-1px);
  box-shadow: 0 2px 6px rgba(79, 102, 249, 0.12);
}

.action-btn.primary {
  background: #eef0ff;
  color: #4f66f9;
}

.action-btn.primary:hover {
  background: #4f66f9;
  color: #fff;
  box-shadow: 0 2px 8px rgba(79, 102, 249, 0.28);
}

.action-btn.danger {
  background: #fff0f0;
  color: #f44336;
}

.action-btn.danger:hover {
  background: #f44336;
  color: #fff;
  box-shadow: 0 2px 8px rgba(244, 67, 54, 0.28);
}

.btn-icon {
  font-size: 12px;
  line-height: 1;
  flex-shrink: 0;
}

.btn-text {
  font-size: 12px;
  white-space: nowrap;
}

.empty-state {
  text-align: center;
  color: #a0a6bd;
  font-size: 14px;
  padding: 60px 0;
}

.loading-mask {
  position: absolute;
  inset: 0;
  background: rgba(255, 255, 255, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #4f66f9;
  font-size: 14px;
}

.dialog-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 999;
}

.dialog {
  background: #fff;
  border-radius: 14px;
  width: 520px;
  max-width: calc(100vw - 48px);
  max-height: calc(100vh - 96px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.dialog.dialog-lg {
  width: 720px;
}

.dialog-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-main);
  padding: 18px 20px;
  border-bottom: 1px solid var(--border);
  margin: 0;
}

.dialog-body {
  padding: 20px;
  overflow-y: auto;
}

.preview-meta {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  margin-bottom: 20px;
  padding: 14px;
  background: #f7f8fc;
  border-radius: 10px;
}

.meta-row {
  display: flex;
  gap: 10px;
  font-size: 13px;
}

.meta-label {
  color: #8a91ad;
  white-space: nowrap;
}

.meta-value {
  color: var(--text-main);
  font-weight: 500;
}

.splitter-value {
  color: #4f66f9;
}

.preview-content {
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
}

.preview-label {
  padding: 10px 14px;
  background: #f7f8fc;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-main);
  border-bottom: 1px solid var(--border);
}

.preview-pre {
  margin: 0;
  padding: 14px;
  min-height: 120px;
  max-height: 360px;
  overflow-y: auto;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  background: #1e2235;
  color: #d5d9ea;
}

.preview-empty {
  padding: 40px;
  text-align: center;
  color: #a0a6bd;
  font-size: 13px;
}

.dialog-footer {
  padding: 14px 20px;
  border-top: 1px solid var(--border);
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.btn-default {
  border: none;
  border-radius: 8px;
  padding: 8px 18px;
  font-size: 13px;
  cursor: pointer;
  background: #f2f3f7;
  color: var(--text-main);
}
</style>
