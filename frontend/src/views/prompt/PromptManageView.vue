<template>
  <div class="manage-page">
    <header class="page-header">
      <div class="header-title">
        <h2 class="page-title">提示词模板</h2>
        <p class="page-sub">
          模板保存在数据库（启动时自动从 resources/prompts 导入）；保存修改后，下一次对话请求立即使用最新内容。
        </p>
      </div>
      <div class="header-actions">
        <button class="refresh-btn" :disabled="refreshing" @click="confirmVisible = true">
          {{ refreshing ? '同步中...' : '从文件同步' }}
        </button>
      </div>
    </header>

    <div class="manage-body">
      <div v-if="promptList.length === 0 && !loading" class="empty">
        暂无提示词模板
      </div>
      <div
        v-for="prompt in promptList"
        :key="prompt.fileName"
        class="prompt-card"
        @click="openEdit(prompt)"
      >
        <div class="prompt-main">
          <div class="prompt-icon">📝</div>
          <div class="prompt-info">
            <h3 class="prompt-name">
              {{ prompt.name }}
              <span class="prompt-file">{{ prompt.fileName }}</span>
            </h3>
            <p class="prompt-desc">{{ prompt.description || '暂无描述' }}</p>
            <p class="prompt-meta">更新于 {{ prompt.updatedAt || '-' }}</p>
          </div>
        </div>
        <div class="prompt-actions">
          <button class="action-btn" @click.stop="openEdit(prompt)">查看 / 编辑</button>
        </div>
      </div>
    </div>

    <!-- 编辑/查看弹窗 -->
    <div v-if="dialogVisible" class="dialog-mask" @click.self="closeDialog">
      <div class="dialog dialog-lg">
        <h3 class="dialog-title">{{ currentPrompt.name }}</h3>
        <div class="dialog-body">
          <div class="form-item">
            <label>文件名</label>
            <input :value="currentPrompt.fileName" class="form-input" disabled />
          </div>
          <div class="form-item">
            <label>用途描述</label>
            <input :value="currentPrompt.description" class="form-input" disabled />
          </div>
          <div class="form-item">
            <label>
              模板内容
              <span class="source-tip">保存后立即生效（对话请求会读取最新模板）</span>
            </label>
            <textarea
              v-model="editContent"
              class="form-textarea code-textarea"
              rows="22"
              spellcheck="false"
            ></textarea>
          </div>
        </div>
        <div class="dialog-footer">
          <button class="btn-default" @click="closeDialog">取消</button>
          <button class="btn-primary" :disabled="saving" @click="save">
            {{ saving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <ConfirmDialog
      v-model="confirmVisible"
      title="从文件同步提示词模板"
      content="将 resources/prompts 目录下的模板文件同步到数据库（新增 + 覆盖）？页面中已保存的修改会被文件内容覆盖。"
      confirm-text="同步"
      @confirm="doRefresh"
    />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { listPromptTemplates, updatePromptTemplate, refreshPromptTemplates } from '../../api/prompt'
import ConfirmDialog from '../../components/ConfirmDialog.vue'

const promptList = ref([])
const loading = ref(false)
const refreshing = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const currentPrompt = ref({})
const editContent = ref('')
const confirmVisible = ref(false)

onMounted(loadList)

async function loadList() {
  loading.value = true
  try {
    promptList.value = await listPromptTemplates()
  } finally {
    loading.value = false
  }
}

async function doRefresh() {
  refreshing.value = true
  try {
    await refreshPromptTemplates()
    await loadList()
  } catch (e) {
    alert('同步失败：' + (e.message || e))
  } finally {
    refreshing.value = false
  }
}

function openEdit(prompt) {
  currentPrompt.value = prompt
  editContent.value = prompt.content || ''
  dialogVisible.value = true
}

function closeDialog() {
  dialogVisible.value = false
}

async function save() {
  saving.value = true
  try {
    const updated = await updatePromptTemplate(currentPrompt.value.name, editContent.value)
    const idx = promptList.value.findIndex(p => p.fileName === updated.fileName)
    if (idx >= 0) {
      promptList.value[idx] = updated
    }
    closeDialog()
  } catch (e) {
    alert('保存失败：' + (e.message || e))
  } finally {
    saving.value = false
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

.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.refresh-btn {
  border: none;
  border-radius: 8px;
  padding: 8px 16px;
  font-size: 13px;
  cursor: pointer;
  background: #eef0ff;
  color: #4f66f9;
}

.refresh-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.manage-body {
  flex: 1;
  overflow-y: auto;
  padding: 0 24px 16px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 14px;
  align-content: start;
}

.empty {
  grid-column: 1 / -1;
  text-align: center;
  color: #a0a6bd;
  font-size: 14px;
  padding: 60px 0;
}

.prompt-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition: box-shadow 0.2s;
  cursor: pointer;
}

.prompt-card:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
}

.prompt-main {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.prompt-icon {
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

.prompt-info {
  min-width: 0;
}

.prompt-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-main);
  margin: 0 0 4px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.prompt-file {
  font-size: 11px;
  font-weight: 500;
  color: #8a91ad;
  background: #f2f3f7;
  border-radius: 4px;
  padding: 2px 6px;
  font-family: Consolas, monospace;
}

.prompt-desc {
  font-size: 13px;
  color: var(--text-sub);
  margin: 0 0 6px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.prompt-meta {
  font-size: 12px;
  color: #a0a6bd;
  margin: 0;
}

.prompt-actions {
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
  width: 860px;
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
  background: #f7f8fc;
  color: var(--text-sub);
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
  min-height: 420px;
}

.source-tip {
  font-weight: 400;
  color: #a0a6bd;
  margin-left: 6px;
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
