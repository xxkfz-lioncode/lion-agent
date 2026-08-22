<template>
  <Teleport to="body">
    <Transition name="confirm-fade">
      <div v-if="modelValue" class="confirm-mask" @click.self="handleCancel">
        <div class="confirm-dialog">
          <div class="confirm-icon" v-if="iconVisible">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
              <line x1="12" y1="9" x2="12" y2="13"/>
              <line x1="12" y1="17" x2="12.01" y2="17"/>
            </svg>
          </div>
          <h3 class="confirm-title">{{ title }}</h3>
          <p class="confirm-content">{{ content }}</p>
          <div class="confirm-footer">
            <button class="confirm-btn cancel" @click="handleCancel">{{ cancelText }}</button>
            <button
              class="confirm-btn"
              :class="type"
              :disabled="loading"
              @click="handleConfirm"
            >
              {{ loading ? confirmLoadingText : confirmText }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, default: '提示' },
  content: { type: String, default: '' },
  type: { type: String, default: 'danger' },
  confirmText: { type: String, default: '确定' },
  confirmLoadingText: { type: String, default: '处理中...' },
  cancelText: { type: String, default: '取消' },
  iconVisible: { type: Boolean, default: true },
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['confirm', 'cancel', 'update:modelValue'])

function handleConfirm() {
  emit('confirm')
}

function handleCancel() {
  if (props.loading) return
  emit('cancel')
  emit('update:modelValue', false)
}
</script>

<style scoped>
.confirm-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.confirm-dialog {
  width: 420px;
  max-width: 90vw;
  background: #fff;
  border-radius: 16px;
  padding: 28px;
  text-align: center;
  box-shadow: 0 20px 48px rgba(0, 0, 0, 0.15);
}

.confirm-icon {
  width: 52px;
  height: 52px;
  margin: 0 auto 16px;
  border-radius: 50%;
  background: #fff5f5;
  color: var(--danger, #ef4444);
  display: flex;
  align-items: center;
  justify-content: center;
}

.confirm-icon svg {
  width: 28px;
  height: 28px;
}

.confirm-title {
  margin: 0 0 10px;
  font-size: 17px;
  font-weight: 600;
  color: var(--text-main, #1f2937);
}

.confirm-content {
  margin: 0 0 24px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--text-sub, #6b7280);
  white-space: pre-line;
}

.confirm-footer {
  display: flex;
  justify-content: center;
  gap: 12px;
}

.confirm-btn {
  min-width: 92px;
  padding: 9px 18px;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
  border: 1px solid transparent;
}

.confirm-btn.cancel {
  background: #fff;
  border-color: var(--border, #e5e7eb);
  color: var(--text-main, #374151);
}

.confirm-btn.cancel:hover {
  border-color: var(--primary, #6366f1);
  color: var(--primary, #6366f1);
}

.confirm-btn.danger {
  background: var(--danger, #ef4444);
  color: #fff;
}

.confirm-btn.danger:hover:not(:disabled) {
  background: #dc2626;
}

.confirm-btn.primary {
  background: var(--primary, #6366f1);
  color: #fff;
}

.confirm-btn.primary:hover:not(:disabled) {
  opacity: 0.9;
}

.confirm-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.confirm-fade-enter-active,
.confirm-fade-leave-active {
  transition: opacity 0.2s ease;
}

.confirm-fade-enter-from,
.confirm-fade-leave-to {
  opacity: 0;
}

.confirm-fade-enter-active .confirm-dialog,
.confirm-fade-leave-active .confirm-dialog {
  transition: transform 0.2s ease;
}

.confirm-fade-enter-from .confirm-dialog,
.confirm-fade-leave-to .confirm-dialog {
  transform: scale(0.95);
}
</style>
