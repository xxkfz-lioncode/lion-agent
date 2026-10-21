<template>
  <div class="manage-page">
    <header class="page-header">
      <div class="header-title">
        <h2 class="page-title">MCP 服务管理</h2>
        <p class="page-sub">
          管理外部 MCP Server（SSE 协议），连接后自动发现工具并加入 Agent 工具池；支持在线测试调用。
        </p>
      </div>
    </header>

    <div class="manage-body">
      <!-- Tab 切换 -->
      <div class="tabs">
        <button
          class="tab-btn"
          :class="{ active: activeTab === 'mcp' }"
          @click="activeTab = 'mcp'"
        >MCP 服务</button>
        <button
          class="tab-btn"
          :class="{ active: activeTab === 'local' }"
          @click="switchLocalTab"
        >本地 @Tool 工具</button>
      </div>

      <!-- MCP 服务 Tab -->
      <div v-if="activeTab === 'mcp'">
        <!-- 新增/快速连接区 -->
        <div class="add-card">
          <h3 class="section-title">新增 MCP 服务</h3>
          <div class="form-row">
            <input v-model="form.name" class="form-input" placeholder="服务别名（如 钉钉 MCP）" />
            <input v-model="form.url" class="form-input flex-2" placeholder="MCP Server URL，如 https://mcp-gw.dingtalk.com/server/xxx/sse" />
            <input v-model="form.description" class="form-input flex-2" placeholder="描述（可选）" />
            <button class="btn-primary" :disabled="saving" @click="saveServer">
              {{ saving ? '保存中...' : '连接' }}
            </button>
          </div>
        </div>

        <!-- 服务列表 -->
        <div v-if="serverList.length === 0 && !loading" class="empty">
          暂无 MCP 服务，请在上方添加
        </div>
        <div
          v-for="server in serverList"
          :key="server.id"
          class="server-card"
        >
          <div class="server-main">
            <div class="server-icon">🔌</div>
            <div class="server-info">
              <h3 class="server-name">
                {{ server.name }}
                <span class="status-badge" :class="server.status">{{ statusText(server.status) }}</span>
                <span class="tool-count">{{ server.toolCount || 0 }} 个工具</span>
              </h3>
              <p class="server-url" :title="server.url">{{ server.url }}</p>
              <p v-if="server.errorMsg" class="server-error">错误：{{ server.errorMsg }}</p>
              <p v-if="server.description" class="server-desc">{{ server.description }}</p>
            </div>
          </div>
          <div class="server-actions">
            <button v-if="server.status !== 'connected'" class="action-btn" :disabled="connectingId === server.id" @click="connect(server)">
              {{ connectingId === server.id ? '连接中...' : '连接/重连' }}
            </button>
            <button v-else class="action-btn" :disabled="connectingId === server.id" @click="disconnect(server)">
              {{ connectingId === server.id ? '断开中...' : '断开' }}
            </button>
            <button class="action-btn" :disabled="discoveringId === server.id" @click="discover(server)">
              {{ discoveringId === server.id ? '发现中...' : '发现工具' }}
            </button>
            <button class="action-btn" @click="editServer(server)">编辑</button>
            <button class="action-btn danger" @click="remove(server)">移除</button>
          </div>

          <!-- 已发现的工具列表 -->
          <div v-if="expanded[server.id]" class="tool-panel">
            <div class="tool-panel-header">
              <span>已发现工具</span>
              <button class="btn-text" @click="toggleExpand(server.id)">收起</button>
            </div>
            <div v-if="!toolsMap[server.id]" class="tool-empty">点击「发现工具」刷新</div>
            <div v-else-if="toolsMap[server.id].length === 0" class="tool-empty">未发现工具</div>
            <div v-else class="tool-list">
              <div v-for="tool in toolsMap[server.id]" :key="tool.id" class="tool-item">
                <div class="tool-info">
                  <span class="tool-name">{{ tool.name }}</span>
                  <span class="tool-desc">{{ tool.description || '无描述' }}</span>
                </div>
                <div class="tool-actions">
                  <button class="btn-text" @click="showSchema(tool)">查看参数</button>
                  <button class="btn-text" @click="testTool(server, tool)">测试调用</button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 本地 @Tool 工具 Tab -->
      <div v-else class="local-tools-panel">
        <div v-if="localToolsLoading" class="tool-empty">加载中...</div>
        <div v-else-if="localTools.length === 0" class="tool-empty">暂无本地工具</div>
        <div v-else class="tool-list">
          <div v-for="tool in localTools" :key="tool.name" class="tool-item">
            <div class="tool-info">
              <span class="tool-name">{{ tool.name }}</span>
              <span class="tool-desc">{{ tool.description || '无描述' }}</span>
              <span class="tool-source">来源：{{ tool.source }}</span>
            </div>
            <div class="tool-actions">
              <button class="btn-text" @click="showSchema(tool)">查看参数</button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 编辑弹窗 -->
    <div v-if="editVisible" class="dialog-mask" @click.self="closeEdit">
      <div class="dialog">
        <h3 class="dialog-title">编辑 MCP 服务</h3>
        <div class="dialog-body">
          <div class="form-item">
            <label>服务别名</label>
            <input v-model="editForm.name" class="form-input" />
          </div>
          <div class="form-item">
            <label>MCP Server URL</label>
            <input v-model="editForm.url" class="form-input" />
          </div>
          <div class="form-item">
            <label>描述</label>
            <input v-model="editForm.description" class="form-input" />
          </div>
          <div class="form-item">
            <label class="checkbox-label">
              <input v-model="editForm.enabled" type="checkbox" />
              启用（启用后保存会自动重连）
            </label>
          </div>
        </div>
        <div class="dialog-footer">
          <button class="btn-default" @click="closeEdit">取消</button>
          <button class="btn-primary" :disabled="saving" @click="confirmEdit">
            {{ saving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 查看参数弹窗 -->
    <div v-if="schemaVisible" class="dialog-mask" @click.self="closeSchema">
      <div class="dialog dialog-lg">
        <h3 class="dialog-title">工具参数：{{ currentTool.name }}</h3>
        <div class="dialog-body">
          <pre class="code-block">{{ prettySchema }}</pre>
        </div>
        <div class="dialog-footer">
          <button class="btn-default" @click="closeSchema">关闭</button>
        </div>
      </div>
    </div>

    <!-- 测试调用弹窗 -->
    <div v-if="testVisible" class="dialog-mask" @click.self="closeTest">
      <div class="dialog dialog-lg">
        <h3 class="dialog-title">测试调用：{{ currentTool.name }}</h3>
        <div class="dialog-body">
          <div class="form-item">
            <label>请求参数</label>
            <p class="form-hint">已按 Schema 生成表单，可直接填写；复杂类型可展开下方「原始 JSON」编辑</p>
            <div v-if="testFormFields.length" class="schema-form">
              <div v-for="field in testFormFields" :key="field.key" class="schema-field">
                <label class="field-label">
                  {{ field.key }}
                  <span v-if="field.required" class="required">*</span>
                  <span class="field-type">{{ field.type }}</span>
                </label>
                <p v-if="field.description" class="field-desc">{{ field.description }}</p>
                <input
                  v-if="field.control === 'text'"
                  v-model="testFormValues[field.key]"
                  :placeholder="field.description || '请输入'"
                  class="form-input"
                />
                <input
                  v-else-if="field.control === 'number'"
                  v-model.number="testFormValues[field.key]"
                  type="number"
                  :placeholder="field.description || '请输入数字'"
                  class="form-input"
                />
                <select
                  v-else-if="field.control === 'enum'"
                  v-model="testFormValues[field.key]"
                  class="form-input"
                >
                  <option v-for="opt in field.options" :key="opt" :value="opt">{{ opt }}</option>
                </select>
                <label v-else-if="field.control === 'boolean'" class="field-boolean">
                  <input type="checkbox" v-model="testFormValues[field.key]" />
                  <span>{{ testFormValues[field.key] ? '是' : '否' }}</span>
                </label>
                <textarea
                  v-else
                  :value="stringifyComplex(field.key)"
                  @input="updateComplexField(field.key, $event.target.value)"
                  class="form-textarea code-textarea"
                  rows="2"
                  :placeholder="field.description || '复杂类型，请填写 JSON'"
                ></textarea>
              </div>
            </div>
            <details class="raw-json">
              <summary>原始 JSON（高级）</summary>
              <textarea v-model="testArgs" class="form-textarea code-textarea" rows="6" spellcheck="false"></textarea>
            </details>
          </div>
          <div class="form-item">
            <label>调用结果</label>
            <pre class="code-block result-block">{{ testResult || '点击运行后显示结果' }}</pre>
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
  </div>

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

  <!-- 移除确认弹窗 -->
  <ConfirmDialog
    v-model="removeDialogVisible"
    title="确认移除"
    :content="`确定移除 MCP 服务「${removingServer?.name}」吗？移除后该服务下的工具将不再可用。`"
    type="danger"
    confirm-text="移除"
    @confirm="doRemove"
  />
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import ConfirmDialog from '../../components/ConfirmDialog.vue'
import {
  listMcpServers,
  createMcpServer,
  updateMcpServer,
  deleteMcpServer,
  connectMcpServer,
  disconnectMcpServer,
  discoverMcpServerTools,
  testMcpTool,
  listLocalTools
} from '../../api/mcp'

const serverList = ref([])
const loading = ref(false)
const saving = ref(false)
const connectingId = ref(null)
const discoveringId = ref(null)
const testing = ref(false)
const expanded = reactive({})
const toolsMap = reactive({})

const activeTab = ref('mcp')
const localTools = ref([])
const localToolsLoading = ref(false)

const form = reactive({ name: '', url: '', description: '' })

const editVisible = ref(false)
const editForm = reactive({ id: null, name: '', url: '', description: '', enabled: true })

const schemaVisible = ref(false)
const currentTool = ref({})
const prettySchema = computed(() => {
  try {
    return JSON.stringify(JSON.parse(currentTool.value.inputSchema || '{}'), null, 2)
  } catch (e) {
    return currentTool.value.inputSchema || '{}'
  }
})

const testVisible = ref(false)
const testArgs = ref('{}')
const testFormFields = ref([])
const testFormValues = ref({})
const testResult = ref('')
const currentServer = ref({})

const messageVisible = ref(false)
const messageText = ref('')
const messageType = ref('info')
let messageTimer = null

const removeDialogVisible = ref(false)
const removingServer = ref(null)

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
    serverList.value = await listMcpServers()
  } finally {
    loading.value = false
  }
}

