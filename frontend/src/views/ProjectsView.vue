<template>
  <div class="grid grid-cols-1 xl:grid-cols-[300px_1fr] gap-6">
    <!-- Danh sách phim -->
    <aside class="space-y-3">
      <button @click="openModal(null)" class="w-full px-4 py-2.5 rounded-xl bg-sky-600 hover:bg-sky-500 text-white font-semibold text-sm">
        + Thêm phim
      </button>
      <div v-if="projects.length === 0" class="p-4 rounded-2xl border border-dashed border-slate-700 text-sm text-slate-400">
        Chưa có phim nào. Bấm "Thêm phim" rồi chọn thư mục output của Omni-Flash-Pipeline.
      </div>
      <button v-for="pv in projects" :key="pv.project.id" @click="store.selectProject(pv.project.id)"
        :class="pv.project.id === store.projectId ? 'border-sky-500 bg-sky-950/30' : 'border-slate-800 bg-slate-900/60 hover:border-slate-600'"
        class="w-full text-left p-4 rounded-2xl border transition">
        <div class="flex items-center justify-between gap-2">
          <span class="font-semibold text-slate-100 truncate">{{ pv.project.name }}</span>
          <StatusBadge :status="pv.project.status" />
        </div>
        <div class="text-[11px] text-slate-500 font-mono mt-1">{{ pv.project.code }}</div>
        <div class="mt-3 h-1.5 bg-slate-800 rounded-full overflow-hidden">
          <div class="h-full bg-emerald-500" :style="{ width: pct(pv) + '%' }"></div>
        </div>
        <div class="flex justify-between text-[11px] text-slate-400 mt-1">
          <span>{{ pv.counts.SUCCESS }}/{{ pv.total }} xong</span>
          <span v-if="pv.counts.AWAITING_REVIEW" class="text-amber-300">{{ pv.counts.AWAITING_REVIEW }} chờ duyệt</span>
        </div>
      </button>
    </aside>

    <!-- Chi tiết phim đang chọn -->
    <section v-if="current" class="space-y-5 min-w-0">
      <div class="p-5 rounded-3xl bg-slate-900 border border-slate-800 space-y-4">
        <div class="flex flex-wrap items-start justify-between gap-3">
          <div class="min-w-0">
            <h2 class="text-xl font-black text-white flex items-center gap-2 flex-wrap">
              {{ current.project.name }} <StatusBadge :status="current.project.status" />
            </h2>
            <p class="text-xs text-slate-400 mt-1">
              {{ current.project.runMode === 'BACKGROUND' ? 'Chạy ngầm' : 'Mở Flow (bạn tự bấm tạo)' }} ·
              {{ providerLabel(current.project.provider) }} ·
              song song {{ current.project.runMode === 'FOREGROUND' ? 1 : current.project.maxParallel }}
            </p>
          </div>
          <div class="flex flex-wrap gap-2">
            <button @click="doImport" :disabled="busy" class="btn bg-slate-800 hover:bg-slate-700">Import prompt</button>
            <button v-if="current.project.status !== 'RUNNING'" @click="start" :disabled="busy || current.total === 0" class="btn bg-emerald-600 hover:bg-emerald-500 text-white">▶ Chạy</button>
            <button v-else @click="pause" :disabled="busy" class="btn bg-amber-600 hover:bg-amber-500 text-white">⏸ Tạm dừng</button>
            <button v-if="current.counts.FAILED + current.counts.CANCELLED > 0" @click="retryFailed" :disabled="busy" class="btn bg-slate-800 hover:bg-slate-700">Thử lại mục lỗi</button>
            <button v-if="current.counts.AWAITING_REVIEW" @click="store.go('review')" class="btn bg-amber-500 hover:bg-amber-400 text-slate-950">Duyệt {{ current.counts.AWAITING_REVIEW }} mục →</button>
            <button @click="openModal(current.project)" class="btn bg-slate-800 hover:bg-slate-700">Sửa</button>
            <button @click="remove" class="btn bg-rose-950/60 hover:bg-rose-900/60 text-rose-300 border-rose-800/40">Xóa</button>
          </div>
        </div>

        <div v-if="current.project.status === 'PAUSED' && current.project.pausedReason" class="p-3 rounded-xl bg-amber-500/10 border border-amber-500/30 text-xs text-amber-200 whitespace-pre-wrap">
          Tạm dừng: {{ current.project.pausedReason }}
        </div>
        <div v-if="current.project.status === 'RUNNING' && current.readyAccounts === 0" class="p-3 rounded-xl bg-rose-500/10 border border-rose-500/30 text-xs text-rose-200">
          Không còn tài khoản {{ providerLabel(current.project.provider) }} nào sẵn sàng (hết quota, cần đăng nhập hoặc chưa có). Job sẽ tự chạy tiếp khi có tài khoản.
          <button @click="store.go('accounts')" class="underline ml-1">Mở trang Tài khoản</button>
        </div>
        <div v-if="current.total > 0 && current.imagesDone < current.imagesTotal" class="text-[11px] text-slate-400">
          Video chỉ bắt đầu khi mọi ảnh reference của nó đã được duyệt ({{ current.imagesDone }}/{{ current.imagesTotal }} ảnh xong).
        </div>

        <div class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3">
          <div v-for="s in stats" :key="s.key" class="p-3 rounded-xl bg-slate-950/60 border border-slate-800">
            <div class="text-[11px] uppercase font-semibold" :class="s.color">{{ s.label }}</div>
            <div class="text-xl font-bold text-white mt-0.5">{{ s.value }}</div>
          </div>
        </div>

        <div class="grid grid-cols-1 md:grid-cols-2 gap-2 text-xs">
          <button v-for="d in folders" :key="d.label" @click="openFolder(d.path)" class="text-left p-2.5 rounded-xl bg-slate-950/60 border border-slate-800 hover:border-slate-600">
            <span class="text-slate-400">{{ d.label }}:</span> <span class="font-mono text-slate-200 break-all">{{ d.path }}</span>
          </button>
        </div>
      </div>

      <JobTable :jobs="jobs" @changed="refresh" @review="store.go('review')" />
    </section>

    <section v-else-if="projects.length" class="p-8 rounded-3xl border border-dashed border-slate-700 text-slate-400 text-sm">
      Chọn một phim ở bên trái.
    </section>

    <ProjectModal :show="showModal" :project="editing" @close="showModal = false" @saved="onSaved" />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
