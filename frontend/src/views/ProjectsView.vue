<template>
  <div class="space-y-4">
    <div class="grid grid-cols-1 xl:grid-cols-2 gap-4">
      <!-- Danh sách phim -->
      <section class="card flex flex-col min-h-[360px]">
        <div class="card-head">
          <Film class="w-5 h-5 text-indigo-500 mt-0.5" />
          <div class="flex-1">
            <div class="card-title">Danh sách phim <span class="count-pill ml-1">{{ store.projects.length }} phim</span></div>
            <div class="card-sub">Mỗi phim là một thư mục output của Omni-Flash-Pipeline</div>
          </div>
        </div>

        <div class="p-4 flex-1 flex flex-col gap-2">
          <div v-if="!store.projects.length" class="flex-1 flex flex-col items-center justify-center text-center py-10">
            <FileText class="w-9 h-9 text-indigo-300" />
            <div class="text-base text-slate-700 mt-3">Bắt đầu với phim đầu tiên</div>
            <div class="text-sm text-slate-500 mt-1 max-w-xs">Chọn thư mục kết quả của Omni-Flash-Pipeline, Folder Ảnh và Folder Video.</div>
          </div>

          <button v-for="pv in store.projects" :key="pv.project.id" @click="store.selectProject(pv.project.id)"
            :class="pv.project.id === store.projectId ? 'border-indigo-400 bg-indigo-50/60 ring-2 ring-indigo-100' : 'border-slate-200 hover:border-slate-300 bg-white'"
            class="w-full text-left px-4 py-3 rounded-xl border transition">
            <div class="flex items-center justify-between gap-2">
              <span class="font-medium text-slate-800 truncate">{{ pv.project.name }}</span>
              <StatusBadge :status="pv.project.status" />
            </div>
            <div class="mt-2 h-1.5 bg-slate-100 rounded-full overflow-hidden">
              <div class="h-full bg-gradient-to-r from-indigo-500 to-emerald-500" :style="{ width: pct(pv) + '%' }"></div>
            </div>
            <div class="flex justify-between text-[11px] text-slate-500 mt-1.5">
              <span>{{ pv.counts.SUCCESS }}/{{ pv.total }} xong · {{ pv.imagesTotal }} ảnh, {{ pv.total - pv.imagesTotal }} video</span>
              <span v-if="pv.counts.AWAITING_REVIEW" class="text-amber-600 font-medium">{{ pv.counts.AWAITING_REVIEW }} chờ duyệt</span>
            </div>
          </button>
        </div>

        <div class="px-4 pb-4">
          <button @click="openModal(null)" class="btn btn-block"><Plus class="w-4 h-4" /> Thêm phim mới</button>
        </div>
      </section>

      <!-- Chi tiết phim -->
      <section class="card flex flex-col min-h-[360px]">
        <div class="card-head">
          <Clapperboard class="w-5 h-5 text-indigo-500 mt-0.5" />
          <div class="flex-1 min-w-0">
            <div class="card-title truncate">{{ current ? current.project.name : 'Chi tiết phim' }}</div>
            <div class="card-sub">{{ current ? modeText(current.project) : 'Chọn một phim ở bên trái' }}</div>
          </div>
          <StatusBadge v-if="current" :status="current.project.status" />
        </div>

        <div v-if="!current" class="flex-1 flex flex-col items-center justify-center text-center p-10">
          <Images class="w-9 h-9 text-indigo-300" />
          <div class="text-base text-slate-700 mt-3">Chưa chọn phim</div>
          <div class="text-sm text-slate-500 mt-1">Thông tin, thư mục và tiến độ của phim sẽ hiện ở đây.</div>
        </div>

        <div v-else class="p-4 flex-1 flex flex-col gap-3">
          <div v-if="current.project.status === 'PAUSED' && current.project.pausedReason" class="note-warn whitespace-pre-wrap">
            <b>Tạm dừng:</b> {{ current.project.pausedReason }}
          </div>
          <div v-if="current.project.status === 'RUNNING' && current.readyAccounts === 0" class="note-danger">
            Không còn tài khoản {{ providerLabel(current.project.provider) }} nào sẵn sàng (hết quota, cần đăng nhập hoặc chưa có).
            Job sẽ tự chạy tiếp khi có tài khoản.
            <button @click="store.go('accounts')" class="underline font-medium ml-1">Mở Tài khoản</button>
          </div>
          <div v-if="current.total > 0 && current.imagesDone < current.imagesTotal" class="note-info">
            <template v-if="current.project.waitAllImages !== false">
              Giai đoạn 1 – ảnh: duyệt xong toàn bộ {{ current.imagesTotal }} ảnh thì app mới chuyển sang tạo video
              ({{ current.imagesDone }}/{{ current.imagesTotal }} ảnh đã duyệt).
            </template>
            <template v-else>
              Video của cảnh nào bắt đầu ngay khi mọi ảnh reference của cảnh đó đã được duyệt ({{ current.imagesDone }}/{{ current.imagesTotal }} ảnh xong).
            </template>
          </div>
          <div v-else-if="current.total > current.imagesTotal && current.imagesTotal > 0" class="note-info">
            Giai đoạn 2 – video: ảnh đã duyệt xong, app tự upload ảnh reference lên Flow theo đúng thứ tự từng cảnh rồi tạo video.
          </div>

          <div class="grid grid-cols-3 gap-2">
            <div v-for="s in stats" :key="s.key" class="rounded-xl border border-slate-200 px-3 py-2">
              <div class="text-[11px] font-medium" :class="s.color">{{ s.label }}</div>
              <div class="text-xl font-semibold text-slate-800">{{ s.value }}</div>
            </div>
          </div>

          <div class="space-y-1.5 text-xs">
            <button v-for="d in folders" :key="d.label" @click="openFolder(d.path)"
              class="w-full flex items-center gap-2 text-left px-3 py-2 rounded-xl border border-slate-200 hover:border-indigo-300 hover:bg-indigo-50/40 transition">
              <Folder class="w-4 h-4 text-slate-400 shrink-0" />
              <span class="text-slate-500 shrink-0">{{ d.label }}:</span>
              <span class="font-mono text-slate-700 truncate">{{ d.path }}</span>
            </button>
          </div>

          <div class="mt-auto grid grid-cols-2 gap-2">
            <button @click="doImport" :disabled="busy" class="btn"><Download class="w-4 h-4" /> Import prompt</button>
            <button v-if="current.project.status !== 'RUNNING'" @click="start" :disabled="busy || current.total === 0" class="btn-primary">
              <Play class="w-4 h-4" /> Chạy
            </button>
            <button v-else @click="pause" :disabled="busy" class="btn-warn"><Pause class="w-4 h-4" /> Tạm dừng</button>
            <button v-if="current.counts.AWAITING_REVIEW" @click="store.go('review')" class="btn-success col-span-2">
              <CheckCircle2 class="w-4 h-4" /> Duyệt {{ current.counts.AWAITING_REVIEW }} mục đang chờ
            </button>
            <button v-if="current.counts.FAILED + current.counts.CANCELLED > 0" @click="retryFailed" :disabled="busy" class="btn">
              <RotateCcw class="w-4 h-4" /> Thử lại mục lỗi
            </button>
            <button @click="verifyFiles" :disabled="busy" class="btn" title="Tìm file đã duyệt bị mất/hỏng trong Folder Ảnh/Video và cho tạo lại">
              <ShieldCheck class="w-4 h-4" /> Kiểm tra file thiếu/hỏng
            </button>
            <button @click="remove" class="btn-danger"><Trash2 class="w-4 h-4" /> Xóa phim</button>
          </div>
        </div>
      </section>
    </div>

    <!-- Cài đặt của phim (giống thanh "Cài đặt chung") -->
    <section v-if="current" class="card px-4 py-3">
      <div class="flex flex-wrap items-center gap-x-6 gap-y-2">
        <div class="flex items-center gap-2">
          <SlidersHorizontal class="w-4 h-4 text-slate-500" />
          <span class="font-medium text-slate-700">Cài đặt phim</span>
          <span class="px-2 py-0.5 rounded-md bg-slate-100 text-[11px] text-slate-500">Áp dụng cho cả ảnh và video của phim</span>
        </div>
        <div class="flex flex-wrap items-center gap-4 text-sm">
          <span class="text-slate-500">Tỉ lệ: <b class="text-slate-700 font-medium">{{ current.project.aspectRatio }}</b></span>
          <span class="text-slate-500">Chế độ: <b class="text-slate-700 font-medium">{{ current.project.runMode === 'BACKGROUND' ? 'Chạy ngầm' : 'Mở Flow' }}</b></span>
          <span class="text-slate-500">Nền tảng: <b class="text-slate-700 font-medium">{{ providerLabel(current.project.provider) }}</b></span>
          <span class="text-slate-500">Song song: <b class="text-slate-700 font-medium">{{ current.project.runMode === 'FOREGROUND' ? 1 : current.project.maxParallel }}</b></span>
          <span class="text-slate-500">Số lần thử: <b class="text-slate-700 font-medium">{{ current.project.maxAttempts }}</b></span>
          <span class="text-slate-500">Video: <b class="text-slate-700 font-medium">{{ current.project.waitAllImages !== false ? 'sau khi duyệt hết ảnh' : 'ngay khi đủ ảnh của cảnh' }}</b></span>
        </div>
        <button @click="openModal(current.project)" class="btn btn-sm ml-auto"><Pencil class="w-3.5 h-3.5" /> Sửa</button>
      </div>
    </section>

    <JobTable v-if="current" :jobs="jobs" @changed="reload" @review="store.go('review')" />

    <ProjectModal :show="showModal" :project="editing" @close="showModal = false" @saved="onSaved" />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  CheckCircle2, Clapperboard, Download, FileText, Film, Folder, Images, Pause, Pencil, Play, Plus, RotateCcw,
  ShieldCheck, SlidersHorizontal, Trash2,
} from 'lucide-vue-next'
import StatusBadge from '@/components/StatusBadge.vue'
import JobTable from '@/components/JobTable.vue'
import ProjectModal from '@/components/ProjectModal.vue'
import { projectsApi, jobsApi, systemApi, errMsg } from '@/api'
import { store } from '@/store'