async function saveServer() {
  if (!form.name || !form.url) {
    showMessage('请填写服务别名和 URL', 'warning')
    return
  }
  saving.value = true
  try {
    await createMcpServer({
      name: form.name,
      url: form.url,
      description: form.description,
      transportType: 'sse',
      enabled: true
    })
    form.name = ''
    form.url = ''
    form.description = ''
    await loadList()
  } catch (e) {
    showMessage('新增失败：' + (e.message || e), 'error')
  } finally {
    saving.value = false
  }
}

async function connect(server) {
  connectingId.value = server.id
  try {
    await connectMcpServer(server.id)
    await loadList()
  } catch (e) {
    showMessage('连接失败：' + (e.message || e), 'error')
  } finally {
    connectingId.value = null
  }
}

async function disconnect(server) {
  connectingId.value = server.id
  try {
    await disconnectMcpServer(server.id)
    await loadList()
  } catch (e) {
    showMessage('断开失败：' + (e.message || e), 'error')
  } finally {
    connectingId.value = null
  }
}

async function discover(server) {
  expanded[server.id] = true
  discoveringId.value = server.id
  try {
    const tools = await discoverMcpServerTools(server.id)
    toolsMap[server.id] = tools
  } catch (e) {
    showMessage('发现工具失败：' + (e.message || e), 'error')
  } finally {
    discoveringId.value = null
  }
}

