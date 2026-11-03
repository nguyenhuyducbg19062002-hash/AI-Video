<template>
  <div class="space-y-4">
    <div v-if="!items.length" class="card flex flex-col items-center justify-center text-center py-20">
      <CheckCircle2 class="w-10 h-10 text-indigo-300" />
      <div class="text-base text-slate-700 mt-3">Không có mục nào chờ duyệt</div>
      <div class="text-sm text-slate-500 mt-1">
        {{ store.current ? `Kết quả mới của "${store.current.project.name}" sẽ tự hiện ở đây.` : 'Chọn một phim ở thanh bên trái.' }}
      </div>
    </div>

    <div v-else class="grid grid-cols-1 2xl:grid-cols-[220px_1fr_420px] xl:grid-cols-[200px_1fr_380px] gap-4">
      <!-- Danh sách chờ duyệt -->
      <section class="card flex flex-col min-h-0">
        <div class="card-head items-center">
          <ListChecks class="w-5 h-5 text-indigo-500" />
          <div class="card-title">Chờ duyệt <span class="count-pill ml-1">{{ items.length }}</span></div>
        </div>
        <div class="p-2 space-y-1 overflow-y-auto max-h-[62vh]">
          <button v-for="(j, i) in items" :key="j.id" @click="idx = i"
            :class="i === idx ? 'bg-indigo-50 border-indigo-300' : 'border-transparent hover:bg-slate-50'"
            class="w-full text-left px-3 py-2 rounded-xl border text-xs transition">
            <div class="flex items-center gap-1.5 font-mono text-slate-800">
              <component :is="j.kind === 'IMAGE' ? ImageIcon : Video" class="w-3.5 h-3.5 text-slate-400" />
              {{ j.externalId }}
            </div>
            <div class="text-slate-400 truncate mt-0.5">bản {{ j.revisionNo }} · {{ j.accountEmail }}</div>
          </button>
        </div>
        <div class="p-3 border-t border-slate-100">
          <button v-if="items.length > 1" @click="approveAll" :disabled="busy" class="btn-success w-full btn-sm !py-2">
            Duyệt tất cả {{ items.length }} mục
          </button>
        </div>
      </section>

      <!-- Xem trước -->
      <section class="card flex flex-col min-w-0">
        <div class="card-head items-center">
          <component :is="current.kind === 'IMAGE' ? ImageIcon : Video" class="w-5 h-5 text-indigo-500" />
          <div class="flex-1 min-w-0">
            <div class="card-title">{{ current.kind === 'IMAGE' ? 'Ảnh' : 'Video' }} {{ current.externalId }}</div>
            <div class="card-sub">{{ idx + 1 }} / {{ items.length }} · bản prompt {{ current.revisionNo }} · {{ current.accountEmail }}</div>
          </div>
          <a v-if="current.remoteRef && current.remoteRef.startsWith('http')" :href="current.remoteRef" target="_blank" class="btn btn-sm">
            <ExternalLink class="w-3.5 h-3.5" /> Mở trên Flow
          </a>
        </div>
        <div class="p-4 space-y-3">
          <div class="rounded-xl bg-slate-900 overflow-hidden flex items-center justify-center min-h-[320px]">
            <video v-if="current.previewType === 'video'" :key="'v' + current.id + current.generatedAt" :src="jobsApi.previewUrl(current)"
              controls autoplay loop class="w-full max-h-[60vh]"></video>
            <img v-else :key="'i' + current.id + current.generatedAt" :src="jobsApi.previewUrl(current)" class="max-w-full max-h-[60vh] object-contain" />
          </div>
          <div v-if="current.referenceIds.length" class="flex flex-wrap items-center gap-1.5 text-xs">
            <span class="text-slate-500">Ảnh reference:</span>
            <span v-for="r in current.referenceIds" :key="r" class="px-2 py-0.5 rounded-md bg-slate-100 font-mono text-slate-600">{{ r }}</span>
          </div>
          <p v-if="current.summaryVi" class="note-info !text-sm !text-slate-700">{{ current.summaryVi }}</p>
        </div>
      </section>

      <!-- Prompt + thao tác -->
      <section class="card flex flex-col">
        <div class="card-head items-center">
          <FileText class="w-5 h-5 text-indigo-500" />
          <div class="flex-1">
            <div class="card-title">Prompt</div>
            <div class="card-sub">Sửa rồi bấm "Tạo lại" nếu chưa ưng</div>
          </div>
        </div>
        <div class="p-4 flex-1 flex flex-col gap-2">
          <textarea v-model="draft" rows="16" class="input flex-1 font-mono !text-xs leading-relaxed resize-none"></textarea>
          <input v-model="note" placeholder="Ghi chú lý do tạo lại (tùy chọn)" class="input !text-xs" />
          <div class="grid grid-cols-2 gap-2 pt-1">
            <button :disabled="busy" @click="approve" class="btn-success !py-2.5"><Check class="w-4 h-4" /> Duyệt (A)</button>
            <button :disabled="busy" @click="regenerate" class="btn-warn !py-2.5">
              <RotateCcw class="w-4 h-4" /> {{ edited ? 'Lưu & tạo lại (R)' : 'Tạo lại (R)' }}
            </button>
          </div>
          <p class="hint">
            Duyệt = chép file vào {{ current.kind === 'IMAGE' ? 'Folder Ảnh' : 'Folder Video' }} với tên <b class="font-mono">{{ targetName }}</b>.
            Phím tắt: <kbd>A</kbd> duyệt · <kbd>R</kbd> tạo lại · <kbd>←</kbd><kbd>→</kbd> chuyển mục.
          </p>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { Check, CheckCircle2, ExternalLink, FileText, Image as ImageIcon, ListChecks, RotateCcw, Video } from 'lucide-vue-next'
import { jobsApi, errMsg } from '@/api'
import { store } from '@/store'

const items = ref([])
const idx = ref(0)
const draft = ref('')
const note = ref('')
const busy = ref(false)

const current = computed(() => items.value[idx.value])
const edited = computed(() => current.value && draft.value !== current.value.prompt)
// Trùng quy tắc với ReviewService.finalFileName: ảnh = ID, video = mã phim + "_" + STT
const targetName = computed(() => {
  const j = current.value
  if (!j) return ''
  return j.kind === 'IMAGE' ? j.externalId : `${store.current?.project.code ?? ''}_${j.externalId}`
})

// Chỉ nạp lại prompt khi CHUYỂN mục, để lần làm mới định kỳ không ghi đè chữ đang sửa
watch(() => current.value?.id, () => {
  draft.value = current.value?.prompt ?? ''
  note.value = ''
})

async function load() {
  if (!store.projectId) {
    items.value = []
    return
  }
  try {
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
    store.refresh()
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
    store.refresh()
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
