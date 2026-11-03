import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 120000 })

/** Thông báo lỗi tiếng Việt từ backend ({"error": "..."}), hoặc lỗi mạng. */
export function errMsg(e) {
  return e?.response?.data?.error || e?.message || 'Lỗi không xác định'
}

const data = (p) => p.then((r) => r.data)

export const projectsApi = {
  list: () => data(http.get('/projects')),
  get: (id) => data(http.get(`/projects/${id}`)),
  create: (form) => data(http.post('/projects', form)),
  update: (id, form) => data(http.put(`/projects/${id}`, form)),
  remove: (id) => data(http.delete(`/projects/${id}`)),
  importPrompts: (id) => data(http.post(`/projects/${id}/import`)),
  start: (id) => data(http.post(`/projects/${id}/start`)),
  pause: (id) => data(http.post(`/projects/${id}/pause`)),
  retryFailed: (id) => data(http.post(`/projects/${id}/retry-failed`)),
  verifyFiles: (id) => data(http.post(`/projects/${id}/verify-files`)),
}

export const jobsApi = {
  list: (projectId, status) => data(http.get(`/projects/${projectId}/jobs`, { params: status ? { status } : {} })),
  get: (id) => data(http.get(`/jobs/${id}`)),
  history: (id) => data(http.get(`/jobs/${id}/history`)),
  approve: (id) => data(http.post(`/jobs/${id}/approve`)),
  approveBatch: (ids) => data(http.post('/jobs/approve-batch', ids)),
  regenerate: (id, promptText, note) => data(http.post(`/jobs/${id}/regenerate`, { promptText, note })),
  cancel: (id) => data(http.post(`/jobs/${id}/cancel`)),
  previewUrl: (job) => `/api/jobs/${job.id}/preview?v=${encodeURIComponent(job.generatedAt || '')}`,
}

export const accountsApi = {
  list: () => data(http.get('/accounts')),
  create: (form) => data(http.post('/accounts', form)),
  update: (id, form) => data(http.put(`/accounts/${id}`, form)),
  remove: (id) => data(http.delete(`/accounts/${id}`)),
  openLogin: (id) => data(http.post(`/accounts/${id}/open-login`)),
  confirmLogin: (id) => data(http.post(`/accounts/${id}/confirm-login`)),
  enable: (id) => data(http.post(`/accounts/${id}/enable`)),
  disable: (id) => data(http.post(`/accounts/${id}/disable`)),
}

export const settingsApi = {
  get: () => data(http.get('/settings')),
  save: (s) => data(http.post('/settings', s)),
}

export const systemApi = {
  info: () => data(http.get('/system/info')),
  shutdown: () => data(http.post('/system/shutdown')),
  pickFolder: (initial) => data(http.post('/system/pick-folder', { initial: initial || '' })),
  openFolder: (path) => data(http.post('/system/open-folder', { path })),
}