function editServer(server) {
  editForm.id = server.id
  editForm.name = server.name
  editForm.url = server.url
  editForm.description = server.description || ''
  editForm.enabled = server.enabled
  editVisible.value = true
}

function closeEdit() {
  editVisible.value = false
}

async function confirmEdit() {
  saving.value = true
  try {
    await updateMcpServer(editForm.id, {
      name: editForm.name,
      url: editForm.url,
      description: editForm.description,
      enabled: editForm.enabled,
      transportType: 'sse'
    })
    closeEdit()
    await loadList()
  } catch (e) {
    showMessage('保存失败：' + (e.message || e), 'error')
  } finally {
    saving.value = false
  }
}

function remove(server) {
  removingServer.value = server
  removeDialogVisible.value = true
}

async function doRemove() {
  removeDialogVisible.value = false
  const server = removingServer.value
  if (!server) return
  try {
    await deleteMcpServer(server.id)
    await loadList()
  } catch (e) {
    showMessage('移除失败：' + (e.message || e), 'error')
  }
}

function toggleExpand(id) {
  expanded[id] = !expanded[id]
}

function switchLocalTab() {
  activeTab.value = 'local'
  if (localTools.value.length === 0 && !localToolsLoading.value) {
    loadLocalTools()
  }
}

async function loadLocalTools() {
  localToolsLoading.value = true
  try {
    localTools.value = await listLocalTools()
  } catch (e) {
    showMessage('加载本地工具失败：' + (e.message || e), 'error')
  } finally {
    localToolsLoading.value = false
  }
}

