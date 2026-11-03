<template>
  <div class="space-y-4">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <div class="flex items-center gap-3">
        <h2 class="text-xl font-black text-white">Duyệt kết quả</h2>
        <select :value="store.projectId" @change="store.selectProject(Number($event.target.value))"
          class="px-3 py-2 rounded-xl bg-slate-900 border border-slate-700 text-sm">
          <option v-for="pv in projects" :key="pv.project.id" :value="pv.project.id">
            {{ pv.project.name }} ({{ pv.counts.AWAITING_REVIEW }} chờ duyệt)
          </option>
        </select>
      </div>
      <div class="flex items-center gap-2 text-xs text-slate-400">
        <span>Phím tắt: <kbd>A</kbd> duyệt · <kbd>R</kbd> tạo lại · <kbd>←</kbd><kbd>→</kbd> chuyển mục</span>
        <button v-if="items.length > 1" @click="approveAll" :disabled="busy"
          class="px-3 py-2 rounded-xl bg-emerald-700 hover:bg-emerald-600 text-white font-semibold disabled:opacity-40">
          Duyệt tất cả {{ items.length }} mục
        </button>
      </div>
    </div>

    <div v-if="!items.length" class="p-10 rounded-3xl border border-dashed border-slate-700 text-center text-slate-400">
      Không có mục nào chờ duyệt. Kết quả mới sẽ tự hiện ở đây.
    </div>

    <div v-else class="grid grid-cols-1 xl:grid-cols-[220px_1fr_420px] gap-4">
      <!-- Danh sách chờ duyệt -->
      <div class="space-y-1.5 max-h-[75vh] overflow-y-auto pr-1">
        <button v-for="(j, i) in items" :key="j.id" @click="idx = i"
          :class="i === idx ? 'border-amber-400 bg-amber-500/10' : 'border-slate-800 bg-slate-900/60 hover:border-slate-600'"
          class="w-full text-left px-3 py-2 rounded-xl border text-xs">
          <div class="font-mono text-slate-100">{{ j.kind === 'IMAGE' ? 'Ảnh' : 'Video' }} {{ j.externalId }}</div>
          <div class="text-slate-500 truncate">bản {{ j.revisionNo }} · {{ j.accountEmail }}</div>
        </button>
      </div>

      <!-- Xem trước -->
      <div class="min-w-0 space-y-2">
        <div class="rounded-2xl bg-black border border-slate-800 overflow-hidden flex items-center justify-center min-h-[300px]">
          <video v-if="current.previewType === 'video'" :key="'v' + current.id + current.generatedAt" :src="jobsApi.previewUrl(current)"
            controls autoplay loop class="w-full max-h-[70vh]"></video>
          <img v-else :key="'i' + current.id + current.generatedAt" :src="jobsApi.previewUrl(current)" class="max-w-full max-h-[70vh] object-contain" />
        </div>
        <div class="text-xs text-slate-400 flex flex-wrap gap-x-4 gap-y-1">
          <span>{{ idx + 1 }} / {{ items.length }}</span>
          <span class="font-mono text-slate-200">{{ current.kind === 'IMAGE' ? 'Ảnh' : 'Video' }} {{ current.externalId }}</span>
          <span>bản prompt {{ current.revisionNo }}</span>
          <span>{{ current.accountEmail }}</span>
          <a v-if="current.remoteRef && current.remoteRef.startsWith('http')" :href="current.remoteRef" target="_blank" class="text-sky-400 underline">Mở trên Flow</a>
        </div>
        <div v-if="current.referenceIds.length" class="text-xs text-slate-400">
          Ảnh reference: <span class="font-mono text-slate-300">{{ current.referenceIds.join(', ') }}</span>
        </div>
        <p v-if="current.summaryVi" class="p-3 rounded-xl bg-slate-900 border border-slate-800 text-sm text-slate-200">{{ current.summaryVi }}</p>
      </div>

      <!-- Prompt + thao tác -->
      <div class="flex flex-col gap-2">
        <label class="text-xs font-semibold text-slate-300">Prompt (sửa rồi bấm "Tạo lại" nếu chưa ưng)</label>
        <textarea v-model="draft" rows="18" class="w-full flex-1 rounded-xl bg-slate-950 border border-slate-700 p-3 text-xs font-mono focus:border-sky-500 focus:outline-none"></textarea>
        <input v-model="note" placeholder="Ghi chú lý do tạo lại (tùy chọn)" class="px-3 py-2 rounded-xl bg-slate-950 border border-slate-700 text-xs" />
        <div class="flex gap-2">
          <button :disabled="busy" @click="approve" class="flex-1 px-4 py-2.5 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-sm disabled:opacity-40">
            Duyệt (A)
          </button>
          <button :disabled="busy" @click="regenerate" class="flex-1 px-4 py-2.5 rounded-xl bg-amber-600 hover:bg-amber-500 text-white font-bold text-sm disabled:opacity-40">
            {{ edited ? 'Lưu prompt và tạo lại (R)' : 'Tạo lại (R)' }}
          </button>
        </div>
        <p class="text-[11px] text-slate-500">
          Duyệt = chép file vào {{ current.kind === 'IMAGE' ? 'Folder Ảnh' : 'Folder Video' }} với tên {{ current.externalId }}.
          Tạo lại = đưa về hàng đợi; nếu đã sửa prompt thì lưu thành bản mới (bản cũ vẫn giữ trong lịch sử).
        </p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { projectsApi, jobsApi, errMsg } from '@/api'
