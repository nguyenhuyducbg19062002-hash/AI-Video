/**
 * Ô tìm cảnh dùng chung cho "Phim & hàng đợi" và "Danh sách cảnh".
 * Gõ được nhiều thứ, cách nhau bằng dấu phẩy / khoảng trắng:
 *   PANEL-1            → đúng ID đó (không lẫn PANEL-10, PANEL-11…)
 *   5  hoặc  5-20      → STT 5, hoặc STT 5 tới 20 (video theo STT; ảnh kiểu PANEL-5, LOC-5 theo số)
 *   CALEB              → ID / mô tả có chứa chữ đó
 */
export function parseSceneQuery(q) {
  return (q || '')
    .split(/[,;\s]+/)
    .map((t) => t.trim().toLowerCase())
    .filter(Boolean)
    .map((t) => {
      const range = t.match(/^(\d+)\s*[-–]\s*(\d+)$/)
      if (range) {
        const a = +range[1], b = +range[2]
        return { type: 'range', from: Math.min(a, b), to: Math.max(a, b) }
      }
      if (/^\d+$/.test(t)) return { type: 'range', from: +t, to: +t }
      return { type: 'text', text: t }
    })
}

// Số để lọc theo khoảng: video = STT; ảnh chỉ khi ID một đoạn chữ + số (PANEL-5, LOC-2, PROP-1),
// không tính ảnh nhân vật CHAR-TÊN-1 (gõ "1-2" không ra mọi nhân vật số 1, 2)
const numberOf = (j) => {
  if (j.kind === 'VIDEO') return Number(j.seqNo)
  const m = String(j.externalId).match(/^[A-Za-z]+-(\d+)$/)
  return m ? Number(m[1]) : NaN
}

/** terms: kết quả parseSceneQuery; allIds: Set các ID (chữ thường) để nhận ra "gõ đúng một ID". */
export function matchScene(j, terms, allIds) {
  if (!terms.length) return true
  const id = String(j.externalId).toLowerCase()
  return terms.some((t) => {
    if (t.type === 'range') {
      const n = numberOf(j)
      return n >= t.from && n <= t.to
    }
    if (allIds && allIds.has(t.text)) return id === t.text
    return id.includes(t.text) || (j.summaryVi || '').toLowerCase().includes(t.text) || (j.sourceName || '').toLowerCase().includes(t.text)
  })
}

/** Cảnh có thể đưa vào "Chạy các cảnh đã chọn" (cảnh đang tạo / chờ duyệt / đã xong thì không). */
export const canRun = (j) => ['PENDING', 'FAILED', 'CANCELLED'].includes(j.status) && !(j.kind === 'IMAGE' && j.inStore && j.status === 'CANCELLED')
