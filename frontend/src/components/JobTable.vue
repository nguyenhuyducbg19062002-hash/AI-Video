<template>
  <div class="card">
    <div class="card-head items-center">
      <ListChecks class="w-5 h-5 text-indigo-500" />
      <div class="flex-1">
        <div class="card-title">Hàng đợi chi tiết <span class="count-pill ml-1">{{ jobs.length }} mục</span></div>
        <div class="card-sub">Ảnh chạy trước theo STT, video chạy khi đủ ảnh reference đã duyệt. Tick chọn cảnh để chạy riêng.</div>
      </div>
    </div>

    <!-- Bộ lọc cảnh -->
    <div class="px-4 pt-3 flex flex-wrap items-center gap-2">
      <div class="flex p-0.5 rounded-lg bg-slate-100">
        <button v-for="k in kindFilters" :key="k.key" @click="kind = k.key"
          :class="kind === k.key ? 'bg-white shadow-sm text-indigo-700' : 'text-slate-600 hover:text-slate-800'"
          class="px-3 py-1 rounded-md text-xs font-medium transition">
          {{ k.label }} <span class="opacity-75">({{ countKind(k.key) }})</span>
        </button>
      </div>
      <select v-if="sources.length > 1" v-model="source" class="input !w-auto !py-1 !text-xs">
        <option value="">Mọi file Excel</option>
        <option v-for="x in sources" :key="x" :value="x">{{ x }}</option>
      </select>
      <label v-if="providedCount" class="flex items-center gap-1.5 text-xs text-slate-600 cursor-pointer whitespace-nowrap"
        title="Ảnh bạn đã có sẵn trong thư mục: app không tạo lại, chỉ dùng để gắn vào cảnh">
        <input type="checkbox" v-model="hideProvided" class="accent-indigo-600" /> ẩn {{ providedCount }} ảnh có sẵn
      </label>
      <div class="relative flex-1 min-w-[240px]">
        <Search class="w-3.5 h-3.5 text-slate-400 absolute left-2.5 top-1/2 -translate-y-1/2" />
        <input v-model="q" placeholder="Lọc cảnh: PANEL-1, CHAR-CALEB-1, 5-20 (STT), chữ trong mô tả…" class="input !pl-8 !py-1 !text-xs" />
      </div>
    </div>
    <div class="px-4 pt-2 flex flex-wrap gap-1.5">
      <button v-for="f in filters" :key="f.key" @click="filter = f.key"
        :class="filter === f.key ? 'bg-indigo-600 text-white border-indigo-600' : 'bg-white text-slate-600 border-slate-200 hover:border-slate-300'"
        class="px-3 py-1 rounded-lg border text-xs font-medium transition">
        {{ f.label }} <span class="opacity-75">({{ countOf(f) }})</span>
      </button>
    </div>

    <!-- Thanh chạy các cảnh đã chọn -->
    <div v-if="selected.size" class="mx-4 mt-3 px-3 py-2 rounded-xl bg-indigo-50 border border-indigo-200 flex flex-wrap items-center gap-3 text-sm">
      <b class="text-indigo-800">Đã chọn {{ selected.size }} cảnh</b>
      <span class="text-xs text-slate-500">({{ selectedImages }} ảnh, {{ selectedVideos }} video)</span>
      <span v-if="selectedNotRunnable" class="text-xs text-slate-500">({{ selectedNotRunnable }} cảnh đang tạo / chờ duyệt / đã xong sẽ bỏ qua)</span>
      <label class="flex items-center gap-1.5 text-xs text-slate-700 cursor-pointer">
        <input type="checkbox" v-model="onlySelected" class="accent-indigo-600" />
        chỉ chạy các cảnh này (xong thì phim tự dừng)
      </label>
      <div class="ml-auto flex gap-1.5">
        <button @click="runSelected" :disabled="running" class="btn-primary btn-sm"><Play class="w-3.5 h-3.5" />
          {{ selectedVideos && !selectedImages ? 'Tạo ' + selectedVideos + ' video đã chọn' : selectedImages && !selectedVideos ? 'Tạo ' + selectedImages + ' ảnh đã chọn' : 'Chạy các cảnh đã chọn' }}
        </button>
        <button @click="selected = new Set()" class="btn btn-sm">Bỏ chọn</button>
      </div>
    </div>

    <PaginationBar v-if="pageCount > 1" class="mt-1" v-model:page="page" v-model:page-size="pageSize" :total="shown.length" />

    <div class="overflow-x-auto mt-3">
      <table class="table">
        <thead>
          <tr>
            <th class="w-8">
              <input type="checkbox" class="accent-indigo-600" :checked="allShownSelected" :indeterminate.prop="someShownSelected && !allShownSelected"
                @change="toggleAllShown($event.target.checked)" :title="`Chọn tất cả ${shown.length} cảnh đang lọc`" />
            </th>
            <th>Loại</th>
            <th>ID / tên file</th>
            <th>Trạng thái</th>
            <th>Ảnh reference</th>
            <th>Tài khoản</th>
            <th>Ghi chú</th>
            <th class="text-right">Thao tác</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="shown.length === 0">
            <td colspan="8" class="!py-12 text-center">
              <Inbox class="w-8 h-8 mx-auto text-indigo-300" />
              <div class="text-sm text-slate-600 mt-2">Không có mục nào</div>
              <div class="text-xs text-slate-400 mt-0.5">{{ jobs.length ? 'Không có cảnh nào khớp bộ lọc.' : 'Bấm "Import prompt" để nạp ảnh và video của phim.' }}</div>
            </td>
          </tr>
          <tr v-for="j in visible" :key="j.id" :class="selected.has(j.id) ? 'bg-indigo-50/60' : ''">
            <td class="border-l-4" :class="j.kind === 'VIDEO' ? 'border-l-violet-400' : 'border-l-sky-400'"><input type="checkbox" class="accent-indigo-600" :checked="selected.has(j.id)" @change="toggle(j.id, $event.target.checked)" /></td>
            <td>
              <span :class="j.kind === 'VIDEO' ? 'bg-violet-100 text-violet-700 border-violet-200' : 'bg-sky-100 text-sky-700 border-sky-200'"
                class="inline-flex items-center gap-1 px-2 py-0.5 rounded-md border text-[11px] font-semibold whitespace-nowrap">
                <component :is="j.kind === 'IMAGE' ? ImageIcon : Video" class="w-3 h-3" />
                {{ j.kind === 'IMAGE' ? 'Ảnh' : 'Video' }}
              </span>
            </td>
            <td class="font-mono text-xs text-slate-800">{{ j.kind === 'VIDEO' ? 'STT ' + j.externalId : j.externalId }}</td>
            <td>
              <span v-if="j.kind === 'IMAGE' && j.inStore && j.status === 'CANCELLED'"
                class="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-[11px] font-semibold border bg-emerald-50 text-emerald-700 border-emerald-200">
                <span class="w-1.5 h-1.5 rounded-full bg-emerald-500"></span>Có sẵn
              </span>
              <StatusBadge v-else :status="j.status" />
              <div v-if="j.cancelRequested" class="text-[11px] text-slate-400 mt-1">đang hủy…</div>
            </td>
            <td class="text-[11px] font-mono text-slate-500 max-w-[220px]">{{ j.referenceIds.join(', ') || '—' }}</td>
            <td class="text-xs text-slate-500">{{ j.accountEmail || '—' }}</td>
            <td class="text-[11px] max-w-xs">
              <span v-if="j.errorMessage" class="text-rose-600">{{ j.errorMessage }}</span>
              <span v-else-if="j.attempt" class="text-slate-400">đã thử {{ j.attempt }} lần</span>
              <span v-else-if="j.finalPath" class="text-slate-400 font-mono break-all">{{ j.finalPath }}</span>
            </td>
            <td class="text-right whitespace-nowrap space-x-1">
              <button v-if="j.status === 'AWAITING_REVIEW'" @click="$emit('review', j)" class="btn-warn btn-sm">Duyệt</button>
              <template v-if="canRun(j)">
                <button @click="runOne(j, true)" :disabled="running" class="btn-primary btn-sm" title="Chỉ chạy cảnh này, xong thì phim tự dừng (không chạy cả hàng)">
                  <Play class="w-3 h-3" /> Chạy riêng
                </button>
                <button @click="runOne(j, false)" :disabled="running" class="btn btn-sm" title="Đưa cảnh này lên đầu hàng, sau đó phim chạy tiếp như thường">
                  <ChevronsUp class="w-3 h-3" /> Chạy trước
                </button>
              </template>
              <button v-if="['FAILED', 'CANCELLED', 'SUCCESS'].includes(j.status)" @click="act(j, 'regenerate')" class="btn btn-sm">
                <RotateCcw class="w-3 h-3" /> {{ j.status === 'SUCCESS' ? 'Làm lại' : 'Thử lại' }}
              </button>
              <button v-if="['PENDING', 'GENERATING', 'AWAITING_REVIEW'].includes(j.status) && !j.cancelRequested" @click="act(j, 'cancel')" class="btn btn-sm">Hủy</button>
              <button @click="showHistory(j)" class="btn btn-sm"><History class="w-3 h-3" /> Lịch sử</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <PaginationBar v-if="shown.length" v-model:page="page" v-model:page-size="pageSize" :total="shown.length" />
    <!-- (bảng đã chia trang: chỉ vẽ một trang, nên 1000 cảnh cũng không kéo dài) -->

    <!-- Lịch sử một job -->
    <div v-if="history" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/40" @click.self="history = null">
      <div class="card w-full max-w-2xl max-h-[85vh] overflow-y-auto shadow-2xl">
        <div class="card-head items-center">
          <History class="w-5 h-5 text-indigo-500" />
          <div class="card-title flex-1">Lịch sử {{ history.job.kind === 'IMAGE' ? 'ảnh' : 'video' }} {{ history.job.externalId }}</div>
          <button @click="history = null" class="text-slate-400 hover:text-slate-700"><X class="w-5 h-5" /></button>
        </div>
        <div class="p-5">
          <ol class="space-y-2 text-xs">
            <li v-for="(e, i) in history.events" :key="i" class="flex gap-3">
              <span class="text-slate-400 font-mono whitespace-nowrap">{{ fmt(e.at) }}</span>
              <span class="whitespace-nowrap text-slate-500">{{ e.from || '∅' }} → <b class="text-slate-800">{{ e.to }}</b></span>
              <span class="text-slate-600">{{ e.message }}</span>
            </li>
          </ol>
          <div class="section-label mt-5 mb-2">Các bản prompt</div>
          <details v-for="r in history.revisions" :key="r.revisionNo" class="mb-2 text-xs rounded-xl border border-slate-200">
            <summary class="cursor-pointer px-3 py-2 text-slate-700">
              Bản {{ r.revisionNo }} · {{ r.source === 'USER' ? 'bạn sửa' : 'import' }} · {{ fmt(r.createdAt) }}
              <span v-if="r.note" class="text-slate-400">· {{ r.note }}</span>
            </summary>
            <pre class="whitespace-pre-wrap px-3 pb-3 text-slate-500 max-h-48 overflow-y-auto">{{ r.promptText }}</pre>
          </details>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ChevronsUp, History, Image as ImageIcon, Inbox, ListChecks, Play, RotateCcw, Search, Video, X } from 'lucide-vue-next'
