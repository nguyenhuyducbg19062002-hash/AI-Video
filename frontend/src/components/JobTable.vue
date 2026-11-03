<template>
  <div class="card">
    <div class="card-head items-center">
      <ListChecks class="w-5 h-5 text-indigo-500" />
      <div class="flex-1">
        <div class="card-title">Hàng đợi chi tiết <span class="count-pill ml-1">{{ jobs.length }} mục</span></div>
        <div class="card-sub">Ảnh chạy trước theo STT, video chạy khi đủ ảnh reference đã duyệt</div>
      </div>
    </div>

    <div class="px-4 pt-3 flex flex-wrap gap-1.5">
      <button v-for="f in filters" :key="f.key" @click="filter = f.key"
        :class="filter === f.key ? 'bg-indigo-600 text-white border-indigo-600' : 'bg-white text-slate-600 border-slate-200 hover:border-slate-300'"
        class="px-3 py-1 rounded-lg border text-xs font-medium transition">
        {{ f.label }} <span class="opacity-75">({{ countOf(f) }})</span>
      </button>
    </div>

    <div class="overflow-x-auto mt-3">
      <table class="table">
        <thead>
          <tr>
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
            <td colspan="7" class="!py-12 text-center">
              <Inbox class="w-8 h-8 mx-auto text-indigo-300" />
              <div class="text-sm text-slate-600 mt-2">Không có mục nào</div>
              <div class="text-xs text-slate-400 mt-0.5">Bấm "Import prompt" để nạp ảnh và video của phim.</div>
            </td>
          </tr>
          <tr v-for="j in shown" :key="j.id">
            <td>
              <span class="inline-flex items-center gap-1.5 text-xs text-slate-600">
                <component :is="j.kind === 'IMAGE' ? ImageIcon : Video" class="w-3.5 h-3.5 text-slate-400" />
                {{ j.kind === 'IMAGE' ? 'Ảnh' : 'Video' }}
              </span>
            </td>
            <td class="font-mono text-xs text-slate-800">{{ j.externalId }}</td>
            <td>
              <StatusBadge :status="j.status" />
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
import { computed, ref } from 'vue'
import { History, Image as ImageIcon, Inbox, ListChecks, RotateCcw, Video, X } from 'lucide-vue-next'
import StatusBadge from './StatusBadge.vue'
import { jobsApi, errMsg } from '@/api'
import { store } from '@/store'

const props = defineProps({ jobs: { type: Array, default: () => [] } })
const emit = defineEmits(['changed', 'review'])

const filters = [
  { key: 'all', label: 'Tất cả', statuses: null },
  { key: 'review', label: 'Chờ duyệt', statuses: ['AWAITING_REVIEW'] },
  { key: 'running', label: 'Đang tạo', statuses: ['GENERATING'] },
  { key: 'pending', label: 'Chờ chạy', statuses: ['PENDING'] },
  { key: 'failed', label: 'Lỗi / hủy', statuses: ['FAILED', 'CANCELLED'] },
  { key: 'done', label: 'Xong', statuses: ['SUCCESS', 'APPROVED', 'DOWNLOADING'] },
]
const filter = ref('all')
const history = ref(null)

const countOf = (f) => (f.statuses ? props.jobs.filter((j) => f.statuses.includes(j.status)).length : props.jobs.length)
const shown = computed(() => {
  const f = filters.find((x) => x.key === filter.value)
  return f.statuses ? props.jobs.filter((j) => f.statuses.includes(j.status)) : props.jobs
})

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
