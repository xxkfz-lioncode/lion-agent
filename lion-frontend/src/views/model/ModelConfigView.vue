<template>
  <div class="manage-page">
    <header class="page-header">
      <div class="header-title">
        <h2 class="page-title">模型配置管理</h2>
        <p class="page-sub">
          维护可切换的模型列表；所有模型共用服务端配置的同一个端点与密钥，切换只换模型名，将某模型设为默认后下一次对话即热切换生效（无需重启），支持连通性测试。
        </p>
      </div>
      <button class="btn-primary" @click="openCreate">
        <span class="btn-plus">＋</span> 新增模型
      </button>
    </header>

    <div class="manage-body">
      <!-- 类型筛选 + 统计 -->
      <div class="toolbar">
        <div class="tabs">
          <button
            v-for="t in typeTabs"
            :key="t.value"
            class="tab-btn"
            :class="{ active: activeType === t.value }"
            @click="activeType = t.value"
          >{{ t.label }}</button>
        </div>
        <span class="count-tip">共 {{ filteredList.length }} 个模型</span>
      </div>

      <!-- 模型列表（表格） -->
      <div class="table-card">
        <table class="model-table">
          <thead>
            <tr>
              <th class="col-model">模型</th>
              <th class="col-type">类型</th>
              <th class="col-temp">温度</th>
              <th class="col-status">状态</th>
              <th class="col-time">更新时间</th>
              <th class="col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading">
              <td colspan="6" class="empty-cell">加载中...</td>
            </tr>
            <tr v-else-if="filteredList.length === 0">
              <td colspan="6" class="empty-cell">暂无模型配置，点击右上角「新增模型」添加</td>
            </tr>
            <tr v-for="model in filteredList" :key="model.id" :class="{ disabled: !model.enabled }">
              <td class="col-model">
                <div class="model-cell">
                  <div class="model-icon">{{ model.modelType === 'multimodal' ? '🖼️' : '💬' }}</div>
                  <div class="model-text">
                    <div class="model-name">
                      {{ model.displayName }}
                      <span v-if="model.isDefault" class="default-badge">默认</span>
                    </div>
                    <div class="model-id" :title="model.modelName">{{ model.modelName }}</div>
                  </div>
                </div>
              </td>
              <td class="col-type">
                <span class="type-badge" :class="model.modelType">{{ typeText(model.modelType) }}</span>
              </td>
              <td class="col-temp">{{ model.temperature ?? '默认' }}</td>
              <td class="col-status">
                <span class="status-badge" :class="model.enabled ? 'on' : 'off'">
                  {{ model.enabled ? '启用' : '停用' }}
                </span>
              </td>
              <td class="col-time">{{ formatTime(model.updatedAt) }}</td>
              <td class="col-actions">
                <button class="link-btn" :disabled="testingId === model.id" @click="test(model)">
                  {{ testingId === model.id ? '测试中' : '测试' }}
                </button>
                <button v-if="!model.isDefault" class="link-btn" :disabled="defaultingId === model.id" @click="setDefault(model)">
                  {{ defaultingId === model.id ? '切换中' : '设默认' }}
                </button>
                <button class="link-btn" @click="openEdit(model)">编辑</button>
                <button class="link-btn" @click="toggleEnabled(model)">{{ model.enabled ? '停用' : '启用' }}</button>
                <button class="link-btn danger" @click="remove(model)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 新增/编辑弹窗（共用表单） -->
    <div v-if="dialogVisible" class="dialog-mask" @click.self="closeDialog">
      <div class="dialog">
        <h3 class="dialog-title">{{ dialogForm.id ? '编辑模型配置' : '新增模型配置' }}</h3>
        <div class="dialog-body">
          <div class="form-grid">
            <div class="form-item">
              <label>显示名 <i class="req">*</i></label>
              <input v-model="dialogForm.displayName" class="form-input" placeholder="如 千问旗舰" />
            </div>
            <div class="form-item">
              <label>模型名 <i class="req">*</i></label>
              <input v-model="dialogForm.modelName" class="form-input" placeholder="如 qwen-max / deepseek-v3" />
            </div>
            <div class="form-item">
              <label>模型类型</label>
              <select v-model="dialogForm.modelType" class="form-input">
                <option value="chat">文本对话</option>
                <option value="multimodal">多模态</option>
              </select>
            </div>
            <div class="form-item">
              <label>采样温度（0~2，可选）</label>
              <input v-model.number="dialogForm.temperature" type="number" step="0.1" min="0" max="2" class="form-input" placeholder="留空使用默认 0.1" />
            </div>
            <div class="form-item">
              <label>备注</label>
              <input v-model="dialogForm.remark" class="form-input" placeholder="可选" />
            </div>
            <div class="form-item check-item">
              <label class="checkbox-label">
                <input v-model="dialogForm.enabled" type="checkbox" />
                启用
              </label>
            </div>
          </div>
        </div>
        <div class="dialog-footer">
          <button class="btn-default" @click="closeDialog">取消</button>
          <button class="btn-primary" :disabled="saving" @click="submitDialog">
            {{ saving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 连通性测试结果弹窗 -->
    <div v-if="testVisible" class="dialog-mask" @click.self="closeTest">
      <div class="dialog dialog-sm">
        <h3 class="dialog-title">连通性测试：{{ testResult?.modelName }}</h3>
        <div class="dialog-body">
          <div class="form-item">
            <label>模型回复（耗时 {{ testResult?.costMs }} ms）</label>
            <pre class="code-block result-block">{{ testResult?.reply }}</pre>
          </div>
        </div>
        <div class="dialog-footer">
          <button class="btn-primary" @click="closeTest">关闭</button>
        </div>
      </div>
    </div>

    <!-- 删除确认弹窗 -->
    <ConfirmDialog
      v-model="removeDialogVisible"
      title="确认删除"
      :content="`确定删除模型配置「${removingModel?.displayName}」吗？`"
      type="danger"
      confirm-text="删除"
      @confirm="doRemove"
    />

    <!-- 消息提示弹窗 -->
    <Teleport to="body">
      <Transition name="message-fade">
        <div v-if="messageVisible" class="message-mask" @click.self="closeMessage">
          <div class="message-card" :class="messageType">
            <div class="message-icon">
              <span v-if="messageType === 'warning'">⚠️</span>
              <span v-else-if="messageType === 'error'">❌</span>
              <span v-else-if="messageType === 'success'">✅</span>
              <span v-else>ℹ️</span>
            </div>
            <div class="message-title">
              {{ messageType === 'success' ? '成功' : messageType === 'error' ? '错误' : '提示' }}
            </div>
            <div class="message-body">{{ messageText }}</div>
            <div class="message-footer">
              <button class="btn-primary" @click="closeMessage">知道了</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import ConfirmDialog from '../../components/ConfirmDialog.vue'
import {
  listModels,
  createModel,
  updateModel,
  deleteModel,
  setDefaultModel,
  testModel
} from '../../api/model'

const modelList = ref([])
const loading = ref(false)
const saving = ref(false)
const testingId = ref(null)
const defaultingId = ref(null)

const activeType = ref('all')
const typeTabs = [
  { value: 'all', label: '全部' },
  { value: 'chat', label: '文本对话' },
  { value: 'multimodal', label: '多模态' }
]

const dialogVisible = ref(false)
const dialogForm = reactive({
  id: null,
  displayName: '',
  modelName: '',
  modelType: 'chat',
  temperature: null,
  enabled: true,
  remark: ''
})

const testVisible = ref(false)
const testResult = ref(null)

const removeDialogVisible = ref(false)
const removingModel = ref(null)

const messageVisible = ref(false)
const messageText = ref('')
const messageType = ref('info')
let messageTimer = null

const filteredList = computed(() =>
  activeType.value === 'all'
    ? modelList.value
    : modelList.value.filter((m) => m.modelType === activeType.value)
)

function showMessage(text, type = 'info') {
  messageText.value = text
  messageType.value = type
  messageVisible.value = true
  if (messageTimer) clearTimeout(messageTimer)
  messageTimer = setTimeout(() => {
    messageVisible.value = false
  }, 2500)
}

function closeMessage() {
  messageVisible.value = false
  if (messageTimer) clearTimeout(messageTimer)
}

onMounted(loadList)

async function loadList() {
  loading.value = true
  try {
    modelList.value = await listModels()
  } catch (e) {
    showMessage('加载模型列表失败：' + (e.message || e), 'error')
  } finally {
    loading.value = false
  }
}

/** 打开新增弹窗 */
function openCreate() {
  Object.assign(dialogForm, {
    id: null,
    displayName: '',
    modelName: '',
    modelType: 'chat',
    temperature: null,
    enabled: true,
    remark: ''
  })
  dialogVisible.value = true
}

/** 打开编辑弹窗 */
function openEdit(model) {
  Object.assign(dialogForm, {
    id: model.id,
    displayName: model.displayName,
    modelName: model.modelName,
    modelType: model.modelType,
    temperature: model.temperature ?? null,
    enabled: model.enabled,
    remark: model.remark || ''
  })
  dialogVisible.value = true
}

function closeDialog() {
  dialogVisible.value = false
}

/** 收集表单参数（温度留空不传） */
function buildPayload(src) {
  const payload = {
    displayName: src.displayName,
    modelName: src.modelName,
    modelType: src.modelType,
    remark: src.remark
  }
  if (src.temperature !== null && src.temperature !== undefined && src.temperature !== '') {
    payload.temperature = src.temperature
  }
  if (src.enabled !== undefined) payload.enabled = src.enabled
  return payload
}

/** 新增/编辑提交（共用校验） */
async function submitDialog() {
  if (!dialogForm.displayName?.trim() || !dialogForm.modelName?.trim()) {
    showMessage('请填写显示名和模型名', 'warning')
    return
  }
  saving.value = true
  try {
    if (dialogForm.id) {
      await updateModel(dialogForm.id, buildPayload(dialogForm))
      showMessage('保存成功', 'success')
    } else {
      await createModel(buildPayload(dialogForm))
      showMessage('新增成功', 'success')
    }
    closeDialog()
    await loadList()
  } catch (e) {
    showMessage('保存失败：' + (e.message || e), 'error')
  } finally {
    saving.value = false
  }
}

async function setDefault(model) {
  defaultingId.value = model.id
  try {
    await setDefaultModel(model.id)
    showMessage(`已将「${model.displayName}」设为默认，下次对话即生效`, 'success')
    await loadList()
  } catch (e) {
    showMessage('设置默认失败：' + (e.message || e), 'error')
  } finally {
    defaultingId.value = null
  }
}

async function toggleEnabled(model) {
  try {
    await updateModel(model.id, buildPayload({ ...model, enabled: !model.enabled }))
    await loadList()
  } catch (e) {
    showMessage((model.enabled ? '停用' : '启用') + '失败：' + (e.message || e), 'error')
  }
}

function remove(model) {
  removingModel.value = model
  removeDialogVisible.value = true
}

async function doRemove() {
  removeDialogVisible.value = false
  const model = removingModel.value
  if (!model) return
  try {
    await deleteModel(model.id)
    await loadList()
  } catch (e) {
    showMessage('删除失败：' + (e.message || e), 'error')
  }
}

async function test(model) {
  testingId.value = model.id
  try {
    testResult.value = await testModel(model.id)
    testVisible.value = true
  } catch (e) {
    showMessage('连通性测试失败：' + (e.message || e), 'error')
  } finally {
    testingId.value = null
  }
}

function closeTest() {
  testVisible.value = false
  testResult.value = null
}

function typeText(type) {
  return type === 'multimodal' ? '多模态' : '文本对话'
}

function formatTime(time) {
  if (!time) return '-'
  return String(time).replace('T', ' ').slice(0, 19)
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

.btn-plus {
  font-size: 14px;
  margin-right: 2px;
}

.manage-body {
  flex: 1;
  overflow-y: auto;
  padding: 0 24px 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* 工具栏：筛选 + 统计 */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.tabs {
  display: flex;
  gap: 8px;
}

.tab-btn {
  border: none;
  background: transparent;
  color: #8a91ad;
  font-size: 14px;
  font-weight: 500;
  padding: 6px 12px;
  cursor: pointer;
  border-radius: 8px;
}

.tab-btn.active {
  background: #eef0ff;
  color: #4f66f9;
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

.model-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.model-table thead th {
  text-align: left;
  font-size: 12px;
  font-weight: 600;
  color: #8a91ad;
  background: #f7f8fc;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}

.model-table tbody td {
  padding: 12px 14px;
  border-bottom: 1px solid #f0f1f6;
  vertical-align: middle;
}

.model-table tbody tr:last-child td {
  border-bottom: none;
}

.model-table tbody tr:hover {
  background: #fafbff;
}

.model-table tbody tr.disabled .model-name {
  color: #b6bac9;
}

.empty-cell {
  text-align: center;
  color: #a0a6bd;
  font-size: 13px;
  padding: 48px 0 !important;
}

/* 模型列 */
.model-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 200px;
}

.model-icon {
  width: 36px;
  height: 36px;
  border-radius: 9px;
  background: linear-gradient(135deg, #4f66f9, #7b8cff);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
  flex-shrink: 0;
}

.model-text {
  min-width: 0;
}

.model-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-main);
  display: flex;
  align-items: center;
  gap: 6px;
}

.model-id {
  font-size: 11px;
  color: #8a91ad;
  margin-top: 2px;
  font-family: Consolas, monospace;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 徽标 */
.type-badge,
.default-badge,
.status-badge {
  font-size: 11px;
  font-weight: 500;
  border-radius: 4px;
  padding: 2px 6px;
  white-space: nowrap;
}

.type-badge.chat {
  background: #eef0ff;
  color: #4f66f9;
}

.type-badge.multimodal {
  background: #fff4e6;
  color: #d97706;
}

.default-badge {
  background: #e6f9ed;
  color: #1aa857;
}

.status-badge.on {
  background: #f2f3f7;
  color: #1aa857;
}

.status-badge.off {
  background: #f2f3f7;
  color: #8a91ad;
}

/* 操作列 */
.col-actions {
  white-space: nowrap;
}

.link-btn {
  border: none;
  background: transparent;
  color: #4f66f9;
  font-size: 12px;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 4px;
}

.link-btn:hover {
  background: #eef0ff;
}

.link-btn.danger {
  color: #e14b4b;
}

.link-btn.danger:hover {
  background: #feecec;
}

.link-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 弹窗 */
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
  width: 560px;
  max-width: calc(100vw - 48px);
  max-height: calc(100vh - 96px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.dialog-sm {
  width: 480px;
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

/* 表单：两列网格 */
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}

.form-item {
  margin-bottom: 14px;
}

.form-item.span-2 {
  grid-column: span 2;
}

.form-item label {
  display: block;
  font-size: 13px;
  color: var(--text-main);
  margin-bottom: 6px;
  font-weight: 500;
}

.req {
  color: #e14b4b;
  font-style: normal;
}

.form-item .form-input {
  width: 100%;
  box-sizing: border-box;
}

.check-item {
  display: flex;
  align-items: flex-end;
  padding-bottom: 2px;
}

.checkbox-label {
  display: flex !important;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  margin-bottom: 0 !important;
}

.form-input {
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 13px;
  outline: none;
  background: #fff;
  color: var(--text-main);
}

.form-input:focus {
  border-color: #4f66f9;
}

.code-block {
  background: #f7f8fc;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
  font-size: 12px;
  font-family: 'JetBrains Mono', Consolas, monospace;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 300px;
  overflow-y: auto;
  margin: 0;
}

.result-block {
  min-height: 80px;
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
  white-space: nowrap;
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 消息提示 */
.message-mask {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(2px);
}

.message-card {
  width: 360px;
  max-width: calc(100vw - 40px);
  padding: 28px 24px 24px;
  background: #fff;
  border-radius: 16px;
  text-align: center;
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.18);
}

.message-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 26px;
}

.message-card.warning .message-icon { background: rgba(79, 102, 249, 0.12); }
.message-card.error .message-icon { background: rgba(225, 75, 75, 0.12); }
.message-card.success .message-icon { background: rgba(16, 185, 129, 0.12); }
.message-card.info .message-icon { background: rgba(107, 114, 128, 0.12); }

.message-title {
  font-size: 18px;
  font-weight: 600;
  color: #1f2937;
  margin-bottom: 10px;
}

.message-body {
  font-size: 14px;
  line-height: 1.6;
  color: #6b7280;
  margin-bottom: 24px;
}

.message-footer .btn-primary {
  min-width: 120px;
}

.message-fade-enter-active,
.message-fade-leave-active {
  transition: opacity 0.25s ease;
}

.message-fade-enter-active .message-card,
.message-fade-leave-active .message-card {
  transition: transform 0.25s ease, opacity 0.25s ease;
}

.message-fade-enter-from,
.message-fade-leave-to {
  opacity: 0;
}

.message-fade-enter-from .message-card,
.message-fade-leave-to .message-card {
  opacity: 0;
  transform: scale(0.95);
}
</style>
