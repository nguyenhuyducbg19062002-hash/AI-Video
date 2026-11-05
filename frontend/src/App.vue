<template>
  <div class="h-full flex flex-col overflow-hidden">
    <!-- Đầu trang: logo + tên + trạng thái -->
    <header class="shrink-0 px-6 pt-5 pb-4 flex items-center justify-between gap-6">
      <div class="flex items-center gap-4 min-w-0">
        <div class="w-12 h-12 rounded-2xl bg-gradient-to-br from-indigo-500 to-violet-500 shadow-lg shadow-indigo-300/50 flex items-center justify-center shrink-0">
          <Clapperboard class="w-6 h-6 text-white" />
        </div>
        <div class="min-w-0">
          <h1 class="text-2xl font-semibold text-slate-800 tracking-tight truncate">AI FILM MAKER</h1>
          <p class="text-sm text-slate-500">Không gian sản xuất ảnh reference &amp; video từ kịch bản</p>
        </div>
      </div>

      <div class="card flex items-center divide-x divide-slate-200 px-1 py-2 shrink-0">
        <div class="px-4">
          <div class="text-[11px] text-slate-500">Trạng thái</div>
          <div class="flex items-center gap-1.5 text-sm font-medium" :class="statusOk ? 'text-slate-700' : 'text-rose-600'">
            <span class="w-2 h-2 rounded-full" :class="statusOk ? 'bg-emerald-500' : 'bg-rose-500'"></span>
            {{ statusText }}
          </div>
        </div>
        <div class="px-4 flex items-center gap-3">
          <div class="w-9 h-9 rounded-xl bg-indigo-50 flex items-center justify-center">
            <Users class="w-4 h-4 text-indigo-600" />
          </div>
          <div>
            <div class="text-sm font-medium text-slate-700">{{ store.accounts.length }} tài khoản</div>
            <div class="text-[11px] text-slate-500 flex items-center gap-1">
              <span class="px-1.5 rounded bg-emerald-50 text-emerald-700 border border-emerald-200">{{ store.readyAccounts }} sẵn sàng</span>
            </div>
          </div>
        </div>
      </div>
    </header>

    <div v-if="stopped" class="flex-1 flex items-center justify-center text-slate-500">
      Ứng dụng đã tắt. Bạn có thể đóng cửa sổ này.
    </div>

    <div v-else class="flex-1 min-h-0 flex gap-5 px-6 pb-5">
      <!-- Thanh bên trái -->
      <aside class="card w-[236px] shrink-0 flex flex-col p-3 gap-3 min-h-0">
        <div>
          <div class="section-label px-2 mb-2">Phim đang chọn</div>
          <select :value="store.projectId ?? ''" @change="store.selectProject(Number($event.target.value))"
            class="input !py-1.5 !text-[13px] !bg-slate-50" :disabled="!store.projects.length">
            <option v-if="!store.projects.length" value="">Chưa có phim</option>
            <option v-for="pv in store.projects" :key="pv.project.id" :value="pv.project.id">{{ pv.project.name }}</option>
          </select>
        </div>

        <div>
          <div class="section-label px-2 mb-1.5 mt-1">Sản xuất</div>
          <nav class="space-y-1">
            <button v-for="t in tabs" :key="t.key" @click="store.go(t.key)"
              :class="store.view === t.key
                ? 'bg-indigo-50 text-indigo-700 border-indigo-500'
                : 'text-slate-600 border-transparent hover:bg-slate-50'"
              class="w-full flex items-center gap-3 pl-3 pr-2 py-2.5 rounded-xl border-l-[3px] text-sm font-medium transition">
              <span :class="store.view === t.key ? 'bg-white border-indigo-200 text-indigo-600' : 'border-slate-200 text-slate-500'"
                class="w-7 h-7 rounded-lg border flex items-center justify-center">
                <component :is="t.icon" class="w-4 h-4" />
              </span>
              <span class="flex-1 text-left">{{ t.label }}</span>
            </button>
          </nav>
        </div>

        <!-- Hàng đợi của phim đang chọn -->
        <div v-if="store.current" class="rounded-xl border border-slate-200 bg-slate-50/60 p-3 text-xs space-y-1.5">
          <div class="text-slate-500 font-medium mb-1">Hàng đợi phim này</div>
          <div v-for="q in queue" :key="q.label" class="flex justify-between">
            <span class="text-slate-600">{{ q.label }}</span>
            <span class="font-semibold" :class="q.cls">{{ q.value }}</span>
          </div>
        </div>

        <div class="mt-auto space-y-2">
          <div class="flex items-center gap-3 px-3 py-2 rounded-xl text-sm text-slate-600">
            <Cpu class="w-4 h-4 text-slate-500" />
            <span class="flex-1">Worker Python</span>
            <span class="text-xs font-semibold" :class="workers ? 'text-emerald-600' : 'text-rose-600'">{{ workers }}</span>
            <span class="w-2 h-2 rounded-full" :class="workers ? 'bg-emerald-500' : 'bg-rose-500'"></span>
          </div>
          <button @click="quit" class="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-600 hover:bg-rose-50 hover:border-rose-200 hover:text-rose-600 transition">
            <Power class="w-4 h-4" />
            <span>Thoát ứng dụng</span>
          </button>
        </div>
      </aside>

      <!-- Nội dung -->
      <main class="flex-1 min-w-0 flex flex-col gap-4 min-h-0">
        <section class="shrink-0 rounded-2xl px-6 py-4 text-white bg-gradient-to-r from-[#0e1f4d] via-[#173a6e] to-[#1d6579] shadow-md flex items-center justify-between gap-6">
          <div>
            <div class="text-[11px] font-semibold tracking-widest text-cyan-300 uppercase">{{ banner.label }}</div>
            <div class="text-2xl font-semibold mt-0.5">{{ banner.title }}</div>
          </div>
          <div class="text-right hidden lg:block">
            <div class="flex items-center justify-end gap-2 text-sm font-semibold">
              <template v-for="(s, i) in steps" :key="s">
                <ArrowRight v-if="i" class="w-3.5 h-3.5 text-cyan-300" />
                <span :class="i === banner.step ? 'text-white' : 'text-slate-300/80'">{{ String(i + 1).padStart(2, '0') }} {{ s }}</span>
              </template>
            </div>
            <div class="text-xs text-slate-300 mt-1">{{ banner.desc }}</div>
          </div>
        </section>

        <div class="flex-1 min-h-0 overflow-y-auto pr-1 -mr-1">
          <ProjectsView v-if="store.view === 'projects'" />
          <ScenesView v-else-if="store.view === 'scenes'" />
          <ManualView v-else-if="store.view === 'manual'" />
          <AccountsView v-else-if="store.view === 'accounts'" />
          <SettingsView v-else-if="store.view === 'settings'" />
        </div>
      </main>
    </div>

    <!-- Thông báo nổi -->
    <div class="fixed bottom-5 right-5 z-[60] space-y-2 max-w-md">
      <div v-for="t in store.toasts" :key="t.id"
        :class="{
          'border-rose-200 bg-rose-50 text-rose-700': t.kind === 'error',
          'border-emerald-200 bg-emerald-50 text-emerald-700': t.kind === 'success',
          'border-slate-200 bg-white text-slate-700': t.kind === 'info',
        }"
        class="px-4 py-3 rounded-xl border text-sm shadow-lg whitespace-pre-wrap">
        {{ t.message }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ArrowRight, Clapperboard, Cpu, Film, Hand, LayoutList, Power, Settings, User, Users } from 'lucide-vue-next'