const jobs = ref([])
const busy = ref(false)
const showModal = ref(false)
const editing = ref(null)

const current = computed(() => store.current)

const stats = computed(() => {
  const c = current.value?.counts || {}
  return [
    { key: 'pending', label: 'Chờ chạy', value: c.PENDING ?? 0, color: 'text-slate-500' },
    { key: 'gen', label: 'Đang tạo', value: c.GENERATING ?? 0, color: 'text-sky-600' },
    { key: 'review', label: 'Chờ duyệt', value: c.AWAITING_REVIEW ?? 0, color: 'text-amber-600' },
    { key: 'done', label: 'Xong', value: (c.SUCCESS ?? 0) + (c.APPROVED ?? 0) + (c.DOWNLOADING ?? 0), color: 'text-emerald-600' },
    { key: 'failed', label: 'Lỗi / hủy', value: (c.FAILED ?? 0) + (c.CANCELLED ?? 0), color: 'text-rose-600' },
    { key: 'total', label: 'Tổng', value: current.value?.total ?? 0, color: 'text-slate-500' },
  ]
})

const folders = computed(() => current.value ? [
  { label: 'Nguồn prompt', path: current.value.project.sourceDir },
  { label: 'Folder Ảnh', path: current.value.project.imageDir },
  { label: 'Folder Video', path: current.value.project.videoDir },
] : [])

