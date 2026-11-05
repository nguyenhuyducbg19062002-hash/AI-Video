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
            <div class="text-sm text-slate-500 mt-1 max-w-xs">Tạo phim, chọn Folder Ảnh và Folder Video, rồi thêm file Excel prompt của Omni.</div>
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
              <span v-if="pv.counts.AWAITING_REVIEW" class="text-amber-600 font-medium">{{ pv.counts.AWAITING_REVIEW }} đang lưu</span>
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
              Giai đoạn 1 – ảnh: xong toàn bộ {{ current.imagesTotal }} ảnh thì app mới chuyển sang tạo video
              ({{ current.imagesDone }}/{{ current.imagesTotal }} ảnh đã có).
            </template>
            <template v-else>
              Chạy theo cảnh: cảnh đủ ảnh thì tạo video ngay; cảnh thiếu ảnh thì tạo đúng ảnh đó trước rồi mới tạo video
              ({{ current.imagesDone }}/{{ current.imagesTotal }} ảnh xong). Ảnh có sẵn được bỏ qua.
            </template>
          </div>
          <div v-else-if="current.total > current.imagesTotal && current.imagesTotal > 0" class="note-info">
            Giai đoạn 2 – video: đã đủ ảnh, app tự upload ảnh reference lên Flow theo đúng thứ tự từng cảnh rồi tạo video.
          </div>
          <div v-if="current.project.runMode === 'FOREGROUND' && current.counts.GENERATING > 0" class="note-warn">
            <b>Đang chờ bạn thao tác trên cửa sổ Google Flow.</b> Làm theo hộp hướng dẫn ở góc phải dưới của cửa sổ Flow:
            mở/tạo dự án → gắn ảnh theo thứ tự (thư mục ảnh đánh số đã được mở sẵn) → Copy prompt, dán, bấm Tạo →
            bấm <b>Tải xuống</b>: app tự lưu vào Folder Video. Đóng cửa sổ Flow = bỏ qua cảnh đó.
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

          <div v-if="current.missingRefs.length" class="note-danger">
            <b>Thiếu {{ current.missingRefs.length }} ảnh gốc</b> mà các cảnh cần nhưng chưa có prompt tạo ảnh:
            <span class="font-mono">{{ current.missingRefs.slice(0, 8).join(', ') }}{{ current.missingRefs.length > 8 ? '…' : '' }}</span>.
            Bấm <b>"Thêm ảnh có sẵn"</b> và chọn thư mục chứa các ảnh đặt tên theo ID.
          </div>

          <!-- Nạp dữ liệu -->
          <button @click="importMovieFolder" :disabled="busy" class="btn w-full !h-10"
            title="Chọn thư mục phim (ví dụ D:\Dev\Tập 5. Hà - Cupid): app tự tìm file prompt ảnh/video (.xlsx, .txt) và ảnh có sẵn bên trong">
            <FolderOpen class="w-4 h-4" /> Nạp cả thư mục phim (Excel / .txt + ảnh có sẵn)
          </button>
          <div class="grid grid-cols-3 gap-2">
            <label class="btn cursor-pointer" :class="{ 'opacity-50 pointer-events-none': busy }" title="reference-prompts.xlsx / prompt-anh-panel.xlsx / .txt mỗi dòng 1 prompt">
              <ImageIcon class="w-4 h-4" /> Thêm file ảnh
              <input type="file" accept=".xlsx,.txt" class="hidden" @change="importExcel($event, 'IMAGE')" />
            </label>
            <label class="btn cursor-pointer" :class="{ 'opacity-50 pointer-events-none': busy }" title="all-video-prompts.xlsx / FINAL_VIDEO_PROMPTS.txt">
              <Video class="w-4 h-4" /> Thêm file video
              <input type="file" accept=".xlsx,.txt" class="hidden" @change="importExcel($event, 'VIDEO')" />
            </label>
            <button @click="importAssets" :disabled="busy" class="btn" title="Thư mục ảnh gốc đặt tên theo ID: CHAR-CALEB-1.png, LOC-1.jpg…">
              <FolderInputIcon class="w-4 h-4" /> Thêm ảnh có sẵn
            </button>
            <button @click="tools = 'text'" :disabled="busy" class="btn"><PencilLine class="w-4 h-4" /> Nhập thủ công</button>
            <button @click="tools = 'groups'" :disabled="busy" class="btn col-span-2"><FolderTree class="w-4 h-4" /> Ảnh → Video theo thư mục con</button>
          </div>
          <div class="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-slate-500 -mt-1">
            <span>Kho ảnh: <b class="text-slate-700">{{ current.assetsCount }}</b> ảnh</span>
            <button v-if="current.project.sourceDir" @click="doImport" :disabled="busy" class="text-indigo-600 hover:underline">
              Nạp lại tất cả từ thư mục Omni
            </button>
            <button @click="store.go('scenes')" class="text-indigo-600 hover:underline">Xem danh sách cảnh →</button>
          </div>

          <div class="mt-auto grid grid-cols-2 gap-2">
            <button v-if="current.project.status !== 'RUNNING'" @click="start" :disabled="busy || current.total === 0" class="btn-primary">
              <Play class="w-4 h-4" /> Chạy
            </button>
            <button v-else @click="pause" :disabled="busy" class="btn-warn"><Pause class="w-4 h-4" /> Tạm dừng</button>
            <button v-if="current.counts.FAILED + current.counts.CANCELLED > 0" @click="retryFailed" :disabled="busy" class="btn">
              <RotateCcw class="w-4 h-4" /> Thử lại mục lỗi
            </button>
            <button @click="verifyFiles" :disabled="busy" class="btn" title="Tìm file đã lưu bị mất/hỏng trong Folder Ảnh/Video và cho tạo lại">
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
          <span class="text-slate-500">Độ dài: <b class="text-slate-700 font-medium">{{ current.project.videoDuration ? current.project.videoDuration + 's' : 'theo prompt' }}</b></span>
          <span class="text-slate-500">Chất lượng: <b class="text-slate-700 font-medium">{{ current.project.videoQuality || '720p' }}</b></span>
          <span class="text-slate-500">Chế độ: <b class="text-slate-700 font-medium">{{ current.project.runMode === 'BACKGROUND' ? 'Chạy ngầm' : 'Mở Flow' }}</b></span>
          <span class="text-slate-500">Nền tảng: <b class="text-slate-700 font-medium">{{ providerLabel(current.project.provider) }}</b></span>
          <span class="text-slate-500">Song song: <b class="text-slate-700 font-medium">{{ current.project.maxParallel }}</b></span>
          <span class="text-slate-500">Số lần thử: <b class="text-slate-700 font-medium">{{ current.project.maxAttempts }}</b></span>
          <label class="flex items-center gap-2 cursor-pointer select-none" title="Bật: chạy theo cảnh. Ảnh có sẵn thì bỏ qua; cảnh nào thiếu ảnh thì tạo đúng ảnh đó trước rồi mới tạo video; không đợi xong hết ảnh của cả phim">
            <button type="button" role="switch" :aria-checked="current.project.waitAllImages === false" @click="toggleVideoEarly" :disabled="busy"
              :class="current.project.waitAllImages === false ? 'bg-emerald-500' : 'bg-slate-300'"
              class="relative inline-flex h-5 w-9 shrink-0 rounded-full transition">
              <span :class="current.project.waitAllImages === false ? 'translate-x-4' : 'translate-x-0.5'"
                class="inline-block h-4 w-4 mt-0.5 rounded-full bg-white shadow transition"></span>
            </button>
            <span class="text-slate-700 font-medium">Tạo video ngay khi đủ ảnh của cảnh</span>
            <span class="text-[11px] text-slate-400">{{ current.project.waitAllImages === false ? '(đang bật: không đợi xong hết ảnh)' : '(đang tắt: video đợi xong hết ảnh của phim)' }}</span>
          </label>
        </div>
        <button @click="openModal(current.project)" class="btn btn-sm ml-auto"><Pencil class="w-3.5 h-3.5" /> Sửa</button>
      </div>
    </section>

    <JobTable v-if="current" :jobs="jobs" :project-id="current.project.id" :skip-missing="!!current.project.skipMissingRefs" @changed="reload" />

    <ProjectModal :show="showModal" :project="editing" @close="showModal = false" @saved="onSaved" />
    <ImportToolsModal :show="!!tools" :mode="tools || 'text'" :project-id="current?.project.id" @close="tools = null" @done="onToolsDone" />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  CheckCircle2, Clapperboard, FileText, Film, Folder, FolderInput as FolderInputIcon, FolderOpen, FolderTree,
  Image as ImageIcon, Images, Pause, Pencil, PencilLine, Play, Plus, RotateCcw, ShieldCheck, SlidersHorizontal, Trash2, Video,
} from 'lucide-vue-next'
import ImportToolsModal from '@/components/ImportToolsModal.vue'
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
    { key: 'done', label: 'Xong', value: (c.SUCCESS ?? 0) + (c.APPROVED ?? 0) + (c.DOWNLOADING ?? 0), color: 'text-emerald-600' },
    { key: 'failed', label: 'Lỗi', value: c.FAILED ?? 0, color: 'text-rose-600' },
    { key: 'cancelled', label: 'Có sẵn / đã hủy', value: c.CANCELLED ?? 0, color: 'text-slate-500' },
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
    jobs.value = current.value ? await jobsApi.list(current.value.project.id, null, false) : []
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
const describeImport = (r) => `${r.file}: ${r.rows} ${r.kind === 'IMAGE' ? 'ảnh' : 'video'} (mới ${r.added}, đổi ${r.updated}, giữ nguyên ${r.unchanged})`
  + (r.refsFromTags ? `; ${r.refsFromTags} cảnh lấy ảnh từ tag @… trong prompt` : '')
  + (r.tagMismatch ? `\n⚠ ${r.tagMismatch} cảnh có cột Reference IDs khác tag trong prompt, ví dụ: ${r.mismatchExamples[0]}` : '')