import JobTable from '@/components/JobTable.vue'
import ProjectModal from '@/components/ProjectModal.vue'
import { projectsApi, jobsApi, systemApi, errMsg } from '@/api'
import { store } from '@/store'

const projects = ref([])
const jobs = ref([])
const busy = ref(false)
const showModal = ref(false)
const editing = ref(null)

const current = computed(() => projects.value.find((p) => p.project.id === store.projectId) || null)

const stats = computed(() => {
  const c = current.value?.counts || {}
  return [
    { key: 'total', label: 'Tổng', value: current.value?.total ?? 0, color: 'text-slate-400' },
    { key: 'pending', label: 'Chờ chạy', value: c.PENDING ?? 0, color: 'text-slate-400' },
    { key: 'gen', label: 'Đang tạo', value: c.GENERATING ?? 0, color: 'text-sky-400' },
    { key: 'review', label: 'Chờ duyệt', value: c.AWAITING_REVIEW ?? 0, color: 'text-amber-400' },
    { key: 'done', label: 'Xong', value: (c.SUCCESS ?? 0) + (c.APPROVED ?? 0) + (c.DOWNLOADING ?? 0), color: 'text-emerald-400' },
    { key: 'failed', label: 'Lỗi / hủy', value: (c.FAILED ?? 0) + (c.CANCELLED ?? 0), color: 'text-rose-400' },
  ]
})

const folders = computed(() => current.value ? [
  { label: 'Folder Ảnh', path: current.value.project.imageDir },
  { label: 'Folder Video', path: current.value.project.videoDir },
] : [])

const pct = (pv) => (pv.total ? Math.round((pv.counts.SUCCESS / pv.total) * 100) : 0)
const providerLabel = (p) => ({ FLOW_BROWSER: 'Google Flow', VERTEX_API: 'Vertex AI', FAKE: 'Chạy thử' }[p] || p)

async function refresh() {
  try {
    projects.value = await projectsApi.list()
    if (!current.value && projects.value.length) store.selectProject(projects.value[0].project.id)
    jobs.value = current.value ? await jobsApi.list(current.value.project.id) : []
  } catch (e) {
    console.error(e)
  }
}

async function run(fn, okMsg) {
  busy.value = true
  try {
    const r = await fn()
    if (okMsg) store.toast(typeof okMsg === 'function' ? okMsg(r) : okMsg, 'success')
    await refresh()
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
const pause = () => run(() => projectsApi.pause(id()), 'Đã tạm dừng: các job đang tạo sẽ chạy nốt, không giao job mới')
const retryFailed = () => run(() => projectsApi.retryFailed(id()), (r) => `Đã đưa ${r.requeued} mục về hàng đợi`)

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
  await refresh()
}

watch(() => store.projectId, refresh)

let timer
onMounted(() => {
  refresh()
  timer = setInterval(refresh, 4000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.btn {
  @apply px-3.5 py-2 rounded-xl text-xs font-semibold border border-slate-700 transition disabled:opacity-40;
}
</style>
