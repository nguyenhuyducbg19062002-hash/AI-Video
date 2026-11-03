<template>
  <div class="space-y-5">
    <div class="flex flex-col md:flex-row md:items-center justify-between gap-4 p-5 rounded-3xl bg-slate-900 border border-slate-800">
      <div>
        <h2 class="text-xl font-black text-white">Tài khoản xoay vòng</h2>
        <p class="text-slate-400 text-sm mt-1">
          Mỗi tài khoản Google Flow có một profile Edge riêng (cookie nằm trong profile, không lưu mật khẩu).
          Khi một tài khoản hết quota, job tự chuyển sang tài khoản kế tiếp theo thứ tự ưu tiên.
        </p>
      </div>
      <button @click="openModal(null)" class="px-4 py-2.5 rounded-xl bg-sky-600 hover:bg-sky-500 text-white font-semibold text-sm whitespace-nowrap">
        + Thêm tài khoản
      </button>
    </div>

    <div class="grid grid-cols-2 sm:grid-cols-5 gap-3">
      <div v-for="s in summary" :key="s.status" class="p-3 rounded-xl bg-slate-900/80 border border-slate-800">
        <StatusBadge :status="s.status" />
        <div class="text-xl font-bold text-white mt-1.5">{{ s.count }}</div>
      </div>
    </div>

    <div class="overflow-x-auto rounded-2xl border border-slate-800 bg-slate-900/60">
      <table class="w-full text-left text-sm text-slate-300">
        <thead class="bg-slate-950/80 text-[11px] uppercase tracking-wider text-slate-400 border-b border-slate-800">
          <tr>
            <th class="px-4 py-3">Ưu tiên</th>
            <th class="px-4 py-3">Tài khoản</th>
            <th class="px-4 py-3">Nền tảng</th>
            <th class="px-4 py-3">Trạng thái</th>
            <th class="px-4 py-3">Dùng gần nhất</th>
            <th class="px-4 py-3 text-right">Thao tác</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-800/60">
          <tr v-if="accounts.length === 0">
            <td colspan="6" class="px-4 py-8 text-center text-slate-500">Chưa có tài khoản nào.</td>
          </tr>
          <tr v-for="a in accounts" :key="a.id" class="hover:bg-slate-800/30 align-top">
            <td class="px-4 py-3 font-mono text-xs text-slate-400">{{ a.priority }}</td>
            <td class="px-4 py-3">
              <div class="font-semibold text-slate-100">{{ a.email }}</div>
              <div v-if="a.proxy" class="text-[11px] font-mono text-sky-300 mt-0.5">proxy {{ a.proxy }}</div>
              <div v-if="a.notes" class="text-[11px] text-slate-400 mt-0.5">{{ a.notes }}</div>
            </td>
            <td class="px-4 py-3 text-xs">{{ providerLabel(a.provider) }}</td>
            <td class="px-4 py-3">
              <StatusBadge :status="a.status" />
              <div v-if="a.status === 'COOLDOWN' && a.cooldownUntil" class="text-[11px] text-amber-300 mt-1">tới {{ fmt(a.cooldownUntil) }}</div>
              <div v-if="a.lastErrorMessage" class="text-[11px] text-rose-300 mt-1 max-w-xs truncate" :title="a.lastErrorMessage">{{ a.lastErrorMessage }}</div>
            </td>
            <td class="px-4 py-3 text-xs text-slate-400">{{ a.lastUsedAt ? fmt(a.lastUsedAt) : 'Chưa dùng' }}</td>
            <td class="px-4 py-3 text-right whitespace-nowrap space-x-1">
              <template v-if="a.provider === 'FLOW_BROWSER'">
                <button @click="act(a, 'openLogin')" :disabled="a.status === 'LEASED'" class="btn-xs bg-sky-900/60 hover:bg-sky-800 text-sky-200">Đăng nhập</button>
                <button v-if="a.status === 'NEED_LOGIN'" @click="act(a, 'confirmLogin')" class="btn-xs bg-emerald-800/70 hover:bg-emerald-700 text-emerald-100">Đã đăng nhập xong</button>
              </template>
              <button v-if="['COOLDOWN', 'DISABLED'].includes(a.status)" @click="act(a, 'enable')" class="btn-xs bg-slate-800 hover:bg-slate-700">Bật lại</button>
              <button v-if="['READY', 'COOLDOWN', 'NEED_LOGIN'].includes(a.status)" @click="act(a, 'disable')" class="btn-xs bg-slate-800 hover:bg-slate-700">Tắt</button>
              <button @click="openModal(a)" class="btn-xs bg-slate-800 hover:bg-slate-700">Sửa</button>
              <button @click="remove(a)" :disabled="a.status === 'LEASED'" class="btn-xs bg-rose-950/60 hover:bg-rose-900/60 text-rose-300">Xóa</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="p-4 rounded-2xl bg-slate-900/60 border border-slate-800 text-xs text-slate-400 space-y-1">
      <p><b class="text-slate-200">Đăng nhập lần đầu (Google Flow):</b> bấm "Đăng nhập" → Edge mở với profile riêng của tài khoản → đăng nhập Google, mở thử Flow → <b>đóng cửa sổ Edge đó</b> → bấm "Đã đăng nhập xong".</p>
      <p>Tool không dùng mật khẩu. Nếu phiên hết hạn, tài khoản tự chuyển sang "Cần đăng nhập" và job chạy tiếp bằng tài khoản khác.</p>
    </div>

    <!-- Thêm / sửa -->
    <div v-if="showModal" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-sm">
      <form @submit.prevent="save" class="bg-slate-900 border border-slate-700 rounded-2xl max-w-lg w-full p-6 space-y-4">
        <div class="flex items-center justify-between pb-3 border-b border-slate-800">
          <h3 class="text-lg font-bold text-sky-400">{{ editing ? 'Sửa tài khoản' : 'Thêm tài khoản' }}</h3>
          <button type="button" @click="showModal = false" class="text-slate-400 hover:text-white">✕</button>
        </div>
        <div>
          <label class="lbl">Email <span class="text-rose-400">*</span></label>
          <input v-model="form.email" :disabled="!!editing" required class="inp" placeholder="ten@gmail.com" />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="lbl">Nền tảng</label>
            <select v-model="form.provider" :disabled="!!editing" class="inp">
              <option value="FLOW_BROWSER">Google Flow</option>
              <option value="VERTEX_API">Vertex AI</option>
              <option value="FAKE">Chạy thử</option>
            </select>
          </div>
          <div>
            <label class="lbl">Ưu tiên (nhỏ = dùng trước)</label>
            <input v-model.number="form.priority" type="number" class="inp" />
          </div>
        </div>
        <div v-if="form.provider === 'FLOW_BROWSER'">
          <label class="lbl">Proxy (tùy chọn)</label>
          <input v-model="form.proxy" class="inp font-mono" placeholder="ip:port:user:pass hoặc http://ip:port" />
        </div>
        <template v-if="form.provider === 'VERTEX_API'">
          <div>
            <label class="lbl">File service-account JSON</label>
            <input v-model="form.credentialFile" class="inp font-mono" placeholder="D:\keys\project-sa.json" />
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div><label class="lbl">GCP project</label><input v-model="form.gcpProject" class="inp font-mono" /></div>
            <div><label class="lbl">Vùng (location)</label><input v-model="form.gcpLocation" class="inp font-mono" placeholder="us-central1" /></div>
          </div>
        </template>
        <div>
          <label class="lbl">Ghi chú</label>
          <input v-model="form.notes" class="inp" />
        </div>
        <div class="flex justify-end gap-3 pt-3 border-t border-slate-800">
          <button type="button" @click="showModal = false" class="px-4 py-2 rounded-xl border border-slate-700 hover:bg-slate-800 text-sm">Hủy</button>
          <button type="submit" class="px-5 py-2 rounded-xl bg-sky-600 hover:bg-sky-500 text-white font-semibold text-sm">Lưu</button>
        </div>
      </form>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { accountsApi, errMsg } from '@/api'
