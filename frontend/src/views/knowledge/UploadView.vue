<template>
  <div class="upload-page">
    <header class="page-header">
      <h2 class="page-title">知识库上传</h2>
    </header>

    <div class="upload-body">
      <div class="form-card">
        <div class="form-item">
          <label>选择知识库</label>
          <select v-model="selectedId" class="form-select">
            <option value="">请选择</option>
            <option v-for="kb in knowledgeList" :key="kb.id" :value="String(kb.id)">
              {{ kb.name }}
            </option>
          </select>
        </div>

        <div class="form-item splitter-item">
          <label>切分方式</label>
          <select v-model="splitter" class="form-select">
            <option value="token" title="按 Token 数量切分（默认策略），块大小可控，适合大多数通用文档">Token 切分（默认）</option>
            <option value="recursive" title="按 段落 → 句子 → 短句 → 空白 多级递归切分，尽量保留语义边界">递归切分（保留语义边界）</option>
            <option value="paragraph" title="以连续空行（段落边界）为切分点，适合段落结构清晰的文档">段落切分</option>
            <option value="sentence" title="以中英文句末标点为边界切分，适合需要细粒度检索的场景">句子切分</option>
            <option value="line" title="以换行为边界，适合日志、列表、代码等结构化文本">按行切分</option>
            <option value="semantic" title="基于 embedding 计算相邻句语义相似度，在语义断裂处切分；需要调用向量模型，较慢，适合长文">语义切分（较慢，适合长文）</option>
          </select>
          <p class="upload-hint">鼠标悬浮在选项上查看详细说明；默认 Token 切分兼顾性能与通用性</p>
        </div>

        <div class="form-item">
          <label>上传文件</label>
          <div class="upload-zone" @click="triggerFile" @drop.prevent="onDrop" @dragover.prevent>
            <input ref="fileInput" type="file" accept=".txt,.md,.pdf,.doc,.docx" hidden @change="onFileChange">
            <p v-if="!selectedFile" class="upload-tip">点击或拖拽文件至此处</p>
            <p v-else class="upload-file">{{ selectedFile.name }}</p>
          </div>
          <p class="upload-hint">支持 txt、md、pdf、doc、docx</p>
        </div>

        <button class="submit-btn" :disabled="!canSubmit || uploading" @click="submit">
          {{ uploading ? '上传中...' : '开始上传' }}
        </button>
      </div>

      <div class="doc-list">
        <div class="doc-list-header">
          <h3>已上传文档</h3>
          <div class="doc-list-actions">
            <input
              v-model="keyword"
              class="doc-search"
              type="text"
              placeholder="搜索文档名称"
              @keyup.enter="onSearch"
            >
            <button class="doc-query-btn" :disabled="!selectedId" @click="onSearch">查询</button>
          </div>
        </div>
        <div v-if="documents.length === 0" class="empty">{{ keyword ? '未找到匹配的文档' : '暂无文档' }}</div>
        <div v-for="doc in documents" :key="doc.id" class="doc-item">
          <div class="doc-info">
            <div class="doc-name-row">
              <span class="doc-name">{{ doc.fileName }}</span>
              <span class="doc-status" :class="statusClass(doc.status)">{{ statusText(doc.status) }}</span>
            </div>
            <div class="doc-meta">
              <span class="doc-size">{{ formatSize(doc.fileSize) }}</span>
              <span v-if="doc.fileType" class="doc-type">{{ doc.fileType }}</span>
              <span v-if="doc.filePath" class="doc-path" :title="doc.filePath">{{ doc.filePath }}</span>
            </div>
            <div v-if="doc.status === 0 && doc.failReason" class="doc-fail-reason" :title="doc.failReason">
              {{ doc.failReason }}
            </div>
          </div>
          <button class="doc-delete" @click="removeDoc(doc.id)">删除</button>
        </div>
      </div>
    </div>
  </div>

  <ConfirmDialog
    v-model="confirmVisible"
    :title="confirmConfig.title"
    :content="confirmConfig.content"
    :confirm-text="confirmConfig.confirmText"
    :cancel-text="confirmConfig.cancelText"
    :loading="deletingDoc"
    @confirm="onConfirmDeleteDoc"
  />

  <PaginationBar
    :page-num="pagination.pageNum"
    :page-size="pagination.pageSize"
    :pages="pagination.pages"
    :total="pagination.total"
    @change="onPageChange"
  />
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { listKnowledge, listDocuments, uploadDocument, deleteDocument } from '../../api/knowledge'
import ConfirmDialog from '../../components/ConfirmDialog.vue'
import PaginationBar from '../../components/PaginationBar.vue'

const knowledgeList = ref([])
const selectedId = ref('')
const splitter = ref('token')
const documents = ref([])
const selectedFile = ref(null)
const fileInput = ref(null)
const uploading = ref(false)
const deletingDoc = ref(false)
const deletingDocId = ref(null)
const confirmVisible = ref(false)
const confirmConfig = ref({ title: '', content: '', confirmText: '确定', cancelText: '取消' })
const keyword = ref('')
const pagination = ref({ pageNum: 1, pageSize: 10, pages: 1, total: 0 })

const canSubmit = computed(() => selectedId.value && selectedFile.value)

onMounted(async () => {
  await loadKnowledgeList()
  // 默认选中第一个知识库并自动查询文档列表
  if (knowledgeList.value.length > 0 && !selectedId.value) {
    selectedId.value = knowledgeList.value[0].id
  }
})

watch(selectedId, async (id) => {
  if (!id) {
    documents.value = []
    return
  }
  pagination.value.pageNum = 1
  await queryDocuments()
})

