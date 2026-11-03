<template>
  <div class="space-y-4">
    <div class="grid grid-cols-2 md:grid-cols-5 gap-3">
      <div v-for="s in summary" :key="s.status" class="card px-4 py-3">
        <StatusBadge :status="s.status" />
        <div class="text-2xl font-semibold text-slate-800 mt-1.5">{{ s.count }}</div>
      </div>
    </div>

    <section class="card">
      <div class="card-head items-center">
        <Users class="w-5 h-5 text-indigo-500" />
        <div class="flex-1">
          <div class="card-title">Danh sách tài khoản <span class="count-pill ml-1">{{ store.accounts.length }}</span></div>
          <div class="card-sub">Ưu tiên nhỏ được dùng trước. Hết quota thì job tự chuyển sang tài khoản kế tiếp.</div>
        </div>
        <button @click="openModal(null)" class="btn-primary"><Plus class="w-4 h-4" /> Thêm tài khoản</button>
      </div>

      <div class="overflow-x-auto">
        <table class="table">
          <thead>
            <tr>
              <th>Ưu tiên</th>
              <th>Tài khoản</th>
              <th>Nền tảng</th>
              <th>Trạng thái</th>
              <th>Dùng gần nhất</th>
              <th class="text-right">Thao tác</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!store.accounts.length">
              <td colspan="6" class="!py-12 text-center">
                <User class="w-8 h-8 mx-auto text-indigo-300" />
                <div class="text-sm text-slate-600 mt-2">Chưa có tài khoản nào</div>
                <div class="text-xs text-slate-400 mt-0.5">Thêm ít nhất một tài khoản Google Flow để bắt đầu.</div>
              </td>
            </tr>
            <tr v-for="a in store.accounts" :key="a.id">
              <td class="font-mono text-xs text-slate-500">{{ a.priority }}</td>
              <td>
                <div class="font-medium text-slate-800">{{ a.email }}</div>
                <div v-if="a.proxy" class="text-[11px] font-mono text-indigo-600 mt-0.5">proxy {{ a.proxy }}</div>
                <div v-if="a.notes" class="text-[11px] text-slate-500 mt-0.5">{{ a.notes }}</div>
              </td>
              <td class="text-xs text-slate-600">{{ providerLabel(a.provider) }}</td>
              <td>
                <StatusBadge :status="a.status" />
                <div v-if="a.status === 'COOLDOWN' && a.cooldownUntil" class="text-[11px] text-amber-600 mt-1">tới {{ fmt(a.cooldownUntil) }}</div>
                <div v-if="a.lastErrorMessage" class="text-[11px] text-rose-600 mt-1 max-w-xs truncate" :title="a.lastErrorMessage">{{ a.lastErrorMessage }}</div>
              </td>
              <td class="text-xs text-slate-500">{{ a.lastUsedAt ? fmt(a.lastUsedAt) : 'Chưa dùng' }}</td>
              <td class="text-right whitespace-nowrap space-x-1">
                <template v-if="a.provider === 'FLOW_BROWSER'">
                  <button @click="act(a, 'openLogin')" :disabled="a.status === 'LEASED'" class="btn btn-sm"><KeyRound class="w-3 h-3" /> Đăng nhập</button>
                  <button v-if="a.status === 'NEED_LOGIN'" @click="act(a, 'confirmLogin')" class="btn-success btn-sm">Đã đăng nhập xong</button>
                </template>
                <button v-if="['COOLDOWN', 'DISABLED'].includes(a.status)" @click="act(a, 'enable')" class="btn btn-sm">Bật lại</button>
                <button v-if="['READY', 'COOLDOWN', 'NEED_LOGIN'].includes(a.status)" @click="act(a, 'disable')" class="btn btn-sm">Tắt</button>
                <button @click="openModal(a)" class="btn btn-sm"><Pencil class="w-3 h-3" /></button>
                <button @click="remove(a)" :disabled="a.status === 'LEASED'" class="btn-danger btn-sm"><Trash2 class="w-3 h-3" /></button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <div class="note-warn space-y-1">
      <p><b>Đăng nhập lần đầu (Google Flow):</b> bấm "Đăng nhập" → Edge mở với profile riêng của tài khoản → đăng nhập Google, mở thử Flow → <b>đóng cửa sổ Edge đó</b> → bấm "Đã đăng nhập xong".</p>
      <p>Tool không lưu mật khẩu. Phiên hết hạn thì tài khoản tự chuyển sang "Cần đăng nhập" và job chạy tiếp bằng tài khoản khác.</p>
    </div>

    <!-- Thêm / sửa -->
    <div v-if="showModal" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/40 backdrop-blur-[2px]">
      <form @submit.prevent="save" class="card w-full max-w-lg shadow-2xl" autocomplete="off">
        <div class="card-head items-center">
          <User class="w-5 h-5 text-indigo-500" />
          <div class="card-title flex-1">{{ editing ? 'Sửa tài khoản' : 'Thêm tài khoản' }}</div>
          <button type="button" @click="showModal = false" class="text-slate-400 hover:text-slate-700"><X class="w-5 h-5" /></button>
        </div>
        <div class="p-5 space-y-4">
          <div>
            <label class="label">Email <span class="text-rose-500">*</span></label>
            <input v-model="form.email" :disabled="!!editing" required class="input" placeholder="ten@gmail.com"
              name="afm-account-email" autocomplete="off" />
            <p class="hint">App không cần và không lưu mật khẩu Google: bạn tự đăng nhập một lần bằng nút "Đăng nhập".</p>
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div>
              <label class="label">Nền tảng</label>
              <select v-model="form.provider" :disabled="!!editing" class="input">
                <option value="FLOW_BROWSER">Google Flow</option>
                <option value="VERTEX_API">Vertex AI</option>
                <option value="FAKE">Chạy thử</option>
              </select>
            </div>
            <div>
              <label class="label">Ưu tiên (nhỏ = dùng trước)</label>
              <input v-model.number="form.priority" type="number" class="input" />
            </div>
          </div>
          <div v-if="form.provider === 'FLOW_BROWSER'">
            <label class="label">Proxy (tùy chọn, không dùng thì để trống)</label>
            <!-- name/autocomplete lạ để trình duyệt không tự điền mật khẩu đã lưu vào ô này -->
            <input v-model="form.proxy" class="input font-mono" placeholder="Để trống nếu không dùng proxy"
              name="afm-proxy-server" autocomplete="off" autocorrect="off" spellcheck="false" data-lpignore="true" data-form-type="other" />
            <p class="hint">Dạng: <span class="font-mono">ip:port</span>, <span class="font-mono">ip:port:user:pass</span> hoặc <span class="font-mono">http://user:pass@ip:port</span>. Đây <b>không phải</b> chỗ nhập mật khẩu Google.</p>
          </div>
          <template v-if="form.provider === 'VERTEX_API'">
            <div>
              <label class="label">File service-account JSON</label>
              <input v-model="form.credentialFile" class="input font-mono" placeholder="D:\keys\project-sa.json" />
            </div>
            <div class="grid grid-cols-2 gap-3">
              <div><label class="label">GCP project</label><input v-model="form.gcpProject" class="input font-mono" /></div>
              <div><label class="label">Vùng (location)</label><input v-model="form.gcpLocation" class="input font-mono" placeholder="us-central1" /></div>
            </div>
          </template>
          <div>
            <label class="label">Ghi chú</label>
            <input v-model="form.notes" class="input" />
          </div>
        </div>
        <div class="flex justify-end gap-2 px-5 py-4 border-t border-slate-100 bg-slate-50/60 rounded-b-2xl">
          <button type="button" @click="showModal = false" class="btn">Hủy</button>
          <button type="submit" class="btn-primary">Lưu</button>
        </div>
      </form>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { KeyRound, Pencil, Plus, Trash2, User, Users, X } from 'lucide-vue-next'
import StatusBadge from '@/components/StatusBadge.vue'
import { accountsApi, errMsg } from '@/api'
import { store } from '@/store'

const showModal = ref(false)
const editing = ref(null)
const blank = () => ({ email: '', provider: 'FLOW_BROWSER', priority: 100, proxy: '', notes: '', credentialFile: '', gcpProject: '', gcpLocation: '' })
const form = ref(blank())

const summary = computed(() => ['READY', 'LEASED', 'COOLDOWN', 'NEED_LOGIN', 'DISABLED']
  .map((status) => ({ status, count: store.accounts.filter((a) => a.status === status).length })))

const providerLabel = (p) => ({ FLOW_BROWSER: 'Google Flow', VERTEX_API: 'Vertex AI', FAKE: 'Chạy thử' }[p] || p)
const fmt = (s) => new Date(s).toLocaleString('vi-VN')

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
    await store.refresh()
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
    await store.refresh()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

async function remove(a) {
  if (!confirm(`Xóa tài khoản ${a.email}? Thư mục profile (cookie) vẫn giữ trên đĩa.`)) return
  try {
    await accountsApi.remove(a.id)
    await store.refresh()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}
</script>