const pct = (pv) => (pv.total ? Math.round((pv.counts.SUCCESS / pv.total) * 100) : 0)
const providerLabel = (p) => ({ FLOW_BROWSER: 'Google Flow', VERTEX_API: 'Vertex AI', FAKE: 'Chạy thử' }[p] || p)
const modeText = (p) => `${p.runMode === 'BACKGROUND' ? 'Chạy ngầm' : 'Mở Flow (bạn tự bấm tạo)'} · ${providerLabel(p.provider)}`

async function loadJobs() {
  try {
    jobs.value = current.value ? await jobsApi.list(current.value.project.id) : []
  } catch (e) {
    console.error(e)
  }
}

async function reload() {
  await store.refresh()
  await loadJobs()
}

async function run(fn, okMsg) {
  busy.value = true
  try {
    const r = await fn()
    if (okMsg) store.toast(typeof okMsg === 'function' ? okMsg(r) : okMsg, 'success')
    await reload()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  } finally {
    busy.value = false
  }
}

const id = () => current.value.project.id
const doImport = () => run(() => projectsApi.importPrompts(id()),
  (r) => `Đã nạp ${r.images} ảnh + ${r.videos} video (mới ${r.added}, đổi ${r.updated}, giữ nguyên ${r.unchanged})`)
const start = () => run(() => projectsApi.start(id()), 'Đã cho phim chạy')
const pause = () => run(() => projectsApi.pause(id()), 'Đã tạm dừng: job đang tạo sẽ chạy nốt, không giao job mới')
const retryFailed = () => run(() => projectsApi.retryFailed(id()), (r) => `Đã đưa ${r.requeued} mục về hàng đợi`)
const verifyFiles = () => run(() => projectsApi.verifyFiles(id()), (r) => r.requeued
  ? `Đã kiểm tra ${r.checked} file: ${r.missing} mất, ${r.broken} hỏng → đưa ${r.requeued} mục về hàng đợi tạo lại.\n${r.details.join('\n')}`
  : `Đã kiểm tra ${r.checked} file đã duyệt: tất cả đều ổn.`)

async function remove() {
  if (!confirm(`Xóa phim "${current.value.project.name}" khỏi ứng dụng?\nFile ảnh/video đã lưu trong 2 folder KHÔNG bị xóa.`)) return
  await run(async () => {
    await projectsApi.remove(id())
    store.selectProject(null)
  }, 'Đã xóa phim')
}

async function openFolder(path) {
  try {
    await systemApi.openFolder(path)
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

function openModal(p) {
  editing.value = p
  showModal.value = true
}

async function onSaved(pv) {
  showModal.value = false
  store.selectProject(pv.project.id)
  await reload()
}

watch(() => store.projectId, loadJobs)

let timer
onMounted(() => {
  loadJobs()
  timer = setInterval(loadJobs, 4000)
})
onUnmounted(() => clearInterval(timer))
</script>
