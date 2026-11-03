<template>
  <div class="space-y-5 max-w-4xl">
    <div class="p-5 rounded-3xl bg-slate-900 border border-slate-800">
      <h2 class="text-xl font-black text-white">Cài đặt</h2>
      <p class="text-slate-400 text-sm mt-1">Thông số chung cho mọi phim. Thư mục ảnh/video đặt riêng trong từng phim.</p>
    </div>

    <form @submit.prevent="save" class="p-5 rounded-3xl bg-slate-900/80 border border-slate-800 space-y-5">
      <div class="grid grid-cols-1 md:grid-cols-2 gap-5">
        <div v-for="f in fields" :key="f.key">
          <label class="block text-xs font-semibold uppercase text-slate-300 mb-1.5">{{ f.label }}</label>
          <select v-if="f.type === 'bool'" v-model="settings[f.key]" class="inp">
            <option value="false">Không</option>
            <option value="true">Có</option>
          </select>
          <input v-else v-model="settings[f.key]" type="number" :min="f.min" :max="f.max" class="inp" />
          <p class="text-[11px] text-slate-500 mt-1">{{ f.hint }}</p>
        </div>
      </div>
      <div class="pt-4 border-t border-slate-800 flex justify-end">
        <button type="submit" class="px-6 py-2.5 rounded-xl bg-sky-600 hover:bg-sky-500 text-white font-bold text-sm">Lưu cài đặt</button>
      </div>
    </form>

    <div v-if="info" class="p-5 rounded-3xl bg-slate-900/60 border border-slate-800 text-xs text-slate-400 space-y-1.5">
      <h3 class="text-sm font-bold text-slate-200 mb-2">Hệ thống</h3>
      <div>Worker Python đang chạy: <b :class="info.workersAlive ? 'text-emerald-400' : 'text-rose-400'">{{ info.workersAlive }}</b></div>
      <div>Dữ liệu (DB, profile tài khoản, file chờ duyệt): <button @click="open(info.dataDir)" class="font-mono text-sky-300 underline break-all">{{ info.dataDir }}</button></div>
      <div>Log: <button @click="open(info.logsDir)" class="font-mono text-sky-300 underline break-all">{{ info.logsDir }}</button></div>
      <div>Thư mục cài đặt: <span class="font-mono break-all">{{ info.homeDir }}</span> · Java {{ info.javaVersion }}</div>
      <div>Selector Google Flow: <span class="font-mono">engine\selectors.json</span> trong thư mục cài đặt (sửa được khi Flow đổi giao diện, không cần cài lại).</div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { settingsApi, systemApi, errMsg } from '@/api'
import { store } from '@/store'

const fields = [
  { key: 'worker_processes', label: 'Số worker chạy song song', min: 1, max: 20, hint: 'Mỗi worker mở một trình duyệt Edge. Khuyến nghị 2–5 tùy RAM. Áp dụng ngay khi lưu.' },
  { key: 'job_timeout_minutes', label: 'Thời gian chờ render tối đa (phút)', min: 1, max: 120, hint: 'Quá thời gian này coi là lỗi tạm thời và thử lại.' },
  { key: 'quota_cooldown_minutes', label: 'Tài khoản hết quota nghỉ (phút)', min: 1, max: 10080, hint: 'Sau thời gian này tài khoản được dùng lại tự động.' },
  { key: 'headless', label: 'Ẩn trình duyệt khi chạy ngầm', type: 'bool', hint: 'Google Flow có thể chặn chế độ ẩn; hãy thử trước với vài job.' },
  { key: 'staging_retention_days', label: 'Giữ file xem trước cũ (ngày)', min: 1, max: 365, hint: 'File kết quả bị bỏ khi tạo lại.' },
]

const settings = ref({})
const info = ref(null)

onMounted(async () => {
  try {
    settings.value = await settingsApi.get()
    info.value = await systemApi.info()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
})

async function save() {
  try {
    await settingsApi.save(settings.value)
    store.toast('Đã lưu cài đặt', 'success')
    info.value = await systemApi.info()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

async function open(path) {
  try {
    await systemApi.openFolder(path)
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}
</script>

<style scoped>
.inp {
  @apply w-full px-4 py-2.5 rounded-xl bg-slate-950 border border-slate-700 text-slate-100 text-sm focus:border-sky-500 focus:outline-none;
}
</style>
