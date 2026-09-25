<template>
  <div class="flow-graph" ref="wrapRef">
    <!-- 空画布（基础聊天助手没有编排流程） -->
    <div v-if="!nodes.length" class="graph-empty">
      <div class="empty-icon">🗒️</div>
      <div class="empty-text">该应用没有画布编排（基础聊天助手无流程）</div>
    </div>

    <div v-else class="canvas" :style="{ width: canvasW + 'px', height: canvasH + 'px' }">
      <!-- 连线 -->
      <svg class="edges" :width="canvasW" :height="canvasH">
        <path
          v-for="e in renderEdges"
          :key="e.id"
          :d="e.path"
          class="edge"
          :class="{ active: stageRunning }"
        />
      </svg>

      <!-- 节点 -->
      <div
        v-for="n in renderNodes"
        :key="n.id"
        class="node"
        :class="[n.cat]"
        :style="{ left: n.left + 'px', top: n.top + 'px' }"
        :title="n.desc || n.type"
      >
        <span class="node-icon">{{ n.icon }}</span>
        <div class="node-text">
          <div class="node-title">{{ n.title }}</div>
          <div class="node-type">{{ n.label }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch, nextTick } from 'vue'

const props = defineProps({
  /** 后端归一化后的节点 */
  nodes: { type: Array, default: () => [] },
  /** 后端归一化后的连线 */
  edges: { type: Array, default: () => [] },
  /** 当前运行阶段（node_started / workflow_started …） */
  stage: { type: String, default: '' }
})

/** Dify 节点类型 → 展示图标 / 中文名 / 配色分类 */
const NODE_META = {
  start: { icon: '▶️', label: '开始', cat: 'io' },
  end: { icon: '🏁', label: '结束', cat: 'io' },
  answer: { icon: '💬', label: '直接回复', cat: 'io' },
  llm: { icon: '🤖', label: 'LLM', cat: 'model' },
  agent: { icon: '🧠', label: 'Agent', cat: 'model' },
  'knowledge-retrieval': { icon: '📚', label: '知识检索', cat: 'data' },
  'knowledge-index': { icon: '📚', label: '知识库', cat: 'data' },
  'document-extractor': { icon: '📄', label: '文档解析', cat: 'data' },
  'parameter-extractor': { icon: '🎯', label: '参数提取', cat: 'data' },
  'list-operator': { icon: '📋', label: '列表操作', cat: 'data' },
  'variable-aggregator': { icon: '🧩', label: '变量聚合', cat: 'data' },
  assigner: { icon: '🧩', label: '变量赋值', cat: 'data' },
  'variable-assigner': { icon: '🧩', label: '变量赋值', cat: 'data' },
  code: { icon: '💻', label: '代码执行', cat: 'code' },
  'template-transform': { icon: '📝', label: '模板转换', cat: 'code' },
  tool: { icon: '🔧', label: '工具', cat: 'tool' },
  'http-request': { icon: '🌐', label: 'HTTP 请求', cat: 'tool' },
  'if-else': { icon: '🔀', label: '条件分支', cat: 'logic' },
  'question-classifier': { icon: '🔀', label: '问题分类', cat: 'logic' },
  iteration: { icon: '🔁', label: '迭代', cat: 'logic' },
  loop: { icon: '🔁', label: '循环', cat: 'logic' },
  note: { icon: '🗒️', label: '备注', cat: 'note' }
}

/** 节点渲染尺寸（固定尺寸 + 缩放坐标，保证文字清晰） */
const NODE_W = 168
const NODE_H = 52
const PADDING = 24

const wrapRef = ref(null)
const scale = ref(1)

const hasValidPos = computed(() =>
  props.nodes.some((n) => Number.isFinite(Number(n.x)) && Number.isFinite(Number(n.y)))
)

/** 缩放比例：按容器宽度自适应，0.4 ~ 1.1 */
function calcScale() {
  if (!wrapRef.value || !props.nodes.length) return
  const xs = props.nodes.map((n) => Number(n.x) || 0)
  const ys = props.nodes.map((n) => Number(n.y) || 0)
  const graphW = Math.max(...xs) - Math.min(...xs) + NODE_W
  const avail = wrapRef.value.clientWidth - PADDING * 2
  if (avail > 0 && graphW > avail) {
    scale.value = Math.max(0.4, Math.min(1.1, avail / graphW))
  } else {
    scale.value = 1
  }
}

