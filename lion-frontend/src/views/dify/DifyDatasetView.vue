<template>
  <div class="dify-dataset">
    <!-- 左：知识库列表 -->
    <aside class="ds-side">
      <div class="side-head">
        <span class="side-title">知识库</span>
        <button class="btn-new" @click="showCreate = true">＋ 新建</button>
      </div>
      <input v-model="dsKeyword" class="side-search" placeholder="搜索知识库" @input="loadDatasets" />
      <div class="ds-list">
        <div
          v-for="d in datasets"
          :key="d.id"
          class="ds-item"
          :class="{ active: d.id === currentId }"
          @click="selectDataset(d.id)"
        >
          <div class="ds-name">{{ d.name }}</div>
          <div class="ds-desc">{{ d.description || '暂无描述' }}</div>
          <span class="ds-del" title="删除知识库" @click.stop="handleDeleteDataset(d)">🗑️</span>
        </div>
        <div v-if="!datasets.length" class="side-empty">暂无知识库</div>
      </div>
    </aside>

    <!-- 右：文档管理 -->
    <section class="doc-main">
      <div class="doc-head">
        <div class="head-left">
          <span class="doc-title">{{ currentName || '请选择知识库' }}</span>
          <input v-model="docKeyword" class="doc-search" placeholder="搜索文档" @input="loadDocuments" />
        </div>
        <div class="head-right">
          <button class="btn-outline" :disabled="!currentId" @click="triggerFile">📤 上传文件</button>
          <button class="btn-primary" :disabled="!currentId" @click="showTextDoc = true">📝 文本新建</button>
          <input ref="fileRef" type="file" class="hidden-file" @change="handleFileChange" />
        </div>
      </div>

      <table class="doc-table">
        <thead>
          <tr>
            <th>文档名称</th>
            <th style="width: 120px">状态</th>
            <th style="width: 90px">字数</th>
            <th style="width: 180px">更新时间</th>
            <th style="width: 80px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="doc in documents" :key="doc.id">
            <td :title="doc.name">{{ doc.name }}</td>
            <td>
              <span class="status" :class="statusClass(doc.indexingStatus)">
                {{ statusText(doc.indexingStatus) }}
              </span>
            </td>
            <td>{{ doc.wordCount ?? '-' }}</td>
            <td>{{ formatTime(doc.updatedAt) }}</td>
            <td>
              <span class="op-danger" @click="handleDeleteDocument(doc)">删除</span>
            </td>
          </tr>
          <tr v-if="!documents.length">
            <td colspan="5" class="table-empty">暂无文档</td>
          </tr>
        </tbody>
      </table>
    </section>

    <!-- 新建知识库弹窗 -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <div class="modal">
        <div class="modal-title">新建知识库</div>
        <input v-model="createForm.name" class="modal-input" placeholder="知识库名称" />
        <textarea v-model="createForm.description" class="modal-textarea" placeholder="描述（可选）" />
        <div class="modal-ops">
          <button class="btn-outline" @click="showCreate = false">取消</button>
          <button class="btn-primary" :disabled="!createForm.name.trim()" @click="handleCreate">确认</button>
        </div>
      </div>
    </div>

    <!-- 文本新建文档弹窗 -->
    <div v-if="showTextDoc" class="modal-mask" @click.self="showTextDoc = false">
      <div class="modal">
        <div class="modal-title">文本新建文档</div>
        <input v-model="textForm.name" class="modal-input" placeholder="文档名称" />
        <textarea v-model="textForm.text" class="modal-textarea big" placeholder="粘贴文档正文内容" />
        <div class="modal-ops">
          <button class="btn-outline" @click="showTextDoc = false">取消</button>
          <button
            class="btn-primary"
            :disabled="!textForm.name.trim() || !textForm.text.trim()"
            @click="handleCreateTextDoc"
          >确认</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import {
  pageDatasets,
  createDataset,
  deleteDataset,
  pageDocuments,
  createDocumentByText,
  createDocumentByFile,
  deleteDocument
} from '../../api/dify'

const datasets = ref([])
const currentId = ref('')
const currentName = ref('')
const documents = ref([])
const dsKeyword = ref('')
const docKeyword = ref('')
const showCreate = ref(false)
const showTextDoc = ref(false)
const createForm = ref({ name: '', description: '' })
const textForm = ref({ name: '', text: '' })
const fileRef = ref(null)