import { store } from '@/store'

const projects = ref([])
const items = ref([])
const idx = ref(0)
const draft = ref('')
const note = ref('')
const busy = ref(false)

const current = computed(() => items.value[idx.value])
const edited = computed(() => current.value && draft.value !== current.value.prompt)

// Chỉ nạp lại prompt khi CHUYỂN mục, để lần làm mới 5 giây không ghi đè chữ đang sửa
watch(() => current.value?.id, () => {
  draft.value = current.value?.prompt ?? ''
  note.value = ''
})

async function load() {
  try {
    projects.value = await projectsApi.list()
    if (!store.projectId && projects.value.length) store.selectProject(projects.value[0].project.id)
    if (!store.projectId) return
    const keepId = current.value?.id
    items.value = await jobsApi.list(store.projectId, 'AWAITING_REVIEW')
    const at = items.value.findIndex((j) => j.id === keepId)
    idx.value = at >= 0 ? at : Math.min(idx.value, Math.max(0, items.value.length - 1))
  } catch (e) {
    console.error(e)
  }
}

async function act(fn, msg) {
  if (!current.value || busy.value) return
  busy.value = true
  try {
    await fn(current.value)
    store.toast(msg, 'success')
    await load()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  } finally {
    busy.value = false
  }
}

const approve = () => act((j) => jobsApi.approve(j.id), `Đã duyệt và lưu ${current.value.externalId}`)
const regenerate = () => act((j) => jobsApi.regenerate(j.id, edited.value ? draft.value : null, note.value || null),
  `Đã đưa ${current.value.externalId} về hàng đợi để tạo lại`)

async function approveAll() {
  if (!confirm(`Duyệt và lưu cả ${items.value.length} mục đang chờ?`)) return
  busy.value = true
  try {
    const r = await jobsApi.approveBatch(items.value.map((j) => j.id))
    store.toast(`Đã duyệt ${r.approved.length} mục` + (r.errors.length ? `, lỗi ${r.errors.length}: ${r.errors[0]}` : ''), r.errors.length ? 'error' : 'success')
    await load()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  } finally {
    busy.value = false
  }
}

function onKey(e) {
  if (['TEXTAREA', 'INPUT', 'SELECT'].includes(e.target.tagName) || e.ctrlKey || e.metaKey || e.altKey) return
  if (e.key === 'a' || e.key === 'A') approve()
  else if (e.key === 'r' || e.key === 'R') regenerate()
  else if (e.key === 'ArrowRight') idx.value = Math.min(idx.value + 1, items.value.length - 1)
  else if (e.key === 'ArrowLeft') idx.value = Math.max(idx.value - 1, 0)
}

watch(() => store.projectId, () => { idx.value = 0; load() })

let timer
onMounted(() => {
  load()
  timer = setInterval(load, 5000)
  window.addEventListener('keydown', onKey)
})
onUnmounted(() => {
  clearInterval(timer)
  window.removeEventListener('keydown', onKey)
})
</script>

<style scoped>
kbd {
  @apply px-1.5 py-0.5 rounded bg-slate-800 border border-slate-700 text-slate-200 font-mono text-[10px] mx-0.5;
}
</style>