import StatusBadge from './StatusBadge.vue'
import PaginationBar from './PaginationBar.vue'
import { jobsApi, projectsApi, errMsg } from '@/api'
import { store } from '@/store'
import { canRun, matchScene, parseSceneQuery } from '@/sceneFilter'

const props = defineProps({ jobs: { type: Array, default: () => [] }, projectId: { type: Number, default: null },
  skipMissing: { type: Boolean, default: false } })
const emit = defineEmits(['changed', 'review'])

const filters = [
  { key: 'all', label: 'Tất cả', statuses: null },
  { key: 'review', label: 'Chờ duyệt', statuses: ['AWAITING_REVIEW'] },
  { key: 'running', label: 'Đang tạo', statuses: ['GENERATING'] },
  { key: 'pending', label: 'Chờ chạy', statuses: ['PENDING'] },
  { key: 'failed', label: 'Lỗi / hủy', statuses: ['FAILED', 'CANCELLED'] },
  { key: 'done', label: 'Xong', statuses: ['SUCCESS', 'APPROVED', 'DOWNLOADING'] },
]
const kindFilters = [
  { key: '', label: 'Tất cả' },
  { key: 'IMAGE', label: 'Ảnh' },
  { key: 'VIDEO', label: 'Video' },
]
const filter = ref('all')
const kind = ref('')
const source = ref('')
const q = ref('')
// phân trang: nhớ số dòng mỗi trang của người dùng (localStorage có thể bị chặn nên bọc try/catch)
const PAGE_SIZE_KEY = 'afm.jobTable.pageSize'
const loadPageSize = () => {
  try {
    const n = Number(localStorage.getItem(PAGE_SIZE_KEY))
    return [25, 50, 100, 200].includes(n) ? n : 50
  } catch { return 50 }
}
const pageSize = ref(loadPageSize())
const page = ref(1)
const history = ref(null)
const selected = ref(new Set())
const onlySelected = ref(true)
const running = ref(false)