function formatTime(ts) {
  if (!ts) return '-'
  const d = new Date(Number(ts) * 1000)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function statusClass(status) {
  if (status === 'completed' || status === 'available') return 'ok'
  if (status === 'indexing' || status === 'splitting' || status === 'parsing') return 'running'
  if (status === 'error') return 'error'
  return 'wait'
}

function statusText(status) {
  const map = {
    waiting: '等待中',
    parsing: '解析中',
    splitting: '分段中',
    indexing: '索引中',
    completed: '已完成',
    available: '可用',
    error: '失败'
  }
  return map[status] || status || '-'
}

async function loadDatasets() {
  const res = await pageDatasets({ keyword: dsKeyword.value || undefined, page: 1, limit: 50 })
  const list = res?.data || []
  datasets.value = list
  if (list.length && !currentId.value) {
    selectDataset(list[0].id)
  }
}

function selectDataset(id) {
  currentId.value = id
  const hit = datasets.value.find((d) => d.id === id)
  currentName.value = hit?.name || ''
  docKeyword.value = ''
  loadDocuments()
}

async function loadDocuments() {
  if (!currentId.value) return
  const res = await pageDocuments(currentId.value, { keyword: docKeyword.value || undefined, page: 1, limit: 50 })
  documents.value = res?.data || []
}

async function handleCreate() {
  await createDataset({ name: createForm.value.name, description: createForm.value.description })
  createForm.value = { name: '', description: '' }
  showCreate.value = false
  await loadDatasets()
}

async function handleDeleteDataset(ds) {
  if (!window.confirm(`确认删除知识库「${ds.name}」及其全部文档？`)) return
  await deleteDataset(ds.id)
  if (currentId.value === ds.id) {
    currentId.value = ''
    documents.value = []
  }
  await loadDatasets()
}

function triggerFile() {
  fileRef.value?.click()
}

async function handleFileChange(e) {
  const file = e.target.files?.[0]
  if (!file || !currentId.value) return
  try {
    await createDocumentByFile(currentId.value, file)
    await loadDocuments()
  } catch (err) {
    window.alert('上传失败：' + (err.message || err))
  } finally {
    e.target.value = ''
  }
}

async function handleCreateTextDoc() {
  await createDocumentByText(currentId.value, { name: textForm.value.name, text: textForm.value.text })
  textForm.value = { name: '', text: '' }
  showTextDoc.value = false
  await loadDocuments()
}

async function handleDeleteDocument(doc) {
  if (!window.confirm(`确认删除文档「${doc.name}」？`)) return
  await deleteDocument(currentId.value, doc.id)
  await loadDocuments()
}

onMounted(() => {
  loadDatasets()
})
</script>

<style scoped>
.dify-dataset {
  height: 100%;
  display: flex;
  gap: 12px;
  padding: 14px;
  background: #f7f8fc;
}

.ds-side {
  width: 280px;
  min-width: 280px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.side-head {
  height: 54px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 14px;
  border-bottom: 1px solid var(--border);
}

.side-title {
  font-size: 15px;
  font-weight: 600;
}

.btn-new {
  border: none;
  background: #4f66f9;
  color: #fff;
  font-size: 12px;
  padding: 6px 10px;
  border-radius: 6px;
  cursor: pointer;
}

.side-search,
.doc-search {
  margin: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 7px 10px;
  font-size: 13px;
  outline: none;
}

.doc-search {
  margin: 0;
  width: 200px;
}

.side-search:focus,
.doc-search:focus {
  border-color: #4f66f9;
}

.ds-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px 8px 10px;
}

.ds-item {
  position: relative;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
}

.ds-item:hover {
  background: #f3f5fb;
}

.ds-item.active {
  background: rgba(79, 102, 249, 0.12);
}

.ds-name {
  font-size: 13px;
  color: var(--text-main);
  font-weight: 500;
  padding-right: 30px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ds-desc {
  font-size: 11px;
  color: var(--text-sub);
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ds-del {
  position: absolute;
  right: 10px;
  top: 10px;
  font-size: 13px;
  display: none;
}

.ds-item:hover .ds-del {
  display: block;
}

.side-empty {
  text-align: center;
  font-size: 12px;
  color: var(--text-sub);
  padding: 14px 0;
}

/* ============ 文档区 ============ */
.doc-main {
  flex: 1;
  min-width: 0;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.doc-head {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 14px;
  border-bottom: 1px solid var(--border);
}

.head-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.doc-title {
  font-size: 15px;
  font-weight: 600;
}

.head-right {
  display: flex;
  gap: 8px;
}

.btn-outline,
.btn-primary {
  font-size: 13px;
  padding: 7px 12px;
  border-radius: 6px;
  cursor: pointer;
}

.btn-outline {
  border: 1px solid var(--border);
  background: #fff;
  color: var(--text-main);
}

.btn-primary {
  border: none;
  background: #4f66f9;
  color: #fff;
}

.btn-primary:disabled,
.btn-outline:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.hidden-file {
  display: none;
}

.doc-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.doc-table th {
  background: #fafbfd;
  text-align: left;
  padding: 10px 14px;
  color: var(--text-sub);
  font-weight: 500;
  border-bottom: 1px solid var(--border);
}

.doc-table td {
  padding: 10px 14px;
  border-bottom: 1px solid #f0f1f5;
  color: var(--text-main);
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-empty {
  text-align: center;
  color: var(--text-sub);
  padding: 24px 0;
}

.status {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 10px;
}

.status.ok {
  background: #eafaf1;
  color: #1f9d55;
}

.status.running {
  background: #eef1ff;
  color: #4f66f9;
}

.status.error {
  background: #fdeeee;
  color: #d9534f;
}

.status.wait {
  background: #f2f3f7;
  color: #888;
}

.op-danger {
  color: #d9534f;
  cursor: pointer;
}

/* ============ 弹窗 ============ */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal {
  width: 460px;
  background: #fff;
  border-radius: 10px;
  padding: 18px;
}

.modal-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 12px;
}

.modal-input,
.modal-textarea {
  width: 100%;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 13px;
  outline: none;
  margin-bottom: 10px;
}

.modal-textarea {
  height: 100px;
  resize: vertical;
}

.modal-textarea.big {
  height: 200px;
}

.modal-ops {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
