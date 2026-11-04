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

        <div class="space-y-2">
          <FolderInput v-model="form.sourceDir" label="Thư mục kết quả của Omni-Flash-Pipeline (khuyên dùng)"
            placeholder="Chọn thư mục của tập: chứa Excel, ảnh tham chiếu, phim…"
            hint="Chọn thư mục này là đủ: app tự nạp các file Excel quen thuộc, tự tìm Folder Ảnh và Folder Video trong đó (không phân biệt hoa thường, đủ dấu hay thiếu dấu), chưa có thì tự tạo mới khi bấm Tạo phim." />
          <template v-if="!project">
            <div v-if="scanning" class="text-xs text-slate-500">Đang quét thư mục…</div>
            <div v-else-if="scanError" class="text-xs text-rose-600">{{ scanError }}</div>
            <ul v-else-if="scan" class="text-xs space-y-1 px-1">
              <li :class="scan.promptFiles.length ? 'text-emerald-700' : 'text-slate-500'">
                {{ scan.promptFiles.length ? `✓ Tìm thấy ${scan.promptFiles.length} file prompt (${scan.promptFiles.join(', ')})` : 'Không thấy file prompt trong thư mục này: thêm Excel sau ở trang Phim.' }}
              </li>
              <li :class="scan.imageExists ? 'text-emerald-700' : 'text-amber-700'">
                {{ scan.imageExists ? `✓ Dùng thư mục ảnh có sẵn "${base(scan.imageDir)}" (${scan.imageCount} ảnh)` : `+ Chưa có thư mục ảnh: sẽ tạo mới "${base(scan.imageDir)}"` }}
              </li>
              <li :class="scan.videoExists ? 'text-emerald-700' : 'text-amber-700'">
                {{ scan.videoExists ? `✓ Dùng thư mục video có sẵn "${base(scan.videoDir)}" (${scan.videoCount} video)` : `+ Chưa có thư mục video: sẽ tạo mới "${base(scan.videoDir)}"` }}
              </li>
            </ul>
          </template>
        </div>

        <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
          <FolderInput v-model="form.imageDir" label="Folder Ảnh" placeholder="Để trống: tự tìm / tạo"
            hint="Ảnh đã duyệt, tên = ID (CHAR-DOMINIC-1.png). Để trống thì app tự tìm trong thư mục kết quả ở trên, chưa có thì tạo mới." />
          <FolderInput v-model="form.videoDir" label="Folder Video" placeholder="Để trống: tự tìm / tạo"
            hint="Video đã duyệt, tên = mã phim + STT (tap-5-ha-cupid_001.mp4). Để trống thì app tự tìm trong thư mục kết quả ở trên, chưa có thì tạo mới." />
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
          :class="form.skipMissingRefs ? 'border-emerald-200 bg-emerald-50/50' : 'border-slate-200'">
          <input type="checkbox" v-model="form.skipMissingRefs" class="mt-0.5 w-4 h-4 accent-emerald-600" />
          <span>
            <span class="block text-sm font-medium text-slate-700">Có ảnh sẵn thì tạo video luôn, không tạo thêm ảnh</span>
            <span class="block text-[11px] text-slate-500 mt-0.5">
              Bật: chỉ tạo video, dùng ảnh nhân vật / bối cảnh / đồ vật có sẵn; ảnh panel chưa có thì bỏ qua.
              Tắt: tạo ảnh panel trước (giữ bố cục đúng kịch bản), duyệt xong mới tạo video.
            </span>
          </span>
        </label>

        <label v-if="!form.skipMissingRefs" class="flex items-start gap-3 p-3 rounded-xl border cursor-pointer transition"
          :class="form.waitAllImages ? 'border-indigo-200 bg-indigo-50/50' : 'border-slate-200'">
          <input type="checkbox" v-model="form.waitAllImages" class="mt-0.5 w-4 h-4 accent-indigo-600" />
          <span>
            <span class="block text-sm font-medium text-slate-700">Duyệt xong hết ảnh rồi mới tạo video</span>
            <span class="block text-[11px] text-slate-500 mt-0.5">
              Bật: tạo và duyệt toàn bộ ảnh reference trước, sau đó mới tự upload ảnh lên Flow để tạo video.
              Tắt (chạy theo cảnh): ảnh có sẵn thì bỏ qua; cảnh nào đủ ảnh thì tạo video ngay; cảnh nào thiếu ảnh thì app
              tạo đúng ảnh đó trước rồi mới tạo video. Ảnh không cảnh nào cần thì không tạo.
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
              <input v-model.number="form.maxParallel" type="number" min="1" max="20" class="input" title="Số cảnh chạy cùng lúc; tối đa bằng số tài khoản sẵn sàng (mỗi tài khoản một cửa sổ)" />
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

        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="label">Độ dài video</label>
            <select v-model="form.videoDuration" class="input">
              <option :value="null">Mặc định (theo prompt / nền tảng)</option>
              <option v-for="s in [4, 5, 6, 8, 10]" :key="s" :value="s">{{ s }} giây</option>
            </select>
          </div>
          <div>
            <label class="label">Chất lượng tải video về</label>
            <select v-model="form.videoQuality" class="input">
              <option value="480p">480p</option>
              <option value="720p">720p</option>
              <option value="1080p">1080p</option>
            </select>
          </div>
          <p class="hint col-span-2 -mt-2">Prompt Omni đã ghi sẵn thời lượng mỗi cảnh ("Requested duration: 10 seconds"); chỉ đổi độ dài khi nền tảng cần chọn riêng.</p>
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
  aspectRatio: '16:9', maxParallel: 3, maxAttempts: 3, waitAllImages: true,
  videoDuration: null, videoQuality: '720p', skipMissingRefs: true,
})
const form = ref(blank())
const saving = ref(false)

