<template>
  <div class="manage-page">
    <header class="page-header">
      <h2 class="page-title">技能管理</h2>
      <div class="header-actions">
        <input v-model="keyword" class="search-input" type="text" placeholder="搜索技能名称/描述" @keyup.enter="onSearch">
        <button class="refresh-btn" @click="onSearch">查询</button>
        <button class="create-btn" @click="openDialog()">+ 新建技能</button>
      </div>
    </header>

    <div class="manage-body">
      <div v-if="skillList.length === 0 && !loading" class="empty">
        暂无技能，点击右上角「新建技能」创建；技能会按描述动态注册给对话模型
      </div>
      <div v-for="skill in skillList" :key="skill.id" class="skill-card">
        <div class="skill-main">
          <div class="skill-icon">🧩</div>
          <div class="skill-info">
            <h3 class="skill-name">
              {{ skill.name }}
              <span class="skill-badge" :class="skill.status === 1 ? 'on' : 'off'">
                {{ skill.status === 1 ? '启用' : '禁用' }}
              </span>
            </h3>
            <p class="skill-desc">{{ skill.description || '暂无描述' }}</p>
            <p class="skill-meta">
              参数 {{ paramCount(skill) }} 个 · 更新于 {{ skill.updatedAt }}
            </p>
          </div>
        </div>
        <div class="skill-actions">
          <button class="action-btn" @click="toggleStatus(skill)">
            {{ skill.status === 1 ? '停用' : '启用' }}
          </button>
          <button class="action-btn" @click="openTest(skill)">试跑</button>
          <button class="action-btn" @click="openExport(skill)">导出</button>
          <button class="action-btn" @click="openDialog(skill)">编辑</button>
          <button class="action-btn danger" @click="requestDelete(skill)">删除</button>
        </div>
      </div>
    </div>

    <PaginationBar
      v-if="pagination.total > 0"
      :page-num="pagination.pageNum"
      :page-size="pagination.pageSize"
      :pages="pagination.pages"
      :total="pagination.total"
      @change="onPageChange"
    />

    <!-- 新建/编辑技能弹窗 -->
    <div v-if="dialogVisible" class="dialog-mask" @click.self="closeDialog">
      <div class="dialog dialog-lg">
        <h3 class="dialog-title">{{ isEdit ? '编辑技能' : '新建技能' }}</h3>
        <div class="dialog-body">
          <div class="form-item">
            <label>技能名称（工具名，字母开头）</label>
            <input v-model="form.name" class="form-input" placeholder="如 code_explain" />
          </div>
          <div class="form-item">
            <label>描述（决定模型何时调用它，也是检索语料，务必写清触发场景）</label>
            <textarea
              v-model="form.description"
              class="form-textarea"
              placeholder="解释代码片段的功能和逻辑，逐行分析代码的作用"
            ></textarea>
          </div>
          <div class="form-item">
            <label>提示词模板（<code>&#123;&#123;param&#125;&#125;</code> 占位符运行时替换为模型填的参数）</label>
            <textarea
              v-model="form.promptTemplate"
              class="form-textarea code-textarea"
              placeholder="请详细解释以下代码的功能和逻辑，逐行分析关键代码的作用：&#10;&#10;{{input}}"
            ></textarea>
          </div>
          <div class="form-item">
            <label>参数定义</label>
            <div v-for="(p, idx) in form.parameters" :key="idx" class="param-row">
              <input v-model="p.name" class="param-input param-name" placeholder="参数名" />
              <select v-model="p.type" class="param-input param-type">
                <option value="string">string</option>
                <option value="number">number</option>
                <option value="integer">integer</option>
                <option value="boolean">boolean</option>
              </select>
              <input v-model="p.description" class="param-input param-desc" placeholder="参数说明" />
              <label class="param-required"><input v-model="p.required" type="checkbox" /> 必填</label>
              <input v-model="p.defaultValue" class="param-input param-default" placeholder="默认值" />
              <button class="param-del" title="删除参数" @click="form.parameters.splice(idx, 1)">✕</button>
            </div>
            <button class="add-param-btn" @click="addParam">+ 添加参数</button>
          </div>
        </div>
        <div class="dialog-footer">
          <button class="btn-default" @click="closeDialog">取消</button>
          <button class="btn-primary" :disabled="!canSave || saving" @click="save">
            {{ saving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 导出预览弹窗 -->
    <div v-if="exportVisible" class="dialog-mask" @click.self="closeExport">
      <div class="dialog dialog-lg">
        <h3 class="dialog-title">导出预览：{{ exportSkillObj.name || 'skill' }}.md</h3>
        <div class="dialog-body">
          <pre class="md-preview">{{ exportContent || '加载中...' }}</pre>
        </div>
        <div class="dialog-footer">
          <button class="btn-default" @click="closeExport">关闭</button>
          <button class="btn-primary" :disabled="!exportContent" @click="downloadMd">⬇ 下载 .md</button>
        </div>
      </div>
    </div>

    <!-- 试跑弹窗 -->
    <div v-if="testVisible" class="dialog-mask" @click.self="closeTest">
      <div class="dialog dialog-lg">
        <h3 class="dialog-title">试跑：{{ currentTestSkill.name }}</h3>
        <div class="dialog-body">
          <div v-if="testParams.length === 0" class="empty small">该技能无参数，直接运行</div>
          <div v-for="p in testParams" :key="p.name" class="form-item">
            <label>
              {{ p.name }}{{ p.required ? '（必填）' : '' }}
              <span class="param-tip">{{ p.description }}</span>
            </label>
            <input v-model="testArgs[p.name]" class="form-input" :placeholder="p.defaultValue || ''" />
          </div>
          <div v-if="testResult" class="test-result">
            <div class="test-block">
              <div class="test-label">替换后的 Prompt</div>
              <pre class="md-preview">{{ testResult.prompt }}</pre>
            </div>
            <div class="test-block">
              <div class="test-label">模型输出</div>
              <pre class="md-preview">{{ testResult.result }}</pre>
            </div>
          </div>
        </div>
        <div class="dialog-footer">
          <button class="btn-default" @click="closeTest">关闭</button>
          <button class="btn-primary" :disabled="testing" @click="runTest">
            {{ testing ? '运行中...' : '运行' }}
          </button>
        </div>
      </div>
    </div>

    <ConfirmDialog
      v-model="confirmVisible"
      title="删除技能"
      :content="confirmContent"
      confirm-text="删除"
      @confirm="confirmDelete"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { listSkills, createSkill, updateSkill, deleteSkill, exportSkill, testSkill } from '../../api/skill'
import ConfirmDialog from '../../components/ConfirmDialog.vue'
import PaginationBar from '../../components/PaginationBar.vue'

const skillList = ref([])
const keyword = ref('')
const loading = ref(false)
const pagination = ref({ pageNum: 1, pageSize: 10, pages: 1, total: 0 })

const dialogVisible = ref(false)
const saving = ref(false)
const isEdit = ref(false)
const editingId = ref(null)
const form = ref(emptyForm())

const exportVisible = ref(false)
const exportSkillObj = ref({})
const exportContent = ref('')

const testVisible = ref(false)
const currentTestSkill = ref({})
const testParams = ref([])
const testArgs = ref({})
const testResult = ref(null)
const testing = ref(false)

const confirmVisible = ref(false)
const confirmContent = ref('')
const pendingDeleteId = ref(null)

const canSave = computed(
  () => form.value.name.trim() && form.value.description.trim() && form.value.promptTemplate.trim()
)

onMounted(loadList)

function emptyForm() {
  return { name: '', description: '', promptTemplate: '', status: 1, parameters: [] }
}

function parseParams(json) {
  try {
    const arr = JSON.parse(json || '[]')
    return Array.isArray(arr) ? arr : []
  } catch (e) {
    return []
  }
}

function paramCount(skill) {
  return parseParams(skill.parameters).length
}

async function loadList() {
  loading.value = true
  try {
    const res = await listSkills({
      pageNum: pagination.value.pageNum,
      pageSize: pagination.value.pageSize,
      keyword: keyword.value || undefined
    })
    skillList.value = res.list || []
    pagination.value = res
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

function addParam() {
  form.value.parameters.push({ name: '', type: 'string', description: '', required: false, defaultValue: '' })
}

function openDialog(skill) {
  if (skill) {
    isEdit.value = true
    editingId.value = skill.id
    form.value = {
      name: skill.name,
      description: skill.description || '',
      promptTemplate: skill.promptTemplate || '',
      status: skill.status,
      parameters: parseParams(skill.parameters)
    }
  } else {
    isEdit.value = false
    editingId.value = null
    form.value = emptyForm()
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
      await updateSkill(editingId.value, form.value)
    } else {
      await createSkill(form.value)
    }
    closeDialog()
    await loadList()
  } catch (e) {
    alert('保存失败：' + (e.message || e))
  } finally {
    saving.value = false
  }
}

async function toggleStatus(skill) {
  const newStatus = skill.status === 1 ? 0 : 1
  try {
    await updateSkill(skill.id, {
      name: skill.name,
      description: skill.description,
      promptTemplate: skill.promptTemplate,
      status: newStatus,
      parameters: parseParams(skill.parameters)
    })
    skill.status = newStatus
  } catch (e) {
    alert('状态切换失败：' + (e.message || e))
  }
}

async function openExport(skill) {
  exportSkillObj.value = skill
  exportContent.value = ''
  exportVisible.value = true
  try {
    exportContent.value = await exportSkill(skill.id)
  } catch (e) {
    exportContent.value = '导出失败：' + (e.message || e)
  }
}

function closeExport() {
  exportVisible.value = false
}

function downloadMd() {
  const blob = new Blob([exportContent.value], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${exportSkillObj.value.name || 'skill'}.md`
  a.click()
  URL.revokeObjectURL(url)
}

function openTest(skill) {
  currentTestSkill.value = skill
  testParams.value = parseParams(skill.parameters)
  const args = {}
  testParams.value.forEach(p => {
    args[p.name] = p.defaultValue || ''
  })
  testArgs.value = args
  testResult.value = null
  testVisible.value = true
}

function closeTest() {
  testVisible.value = false
}

async function runTest() {
  testing.value = true
  try {
    testResult.value = await testSkill(currentTestSkill.value.id, testArgs.value)
  } catch (e) {
    testResult.value = { prompt: '', result: '试跑失败：' + (e.message || e) }
  } finally {
    testing.value = false
  }
}

function requestDelete(skill) {
  pendingDeleteId.value = skill.id
  confirmContent.value = `确定删除技能「${skill.name}」吗？删除后对话中将不再提供该工具。`
  confirmVisible.value = true
}

async function confirmDelete() {
  try {
    await deleteSkill(pendingDeleteId.value)
    await loadList()
  } catch (e) {
    alert('删除失败：' + (e.message || e))
  } finally {
    pendingDeleteId.value = null
    confirmVisible.value = false
  }
}
</script>

<style scoped>
.manage-page {
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
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-main);
  margin: 0;
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

.refresh-btn,
.create-btn {
  border: none;
  border-radius: 8px;
  padding: 8px 16px;
  font-size: 13px;
  cursor: pointer;
}

.refresh-btn {
  background: #eef0ff;
  color: #4f66f9;
}

.create-btn {
  background: linear-gradient(135deg, #4f66f9, #7b8cff);
  color: #fff;
}

.manage-body {
  flex: 1;
  overflow-y: auto;
  padding: 0 24px 16px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 14px;
  align-content: start;
}

.skill-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition: box-shadow 0.2s;
}

.skill-card:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
}

.skill-main {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.skill-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: linear-gradient(135deg, #4f66f9, #7b8cff);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  flex-shrink: 0;
}

.skill-info {
  min-width: 0;
}

.skill-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-main);
  margin: 0 0 4px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.skill-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 20px;
  font-weight: 500;
}

.skill-badge.on {
  background: #e6f7ec;
  color: #2e9e5b;
}

.skill-badge.off {
  background: #f2f3f7;
  color: #8a91ad;
}

.skill-desc {
  font-size: 13px;
  color: var(--text-sub);
  margin: 0 0 6px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.skill-meta {
  font-size: 12px;
  color: #a0a6bd;
  margin: 0;
}

.skill-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.action-btn {
  border: 1px solid var(--border);
  background: #fff;
  color: var(--text-main);
  border-radius: 8px;
  padding: 5px 12px;
  font-size: 12px;
  cursor: pointer;
}

.action-btn:hover {
  border-color: #4f66f9;
  color: #4f66f9;
}

.action-btn.danger:hover {
  border-color: #f44336;
  color: #f44336;
}

.empty {
  grid-column: 1 / -1;
  text-align: center;
  color: #a0a6bd;
  font-size: 14px;
  padding: 60px 0;
}

.empty.small {
  padding: 12px 0;
  font-size: 13px;
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
  width: 480px;
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

.dialog-footer {
  padding: 14px 20px;
  border-top: 1px solid var(--border);
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.form-item {
  margin-bottom: 16px;
}

.form-item label {
  display: block;
  font-size: 13px;
  color: var(--text-main);
  margin-bottom: 6px;
  font-weight: 500;
}

.form-input {
  width: 100%;
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 13px;
  outline: none;
  box-sizing: border-box;
}

.form-input:focus,
.form-textarea:focus {
  border-color: #4f66f9;
}

.form-textarea {
  width: 100%;
  min-height: 90px;
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 13px;
  outline: none;
  resize: vertical;
  box-sizing: border-box;
  line-height: 1.6;
}

.code-textarea {
  font-family: 'JetBrains Mono', Consolas, monospace;
  min-height: 140px;
}

.param-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.param-input {
  padding: 7px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 12px;
  outline: none;
  box-sizing: border-box;
}

.param-input:focus {
  border-color: #4f66f9;
}

.param-name {
  width: 110px;
  font-family: Consolas, monospace;
}

.param-type {
  width: 84px;
}

.param-desc {
  flex: 1;
  min-width: 0;
}

.param-default {
  width: 90px;
}

.param-required {
  font-size: 12px;
  color: var(--text-sub);
  white-space: nowrap;
  display: flex;
  align-items: center;
  gap: 4px;
}

.param-del {
  border: none;
  background: transparent;
  color: #a0a6bd;
  font-size: 13px;
  cursor: pointer;
  padding: 4px;
}

.param-del:hover {
  color: #f44336;
}

.add-param-btn {
  border: 1px dashed #c3c8de;
  background: transparent;
  color: #4f66f9;
  font-size: 12px;
  border-radius: 8px;
  padding: 6px 14px;
  cursor: pointer;
}

.add-param-btn:hover {
  border-color: #4f66f9;
  background: #f5f6ff;
}

.md-preview {
  background: #1e2235;
  color: #d5d9ea;
  border-radius: 10px;
  padding: 16px;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 420px;
  overflow-y: auto;
  margin: 0;
}

.param-tip {
  font-weight: 400;
  color: #a0a6bd;
  margin-left: 6px;
}

.test-result {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.test-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-main);
  margin-bottom: 6px;
}

.btn-default,
.btn-primary {
  border: none;
  border-radius: 8px;
  padding: 8px 18px;
  font-size: 13px;
  cursor: pointer;
}

.btn-default {
  background: #f2f3f7;
  color: var(--text-main);
}

.btn-primary {
  background: linear-gradient(135deg, #4f66f9, #7b8cff);
  color: #fff;
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