async function loadKnowledgeList() {
  try {
    const res = await listKnowledge()
    knowledgeList.value = res.list || []
  } catch (e) {
    alert('加载知识库失败')
  }
}

async function queryDocuments() {
  if (!selectedId.value) return
  try {
    const res = await listDocuments(selectedId.value, {
      pageNum: pagination.value.pageNum,
      pageSize: pagination.value.pageSize,
      keyword: keyword.value
    })
    documents.value = res.list || []
    pagination.value = {
      pageNum: Number(res.pageNum) || 1,
      pageSize: Number(res.pageSize) || 10,
      pages: Number(res.pages) || 1,
      total: Number(res.total) || 0
    }
  } catch (e) {
    alert('加载文档失败')
  }
}

function onSearch() {
  pagination.value.pageNum = 1
  queryDocuments()
}

function onPageChange(page) {
  pagination.value.pageNum = page
  queryDocuments()
}

function triggerFile() {
  fileInput.value.click()
}

function onDrop(e) {
  const files = e.dataTransfer.files
  if (files.length) selectedFile.value = files[0]
}

function onFileChange(e) {
  const files = e.target.files
  if (files.length) selectedFile.value = files[0]
}

async function submit() {
  if (!canSubmit.value) return
  uploading.value = true
  try {
    const formData = new FormData()
    formData.append('file', selectedFile.value)
    formData.append('splitter', splitter.value)
    await uploadDocument(selectedId.value, formData)
    selectedFile.value = null
    fileInput.value.value = ''
    await queryDocuments()
  } catch (e) {
    alert('上传失败')
  } finally {
    uploading.value = false
  }
}

function removeDoc(docId) {
  deletingDocId.value = docId
  confirmConfig.value = {
    title: '删除文档',
    content: '确定删除该文档吗？文档删除后无法恢复。',
    confirmText: '删除',
    cancelText: '取消'
  }
  confirmVisible.value = true
}

async function onConfirmDeleteDoc() {
  if (!deletingDocId.value || !selectedId.value) return
  deletingDoc.value = true
  try {
    await deleteDocument(selectedId.value, deletingDocId.value)
    confirmVisible.value = false
    documents.value = documents.value.filter(d => d.id !== deletingDocId.value)
  } catch (e) {
    alert('删除失败')
  } finally {
    deletingDoc.value = false
    deletingDocId.value = null
  }
}

function formatSize(bytes) {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

function statusText(status) {
  const map = { 0: '失败', 1: '成功', 2: '处理中' }
  return map[status] ?? '未知'
}

function statusClass(status) {
  return {
    'status-success': status === 1,
    'status-fail': status === 0,
    'status-processing': status === 2
  }
}
</script>

<style scoped>
.upload-page {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.page-header {
  height: 64px;
  display: flex;
  align-items: center;
  padding: 0 24px;
  border-bottom: 1px solid var(--border);
}

.page-title {
  font-size: 16px;
  font-weight: 600;
}

.upload-body {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  display: flex;
  gap: 24px;
}

.form-card {
  width: 420px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 24px;
  align-self: flex-start;
}

.form-item {
  margin-bottom: 20px;
}

.form-item label {
  display: block;
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 8px;
  color: var(--text-main);
}

.form-select {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 14px;
}

.upload-zone {
  height: 140px;
  border: 2px dashed var(--border);
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;
}

.upload-zone:hover {
  border-color: var(--primary);
  background: #f9faff;
}

.upload-tip {
  color: var(--text-sub);
  font-size: 14px;
}

.upload-file {
  color: var(--primary);
  font-size: 14px;
}

.upload-hint {
  font-size: 12px;
  color: var(--text-sub);
  margin-top: 8px;
}

.form-select option {
  padding: 4px 8px;
}

.splitter-item .form-select option:hover {
  cursor: help;
}

.submit-btn {
  width: 100%;
  padding: 12px;
  border: none;
  border-radius: 8px;
  background: var(--primary);
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}

.submit-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.doc-list {
  flex: 1;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 24px;
}

.doc-list-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  gap: 16px;
}

.doc-list h3 {
  margin: 0;
  font-size: 15px;
}

.doc-list-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.doc-search {
  width: 180px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 14px;
  outline: none;
}

.doc-search:focus {
  border-color: var(--primary);
}

.doc-query-btn {
  padding: 8px 16px;
  border: none;
  border-radius: 8px;
  background: var(--primary);
  color: #fff;
  font-size: 14px;
  cursor: pointer;
  white-space: nowrap;
  transition: opacity 0.2s;
}

.doc-query-btn:hover:not(:disabled) {
  opacity: 0.9;
}

.doc-query-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.empty {
  color: var(--text-sub);
  font-size: 14px;
  text-align: center;
  padding: 40px 0;
}

.doc-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px;
  border-bottom: 1px solid #f0f0f0;
}

.doc-info {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  flex: 1;
  margin-right: 16px;
}

.doc-name-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.doc-name {
  font-size: 14px;
  font-weight: 500;
}

.doc-status {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 12px;
  line-height: 1.4;
  white-space: nowrap;
}

.status-success {
  background: #dcfce7;
  color: #15803d;
}

.status-fail {
  background: #fee2e2;
  color: #b91c1c;
}

.status-processing {
  background: #e0e7ff;
  color: #4338ca;
}

.doc-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: var(--text-sub);
}

.doc-size {
  min-width: 50px;
}

.doc-type {
  padding: 1px 6px;
  border: 1px solid var(--border);
  border-radius: 4px;
  font-size: 11px;
}

.doc-path {
  max-width: 400px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-fail-reason {
  font-size: 12px;
  color: var(--danger);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-delete {
  border: none;
  background: transparent;
  color: var(--danger);
  cursor: pointer;
  font-size: 13px;
}
</style>
