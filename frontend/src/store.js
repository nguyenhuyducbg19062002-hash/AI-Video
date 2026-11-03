import { reactive } from 'vue'

function load(key, fallback) {
  try {
    const v = localStorage.getItem(key)
    return v === null ? fallback : JSON.parse(v)
  } catch {
    return fallback
  }
}

function save(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    /* trình duyệt chặn localStorage: bỏ qua, chỉ là tiện ích */
  }
}

/** Trạng thái dùng chung giữa các màn: phim đang chọn + thông báo nổi. */
export const store = reactive({
  projectId: load('afm.projectId', null),
  view: load('afm.view', 'projects'),
  toasts: [],

  selectProject(id) {
    this.projectId = id
    save('afm.projectId', id)
  },
  go(view) {
    this.view = view
    save('afm.view', view)
  },
  toast(message, kind = 'info') {
    const id = Date.now() + Math.random()
    this.toasts.push({ id, message, kind })
    setTimeout(() => {
      this.toasts = this.toasts.filter((t) => t.id !== id)
    }, kind === 'error' ? 8000 : 3500)
  },
})
