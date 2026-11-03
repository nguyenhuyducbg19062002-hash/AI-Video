<template>
  <div v-if="show" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-sm">
    <form @submit.prevent="submit" class="bg-slate-900 border border-slate-700 rounded-2xl shadow-2xl max-w-2xl w-full p-6 space-y-4 max-h-[92vh] overflow-y-auto">
      <div class="flex items-center justify-between pb-3 border-b border-slate-800">
        <h3 class="text-lg font-bold text-sky-400">{{ project ? 'Sửa phim' : 'Thêm phim mới' }}</h3>
        <button type="button" @click="$emit('close')" class="text-slate-400 hover:text-white">✕</button>
      </div>

      <div>
        <label class="block text-xs font-medium text-slate-300 mb-1">Tên phim <span class="text-rose-400">*</span></label>
        <input v-model="form.name" required placeholder="Ví dụ: Tập 5. Hà - Cupid"
          class="w-full px-3 py-2 rounded-xl bg-slate-950 border border-slate-700 text-sm focus:border-sky-500 focus:outline-none" />
      </div>

      <FolderInput v-model="form.sourceDir" label="Thư mục kết quả của Omni-Flash-Pipeline *"
        hint="Thư mục output/<tên phim>/ có sẵn reference-prompts.xlsx và all-video-prompts.xlsx." />
      <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
        <FolderInput v-model="form.imageDir" label="Folder Ảnh *" hint="Ảnh reference đã duyệt lưu ở đây, tên = ID (CHAR-DOMINIC-1.png)." />
        <FolderInput v-model="form.videoDir" label="Folder Video *" hint="Video đã duyệt lưu ở đây, tên = STT (001.mp4)." />
      </div>

      <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div>
          <label class="block text-xs font-medium text-slate-300 mb-1">Chế độ chạy</label>
          <div class="flex gap-2">
            <button v-for="m in modes" :key="m.value" type="button" @click="form.runMode = m.value"
              :class="form.runMode === m.value ? 'bg-sky-600 text-white border-sky-500' : 'bg-slate-950 text-slate-300 border-slate-700'"
              class="flex-1 px-3 py-2 rounded-xl border text-xs font-semibold text-left">
              <div>{{ m.label }}</div>
              <div class="font-normal opacity-80 mt-0.5">{{ m.desc }}</div>
            </button>
          </div>
        </div>
        <div>
          <label class="block text-xs font-medium text-slate-300 mb-1">Nền tảng</label>
          <select v-model="form.provider" class="w-full px-3 py-2 rounded-xl bg-slate-950 border border-slate-700 text-sm">
            <option value="FLOW_BROWSER">Google Flow (trình duyệt Edge)</option>
            <option value="VERTEX_API">Vertex AI API (Imagen / Veo)</option>
            <option value="FAKE">Chạy thử (không gọi nền tảng nào)</option>
          </select>
          <p class="text-[11px] text-slate-500 mt-1">Prompt Omni dùng tới 7 ảnh reference: nên dùng Google Flow.</p>
        </div>
      </div>

      <div class="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div>
          <label class="block text-xs font-medium text-slate-300 mb-1">Song song</label>
          <input v-model.number="form.maxParallel" type="number" min="1" max="20" :disabled="form.runMode === 'FOREGROUND'"
            class="w-full px-3 py-2 rounded-xl bg-slate-950 border border-slate-700 text-sm disabled:opacity-50" />
        </div>
        <div>
          <label class="block text-xs font-medium text-slate-300 mb-1">Số lần thử lỗi</label>
          <input v-model.number="form.maxAttempts" type="number" min="1" max="10"
            class="w-full px-3 py-2 rounded-xl bg-slate-950 border border-slate-700 text-sm" />
        </div>
        <div class="col-span-2">
          <label class="block text-xs font-medium text-slate-300 mb-1">Tỉ lệ khung</label>
          <select v-model="form.aspectRatio" class="w-full px-3 py-2 rounded-xl bg-slate-950 border border-slate-700 text-sm">
            <option>16:9</option><option>9:16</option><option>1:1</option>
          </select>
        </div>
      </div>

      <div v-if="form.provider === 'FLOW_BROWSER'">
        <label class="block text-xs font-medium text-slate-300 mb-1">Link dự án trên Flow (tùy chọn)</label>
        <input v-model="form.flowProjectUrl" placeholder="https://labs.google/fx/tools/flow/project/..."
          class="w-full px-3 py-2 rounded-xl bg-slate-950 border border-slate-700 text-sm font-mono" />
      </div>

      <div class="flex justify-end gap-3 pt-3 border-t border-slate-800">
        <button type="button" @click="$emit('close')" class="px-4 py-2 rounded-xl border border-slate-700 hover:bg-slate-800 text-sm">Hủy</button>
        <button type="submit" :disabled="saving" class="px-5 py-2 rounded-xl bg-sky-600 hover:bg-sky-500 text-white font-semibold text-sm disabled:opacity-50">
          {{ project ? 'Lưu' : 'Tạo phim' }}
        </button>
      </div>
    </form>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import FolderInput from './FolderInput.vue'
import { projectsApi, errMsg } from '@/api'
import { store } from '@/store'

const props = defineProps({ show: Boolean, project: { type: Object, default: null } })
const emit = defineEmits(['close', 'saved'])

const modes = [
  { value: 'BACKGROUND', label: 'Chạy ngầm', desc: 'Tool tự tạo, bạn chỉ duyệt' },
  { value: 'FOREGROUND', label: 'Mở Flow', desc: 'Điền sẵn, bạn tự bấm tạo' },
]

const blank = () => ({
  name: '', sourceDir: '', imageDir: '', videoDir: '', runMode: 'BACKGROUND', provider: 'FLOW_BROWSER',
  flowProjectUrl: '', aspectRatio: '16:9', maxParallel: 3, maxAttempts: 3,
})
const form = ref(blank())
const saving = ref(false)

watch(() => [props.show, props.project], () => {
  if (!props.show) return
  form.value = props.project ? { ...blank(), ...props.project, flowProjectUrl: props.project.flowProjectUrl || '' } : blank()
}, { immediate: true })

async function submit() {
  saving.value = true
  try {
    const saved = props.project
      ? await projectsApi.update(props.project.id, form.value)
      : await projectsApi.create(form.value)
    store.toast(props.project ? 'Đã lưu phim' : 'Đã tạo phim. Bấm "Import prompt" để nạp dữ liệu.', 'success')
    emit('saved', saved)
  } catch (e) {
    store.toast(errMsg(e), 'error')
  } finally {
    saving.value = false
  }
}
</script>