import { store } from '@/store'

const accounts = ref([])
const showModal = ref(false)
const editing = ref(null)
const blank = () => ({ email: '', provider: 'FLOW_BROWSER', priority: 100, proxy: '', notes: '', credentialFile: '', gcpProject: '', gcpLocation: '' })
const form = ref(blank())

const summary = computed(() => ['READY', 'LEASED', 'COOLDOWN', 'NEED_LOGIN', 'DISABLED']
  .map((status) => ({ status, count: accounts.value.filter((a) => a.status === status).length })))

const providerLabel = (p) => ({ FLOW_BROWSER: 'Google Flow', VERTEX_API: 'Vertex AI', FAKE: 'Chạy thử' }[p] || p)
const fmt = (s) => new Date(s).toLocaleString('vi-VN')

async function load() {
  try {
    accounts.value = await accountsApi.list()
  } catch (e) {
    console.error(e)
  }
}

function openModal(a) {
  editing.value = a
  form.value = a ? { ...blank(), ...Object.fromEntries(Object.entries(a).map(([k, v]) => [k, v ?? ''])) } : blank()
  showModal.value = true
}

async function save() {
  try {
    if (editing.value) await accountsApi.update(editing.value.id, form.value)
    else await accountsApi.create(form.value)
    showModal.value = false
    store.toast(editing.value ? 'Đã lưu tài khoản' : 'Đã thêm tài khoản' + (form.value.provider === 'FLOW_BROWSER' ? '. Bấm "Đăng nhập" để đăng nhập lần đầu.' : ''), 'success')
    await load()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

const MSG = {
  openLogin: 'Đã mở Edge. Đăng nhập Google, đóng cửa sổ Edge, rồi bấm "Đã đăng nhập xong".',
  confirmLogin: 'Tài khoản đã sẵn sàng',
  enable: 'Đã bật lại tài khoản',
  disable: 'Đã tắt tài khoản',
}

async function act(a, action) {
  try {
    await accountsApi[action](a.id)
    store.toast(MSG[action], 'success')
    await load()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

async function remove(a) {
  if (!confirm(`Xóa tài khoản ${a.email}? Thư mục profile (cookie) vẫn giữ trên đĩa.`)) return
  try {
    await accountsApi.remove(a.id)
    await load()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

let timer
onMounted(() => {
  load()
  timer = setInterval(load, 5000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.btn-xs {
  @apply px-2.5 py-1 rounded-lg text-xs border border-slate-700 transition disabled:opacity-40;
}
.lbl {
  @apply block text-xs font-medium text-slate-300 mb-1;
}
.inp {
  @apply w-full px-3 py-2 rounded-xl bg-slate-950 border border-slate-700 text-slate-100 text-sm focus:border-sky-500 focus:outline-none disabled:opacity-50;
}
</style>
