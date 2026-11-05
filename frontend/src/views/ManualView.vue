<template>
  <div class="space-y-4">
    <div v-if="!store.current" class="card p-6 text-slate-500">Chọn hoặc thêm một phim trước.</div>

    <template v-else>
      <!-- Phim đang chạy tự động: worker có thể nhận đúng cảnh người dùng đang làm -->
      <div v-if="state?.autoRunning" class="card p-4 border-amber-300 bg-amber-50 flex items-center justify-between gap-4">
        <div class="text-sm text-amber-800">
          Phim này đang <b>chạy tự động</b>. Tạm dừng trước khi tự làm, để app không chạy trùng cảnh với bạn.
        </div>
        <button class="btn-primary shrink-0" @click="pauseAuto">Tạm dừng chạy tự động</button>
      </div>

      <div class="card p-4 flex flex-wrap items-center gap-x-6 gap-y-2 text-sm">
        <div><span class="text-slate-500">Còn chờ làm:</span> <b>{{ state?.remaining ?? '…' }}</b> cảnh</div>
        <div v-if="skipped.length"><span class="text-slate-500">Để sau:</span> <b>{{ skipped.length }}</b>
          <button class="ml-1 text-indigo-600 hover:underline" @click="clearSkipped">làm lại từ đầu</button></div>
        <div class="min-w-0 flex-1 truncate"><span class="text-slate-500">App chờ file tải về trong:</span>
          <span class="font-mono text-xs">{{ downloadsDir || '…' }}</span></div>
        <label class="flex items-center gap-2 select-none cursor-pointer">
          <input type="checkbox" v-model="autoImport" /> Tự nhận file tải về
        </label>
      </div>

      <div v-if="loading && !job" class="card p-6 text-slate-500">Đang tải cảnh…</div>

      <div v-else-if="!job" class="card p-8 text-center space-y-3">
        <CheckCircle2 class="w-10 h-10 mx-auto text-emerald-500" />
        <div class="text-slate-700 font-medium">{{ state?.note || 'Không còn cảnh nào' }}</div>
        <button class="btn" @click="load">Kiểm tra lại</button>
      </div>

      <div v-else class="card p-5 space-y-4">
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0">
            <div class="flex items-center gap-2">
              <span class="px-2 py-0.5 rounded-md text-xs font-semibold"
                :class="job.kind === 'VIDEO' ? 'bg-violet-100 text-violet-700' : 'bg-sky-100 text-sky-700'">
                {{ job.kind === 'VIDEO' ? 'Video' : 'Ảnh' }}
              </span>
              <span class="font-mono font-semibold text-slate-800">{{ job.externalId }}</span>
              <StatusBadge :status="job.status" />
            </div>
            <div v-if="job.summaryVi" class="text-sm text-slate-600 mt-1">{{ job.summaryVi }}</div>
          </div>
          <div class="flex gap-2 shrink-0">
            <button class="btn" @click="skip" title="Phím S">Để sau</button>
            <button class="btn text-rose-600" @click="cancelScene">Bỏ cảnh này</button>
          </div>
        </div>

        <!-- Các bước người dùng làm trên Flow -->
        <ol class="grid md:grid-cols-3 gap-3 text-sm">
          <li class="rounded-xl border border-slate-200 p-3 space-y-2">
            <div class="font-semibold text-slate-700">1. Gắn ảnh reference</div>
            <div v-if="job.references.length" class="text-slate-500 text-xs">
              {{ job.references.length }} ảnh, đúng thứ tự. Bấm mở rồi chọn hết (Ctrl+A) và kéo thả vào Flow.
            </div>
            <div v-else class="text-slate-500 text-xs">Cảnh này không cần ảnh.</div>
            <button v-if="job.references.length" class="btn w-full" @click="openRefs" title="Phím O">
              <FolderOpen class="w-4 h-4" /> Mở thư mục ảnh
            </button>
          </li>
          <li class="rounded-xl border border-slate-200 p-3 space-y-2">
            <div class="font-semibold text-slate-700">2. Dán prompt, bấm Tạo</div>
            <div class="text-slate-500 text-xs">
              Trên Flow, gõ <b>@</b> rồi chọn đúng ảnh cho từng tag:
              <span v-for="t in tags" :key="t" class="inline-block font-mono text-[11px] px-1 rounded bg-slate-100 mr-1">@{{ t }}</span>
            </div>
            <div class="flex gap-2">
              <button class="btn-primary flex-1" @click="copyPrompt" title="Phím C">
                <Copy class="w-4 h-4" /> {{ copied ? 'Đã copy' : 'Copy prompt' }}
              </button>
              <button class="btn" @click="openFlow" title="Mở Google Flow">
                <ExternalLink class="w-4 h-4" />
              </button>
            </div>
          </li>
          <li class="rounded-xl border p-3 space-y-2" :class="found.length ? 'border-emerald-300 bg-emerald-50/50' : 'border-slate-200'">
            <div class="font-semibold text-slate-700">3. Tải xuống</div>
            <div v-if="importing" class="text-xs text-emerald-700">Đang lưu {{ importing }}…</div>
            <div v-else-if="!found.length" class="text-xs text-slate-500 flex items-center gap-1.5">
              <Loader2 class="w-3.5 h-3.5 animate-spin" /> Đang chờ file {{ job.kind === 'VIDEO' ? 'video' : 'ảnh' }} mới…
            </div>
            <div v-for="f in found" :key="f.path" class="flex items-center gap-2 text-xs">
              <span class="flex-1 truncate font-mono" :title="f.path">{{ f.name }}</span>
              <button class="btn !py-1 !px-2 !text-xs" @click="importFile(f)">Dùng file này</button>
            </div>
            <div class="flex gap-3 text-xs">
              <label class="text-indigo-600 hover:underline cursor-pointer">
                Chọn file khác…
                <input type="file" class="hidden" :accept="job.kind === 'VIDEO' ? '.mp4,.mov,.webm' : '.png,.jpg,.jpeg,.webp'" @change="pickFile" />
              </label>
              <button class="text-indigo-600 hover:underline" @click="lookBack">Tìm file tải trong 30 phút trước</button>
            </div>
          </li>
        </ol>

        <RefStrip v-if="job.references.length" :job="job" :project-id="store.current.project.id" :size="120" :editable="false" />

        <div>
          <div class="text-xs font-semibold text-slate-500 mb-1">Prompt</div>
          <pre class="text-xs whitespace-pre-wrap bg-slate-50 border border-slate-200 rounded-xl p-3 max-h-72 overflow-y-auto">{{ job.prompt }}</pre>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { CheckCircle2, Copy, ExternalLink, FolderOpen, Loader2 } from 'lucide-vue-next'