const allIds = computed(() => new Set(props.jobs.map((j) => String(j.externalId).toLowerCase())))
const terms = computed(() => parseSceneQuery(q.value))
const sources = computed(() => [...new Set(props.jobs.map((j) => j.sourceName).filter(Boolean))])
// lọc theo loại + file + ô tìm (chưa lọc trạng thái): số đếm trên các nút trạng thái theo đúng bộ lọc này
// ảnh người dùng có sẵn (job tạo ảnh đã hủy vì không cần): app không tạo lại, mặc định ẩn cho gọn
const isProvided = (j) => j.kind === 'IMAGE' && j.inStore && j.status === 'CANCELLED'
const hideProvided = ref(true)
const providedCount = computed(() => props.jobs.filter(isProvided).length)
const base = computed(() => props.jobs.filter((j) => (!hideProvided.value || !isProvided(j)) && (!kind.value || j.kind === kind.value)
  && (!source.value || j.sourceName === source.value) && matchScene(j, terms.value, allIds.value)))
const countKind = (k) => (k ? props.jobs.filter((j) => j.kind === k).length : props.jobs.length)
const countOf = (f) => (f.statuses ? base.value.filter((j) => f.statuses.includes(j.status)).length : base.value.length)
const shown = computed(() => {
  const f = filters.find((x) => x.key === filter.value)
  return f.statuses ? base.value.filter((j) => f.statuses.includes(j.status)) : base.value
})
const pageCount = computed(() => Math.max(1, Math.ceil(shown.value.length / pageSize.value)))
const visible = computed(() => shown.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
// đổi bộ lọc / số dòng mỗi trang thì về trang 1; dữ liệu tải lại làm số trang giảm thì kéo trang hiện tại về trang cuối
watch([filter, kind, source, q, hideProvided], () => { page.value = 1 })
watch(pageSize, (n) => {
  page.value = 1
  try { localStorage.setItem(PAGE_SIZE_KEY, String(n)) } catch { /* không lưu được thì thôi */ }
})
watch(pageCount, (n) => { if (page.value > n) page.value = n })
watch(() => props.projectId, () => { selected.value = new Set() })

const allShownSelected = computed(() => shown.value.length > 0 && shown.value.every((j) => selected.value.has(j.id)))
const someShownSelected = computed(() => shown.value.some((j) => selected.value.has(j.id)))
const selectedImages = computed(() => props.jobs.filter((j) => selected.value.has(j.id) && j.kind === 'IMAGE').length)
const selectedVideos = computed(() => props.jobs.filter((j) => selected.value.has(j.id) && j.kind === 'VIDEO').length)
const selectedNotRunnable = computed(() => props.jobs.filter((j) => selected.value.has(j.id) && !canRun(j)).length)

function toggle(id, on) {
  const next = new Set(selected.value)
  if (on) next.add(id)
  else next.delete(id)
  selected.value = next
}
function toggleAllShown(on) {
  const next = new Set(selected.value)
  shown.value.forEach((j) => (on ? next.add(j.id) : next.delete(j.id)))
  selected.value = next
}

async function submit(ids, only, append = false) {
  running.value = true
  try {
    const r = await projectsApi.runSelected(props.projectId, ids, only, append)
    const skipped = r.skipped.length ? `\nBỏ qua ${r.skipped.length}: ${r.skipped.slice(0, 6).join(', ')}${r.skipped.length > 6 ? '…' : ''}` : ''
    store.toast(`Đã cho chạy ${r.queued} cảnh` + (only ? ' (chỉ các cảnh này, xong thì phim tự dừng)' : ', sau đó phim chạy tiếp như thường') + skipped,
      r.queued ? 'success' : 'error')
    emit('changed')
    return r
  } catch (e) {
    store.toast(errMsg(e), 'error')
    return null
  } finally {
    running.value = false
  }
}
async function runSelected() {
  // giữ thứ tự của danh sách (ảnh trước theo file, rồi video)
  const ids = props.jobs.filter((j) => selected.value.has(j.id)).map((j) => j.id)
  const r = await submit(ids, onlySelected.value)
  if (r?.queued) selected.value = new Set()
}
// video còn thiếu ảnh (panel…) mà app tạo được: tạo các ảnh đó trước, rồi tới video
const imageJobById = computed(() => new Map(props.jobs.filter((x) => x.kind === 'IMAGE').map((x) => [x.externalId, x])))
function runOne(j, only) {
  const imgs = j.kind === 'VIDEO' && !props.skipMissing
    ? (j.references || []).filter((r) => !r.available).map((r) => imageJobById.value.get(r.id)).filter((x) => x && canRun(x))
    : []
  return submit([...imgs.map((x) => x.id), j.id], only, true)
}

async function act(job, action) {
  try {
    if (action === 'regenerate') await jobsApi.regenerate(job.id, null, null)
    else await jobsApi.cancel(job.id)
    emit('changed')
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

async function showHistory(job) {
  try {
    const h = await jobsApi.history(job.id)
    history.value = { job, ...h }
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

const fmt = (s) => (s ? new Date(s).toLocaleString('vi-VN') : '')
</script>
