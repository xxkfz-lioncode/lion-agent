<template>
  <div class="manage-page">
    <header class="page-header">
      <h2 class="page-title">敏感词管理</h2>
      <div class="header-actions">
        <input
          v-model="keyword"
          class="search-input"
          type="text"
          placeholder="搜索敏感词"
          @keyup.enter="onSearch"
        >
        <select v-model="enabledFilter" class="filter-select">
          <option value="">全部状态</option>
          <option value="true">仅启用</option>
          <option value="false">仅停用</option>
        </select>
        <button class="refresh-btn" @click="onSearch">查询</button>
        <button class="action-btn" @click="openImport">批量导入</button>
        <button class="action-btn" @click="openCheck">试一试</button>
        <button class="create-btn" @click="openDialog()">+ 新增敏感词</button>
      </div>
    </header>

    <div class="manage-body">
      <div class="tip-bar">
        命中规则：<b>忽略大小写 + 包含匹配</b>；停用状态的词不参与拦截。词表修改后立即生效，无需重启服务。
      </div>

      <div v-if="list.length === 0 && !loading" class="empty">
        暂无敏感词，点击右上角「新增敏感词」或「批量导入」添加
      </div>

      <table v-else class="word-table">
        <thead>
          <tr>
            <th style="width: 60px">ID</th>
            <th>敏感词</th>
            <th style="width: 110px">分类</th>
            <th style="width: 90px">状态</th>
            <th>备注</th>
            <th style="width: 170px">更新时间</th>
            <th style="width: 200px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in list" :key="item.id">
            <td class="col-id">{{ item.id }}</td>
            <td class="col-word">{{ item.word }}</td>
            <td>{{ categoryLabel(item.category) }}</td>
            <td>
              <span class="badge" :class="item.enabled ? 'on' : 'off'">
                {{ item.enabled ? '启用' : '停用' }}
              </span>
            </td>
            <td class="col-remark">{{ item.remark || '-' }}</td>
            <td class="col-time">{{ item.updatedAt }}</td>
            <td>
              <button class="action-btn" @click="toggleRow(item)">
                {{ item.enabled ? '停用' : '启用' }}
              </button>
              <button class="action-btn" @click="openDialog(item)">编辑</button>
              <button class="action-btn danger" @click="removeRow(item)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <PaginationBar
      v-if="pagination.total > 0"
      :page-num="pagination.pageNum"
      :page-size="pagination.pageSize"
      :pages="pagination.pages"
      :total="pagination.total"
      @change="onPageChange"
    />

    <!-- 新增 / 编辑弹窗 -->
    <div v-if="dialogVisible" class="dialog-mask" @click.self="closeDialog">
      <div class="dialog">
        <h3 class="dialog-title">{{ isEdit ? '编辑敏感词' : '新增敏感词' }}</h3>
        <div class="dialog-body">
          <div class="form-item">
            <label>敏感词</label>
            <input v-model="form.word" class="form-input" placeholder="如：违禁词（最长 128 字符）" />
          </div>
          <div class="form-item">
            <label>分类</label>
            <select v-model="form.category" class="form-input">
              <option v-for="c in categories" :key="c.value" :value="c.value">{{ c.label }}</option>
            </select>
          </div>
          <div class="form-item">
            <label>备注</label>
            <input v-model="form.remark" class="form-input" placeholder="选填，说明该词的用途或来源" />
          </div>
          <div class="form-item inline">
            <label class="checkbox-label">
              <input v-model="form.enabled" type="checkbox" /> 启用（停用后仅保留记录，不参与拦截）
            </label>
          </div>
        </div>
        <div class="dialog-footer">
          <button class="btn-default" @click="closeDialog">取消</button>
          <button class="btn-primary" :disabled="!form.word.trim() || saving" @click="save">
            {{ saving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 批量导入弹窗 -->
    <div v-if="importVisible" class="dialog-mask" @click.self="closeImport">
      <div class="dialog dialog-lg">
        <h3 class="dialog-title">批量导入敏感词</h3>
        <div class="dialog-body">
          <div class="form-item">
            <label>敏感词列表（每行一个，也支持逗号分隔；已存在的词自动跳过）</label>
            <textarea
              v-model="importText"
              class="form-textarea"
              placeholder="违禁词1&#10;违禁词2&#10;违禁词3,违禁词4"
            ></textarea>
          </div>
          <div class="form-row">
            <div class="form-item">
              <label>分类</label>
              <select v-model="importCategory" class="form-input">
                <option v-for="c in categories" :key="c.value" :value="c.value">{{ c.label }}</option>
              </select>
            </div>
            <div class="form-item inline">
              <label class="checkbox-label">
                <input v-model="importEnabled" type="checkbox" /> 导入后启用
              </label>
            </div>
          </div>
          <div v-if="importResult" class="import-result">导入完成：新增 {{ importResult }} 个敏感词</div>
        </div>
        <div class="dialog-footer">
          <button class="btn-default" @click="closeImport">关闭</button>
          <button class="btn-primary" :disabled="!importWords.length || importing" @click="doImport">
            {{ importing ? '导入中...' : '导入' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 命中检测弹窗 -->
    <div v-if="checkVisible" class="dialog-mask" @click.self="closeCheck">
      <div class="dialog">
        <h3 class="dialog-title">命中检测（试一试）</h3>
        <div class="dialog-body">
          <div class="form-item">
            <label>待检测文本</label>
            <textarea
              v-model="checkText"
              class="form-textarea"
              placeholder="输入一段话，验证它会不会被拦截（不落库、不调用模型）"
            ></textarea>
          </div>
          <div v-if="checkResult" class="check-result" :class="checkResult.hit ? 'hit' : 'pass'">
            <template v-if="checkResult.hit">
              <div class="result-title">⚠️ 会被拦截</div>
              <div class="result-words">命中敏感词：{{ checkResult.hitWords.join('、') }}</div>
            </template>
            <template v-else>
              <div class="result-title">✅ 不会被拦截</div>
            </template>
          </div>
        </div>
        <div class="dialog-footer">
          <button class="btn-default" @click="closeCheck">关闭</button>
          <button class="btn-primary" :disabled="!checkText.trim() || checking" @click="doCheck">
            {{ checking ? '检测中...' : '检测' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import PaginationBar from '../../components/PaginationBar.vue'
import {
  listSensitiveWords,
  createSensitiveWord,
  updateSensitiveWord,
  deleteSensitiveWord,
  toggleSensitiveWord,
  batchImportSensitiveWords,
  checkSensitiveWord
} from '../../api/sensitive-word'

const categories = [
  { value: 'custom', label: '自定义' },
  { value: 'politics', label: '政治' },
  { value: 'porn', label: '色情' },
  { value: 'violence', label: '暴力' },
  { value: 'abuse', label: '辱骂' }
]

const list = ref([])
const loading = ref(false)
const keyword = ref('')
const enabledFilter = ref('')
const pagination = ref({ pageNum: 1, pageSize: 10, total: 0, pages: 0 })

function categoryLabel(value) {
  const hit = categories.find(c => c.value === value)
  return hit ? hit.label : (value || '自定义')
}

async function loadList() {
  loading.value = true
  try {
    const params = { pageNum: pagination.value.pageNum, pageSize: pagination.value.pageSize }
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    if (enabledFilter.value !== '') params.enabled = enabledFilter.value
    const res = await listSensitiveWords(params)
    list.value = res?.list || []
    pagination.value = {
      pageNum: res?.pageNum || pagination.value.pageNum,
      pageSize: res?.pageSize || pagination.value.pageSize,
      total: res?.total || 0,
      pages: res?.pages || 0
    }
  } catch (e) {
    alert(e.message || '加载敏感词列表失败')
  } finally {
    loading.value = false
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

// ==================== 新增 / 编辑 ====================

const dialogVisible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const form = ref(emptyForm())

function emptyForm() {
  return { id: null, word: '', category: 'custom', enabled: true, remark: '' }
}

function openDialog(item) {
  isEdit.value = !!item
  form.value = item
    ? { id: item.id, word: item.word, category: item.category || 'custom', enabled: !!item.enabled, remark: item.remark || '' }
    : emptyForm()
  dialogVisible.value = true
}

function closeDialog() {
  dialogVisible.value = false
}

async function save() {
  saving.value = true
  try {
    const payload = {
      word: form.value.word.trim(),
      category: form.value.category,
      enabled: form.value.enabled,
      remark: form.value.remark
    }
    if (isEdit.value) {
      await updateSensitiveWord(form.value.id, payload)
    } else {
      await createSensitiveWord(payload)
    }
    closeDialog()
    loadList()
  } catch (e) {
    alert(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

// ==================== 行操作 ====================

async function toggleRow(item) {
  try {
    await toggleSensitiveWord(item.id, !item.enabled)
    loadList()
  } catch (e) {
    alert(e.message || '操作失败')
  }
}

async function removeRow(item) {
  if (!window.confirm(`确定删除敏感词「${item.word}」？`)) return
  try {
    await deleteSensitiveWord(item.id)
    loadList()
  } catch (e) {
    alert(e.message || '删除失败')
  }
}

// ==================== 批量导入 ====================

const importVisible = ref(false)
const importText = ref('')
const importCategory = ref('custom')
const importEnabled = ref(true)
const importing = ref(false)
const importResult = ref(null)

const importWords = computed(() => {
  if (!importText.value.trim()) return []
  return importText.value
    .split(/[\n,，;；]+/)
    .map(w => w.trim())
    .filter(Boolean)
})

function openImport() {
  importText.value = ''
  importCategory.value = 'custom'
  importEnabled.value = true
  importResult.value = null
  importVisible.value = true
}

function closeImport() {
  importVisible.value = false
}

async function doImport() {
  importing.value = true
  try {
    const added = await batchImportSensitiveWords(importWords.value, importCategory.value, importEnabled.value)
    importResult.value = added || 0
    loadList()
  } catch (e) {
    alert(e.message || '导入失败')
  } finally {
    importing.value = false
  }
}

// ==================== 命中检测 ====================

const checkVisible = ref(false)
const checkText = ref('')
const checkResult = ref(null)
const checking = ref(false)

function openCheck() {
  checkText.value = ''
  checkResult.value = null
  checkVisible.value = true
}

function closeCheck() {
  checkVisible.value = false
}

async function doCheck() {
  checking.value = true
  try {
    checkResult.value = await checkSensitiveWord(checkText.value.trim())
  } catch (e) {
    alert(e.message || '检测失败')
  } finally {
    checking.value = false
  }
}

onMounted(loadList)
</script>

<style scoped>
.manage-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #f7f8fc;
}

.page-header {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 24px;
  background: #fff;
  border-bottom: 1px solid var(--border);
}

.page-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--text-main);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.search-input,
.filter-select,
.form-input {
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 13px;
  color: var(--text-main);
  outline: none;
}

.search-input {
  width: 180px;
}

.search-input:focus,
.filter-select:focus,
.form-input:focus {
  border-color: var(--primary);
}

.refresh-btn,
.create-btn,
.action-btn {
  height: 34px;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: #fff;
  font-size: 13px;
  color: var(--text-main);
  cursor: pointer;
  transition: all 0.2s;
}

.refresh-btn:hover,
.action-btn:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.create-btn {
  background: var(--primary);
  border-color: var(--primary);
  color: #fff;
}

.create-btn:hover {
  opacity: 0.9;
}

.action-btn {
  height: 28px;
  padding: 0 10px;
  font-size: 12px;
}

.action-btn.danger {
  color: #e05a5a;
}

.action-btn.danger:hover {
  border-color: #e05a5a;
  color: #e05a5a;
}

.manage-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px 24px;
}

.tip-bar {
  margin-bottom: 12px;
  padding: 10px 14px;
  background: #eef1ff;
  border: 1px solid #d8defa;
  border-radius: 8px;
  font-size: 13px;
  color: #4a587f;
}

.empty {
  padding: 48px 0;
  text-align: center;
  color: var(--text-sub);
  font-size: 14px;
}

.word-table {
  width: 100%;
  border-collapse: collapse;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
  font-size: 13px;
}

.word-table th,
.word-table td {
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
  text-align: left;
  color: var(--text-main);
}

.word-table th {
  background: #fafbff;
  font-weight: 600;
  color: #555;
}

.word-table tr:last-child td {
  border-bottom: none;
}

.col-id,
.col-time {
  color: var(--text-sub);
}

.col-word {
  font-weight: 600;
}

.col-remark {
  color: var(--text-sub);
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 12px;
}

.badge.on {
  background: #e8f7ee;
  color: #2e9e5b;
}

.badge.off {
  background: #f1f1f3;
  color: #999;
}

.dialog-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.dialog {
  width: 460px;
  max-height: 86vh;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
}

.dialog-lg {
  width: 620px;
}

.dialog-title {
  margin: 0;
  padding: 16px 20px;
  font-size: 16px;
  font-weight: 600;
  border-bottom: 1px solid var(--border);
}

.dialog-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px 20px;
}

.form-item {
  margin-bottom: 14px;
}

.form-item.inline {
  margin-bottom: 0;
}

.form-row {
  display: flex;
  gap: 16px;
  align-items: center;
}

.form-item label {
  display: block;
  margin-bottom: 6px;
  font-size: 13px;
  color: #555;
}

.form-input {
  width: 100%;
  box-sizing: border-box;
}

.form-textarea {
  width: 100%;
  box-sizing: border-box;
  min-height: 120px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 13px;
  font-family: inherit;
  resize: vertical;
  outline: none;
}

.form-textarea:focus {
  border-color: var(--primary);
}

.checkbox-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #555;
  cursor: pointer;
}

.import-result,
.check-result {
  margin-top: 6px;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 13px;
}

.import-result {
  background: #eef1ff;
  color: #44507a;
}

.check-result.hit {
  background: #fdeeee;
  color: #c94a4a;
}

.check-result.pass {
  background: #e8f7ee;
  color: #2e9e5b;
}

.result-title {
  font-weight: 600;
  margin-bottom: 4px;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 12px 20px;
  border-top: 1px solid var(--border);
}

.btn-default,
.btn-primary {
  height: 34px;
  padding: 0 16px;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  border: 1px solid var(--border);
  background: #fff;
  color: var(--text-main);
}

.btn-primary {
  background: var(--primary);
  border-color: var(--primary);
  color: #fff;
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