watch(
  () => props.nodes,
  async () => {
    await nextTick()
    calcScale()
  },
  { immediate: true, deep: false }
)

const bounds = computed(() => {
  if (!props.nodes.length) return { minX: 0, minY: 0, w: 0, h: 0 }
  const xs = props.nodes.map((n) => Number(n.x) || 0)
  const ys = props.nodes.map((n) => Number(n.y) || 0)
  return {
    minX: Math.min(...xs),
    minY: Math.min(...ys),
    w: Math.max(...xs) - Math.min(...xs),
    h: Math.max(...ys) - Math.min(...ys)
  }
})

const canvasW = computed(() => (bounds.value.w + NODE_W) * scale.value + PADDING * 2)
const canvasH = computed(() => (bounds.value.h + NODE_H + 40) * scale.value + PADDING * 2)

const renderNodes = computed(() => {
  if (!hasValidPos.value) return []
  const s = scale.value
  return props.nodes.map((n) => {
    const meta = NODE_META[n.type] || { icon: '⚙️', label: n.type || '节点', cat: 'code' }
    return {
      ...n,
      icon: meta.icon,
      label: meta.label,
      cat: meta.cat,
      left: (Number(n.x) - bounds.value.minX) * s + PADDING,
      top: (Number(n.y) - bounds.value.minY) * s + PADDING
    }
  })
})

const nodeMap = computed(() => {
  const m = {}
  renderNodes.value.forEach((n) => {
    m[n.id] = n
  })
  return m
})

const renderEdges = computed(() =>
  props.edges
    .filter((e) => nodeMap.value[e.source] && nodeMap.value[e.target])
    .map((e) => {
      const s = nodeMap.value[e.source]
      const t = nodeMap.value[e.target]
      const x1 = s.left + NODE_W
      const y1 = s.top + NODE_H / 2
      const x2 = t.left
      const y2 = t.top + NODE_H / 2
      const dx = Math.max(30, Math.abs(x2 - x1) * 0.5)
      return {
        id: e.id || `${e.source}-${e.target}`,
        path: `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`
      }
    })
)

const stageRunning = computed(() => !!props.stage && !props.stage.includes('finished'))
</script>

<style scoped>
.flow-graph {
  width: 100%;
  overflow: auto;
}

.canvas {
  position: relative;
}

.edges {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.edge {
  fill: none;
  stroke: #cbd5e1;
  stroke-width: 1.6;
}

.edge.active {
  stroke: #6366f1;
  stroke-width: 2;
  stroke-dasharray: 5 4;
  animation: dash 1s linear infinite;
}

@keyframes dash {
  to {
    stroke-dashoffset: -18;
  }
}

.node {
  position: absolute;
  display: flex;
  align-items: center;
  gap: 8px;
  width: 168px;
  height: 52px;
  padding: 0 10px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid #e2e8f0;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.08);
}

.node-icon {
  font-size: 18px;
}

.node-text {
  min-width: 0;
}

.node-title {
  font-size: 13px;
  font-weight: 600;
  color: #0f172a;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.node-type {
  font-size: 11px;
  color: #94a3b8;
}

/* 分类配色：左侧色条 */
.node::before {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  bottom: 8px;
  width: 3px;
  border-radius: 3px;
  background: #94a3b8;
}

.node.io::before { background: #10b981; }
.node.model::before { background: #8b5cf6; }
.node.data::before { background: #f59e0b; }
.node.code::before { background: #3b82f6; }
.node.tool::before { background: #06b6d4; }
.node.logic::before { background: #f97316; }
.node.note::before { background: #cbd5e1; }

.graph-empty {
  padding: 40px 0;
  text-align: center;
  color: #94a3b8;
}

.empty-icon {
  font-size: 32px;
  margin-bottom: 8px;
}
</style>
