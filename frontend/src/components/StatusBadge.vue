<template>
  <span :class="cls" class="px-2 py-0.5 rounded-full text-[11px] font-semibold uppercase tracking-wide whitespace-nowrap border">
    {{ label }}
  </span>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ status: { type: String, required: true } })

const MAP = {
  // job
  PENDING: ['Chờ chạy', 'bg-slate-800 text-slate-300 border-slate-700'],
  GENERATING: ['Đang tạo', 'bg-sky-500/10 text-sky-300 border-sky-500/30'],
  AWAITING_REVIEW: ['Chờ duyệt', 'bg-amber-500/10 text-amber-300 border-amber-500/40'],
  APPROVED: ['Đã duyệt', 'bg-emerald-500/10 text-emerald-300 border-emerald-500/30'],
  DOWNLOADING: ['Đang lưu', 'bg-emerald-500/10 text-emerald-300 border-emerald-500/30'],
  SUCCESS: ['Xong', 'bg-emerald-500/15 text-emerald-300 border-emerald-500/40'],
  FAILED: ['Lỗi', 'bg-rose-500/10 text-rose-300 border-rose-500/40'],
  CANCELLED: ['Đã hủy', 'bg-slate-800 text-slate-400 border-slate-700'],
  // tài khoản
  READY: ['Sẵn sàng', 'bg-emerald-500/10 text-emerald-300 border-emerald-500/30'],
  LEASED: ['Đang chạy', 'bg-sky-500/10 text-sky-300 border-sky-500/30'],
  COOLDOWN: ['Nghỉ (quota)', 'bg-amber-500/10 text-amber-300 border-amber-500/40'],
  NEED_LOGIN: ['Cần đăng nhập', 'bg-orange-500/10 text-orange-300 border-orange-500/40'],
  DISABLED: ['Đã tắt', 'bg-slate-800 text-slate-400 border-slate-700'],
  // project
  DRAFT: ['Nháp', 'bg-slate-800 text-slate-300 border-slate-700'],
  RUNNING: ['Đang chạy', 'bg-sky-500/10 text-sky-300 border-sky-500/30'],
  PAUSED: ['Tạm dừng', 'bg-amber-500/10 text-amber-300 border-amber-500/40'],
}

const label = computed(() => (MAP[props.status] || [props.status])[0])
const cls = computed(() => (MAP[props.status] || [null, 'bg-slate-800 text-slate-300 border-slate-700'])[1])
</script>