// Thư mục kết quả của Omni-Flash-Pipeline (phim mới): quét sau khi dừng gõ / chọn xong, rồi điền sẵn tên phim, Folder Ảnh,
// Folder Video. Chỉ ghi đè những ô còn trống hoặc do lần quét trước điền; ô người dùng tự gõ thì giữ nguyên.
// Backend cũng tự tìm / tạo khi hai ô này trống, nên bấm Tạo phim ngay (trước khi quét xong) vẫn đúng.
const scan = ref(null)
const scanning = ref(false)
const scanError = ref('')
let autoName = ''
let autoImage = ''
let autoVideo = ''
let scanTimer = null
let scanSeq = 0
const base = (p) => String(p || '').split(/[\\/]/).filter(Boolean).pop() || p

watch(() => form.value.sourceDir, (path) => {
  clearTimeout(scanTimer)
  scan.value = null
  scanError.value = ''
  const seq = ++scanSeq
  if (props.project || !path || !path.trim()) { scanning.value = false; return }
  scanning.value = true
  scanTimer = setTimeout(async () => {
    try {
      const s = await projectsApi.scanFolder(path.trim())
      if (seq !== scanSeq) return   // đã đổi thư mục trong lúc quét: bỏ kết quả cũ
      scan.value = s
      if (!form.value.name || form.value.name === autoName) {
        form.value.name = s.name
        autoName = s.name
      }
      if (!form.value.imageDir || form.value.imageDir === autoImage) {
        form.value.imageDir = s.imageDir
        autoImage = s.imageDir
      }
      if (!form.value.videoDir || form.value.videoDir === autoVideo) {
        form.value.videoDir = s.videoDir
        autoVideo = s.videoDir
      }
    } catch (e) {
      if (seq === scanSeq) scanError.value = errMsg(e)
    } finally {
      if (seq === scanSeq) scanning.value = false
    }
  }, 600)
})

watch(() => [props.show, props.project], () => {
  if (!props.show) return
  scan.value = null
  scanError.value = ''
  autoName = autoImage = autoVideo = ''
  form.value = props.project
    ? { ...blank(), ...props.project, waitAllImages: props.project.waitAllImages !== false,
        videoDuration: props.project.videoDuration ?? null, videoQuality: props.project.videoQuality || '720p',
        skipMissingRefs: !!props.project.skipMissingRefs }
    : blank()
}, { immediate: true })

async function submit() {
  saving.value = true
  try {
    const saved = props.project
      ? await projectsApi.update(props.project.id, form.value)
      : await projectsApi.create(form.value)
    // phim mới: backend đã tự nạp prompt + ảnh có sẵn; hiện kết quả (không có gì để nạp thì chỉ nhắc nhẹ)
    const notes = Array.isArray(saved?.autoImport) ? saved.autoImport : []
    store.toast(props.project ? 'Đã lưu phim'
      : 'Đã tạo phim.' + (notes.length ? '\n' + notes.join('\n') : ' Chưa có file prompt để nạp: thêm Excel ảnh / video ở trang Phim.'), 'success')
    emit('saved', saved)
  } catch (e) {
    store.toast(errMsg(e), 'error')
  } finally {
    saving.value = false
  }
}
</script>