const tools = ref(null)

async function importMovieFolder() {
  let path
  try {
    path = (await systemApi.pickFolder(current.value.project.sourceDir || '')).path
  } catch (e) {
    store.toast(errMsg(e), 'error')
    return
  }
  if (!path) return
  await run(() => projectsApi.importMovieFolder(id(), path), (r) => 'Đã nạp thư mục phim:\n' + r.files.map(describeImport).join('\n')
    + `\nẢnh có sẵn: thêm ${r.assets.added}` + (r.assets.skippedJobs ? `, bỏ qua tạo ${r.assets.skippedJobs} ảnh đã có` : '')
    + (r.skipped.length ? `\nBỏ qua: ${r.skipped.join(', ')}` : ''))
}

async function onToolsDone(r) {
  tools.value = null
  store.toast('Đã thêm ' + describeImport(r), 'success')
  await reload()
}
const doImport = () => run(() => projectsApi.importPrompts(id()), (list) => 'Đã nạp:\n' + list.map(describeImport).join('\n'))

async function importExcel(e, kind) {
  const file = e.target.files[0]
  e.target.value = ''
  if (!file) return
  await run(() => projectsApi.importFile(id(), kind, file), (r) => 'Đã nạp ' + describeImport(r)
    + (r.missingRefs.length ? `\nCòn thiếu ${r.missingRefs.length} ảnh gốc: bấm "Thêm ảnh có sẵn".` : ''))
}