function showSchema(tool) {
  currentTool.value = tool
  schemaVisible.value = true
}

function closeSchema() {
  schemaVisible.value = false
}

function testTool(server, tool) {
  currentServer.value = server
  currentTool.value = tool
  // 根据 inputSchema 动态生成表单字段与初始值，并用 watch 实时同步为 JSON
  buildFormFromSchema(tool.inputSchema)
  testResult.value = ''
  testVisible.value = true
}

/** 把工具的 inputSchema（JSON Schema 字符串）转换成一份示例请求参数 JSON */
function argsExampleFromSchema(schemaJson) {
  try {
    const schema = JSON.parse(schemaJson || '{}')
    return JSON.stringify(buildExampleValue(schema), null, 2)
  } catch (e) {
    return '{}'
  }
}

/** 递归生成示例值：对齐 JSON Schema 的类型/枚举/默认值，对象保留全部字段 */
function buildExampleValue(schema) {
  if (!schema || typeof schema !== 'object') return null
  // anyOf / oneOf：取第一个非 null 分支
  const union = schema.anyOf || schema.oneOf
  if (union) {
    const branch = union.find(b => b && b.type !== 'null') || union[0]
    return buildExampleValue(branch || {})
  }
  if (Array.isArray(schema.enum) && schema.enum.length) {
    return schema.enum.find(v => v !== null) ?? schema.enum[0]
  }
  if (schema.const !== undefined) return schema.const
  if (schema.type === 'object' || schema.properties) {
    const obj = {}
    const props = schema.properties || {}
    const required = Array.isArray(schema.required) ? schema.required : []
    for (const [key, prop] of Object.entries(props)) {
      if (prop && prop.default !== undefined) {
        obj[key] = prop.default
      } else {
        // 可选字段也填充类型默认值，避免输入框里显示字符串 "null"；
        // 是否必填通过表单上的 * 区分
        obj[key] = buildExampleValue(prop)
      }
    }
    return obj
  }
  if (schema.type === 'array' || schema.items) {
    return [buildExampleValue(schema.items)]
  }
  if (schema.type === 'string') return ''
  if (schema.type === 'number' || schema.type === 'integer') return 0
  if (schema.type === 'boolean') return false
  return null
}

/** 根据 Schema 构建表单字段与初始值，并建立双向同步 */
function buildFormFromSchema(schemaJson) {
  const schema = safeJsonParse(schemaJson, {})
  testFormFields.value = buildFormFields(schema)
  testFormValues.value = buildExampleValue(schema)
}

function safeJsonParse(str, fallback) {
  try {
    return JSON.parse(str)
  } catch (e) {
    return fallback
  }
}

/** 解析根级 properties 为表单字段描述 */
function buildFormFields(schema) {
  if (!schema || typeof schema !== 'object') return []
  if (schema.type !== 'object' && !schema.properties) return []
  const props = schema.properties || {}
  const required = Array.isArray(schema.required) ? schema.required : []
  return Object.entries(props).map(([key, prop]) => {
    const control = resolveControl(prop)
    return {
      key,
      type: Array.isArray(prop.type)
        ? prop.type.filter(t => t !== 'null').join('/')
        : (prop.type || 'any'),
      required: required.includes(key),
      description: prop.description || '',
      control,
      options: prop.enum || []
    }
  })
}

/** 字段类型 -> 渲染控件 */
function resolveControl(prop) {
  if (Array.isArray(prop.enum) && prop.enum.length) return 'enum'
  const type = Array.isArray(prop.type) ? prop.type.find(t => t !== 'null') : prop.type
  if (type === 'string') return 'text'
  if (type === 'number' || type === 'integer') return 'number'
  if (type === 'boolean') return 'boolean'
  return 'json'
}

/** 复杂类型字段序列化为可编辑 JSON 字符串 */
function stringifyComplex(key) {
  return JSON.stringify(testFormValues.value[key], null, 2)
}

/** 编辑复杂类型字段时尝试解析 JSON 回写 */
function updateComplexField(key, raw) {
  try {
    testFormValues.value[key] = JSON.parse(raw)
  } catch (e) {
    // 不合法时不回写，运行前会统一校验 testArgs
  }
}

// 表单值变更后实时同步到提交用的 testArgs JSON
watch(
  testFormValues,
  (val) => {
    testArgs.value = JSON.stringify(val, null, 2)
  },
  { deep: true }
)

function closeTest() {
  testVisible.value = false
  testFormFields.value = []
  testFormValues.value = {}
  testArgs.value = '{}'
  testResult.value = ''
}