import RefStrip from '@/components/RefStrip.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { errMsg, jobsApi, manualApi, projectsApi } from '@/api'
import { store } from '@/store'

const FLOW_URL = 'https://flow.google.com/'

const state = ref(null)
const loading = ref(false)
const skipped = ref([])
const startedAt = ref(Date.now())
const found = ref([])
const downloadsDir = ref('')
const autoImport = ref(true)
const importing = ref('')
const copied = ref(false)

const job = computed(() => state.value?.job || null)
/** Các tag @ID trong prompt, theo thứ tự xuất hiện: người dùng cần gắn chip ảnh cho từng tag trên Flow */
const tags = computed(() => {
  const out = []
  for (const m of (job.value?.prompt || '').matchAll(/@([A-Za-z][A-Za-z0-9_]*(?:-[A-Za-z0-9_]+)+)/g)) {
    if (!out.includes(m[1])) out.push(m[1])
  }
  return out
})

async function load() {
  if (!store.current) return
  loading.value = true
  try {
    const prev = job.value?.id
    state.value = await manualApi.next(store.current.project.id, skipped.value)
    if (job.value?.id !== prev) {
      startedAt.value = Date.now()
      found.value = []
      copied.value = false
    }
  } catch (e) {
    store.toast(errMsg(e), 'error')
  } finally {
    loading.value = false
  }
}