import ManualView from './views/ManualView.vue'
import ProjectsView from './views/ProjectsView.vue'
import ScenesView from './views/ScenesView.vue'
import AccountsView from './views/AccountsView.vue'
import SettingsView from './views/SettingsView.vue'
import { systemApi } from './api'
import { store } from './store'

const tabs = [
  { key: 'accounts', label: 'Tài khoản', icon: User },
  { key: 'projects', label: 'Phim & hàng đợi', icon: Film },
  { key: 'scenes', label: 'Danh sách cảnh', icon: LayoutList },
  { key: 'manual', label: 'Tự làm trên Flow', icon: Hand },
  { key: 'settings', label: 'Cài đặt', icon: Settings },
]

const steps = ['Tài khoản', 'Phim', 'Lưu vào folder']
const BANNERS = {
  accounts: { label: 'Tài khoản', title: 'Tài khoản xoay vòng', step: 0, desc: 'Đăng nhập Google Flow một lần cho mỗi tài khoản; hết quota sẽ tự chuyển tài khoản.' },
  projects: { label: 'Phim', title: 'Phim & hàng đợi', step: 1, desc: 'Thêm Excel ảnh / video của Omni và ảnh có sẵn; chạy ảnh trước rồi tới video.' },
  scenes: { label: 'Phim', title: 'Danh sách cảnh', step: 1, desc: 'Từng cảnh theo thứ tự file: mô tả, prompt, ảnh reference (+ thêm ảnh), so sánh kết quả.' },
  manual: { label: 'Phim', title: 'Tự làm trên Flow', step: 1, desc: 'App bày từng cảnh; bạn dán prompt, gắn ảnh, bấm Tạo và Tải xuống trên Flow; app tự nhận file và đặt đúng tên.' },
  settings: { label: 'Hệ thống', title: 'Cài đặt', step: -1, desc: 'Số worker, thời gian chờ render, thời gian nghỉ khi hết quota.' },
}
const banner = computed(() => BANNERS[store.view] || BANNERS.projects)