async function runTest() {
  testing.value = true
  try {
    let args = testArgs.value.trim()
    if (!args) args = '{}'
    try {
      JSON.parse(args)
    } catch (e) {
      testResult.value = '参数不是合法 JSON，请检查后重试'
      return
    }
    const res = await testMcpTool(currentServer.value.id, currentTool.value.name, args)
    testResult.value = JSON.stringify(JSON.parse(res), null, 2)
  } catch (e) {
    testResult.value = '调用失败：' + (e.message || e)
  } finally {
    testing.value = false
  }
}

function statusText(status) {
  const map = {
    connected: '已连接',
    disconnected: '未连接',
    error: '连接失败'
  }
  return map[status] || status
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

.manage-body {
  flex: 1;
  overflow-y: auto;
  padding: 0 24px 24px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.empty {
  text-align: center;
  color: #a0a6bd;
  font-size: 14px;
  padding: 60px 0;
}

.add-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-main);
  margin: 0 0 12px;
}

.form-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
}

.form-input {
  flex: 1;
  min-width: 180px;
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 13px;
  outline: none;
}

.form-input:focus {
  border-color: #4f66f9;
}

.flex-2 {
  flex: 2;
}

.server-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.server-main {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.server-icon {
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

.server-info {
  min-width: 0;
  flex: 1;
}

.server-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-main);
  margin: 0 0 6px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.status-badge {
  font-size: 11px;
  font-weight: 500;
  border-radius: 4px;
  padding: 2px 6px;
}

.status-badge.connected {
  background: #e6f9ed;
  color: #1aa857;
}

.status-badge.disconnected {
  background: #f2f3f7;
  color: #8a91ad;
}

.status-badge.error {
  background: #fff0f0;
  color: #e14b4b;
}

.tool-count {
  font-size: 12px;
  font-weight: 400;
  color: #8a91ad;
}

.server-url {
  font-size: 12px;
  color: #8a91ad;
  margin: 0 0 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: Consolas, monospace;
}

.server-error {
  font-size: 12px;
  color: #e14b4b;
  margin: 0 0 4px;
}

.server-desc {
  font-size: 12px;
  color: var(--text-sub);
  margin: 0;
}

.server-actions {
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
  border-color: #e14b4b;
  color: #e14b4b;
}

.action-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.tool-panel {
  border-top: 1px solid var(--border);
  padding-top: 12px;
}

.tool-panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-main);
  margin-bottom: 8px;
}

.tool-empty {
  font-size: 13px;
  color: #a0a6bd;
  padding: 12px 0;
}

.tool-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tool-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  background: #f9f9fb;
  border-radius: 8px;
  padding: 10px 12px;
}

.tool-info {
  min-width: 0;
}

.tool-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-main);
  margin-right: 8px;
}

.tool-desc {
  font-size: 12px;
  color: var(--text-sub);
}

.tool-source {
  display: inline-block;
  margin-left: 8px;
  font-size: 11px;
  color: #8a91ad;
  background: #eef0f7;
  padding: 1px 6px;
  border-radius: 4px;
}

.tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--border);
  padding-bottom: 8px;
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

.local-tools-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tool-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.btn-text {
  border: none;
  background: transparent;
  color: #4f66f9;
  font-size: 12px;
  cursor: pointer;
}

.btn-text:hover {
  text-decoration: underline;
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
  width: 760px;
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

.checkbox-label {
  display: flex !important;
  align-items: center;
  gap: 6px;
  cursor: pointer;
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
}

.form-hint {
  font-size: 12px;
  color: #8a91ad;
  margin: -2px 0 8px;
}

.code-textarea {
  font-family: 'JetBrains Mono', Consolas, monospace;
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
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.schema-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 12px;
}
.schema-field {
  background: #f9f9fb;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px 12px;
}
.field-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-main);
  margin: 0 0 4px;
}
.field-type {
  font-size: 11px;
  color: #8a91ad;
  font-weight: 400;
  background: #eef0f7;
  padding: 1px 5px;
  border-radius: 4px;
}
.required {
  color: #e14b4b;
  font-weight: 700;
}
.field-desc {
  font-size: 12px;
  color: #8a91ad;
  margin: 0 0 8px;
}
.field-boolean {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  cursor: pointer;
}
.raw-json {
  margin-top: 4px;
  font-size: 13px;
  color: var(--text-main);
}
.raw-json summary {
  cursor: pointer;
  color: #4f66f9;
  user-select: none;
}

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