async function pauseAuto() {
  try {
    await projectsApi.pause(store.current.project.id)
    await store.refresh()
    await load()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

async function copyPrompt() {
  try {
    await navigator.clipboard.writeText(job.value.prompt || '')
    copied.value = true
  } catch {
    store.toast('Không copy được: hãy bôi đen prompt bên dưới rồi Ctrl+C', 'error')
  }
}

async function openRefs() {
  try {
    await manualApi.openRefs(job.value.id)
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

function openFlow() {
  window.open(store.current.project.flowProjectUrl || FLOW_URL, '_blank')
}

function skip() {
  if (!job.value) return
  skipped.value = [...skipped.value, job.value.id]
  load()
}

function clearSkipped() {
  skipped.value = []
  load()
}

async function cancelScene() {
  if (!confirm(`Bỏ cảnh ${job.value.externalId}? Cảnh chuyển sang "đã hủy", có thể chạy lại sau ở Danh sách cảnh.`)) return
  try {
    await jobsApi.cancel(job.value.id)
    await load()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

function lookBack() {
  startedAt.value = Date.now() - 30 * 60 * 1000
  poll()
}

async function finish(name, action) {
  importing.value = name
  try {
    const saved = await action()
    store.toast(`Đã lưu ${saved.externalId} → ${saved.finalPath || 'folder phim'}`, 'success')
    store.refresh()
    await load()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  } finally {
    importing.value = ''
  }
}

/** File đã thử nhận mà lỗi (hỏng, sai loại…): không tự nhận lại mỗi 2 giây, người dùng vẫn bấm "Dùng file này" được */
const failed = new Set()

function importFile(f) {
  return finish(f.name, async () => {
    try {
      return await manualApi.importDownload(job.value.id, f.path)
    } catch (e) {
      failed.add(f.path + '|' + f.modified)
      throw e
    }
  })
}

function pickFile(ev) {
  const file = ev.target.files?.[0]
  ev.target.value = ''
  if (file) finish(file.name, () => jobsApi.manualResult(job.value.id, file))
}

/** Quét thư mục Downloads: có đúng một file mới thì (nếu bật) tự nhận luôn; nhiều file thì để người dùng chọn */
async function poll() {
  if (!job.value || importing.value) return
  try {
    const r = await manualApi.downloads(startedAt.value, job.value.kind)
    downloadsDir.value = r.dir
    found.value = r.files
    const fresh = r.files.filter((f) => !failed.has(f.path + '|' + f.modified))
    if (autoImport.value && r.files.length === 1 && fresh.length === 1) await importFile(fresh[0])
  } catch {
    /* mất kết nối tạm thời: lượt sau quét lại */
  }
}

function onKey(e) {
  if (!job.value || e.ctrlKey || e.altKey || e.metaKey || ['INPUT', 'TEXTAREA', 'SELECT'].includes(e.target.tagName)) return
  const k = e.key.toLowerCase()
  if (k === 'c') copyPrompt()
  else if (k === 'o' && job.value.references.length) openRefs()
  else if (k === 's') skip()
}

// Danh sách phim tải xong (lúc mới mở app) hoặc đổi phim: lấy cảnh của phim đó
watch(() => store.current?.project.id, (id) => {
  skipped.value = []
  state.value = null
  if (id) load()
}, { immediate: true })

let timer
onMounted(() => {
  timer = setInterval(poll, 2000)
  window.addEventListener('keydown', onKey)
})
onUnmounted(() => {
  clearInterval(timer)
  window.removeEventListener('keydown', onKey)
})
</script>