const stopped = ref(false)
const workers = computed(() => store.info?.workersAlive ?? 0)
const statusOk = computed(() => store.online && workers.value > 0)
const statusText = computed(() => (!store.online ? 'Mất kết nối' : workers.value ? 'Ready' : 'Không có worker'))

const queue = computed(() => {
  const c = store.current?.counts || {}
  return [
    { label: 'Chờ chạy', value: c.PENDING ?? 0, cls: 'text-slate-700' },
    { label: 'Đang tạo', value: c.GENERATING ?? 0, cls: 'text-sky-600' },
    { label: 'Xong', value: c.SUCCESS ?? 0, cls: 'text-emerald-600' },
    { label: 'Có sẵn / đã hủy', value: c.CANCELLED ?? 0, cls: 'text-slate-500' },
    { label: 'Lỗi', value: c.FAILED ?? 0, cls: 'text-rose-600' },
  ]
})

async function quit() {
  if (!confirm('Tắt ứng dụng? Các job đang tạo sẽ dừng và chạy lại từ đầu ở lần mở sau.')) return
  try {
    await systemApi.shutdown()
  } finally {
    stopped.value = true
    // Đóng cửa sổ ngay cùng lúc với app tắt. Chạy được trong cửa sổ app (Edge --app); mở bằng tab trình duyệt thường
    // thì trình duyệt chặn, khi đó dòng "Ứng dụng đã tắt" ở trên vẫn hiện và backend cũng tự đóng cửa sổ của nó.
    try { window.close() } catch { /* bị chặn thì thôi */ }
  }
}

// Báo cho backend biết cửa sổ còn mở; đóng cửa sổ (chế độ app desktop) thì backend tự tắt.
function heartbeat() {
  fetch('/api/system/ui-heartbeat', { method: 'POST' }).catch(() => {})
}
function onPageHide() {
  navigator.sendBeacon?.('/api/system/ui-closed')
}

let pollTimer
let beatTimer
onMounted(() => {
  store.refresh()
  heartbeat()
  pollTimer = setInterval(() => !stopped.value && store.refresh(), 4000)
  beatTimer = setInterval(() => !stopped.value && heartbeat(), 3000)
  window.addEventListener('pagehide', onPageHide)
})
onUnmounted(() => {
  clearInterval(pollTimer)
  clearInterval(beatTimer)
  window.removeEventListener('pagehide', onPageHide)
})
</script>