async function importAssets() {
  let path
  try {
    path = (await systemApi.pickFolder(current.value.project.imageDir)).path
  } catch (e) {
    store.toast(errMsg(e), 'error')
    return
  }
  if (!path) return
  await run(() => projectsApi.importAssetFolder(id(), path), (r) => `Đã thêm ${r.added} ảnh có sẵn`
    + (r.replaced ? `, thay ${r.replaced} ảnh` : '')
    + (r.skippedJobs ? `; bỏ qua ${r.skippedJobs} ảnh không cần tạo nữa` : '')
    + (r.notReferenced ? `\n${r.notReferenced} file không thuộc phim (tên không trùng ID nào): ${r.notReferencedNames.slice(0, 5).join(', ')}` : ''))
}
const start = () => run(() => projectsApi.start(id()), 'Đã cho phim chạy')

function formOf(p, changes) {
  return {
    name: p.name, code: p.code, sourceDir: p.sourceDir, imageDir: p.imageDir, videoDir: p.videoDir,
    runMode: p.runMode, provider: p.provider, aspectRatio: p.aspectRatio,
    maxParallel: p.maxParallel, maxAttempts: p.maxAttempts, waitAllImages: p.waitAllImages,
    videoDuration: p.videoDuration, videoQuality: p.videoQuality, autoApproveImages: !!p.autoApproveImages, ...changes,
  }
}

/** Công tắc "Tự duyệt ảnh tham chiếu": ảnh tạo xong vào kho ngay, video cần ảnh đó chạy liền */

/** Công tắc "Tạo video ngay khi đủ ảnh của cảnh" (= tắt "duyệt xong hết ảnh rồi mới tạo video") */
function toggleVideoEarly() {
  const p = current.value.project
  const early = p.waitAllImages === false
  const form = formOf(p, { waitAllImages: early })
  return run(() => projectsApi.update(id(), form), early
    ? 'Video sẽ đợi xong hết ảnh của phim rồi mới chạy'
    : 'Đủ ảnh của cảnh nào thì video cảnh đó chạy luôn')
}
const pause = () => run(() => projectsApi.pause(id()), 'Đã tạm dừng: job đang tạo sẽ chạy nốt, không giao job mới')
const retryFailed = () => run(() => projectsApi.retryFailed(id()), (r) => `Đã đưa ${r.requeued} mục về hàng đợi`)
const verifyFiles = () => run(() => projectsApi.verifyFiles(id()), (r) => r.requeued
  ? `Đã kiểm tra ${r.checked} file: ${r.missing} mất, ${r.broken} hỏng → đưa ${r.requeued} mục về hàng đợi tạo lại.\n${r.details.join('\n')}`
  : `Đã kiểm tra ${r.checked} file đã lưu: tất cả đều ổn.`)

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
