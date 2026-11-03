<template>
  <div v-if="show" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/40 backdrop-blur-[2px]">
    <form @submit.prevent="submit" class="card w-full max-w-2xl max-h-[92vh] overflow-y-auto shadow-2xl">
      <div class="card-head">
        <Film class="w-5 h-5 text-indigo-500 mt-0.5" />
        <div class="flex-1">
          <div class="card-title">{{ project ? 'Sửa phim' : 'Thêm phim mới' }}</div>
          <div class="card-sub">Nạp prompt từ thư mục kết quả của Omni-Flash-Pipeline</div>
        </div>
        <button type="button" @click="$emit('close')" class="text-slate-400 hover:text-slate-700"><X class="w-5 h-5" /></button>
      </div>

      <div class="p-5 space-y-4">
        <div>
          <label class="label">Tên phim <span class="text-rose-500">*</span></label>
          <input v-model="form.name" required placeholder="Ví dụ: Tập 5. Hà - Cupid" class="input" />
        </div>

        <FolderInput v-model="form.sourceDir" label="Thư mục kết quả của Omni-Flash-Pipeline *"
          placeholder="Chọn thư mục output/<tên phim>/…"
          hint="Thư mục có sẵn reference-prompts.xlsx và all-video-prompts.xlsx." />

        <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
          <FolderInput v-model="form.imageDir" label="Folder Ảnh *" hint="Ảnh đã duyệt, tên = ID (CHAR-DOMINIC-1.png)." />
          <FolderInput v-model="form.videoDir" label="Folder Video *" hint="Video đã duyệt, tên = mã phim + STT (tap-5-ha-cupid_001.mp4)." />
        </div>

        <div>
          <label class="label">Chế độ chạy</label>
          <div class="grid grid-cols-2 gap-2 p-1 rounded-xl bg-slate-100">
            <button v-for="m in modes" :key="m.value" type="button" @click="form.runMode = m.value"
              :class="form.runMode === m.value ? 'bg-white shadow-sm text-indigo-700' : 'text-slate-600 hover:text-slate-800'"
              class="px-3 py-2 rounded-lg text-left text-sm transition">
              <div class="font-medium flex items-center gap-1.5"><component :is="m.icon" class="w-4 h-4" /> {{ m.label }}</div>
              <div class="text-[11px] opacity-80 mt-0.5">{{ m.desc }}</div>
            </button>
          </div>
        </div>

        <label class="flex items-start gap-3 p-3 rounded-xl border cursor-pointer transition"
          :class="form.waitAllImages ? 'border-indigo-200 bg-indigo-50/50' : 'border-slate-200'">
          <input type="checkbox" v-model="form.waitAllImages" class="mt-0.5 w-4 h-4 accent-indigo-600" />
          <span>
            <span class="block text-sm font-medium text-slate-700">Duyệt xong hết ảnh rồi mới tạo video</span>
            <span class="block text-[11px] text-slate-500 mt-0.5">
              Bật: tạo và duyệt toàn bộ ảnh reference trước, sau đó mới tự upload ảnh lên Flow để tạo video.
              Tắt: cảnh nào đủ ảnh đã duyệt thì tạo video cảnh đó ngay (nhanh hơn).
            </span>
          </span>
        </label>

        <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <label class="label">Nền tảng</label>
            <select v-model="form.provider" class="input">
              <option value="FLOW_BROWSER">Google Flow (trình duyệt Edge)</option>
              <option value="VERTEX_API">Vertex AI API (Imagen / Veo)</option>
              <option value="FAKE">Chạy thử (không gọi nền tảng nào)</option>
            </select>
            <p class="hint">Prompt Omni dùng tới 7 ảnh reference: nên dùng Google Flow.</p>
          </div>
          <div class="grid grid-cols-3 gap-3">
            <div>
              <label class="label">Song song</label>
              <input v-model.number="form.maxParallel" type="number" min="1" max="20" :disabled="form.runMode === 'FOREGROUND'" class="input" />
            </div>
            <div>
              <label class="label">Số lần thử</label>
              <input v-model.number="form.maxAttempts" type="number" min="1" max="10" class="input" />
            </div>
            <div>
              <label class="label">Tỉ lệ</label>
              <select v-model="form.aspectRatio" class="input">
                <option>16:9</option><option>9:16</option><option>1:1</option>
              </select>
            </div>
          </div>
        </div>

        <div v-if="form.provider === 'FLOW_BROWSER'">
          <label class="label">Link dự án trên Flow (tùy chọn)</label>
          <input v-model="form.flowProjectUrl" placeholder="https://labs.google/fx/tools/flow/project/..." class="input font-mono !text-[13px]" />
        </div>
      </div>

      <div class="flex justify-end gap-2 px-5 py-4 border-t border-slate-100 bg-slate-50/60 rounded-b-2xl">
        <button type="button" @click="$emit('close')" class="btn">Hủy</button>
        <button type="submit" :disabled="saving" class="btn-primary">{{ project ? 'Lưu thay đổi' : 'Tạo phim' }}</button>
      </div>
    </form>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { Film, MonitorPlay, Sparkles, X } from 'lucide-vue-next'
import FolderInput from './FolderInput.vue'
import { projectsApi, errMsg } from '@/api'
import { store } from '@/store'

const props = defineProps({ show: Boolean, project: { type: Object, default: null } })
const emit = defineEmits(['close', 'saved'])

const modes = [
  { value: 'BACKGROUND', label: 'Chạy ngầm', desc: 'Tool tự tạo, bạn chỉ duyệt', icon: Sparkles },
  { value: 'FOREGROUND', label: 'Mở Flow', desc: 'Điền sẵn, bạn tự bấm tạo', icon: MonitorPlay },
]

const blank = () => ({
  name: '', sourceDir: '', imageDir: '', videoDir: '', runMode: 'BACKGROUND', provider: 'FLOW_BROWSER',
  flowProjectUrl: '', aspectRatio: '16:9', maxParallel: 3, maxAttempts: 3, waitAllImages: true,
})
const form = ref(blank())
const saving = ref(false)

watch(() => [props.show, props.project], () => {
  if (!props.show) return
  form.value = props.project
    ? { ...blank(), ...props.project, flowProjectUrl: props.project.flowProjectUrl || '', waitAllImages: props.project.waitAllImages !== false }
    : blank()
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
