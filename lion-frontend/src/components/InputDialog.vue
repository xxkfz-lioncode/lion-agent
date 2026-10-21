<template>
  <Teleport to="body">
    <Transition name="input-dialog-fade">
      <div v-if="modelValue" class="input-dialog-mask" @click.self="handleCancel">
        <div class="input-dialog">
          <h3 class="input-dialog-title">{{ title }}</h3>
          <div class="input-dialog-body">
            <input
              ref="inputRef"
              v-model="localValue"
              type="text"
              class="input-dialog-field"
              :placeholder="placeholder"
              :maxlength="maxlength"
              :disabled="loading"
              @keydown.enter.exact.prevent="handleConfirm"
              @keydown.esc="handleCancel"
            >
            <p v-if="errorTip" class="input-dialog-error">{{ errorTip }}</p>
          </div>
          <div class="input-dialog-footer">
            <button class="input-dialog-btn cancel" :disabled="loading" @click="handleCancel">
              {{ cancelText }}
            </button>
            <button class="input-dialog-btn primary" :disabled="loading" @click="handleConfirm">
              {{ loading ? confirmLoadingText : confirmText }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, default: '请输入' },
  value: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  maxlength: { type: Number, default: 128 },
  required: { type: Boolean, default: true },
  confirmText: { type: String, default: '确定' },
  confirmLoadingText: { type: String, default: '保存中...' },
  cancelText: { type: String, default: '取消' },
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'confirm', 'cancel'])

const inputRef = ref(null)
const localValue = ref(props.value)
const errorTip = ref('')

watch(() => props.modelValue, (visible) => {
  if (visible) {
    localValue.value = props.value
    errorTip.value = ''
    nextTick(() => {
      inputRef.value?.focus()
      inputRef.value?.select()
    })
  }
})

watch(() => props.value, (val) => {
  localValue.value = val
})

function handleConfirm() {
  const val = localValue.value.trim()
  if (props.required && val === '') {
    errorTip.value = '内容不能为空'
    inputRef.value?.focus()
    return
  }
  if (val.length > props.maxlength) {
    errorTip.value = `最多输入 ${props.maxlength} 个字符`
    return
  }
  errorTip.value = ''
  emit('confirm', val)
}

function handleCancel() {
  if (props.loading) return
  errorTip.value = ''
  emit('cancel')
  emit('update:modelValue', false)
}
</script>

<style scoped>
.input-dialog-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.input-dialog {
  width: 420px;
  max-width: 90vw;
  background: #fff;
  border-radius: 16px;
  padding: 24px;
  box-shadow: 0 20px 48px rgba(0, 0, 0, 0.15);
}

.input-dialog-title {
  margin: 0 0 16px;
  font-size: 17px;
  font-weight: 600;
  color: var(--text-main, #1f2937);
}

.input-dialog-body {
  margin-bottom: 20px;
}

.input-dialog-field {
  width: 100%;
  box-sizing: border-box;
  padding: 10px 12px;
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.5;
  color: var(--text-main, #1f2937);
  background: #fff;
  outline: none;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.input-dialog-field:focus {
  border-color: var(--primary, #6366f1);
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.12);
}

.input-dialog-field:disabled {
  background: #f3f4f6;
  cursor: not-allowed;
}

.input-dialog-error {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--danger, #ef4444);
}

.input-dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

.input-dialog-btn {
  min-width: 80px;
  padding: 9px 18px;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
  border: 1px solid transparent;
}

.input-dialog-btn.cancel {
  background: #fff;
  border-color: var(--border, #e5e7eb);
  color: var(--text-main, #374151);
}

.input-dialog-btn.cancel:hover:not(:disabled) {
  border-color: var(--primary, #6366f1);
  color: var(--primary, #6366f1);
}

.input-dialog-btn.primary {
  background: var(--primary, #6366f1);
  color: #fff;
}

.input-dialog-btn.primary:hover:not(:disabled) {
  opacity: 0.9;
}

.input-dialog-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.input-dialog-fade-enter-active,
.input-dialog-fade-leave-active {
  transition: opacity 0.2s ease;
}

.input-dialog-fade-enter-from,
.input-dialog-fade-leave-to {
  opacity: 0;
}

.input-dialog-fade-enter-active .input-dialog,
.input-dialog-fade-leave-active .input-dialog {
  transition: transform 0.2s ease;
}

.input-dialog-fade-enter-from .input-dialog,
.input-dialog-fade-leave-to .input-dialog {
  transform: scale(0.95);
}
</style>
