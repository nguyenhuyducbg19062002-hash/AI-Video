<template>
  <div class="min-h-screen bg-[#090d16] text-slate-100 flex flex-col font-sans">
    <header class="h-16 border-b border-slate-800/80 bg-slate-950/70 backdrop-blur sticky top-0 z-40 px-6 flex items-center justify-between gap-4">
      <div class="flex items-center gap-3">
        <div class="w-9 h-9 rounded-xl bg-gradient-to-tr from-sky-600 via-indigo-600 to-amber-500 flex items-center justify-center font-black text-white">🎬</div>
        <div>
          <h1 class="text-base font-black tracking-wide text-white">AI FILM MAKER</h1>
          <p class="text-[10px] text-sky-400 font-mono tracking-widest uppercase">Ảnh reference · Video · Duyệt</p>
        </div>
      </div>

      <nav class="flex items-center gap-1.5 bg-slate-900/80 p-1.5 rounded-2xl border border-slate-800">
        <button v-for="t in tabs" :key="t.key" @click="store.go(t.key)"
          :class="store.view === t.key ? 'bg-sky-600 text-white' : 'text-slate-400 hover:text-white'"
          class="px-4 py-2 rounded-xl text-xs font-bold transition flex items-center gap-1.5">
          {{ t.label }}
          <span v-if="t.key === 'review' && pendingReview" class="px-1.5 rounded-full bg-amber-500 text-slate-950 text-[10px]">{{ pendingReview }}</span>
        </button>
      </nav>

      <div class="flex items-center gap-2">
        <span class="hidden md:flex items-center gap-2 px-3 py-1.5 rounded-xl bg-slate-900 border border-slate-800 text-xs">
          <span class="w-2 h-2 rounded-full" :class="online ? 'bg-emerald-400' : 'bg-rose-500'"></span>
          {{ online ? `${workers} worker` : 'Mất kết nối' }}
        </span>
        <button @click="quit" class="px-3 py-1.5 rounded-xl border border-slate-700 hover:bg-rose-900/40 hover:border-rose-700 text-xs text-slate-300">Thoát</button>
      </div>
    </header>

    <main class="flex-1 w-full max-w-[1600px] mx-auto p-6">
      <div v-if="stopped" class="p-10 text-center text-slate-300">Ứng dụng đã tắt. Bạn có thể đóng tab này.</div>
      <ProjectsView v-else-if="store.view === 'projects'" />
      <ReviewView v-else-if="store.view === 'review'" />
      <AccountsView v-else-if="store.view === 'accounts'" />
      <SettingsView v-else-if="store.view === 'settings'" />
    </main>

    <!-- Thông báo nổi -->
    <div class="fixed bottom-4 right-4 z-[60] space-y-2 max-w-md">
      <div v-for="t in store.toasts" :key="t.id"
        :class="{ 'border-rose-600 bg-rose-950/95': t.kind === 'error', 'border-emerald-600 bg-emerald-950/95': t.kind === 'success', 'border-slate-600 bg-slate-900/95': t.kind === 'info' }"
        class="px-4 py-3 rounded-xl border text-sm shadow-2xl whitespace-pre-wrap">
        {{ t.message }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import ProjectsView from './views/ProjectsView.vue'
import ReviewView from './views/ReviewView.vue'
import AccountsView from './views/AccountsView.vue'
import SettingsView from './views/SettingsView.vue'
import { projectsApi, systemApi } from './api'
import { store } from './store'

const tabs = [
  { key: 'projects', label: 'Phim' },
  { key: 'review', label: 'Duyệt' },
  { key: 'accounts', label: 'Tài khoản' },
  { key: 'settings', label: 'Cài đặt' },
]

const online = ref(true)
const workers = ref(0)
const pendingReview = ref(0)
const stopped = ref(false)

async function poll() {
  if (stopped.value) return
  try {
    const [info, projects] = await Promise.all([systemApi.info(), projectsApi.list()])
    workers.value = info.workersAlive
    pendingReview.value = projects.reduce((n, p) => n + (p.counts.AWAITING_REVIEW || 0), 0)
    online.value = true
  } catch {
    online.value = false
  }
}

async function quit() {
  if (!confirm('Tắt ứng dụng? Các job đang tạo sẽ dừng và chạy lại từ đầu ở lần mở sau.')) return
  try {
    await systemApi.shutdown()
  } finally {
    stopped.value = true
  }
}

let timer
onMounted(() => {
  poll()
  timer = setInterval(poll, 5000)
})
onUnmounted(() => clearInterval(timer))
</script>
