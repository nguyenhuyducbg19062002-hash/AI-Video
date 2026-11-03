<template>
  <div class="space-y-3">
    <div class="flex flex-wrap items-center gap-2">
      <button v-for="f in filters" :key="f.key" @click="filter = f.key"
        :class="filter === f.key ? 'bg-sky-600 text-white border-sky-500' : 'bg-slate-900 text-slate-300 border-slate-700 hover:text-white'"
        class="px-3 py-1.5 rounded-xl border text-xs font-semibold">
        {{ f.label }} <span class="opacity-70">({{ countOf(f) }})</span>
      </button>
    </div>

    <div class="overflow-x-auto rounded-2xl border border-slate-800 bg-slate-900/60">
      <table class="w-full text-left text-sm text-slate-300">
        <thead class="bg-slate-950/80 text-[11px] uppercase tracking-wider text-slate-400 border-b border-slate-800">
          <tr>
            <th class="px-4 py-3">Loại</th>
            <th class="px-4 py-3">ID / Tên file</th>
            <th class="px-4 py-3">Trạng thái</th>
            <th class="px-4 py-3">Ảnh reference</th>
            <th class="px-4 py-3">Tài khoản</th>
            <th class="px-4 py-3">Ghi chú</th>
            <th class="px-4 py-3 text-right">Thao tác</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-800/60">
          <tr v-if="shown.length === 0">
            <td colspan="7" class="px-4 py-8 text-center text-slate-500">Không có mục nào.</td>
          </tr>
          <tr v-for="j in shown" :key="j.id" class="hover:bg-slate-800/30 align-top">
            <td class="px-4 py-2.5 text-xs">{{ j.kind === 'IMAGE' ? 'Ảnh' : 'Video' }}</td>
            <td class="px-4 py-2.5 font-mono text-xs text-slate-100">{{ j.externalId }}</td>
            <td class="px-4 py-2.5">
              <StatusBadge :status="j.status" />
              <div v-if="j.cancelRequested" class="text-[11px] text-slate-400 mt-1">đang hủy…</div>
            </td>
            <td class="px-4 py-2.5 text-[11px] font-mono text-slate-400 max-w-[220px]">{{ j.referenceIds.join(', ') || '—' }}</td>
            <td class="px-4 py-2.5 text-xs text-slate-400">{{ j.accountEmail || '—' }}</td>
            <td class="px-4 py-2.5 text-[11px] max-w-xs">
              <span v-if="j.errorMessage" class="text-rose-300">{{ j.errorMessage }}</span>
              <span v-else-if="j.attempt" class="text-slate-500">đã thử {{ j.attempt }} lần</span>
              <span v-else-if="j.finalPath" class="text-slate-500 font-mono break-all">{{ j.finalPath }}</span>
            </td>
            <td class="px-4 py-2.5 text-right whitespace-nowrap space-x-1">
              <button v-if="j.status === 'AWAITING_REVIEW'" @click="$emit('review', j)" class="btn-xs bg-amber-600/80 hover:bg-amber-500 text-white">Duyệt</button>
              <button v-if="['FAILED', 'CANCELLED', 'SUCCESS'].includes(j.status)" @click="act(j, 'regenerate')" class="btn-xs bg-slate-800 hover:bg-slate-700">
                {{ j.status === 'SUCCESS' ? 'Làm lại' : 'Thử lại' }}
              </button>
              <button v-if="['PENDING', 'GENERATING', 'AWAITING_REVIEW'].includes(j.status) && !j.cancelRequested" @click="act(j, 'cancel')" class="btn-xs bg-slate-800 hover:bg-slate-700">Hủy</button>
              <button @click="showHistory(j)" class="btn-xs bg-slate-800 hover:bg-slate-700">Lịch sử</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Lịch sử một job -->
    <div v-if="history" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75" @click.self="history = null">
      <div class="bg-slate-900 border border-slate-700 rounded-2xl max-w-2xl w-full p-6 max-h-[85vh] overflow-y-auto">
        <div class="flex items-center justify-between mb-4">
          <h3 class="font-bold text-sky-400">Lịch sử {{ history.job.kind === 'IMAGE' ? 'ảnh' : 'video' }} {{ history.job.externalId }}</h3>
          <button @click="history = null" class="text-slate-400 hover:text-white">✕</button>
        </div>
        <ol class="space-y-1.5 text-xs">
          <li v-for="(e, i) in history.events" :key="i" class="flex gap-3">
            <span class="text-slate-500 font-mono whitespace-nowrap">{{ fmt(e.at) }}</span>
            <span class="text-slate-400 whitespace-nowrap">{{ e.from || '∅' }} → <b class="text-slate-200">{{ e.to }}</b></span>
            <span class="text-slate-300">{{ e.message }}</span>
          </li>
        </ol>
        <h4 class="font-semibold text-slate-300 text-sm mt-5 mb-2">Các bản prompt</h4>
        <details v-for="r in history.revisions" :key="r.revisionNo" class="mb-2 text-xs">
          <summary class="cursor-pointer text-slate-300">Bản {{ r.revisionNo }} · {{ r.source === 'USER' ? 'bạn sửa' : 'import' }} · {{ fmt(r.createdAt) }} <span v-if="r.note" class="text-slate-500">· {{ r.note }}</span></summary>
          <pre class="whitespace-pre-wrap mt-1 p-2 bg-slate-950 rounded-lg text-slate-400 max-h-48 overflow-y-auto">{{ r.promptText }}</pre>
        </details>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
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

<style scoped>
.btn-xs {
  @apply px-2.5 py-1 rounded-lg text-xs border border-slate-700 transition;
}
</style>
