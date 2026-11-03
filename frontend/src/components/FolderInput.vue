<template>
  <div>
    <label class="block text-xs font-medium text-slate-300 mb-1">{{ label }}</label>
    <div class="flex gap-2">
      <input
        :value="modelValue"
        @input="$emit('update:modelValue', $event.target.value)"
        type="text"
        :placeholder="placeholder"
        class="flex-1 min-w-0 px-3 py-2 rounded-xl bg-slate-950 border border-slate-700 text-slate-100 text-sm font-mono focus:border-sky-500 focus:outline-none" />
      <button type="button" @click="pick" :disabled="picking"
        class="px-3 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 border border-slate-700 text-xs font-semibold whitespace-nowrap disabled:opacity-50">
        {{ picking ? 'Đang mở…' : 'Chọn…' }}
      </button>
    </div>
    <p v-if="hint" class="text-[11px] text-slate-500 mt-1">{{ hint }}</p>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { systemApi, errMsg } from '@/api'
import { store } from '@/store'

const props = defineProps({
  modelValue: { type: String, default: '' },
  label: { type: String, required: true },
  hint: { type: String, default: '' },
  placeholder: { type: String, default: 'D:\\...' },
})
const emit = defineEmits(['update:modelValue'])
const picking = ref(false)

// Hộp thoại chọn thư mục của Windows (mở bên dưới trình duyệt nếu cửa sổ trình duyệt đang được phóng to)
async function pick() {
  picking.value = true
  try {
    const { path } = await systemApi.pickFolder(props.modelValue)
    if (path) emit('update:modelValue', path)
  } catch (e) {
    store.toast(errMsg(e), 'error')
  } finally {
    picking.value = false
  }
}
</script>
