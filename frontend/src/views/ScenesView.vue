<template>
  <div class="space-y-4">
    <div v-if="!store.current" class="card py-20 text-center text-slate-500">Chọn một phim ở thanh bên trái.</div>

    <template v-else>
      <!-- Trạng thái chạy của phim: tạm dừng (ví dụ vì đã đóng cửa sổ Flow) / đang chạy -->
      <section v-if="store.current.project.status === 'PAUSED'" class="note-warn flex flex-wrap items-center gap-3 !text-sm">
        <Pause class="w-4 h-4 shrink-0" />
        <span class="flex-1">
          <b>Phim đang tạm dừng.</b> {{ store.current.project.pausedReason || '' }}
        </span>
        <button @click="resumeProject" class="btn-primary btn-sm"><Play class="w-3.5 h-3.5" /> Chạy tiếp</button>
      </section>
      <section v-else-if="store.current.project.status === 'RUNNING'" class="note-info flex flex-wrap items-center gap-3 !text-sm">
        <Play class="w-4 h-4 shrink-0" />
        <span class="flex-1">
          <b>Phim đang chạy.</b>
          {{ store.current.project.runMode === 'FOREGROUND' ? 'Đóng cửa sổ Flow hoặc bấm "Tạm dừng phim" trên cửa sổ đó để dừng.' : '' }}
        </span>
        <button @click="pauseProject" class="btn-warn btn-sm"><Pause class="w-3.5 h-3.5" /> Tạm dừng</button>
      </section>

      <!-- Thanh công cụ: tab Ảnh / Video, lọc, tìm -->
      <section class="card p-3 flex flex-wrap items-center gap-3">
        <div class="flex p-1 rounded-xl bg-slate-100">
          <button v-for="t in kinds" :key="t.key" @click="setKind(t.key)"
            :class="kind === t.key ? 'bg-white shadow-sm text-indigo-700' : 'text-slate-600 hover:text-slate-800'"
            class="px-4 py-1.5 rounded-lg text-sm font-medium flex items-center gap-1.5 transition">
            <component :is="t.icon" class="w-4 h-4" /> {{ t.label }}
            <span class="count-pill">{{ countKind(t.key) }}</span>
          </button>
        </div>
        <select v-if="sources.length > 1" v-model="source" class="input !w-auto !py-1.5">
          <option value="">Mọi file Excel</option>
          <option v-for="s in sources" :key="s" :value="s">{{ s }}</option>
        </select>
        <select v-model="status" class="input !w-auto !py-1.5">
          <option value="">Mọi trạng thái</option>
          <option v-for="s in statusOptions" :key="s.value" :value="s.value">{{ s.label }}</option>
        </select>
        <div class="relative flex-1 min-w-[200px]">
          <Search class="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2" />
          <input v-model="q" placeholder="Tìm: PANEL-1, CHAR-CALEB-1, 5-20 (STT), chữ trong mô tả…" class="input !pl-9 !py-1.5" />
        </div>
        <label class="flex items-center gap-1.5 text-xs text-slate-600 cursor-pointer">
          <input type="checkbox" v-model="onlyMissing" class="accent-indigo-600" /> chỉ cảnh thiếu ảnh
        </label>
        <button @click="load" class="btn btn-sm" title="Tải lại"><RefreshCw class="w-3.5 h-3.5" /></button>
      </section>

      <!-- Chọn một hoặc nhiều cảnh để chạy -->
      <section v-if="filtered.length" class="card px-3 py-2 flex flex-wrap items-center gap-3 text-sm">
        <label class="flex items-center gap-1.5 text-xs text-slate-700 cursor-pointer">
          <input type="checkbox" class="accent-indigo-600" :checked="allPageSelected" @change="togglePage($event.target.checked)" />
          chọn cả trang ({{ pageItems.length }})
        </label>
        <button @click="selectAllFiltered" class="btn btn-sm">Chọn tất cả {{ filtered.length }} cảnh đang lọc</button>
        <template v-if="selected.size">
          <b class="text-indigo-800">Đã chọn {{ selected.size }} cảnh</b>
          <span class="text-xs text-slate-500">({{ selectedImages }} ảnh, {{ selectedVideos }} video)</span>
          <label class="flex items-center gap-1.5 text-xs text-slate-700 cursor-pointer">
            <input type="checkbox" v-model="onlySelected" class="accent-indigo-600" />
            chỉ chạy các cảnh này (xong thì phim tự dừng)
          </label>
          <div class="ml-auto flex gap-1.5">
            <button @click="runSelected" :disabled="running" class="btn-primary btn-sm">
              <Play class="w-3.5 h-3.5" /> {{ selectedVideos && !selectedImages ? 'Tạo các video đã chọn' : selectedImages && !selectedVideos ? 'Tạo các ảnh đã chọn' : 'Chạy các cảnh đã chọn' }}
            </button>
            <button @click="selected = new Set()" class="btn btn-sm">Bỏ chọn</button>
          </div>
        </template>
        <span v-else class="text-xs text-slate-400">Chạy 1 cảnh: bấm "Chạy riêng" ở cảnh đó. Nhiều cảnh: tick ô ở góc mỗi cảnh rồi chạy một lần.</span>
      </section>

      <div v-if="!jobs.length" class="card py-16 text-center">
        <FileSpreadsheet class="w-9 h-9 mx-auto text-indigo-300" />
        <div class="text-slate-700 mt-3">Phim chưa có cảnh nào</div>
        <div class="text-sm text-slate-500 mt-1">Vào trang "Phim &amp; hàng đợi" → "Thêm Excel ảnh" / "Thêm Excel video".</div>
      </div>

      <div v-else-if="!filtered.length" class="card py-12 text-center text-slate-500 text-sm">Không có cảnh nào khớp bộ lọc.</div>

      <!-- Danh sách cảnh theo đúng thứ tự file -->
      <article v-for="j in pageItems" :key="j.id" class="card p-4 border-l-4" :class="[j.kind === 'VIDEO' ? '!border-l-violet-400' : '!border-l-sky-400', selected.has(j.id) ? 'ring-2 ring-indigo-300' : '']">
        <div class="flex flex-col lg:flex-row gap-4">
          <div class="flex-1 min-w-0 space-y-3">
            <div class="flex flex-wrap items-center gap-2">
              <input type="checkbox" class="accent-indigo-600 w-4 h-4" :checked="selected.has(j.id)" @change="toggle(j.id, $event.target.checked)"
                :title="canRun(j) ? 'Chọn cảnh này' : 'Cảnh đang tạo / đã xong: sẽ bỏ qua khi chạy'" />
              <span :class="j.kind === 'VIDEO' ? 'bg-violet-100 text-violet-700 border-violet-200' : 'bg-sky-100 text-sky-700 border-sky-200'"
                class="inline-flex items-center gap-1 px-2 py-0.5 rounded-md border text-[11px] font-semibold">
                <component :is="j.kind === 'VIDEO' ? Video : ImageIcon" class="w-3 h-3" /> {{ j.kind === 'VIDEO' ? 'Video' : 'Ảnh' }}
              </span>
              <span class="font-mono text-sm font-semibold text-slate-800">{{ j.kind === 'VIDEO' ? 'STT ' + j.seqNo : j.externalId }}</span>
              <span v-if="isProvided(j)" class="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-[11px] font-semibold border bg-emerald-50 text-emerald-700 border-emerald-200">
                <span class="w-1.5 h-1.5 rounded-full bg-emerald-500"></span>Có sẵn
              </span>
              <StatusBadge v-else :status="j.status" />
              <span v-if="j.sourceName" class="text-[11px] text-slate-400">{{ j.sourceName }} · dòng {{ j.seqNo }}</span>
              <span v-if="j.errorMessage" class="text-[11px] text-rose-600 truncate max-w-md" :title="j.errorMessage">{{ j.errorMessage }}</span>
            </div>
            <p v-if="j.summaryVi" class="text-sm text-slate-700 whitespace-pre-line leading-relaxed">{{ j.summaryVi }}</p>
            <details class="text-xs" @toggle="$event.target.open && loadPrompt(j)">
              <summary class="cursor-pointer text-indigo-600 select-none">Xem prompt</summary>
              <pre class="mt-2 p-3 rounded-xl bg-slate-50 border border-slate-200 whitespace-pre-wrap font-mono text-[11px] text-slate-600 max-h-64 overflow-y-auto">{{ prompts[j.id] ?? 'Đang tải…' }}</pre>
            </details>
            <div>
              <div class="section-label mb-1.5">Ảnh reference ({{ j.references.length }}) · upload lên Flow theo thứ tự này</div>
              <RefStrip :job="j" :project-id="store.current.project.id" @changed="load" @zoom="zoomRef" @goto="gotoImage" />
            </div>
          </div>

          <!-- Kết quả -->
          <div class="lg:w-72 shrink-0">
            <div class="section-label mb-1.5">Kết quả</div>
            <button v-if="j.hasPreview || j.finalPath" @click="openCompare(j)"
              class="block w-full rounded-xl overflow-hidden border border-slate-200 bg-slate-900 hover:ring-2 hover:ring-indigo-300 transition">
              <img v-if="j.hasPreview && j.previewType === 'image'" :src="jobsApi.previewUrl(j)" loading="lazy" class="w-full h-40 object-contain" />
              <div v-else class="h-40 flex flex-col items-center justify-center text-slate-300 text-xs gap-1">
                <PlayCircle class="w-8 h-8" />
                {{ j.hasPreview ? 'Xem video' : 'Đã lưu vào folder' }}
              </div>
            </button>
            <button v-else-if="j.inStore" @click="zoomRef(j.externalId)" title="Ảnh trong kho của phim (có sẵn hoặc app đã tạo)"
              class="block w-full rounded-xl overflow-hidden border border-emerald-200 bg-slate-50 hover:ring-2 hover:ring-emerald-200 transition">
              <img :src="projectsApi.assetUrl(store.current.project.id, j.externalId)" loading="lazy" class="w-full h-40 object-contain" />
            </button>
            <div v-else class="h-40 rounded-xl border-2 border-dashed border-slate-200 flex items-center justify-center text-xs text-slate-400 text-center px-3">
              {{ j.status === 'GENERATING' ? 'Đang tạo…' : 'Chưa có kết quả' }}
            </div>
            <div class="flex gap-1.5 mt-2">
              <button v-if="j.hasPreview || j.finalPath" @click="openCompare(j)" class="btn btn-sm flex-1"><Columns2 class="w-3.5 h-3.5" /> So sánh</button>
              <button v-if="j.status === 'AWAITING_REVIEW'" @click="approve(j)" class="btn-success btn-sm flex-1"><Check class="w-3.5 h-3.5" /> Lưu</button>
              <button v-if="['FAILED', 'CANCELLED', 'SUCCESS', 'AWAITING_REVIEW'].includes(j.status)" @click="regenerate(j)" class="btn btn-sm flex-1"
                :title="isProvided(j) ? 'Bỏ ảnh có sẵn, cho app tạo ảnh mới từ prompt' : ''">
                <RotateCcw class="w-3.5 h-3.5" /> Tạo lại
              </button>
            </div>
            <!-- Tự tải kết quả từ Flow về máy rồi đưa vào app -->
            <label class="btn btn-sm w-full mt-1.5 !py-1.5 cursor-pointer" :class="uploading === j.id ? 'opacity-60 pointer-events-none' : ''"
              :title="j.kind === 'VIDEO' ? 'Chọn file video bạn đã tải từ Flow (nút ⬇ trên Flow): app lưu vào Folder Video với tên ' + store.current.project.code + '_' + j.externalId + '.mp4' : 'Chọn file ảnh: app lưu vào Folder Ảnh với tên ' + j.externalId"
              v-if="!['SUCCESS', 'APPROVED', 'DOWNLOADING'].includes(j.status)">
              <Upload class="w-3.5 h-3.5" />
              {{ uploading === j.id ? 'Đang lưu…' : j.kind === 'VIDEO' ? 'Đưa video đã tải về vào app' : 'Đưa ảnh đã tải về vào app' }}
              <input type="file" class="hidden" :accept="j.kind === 'VIDEO' ? 'video/mp4,video/quicktime,video/webm,.mp4,.mov,.webm' : 'image/png,image/jpeg,image/webp'"
                @change="manualResult(j, $event)" />
            </label>
            <!-- Nút tạo: cảnh nào cũng có -->
            <div class="mt-1.5 space-y-1.5">
              <template v-if="j.kind === 'IMAGE'">
                <div v-if="canRun(j)" class="text-[11px] font-semibold text-sky-700">Tạo ảnh {{ j.externalId }}:</div>
                <div v-if="canRun(j)" class="flex gap-1.5">
                  <button @click="runScenes([j], j.externalId, true)" :disabled="running" :title="'Chỉ chạy ' + 'ảnh ' + j.externalId + ', xong thì phim tự dừng (không chạy cả hàng)'"
                    class="btn btn-sm flex-1 !py-1.5 !bg-indigo-600 !text-white !border-indigo-600 hover:!bg-indigo-700"><Play class="w-3.5 h-3.5" /> Chạy riêng</button>
                  <button @click="runScenes([j], j.externalId, false)" :disabled="running" :title="'Đưa ' + 'ảnh ' + j.externalId + ' lên đầu hàng, sau đó phim chạy tiếp như thường'"
                    class="btn btn-sm flex-1 !py-1.5"><ChevronsUp class="w-3.5 h-3.5" /> Chạy trước</button>
                </div>
                <div v-else-if="j.status === 'GENERATING'" class="btn btn-sm w-full !py-1.5 opacity-70 cursor-default">Đang tạo ảnh…</div>
                <button v-for="v in videosOf(j)" :key="v.id" @click="createVideo(v, true)" :disabled="running || !canRun(v)"
                  :title="canRun(v) ? 'Tạo ảnh này (nếu chưa có) rồi tạo video dùng ảnh này' : 'Video này ' + statusLabel(v.status)"
                  class="btn btn-sm w-full !py-1.5 !bg-violet-600 !text-white !border-violet-600 hover:!bg-violet-700 disabled:!bg-violet-300 disabled:!border-violet-300">
                  <Video class="w-3.5 h-3.5" /> Tạo video STT {{ v.seqNo }}
                </button>
              </template>
              <template v-else>
                <!-- ảnh của cảnh đã tạo xong, đang chờ duyệt: duyệt luôn ở đây -->
                <div v-if="canRun(j) && imagesToApprove(j).length" class="rounded-lg border border-amber-200 bg-amber-50 p-2 space-y-1.5">
                  <div class="text-[11px] font-semibold text-amber-800">
                    Ảnh {{ imagesToApprove(j).map((x) => x.externalId).join(', ') }} đã tạo xong nhưng chưa lưu được vào folder. Bấm Lưu để tạo được video.
                  </div>
                  <div class="flex gap-1.5">
                    <button v-for="x in imagesToApprove(j)" :key="x.id" @click="approveImage(x)" :disabled="running" class="btn-success btn-sm flex-1">
                      <Check class="w-3.5 h-3.5" /> Lưu {{ x.externalId }}
                    </button>
                    <button @click="gotoImage(imagesToApprove(j)[0].externalId)" class="btn btn-sm">Xem ảnh</button>
                  </div>
                </div>
                <div v-if="canRun(j) && imagesGenerating(j).length" class="text-[11px] text-sky-700">
                  Đang tạo ảnh {{ imagesGenerating(j).map((x) => x.externalId).join(', ') }}…
                </div>
                <div v-if="canRun(j) && skipMissing && j.references.some((r) => !r.available)" class="text-[11px] text-slate-500">
                  Tạo luôn với {{ j.references.filter((r) => r.available).length }} ảnh có sẵn, bỏ qua {{ j.references.filter((r) => !r.available).map((r) => r.id).join(', ') }}.
                </div>
                <div v-if="canRun(j)" class="text-[11px] font-semibold text-violet-700">
                  {{ imagesToCreate(j).length ? 'Tạo ảnh ' + imagesToCreate(j).map((x) => x.externalId).join(', ') + ' rồi tạo video:'
                    : imagesToApprove(j).length || imagesGenerating(j).length ? 'Tạo video STT ' + j.seqNo + ' (chạy khi đủ ảnh):'
                    : 'Tạo video STT ' + j.seqNo + ':' }}
                </div>
                <div v-if="canRun(j)" class="flex gap-1.5">
                  <button @click="createVideo(j, true)" :disabled="running || missingSource(j).length > 0" :title="'Chỉ chạy video STT ' + j.seqNo + ', xong thì phim tự dừng (không chạy cả hàng)'"
                    class="btn btn-sm flex-1 !py-1.5 !bg-violet-600 !text-white !border-violet-600 hover:!bg-violet-700 disabled:!bg-violet-300 disabled:!border-violet-300"><Play class="w-3.5 h-3.5" /> Chạy riêng</button>
                  <button @click="createVideo(j, false)" :disabled="running || missingSource(j).length > 0" :title="'Đưa video STT ' + j.seqNo + ' lên đầu hàng, sau đó phim chạy tiếp như thường'"
                    class="btn btn-sm flex-1 !py-1.5"><ChevronsUp class="w-3.5 h-3.5" /> Chạy trước</button>
                </div>
                <div v-else-if="j.status === 'GENERATING'" class="btn btn-sm w-full !py-1.5 opacity-70 cursor-default">Đang tạo video…</div>
                <div v-if="canRun(j) && imagesToCreate(j).length" class="text-[11px] text-violet-700">
                  App tạo ảnh trước; đủ ảnh thì video tự chạy.
                </div>
                <div v-if="missingSource(j).length" class="text-[11px] text-rose-600">Thiếu ảnh gốc: {{ missingSource(j).join(', ') }}</div>
              </template>
            </div>
          </div>
        </div>
      </article>

      <!-- Phân trang -->
      <div v-if="pages > 1" class="flex items-center justify-center gap-2 pb-2">
        <button @click="page = Math.max(1, page - 1)" :disabled="page === 1" class="btn btn-sm">‹ Trước</button>
        <span class="text-sm text-slate-600">Trang {{ page }} / {{ pages }} · {{ filtered.length }} cảnh</span>
        <button @click="page = Math.min(pages, page + 1)" :disabled="page === pages" class="btn btn-sm">Sau ›</button>
      </div>
    </template>

    <!-- So sánh kết quả với ảnh reference + prompt -->
    <div v-if="compare" class="fixed inset-0 z-50 bg-slate-900/50 backdrop-blur-[2px] flex items-center justify-center p-4" @click.self="compare = null">
      <div class="card w-full max-w-7xl max-h-[94vh] flex flex-col shadow-2xl">
        <div class="card-head items-center">
          <Columns2 class="w-5 h-5 text-indigo-500" />
          <div class="flex-1 min-w-0">
            <div class="card-title">So sánh {{ compare.kind === 'VIDEO' ? 'video STT ' + compare.seqNo : 'ảnh ' + compare.externalId }}</div>
            <div class="card-sub">Đối chiếu kết quả với ảnh reference và mô tả cảnh</div>
          </div>
          <StatusBadge :status="compare.status" />
          <button @click="compare = null" class="text-slate-400 hover:text-slate-700 ml-2"><X class="w-5 h-5" /></button>
        </div>
        <div class="p-4 overflow-y-auto grid grid-cols-1 xl:grid-cols-2 gap-4">
          <div>
            <div class="section-label mb-1.5">Kết quả</div>
            <div class="rounded-xl bg-slate-900 overflow-hidden flex items-center justify-center min-h-[300px]">
              <template v-if="compare.hasPreview">
                <video v-if="compare.previewType === 'video'" :src="jobsApi.previewUrl(compare)" controls autoplay loop class="w-full max-h-[60vh]"></video>
                <img v-else :src="jobsApi.previewUrl(compare)" class="max-w-full max-h-[60vh] object-contain" />
              </template>
              <div v-else class="text-slate-300 text-sm p-6 text-center">Đã lưu: <span class="font-mono break-all">{{ compare.finalPath }}</span></div>
            </div>
            <p v-if="compare.summaryVi" class="note-info !text-sm !text-slate-700 mt-3 whitespace-pre-line">{{ compare.summaryVi }}</p>
          </div>
          <div class="space-y-3">
            <div>
              <div class="section-label mb-1.5">Ảnh reference ({{ compare.references.length }})</div>
              <RefStrip :job="compare" :project-id="store.current.project.id" :size="132" @changed="refreshCompare" @zoom="zoomRef" @goto="gotoImage" />
            </div>
            <div>
              <div class="section-label mb-1.5">Prompt</div>
              <pre class="p-3 rounded-xl bg-slate-50 border border-slate-200 whitespace-pre-wrap font-mono text-[11px] text-slate-600 max-h-72 overflow-y-auto">{{ compare.prompt ?? 'Đang tải…' }}</pre>
            </div>
          </div>
        </div>
        <div class="flex justify-end gap-2 px-4 py-3 border-t border-slate-100">
          <button @click="compare = null" class="btn">Đóng</button>
          <template v-if="compare.kind === 'VIDEO' && compare.flowEditable && ['AWAITING_REVIEW', 'SUCCESS'].includes(compare.status)">
            <input v-model="editText" class="input !py-1.5 !text-xs flex-1 min-w-[220px]" placeholder="Cần Flow sửa gì trong video này?" />
            <button @click="flowEdit(compare)" :disabled="!editText.trim()" class="btn !bg-violet-600 !text-white !border-violet-600 hover:!bg-violet-700 disabled:!bg-violet-300">
              <Wand2 class="w-4 h-4" /> Sửa trên Flow
            </button>
          </template>
          <button v-if="compare.status === 'AWAITING_REVIEW'" @click="regenerate(compare)" class="btn-warn"><RotateCcw class="w-4 h-4" /> Tạo lại</button>
          <button v-if="compare.status === 'AWAITING_REVIEW'" @click="approve(compare)" class="btn-success"><Check class="w-4 h-4" /> Lưu vào folder</button>
        </div>
      </div>
    </div>

    <!-- Phóng to một ảnh reference -->
    <div v-if="zoom" class="fixed inset-0 z-[55] bg-slate-900/80 flex flex-col items-center justify-center p-6" @click="zoom = null">
      <img :src="projectsApi.assetUrl(store.current.project.id, zoom)" class="max-w-full max-h-[85vh] object-contain rounded-xl shadow-2xl" />
      <div class="mt-3 font-mono text-white text-sm">{{ zoom }}</div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import {
  Check, ChevronsUp, Columns2, FileSpreadsheet, Upload, Wand2, Image as ImageIcon, Pause, Play, PlayCircle, RefreshCw, RotateCcw, Search, Video, X,
} from 'lucide-vue-next'
import StatusBadge from '@/components/StatusBadge.vue'
import RefStrip from '@/components/RefStrip.vue'
import { jobsApi, projectsApi, errMsg } from '@/api'
import { store } from '@/store'
import { canRun, matchScene, parseSceneQuery } from '@/sceneFilter'

const PAGE_SIZE = 25
// Cảnh = video (kết quả cuối của phim); ảnh tham chiếu là tab phụ
const kinds = [
  { key: 'VIDEO', label: 'Cảnh – tạo video', icon: Video },
  { key: 'IMAGE', label: 'Ảnh tham chiếu', icon: ImageIcon },
]
const statusOptions = [
  { value: 'PENDING', label: 'Chờ chạy' },
  { value: 'GENERATING', label: 'Đang tạo' },
  { value: 'AWAITING_REVIEW', label: 'Đang lưu' },
  { value: 'SUCCESS', label: 'Xong' },
  { value: 'FAILED', label: 'Lỗi' },
  { value: 'CANCELLED', label: 'Đã hủy / có sẵn' },
]

const jobs = ref([])
const kind = ref('VIDEO')
const source = ref('')
const status = ref('')
const q = ref('')
const onlyMissing = ref(false)
const page = ref(1)
const prompts = reactive({})
const compare = ref(null)
const zoom = ref(null)

const countKind = (k) => jobs.value.filter((j) => j.kind === k).length
// Ảnh gốc người dùng đã đưa vào: job tạo ảnh bị hủy vì không cần tạo nữa
const isProvided = (j) => j.kind === 'IMAGE' && j.inStore && j.status === 'CANCELLED'
const sources = computed(() => [...new Set(jobs.value.filter((j) => j.kind === kind.value).map((j) => j.sourceName).filter(Boolean))])

// gõ đúng một ID (PANEL-1…) thì chỉ ra đúng ID đó, không lẫn PANEL-10; "5-20" = STT 5 tới 20; nhiều mục cách nhau bằng dấu phẩy
const allIds = computed(() => new Set(jobs.value.map((j) => String(j.externalId).toLowerCase())))
const filtered = computed(() => {
  const terms = parseSceneQuery(q.value)
  return jobs.value.filter((j) => j.kind === kind.value
    && (!source.value || j.sourceName === source.value)
    && (!status.value || j.status === status.value)
    && (!onlyMissing.value || j.references.some((r) => !r.available))
    && matchScene(j, terms, allIds.value))
})

// ----- chọn nhiều cảnh để chạy -----
const selected = ref(new Set())
const onlySelected = ref(true)
const running = ref(false)
const selectedJobs = computed(() => jobs.value.filter((j) => selected.value.has(j.id)))
const selectedImages = computed(() => selectedJobs.value.filter((j) => j.kind === 'IMAGE').length)
const selectedVideos = computed(() => selectedJobs.value.filter((j) => j.kind === 'VIDEO').length)
function toggle(id, on) {
  const next = new Set(selected.value)
  if (on) next.add(id)
  else next.delete(id)
  selected.value = next
}
function togglePage(on) {
  const next = new Set(selected.value)
  pageItems.value.forEach((j) => (on ? next.add(j.id) : next.delete(j.id)))
  selected.value = next
}
function selectAllFiltered() {
  const next = new Set(selected.value)
  filtered.value.forEach((j) => next.add(j.id))
  selected.value = next
}
// ảnh (theo ID) → job tạo ảnh đó; ảnh → các video dùng nó (chỉ hiện nút khi ít video dùng, ví dụ ảnh PANEL)
const imageJobById = computed(() => new Map(jobs.value.filter((j) => j.kind === 'IMAGE').map((j) => [j.externalId, j])))
const videosByImage = computed(() => {
  const m = new Map()
  jobs.value.filter((j) => j.kind === 'VIDEO').forEach((v) => v.references.forEach((r) => {
    if (!m.has(r.id)) m.set(r.id, [])
    m.get(r.id).push(v)
  }))
  return m
})
const videosOf = (img) => {
  const list = videosByImage.value.get(img.externalId) || []
  return list.length <= 3 ? list : []
}
/** ảnh reference còn thiếu mà app tạo được (có prompt ở tab Tạo ảnh) */
const missingImages = (v) => v.references.filter((r) => !r.available).map((r) => imageJobById.value.get(r.id)).filter(Boolean)
const skipMissing = computed(() => !!store.current?.project.skipMissingRefs)
const imagesToCreate = (v) => (skipMissing.value ? [] : missingImages(v).filter((x) => canRun(x)))
const imagesToApprove = (v) => (skipMissing.value ? [] : missingImages(v).filter((x) => x.status === 'AWAITING_REVIEW'))
const imagesGenerating = (v) => (skipMissing.value ? [] : missingImages(v).filter((x) => x.status === 'GENERATING'))

const uploading = ref(null)
const editText = ref('')
/** "Sửa trên Flow": Flow chỉnh chính video này (đúng tài khoản đã tạo), bản mới về chờ duyệt */
async function flowEdit(j) {
  try {
    await jobsApi.flowEdit(j.id, editText.value)
    store.toast(`Đã gửi Flow sửa video STT ${j.seqNo}; bản mới sẽ tự lưu vào Folder Video`, 'success')
    editText.value = ''
    compare.value = null
    await load()
    store.refresh()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}
/** "Đưa video / ảnh đã tải về vào app": lưu vào folder của phim với tên đúng, cảnh thành "Xong" */
async function manualResult(j, ev) {
  const file = ev.target.files[0]
  ev.target.value = ''
  if (!file) return
  if (j.status === 'GENERATING' && !confirm('Cảnh này đang được app tạo trên Flow. Dùng file bạn chọn và dừng lượt tạo đó?')) return
  uploading.value = j.id
  try {
    const r = await jobsApi.manualResult(j.id, file)
    store.toast(`Đã lưu ${j.kind === 'VIDEO' ? 'video STT ' + j.seqNo : 'ảnh ' + j.externalId}: ${r.finalPath}`, 'success')
    await load()
    store.refresh()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  } finally {
    uploading.value = null
  }
}

/** Duyệt ảnh panel ngay trên thẻ video: ảnh vào kho, video của cảnh được phép chạy */
async function approveImage(x) {
  running.value = true
  try {
    await jobsApi.approve(x.id)
    store.toast(`Đã lưu ${x.externalId}`, 'success')
    await load()
    store.refresh()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  } finally {
    running.value = false
  }
}
/** ảnh gốc còn thiếu (không có prompt tạo): phải thêm tay */
const missingSource = (v) => (skipMissing.value ? [] : v.references.filter((r) => !r.available && !imageJobById.value.has(r.id)).map((r) => r.id))
const statusLabel = (st) => ({ GENERATING: 'đang tạo', AWAITING_REVIEW: 'đang lưu', SUCCESS: 'đã xong', APPROVED: 'đã xong', DOWNLOADING: 'đang lưu' }[st] || st)

/** Tạo video: tạo trước các ảnh còn thiếu (panel…), rồi tới video. Video tự chạy khi các ảnh đó được duyệt. */
function createVideo(v, only) {
  return runScenes([...imagesToCreate(v), v], 'video STT ' + v.seqNo, only, imagesToApprove(v).map((x) => x.externalId))
}

/** only = true: "Chạy riêng" (chỉ cảnh này, xong thì phim dừng); false: "Chạy trước" (lên đầu hàng, phim chạy tiếp) */
async function runScenes(list, label, only, waitApprove = []) {
  running.value = true
  try {
    const r = await projectsApi.runSelected(store.current.project.id, list.map((j) => j.id), only, true)
    const skipped = r.skipped.length ? `\nBỏ qua: ${r.skipped.slice(0, 4).join(', ')}` : ''
    store.toast(r.queued ? `Đang tạo ${label}` + (list.length > 1 ? ` (${r.queued} việc: ảnh trước, video sau)` : '')
      + (waitApprove.length ? `. Video sẽ chạy khi lưu xong ${waitApprove.join(', ')}.` : '')
      + (only ? ' Chỉ chạy cảnh này, xong thì phim tự dừng.' : ' Sau đó phim chạy tiếp như thường.') + skipped : `Không chạy được ${label}.` + skipped,
      r.queued ? 'success' : 'error')
    await load()
    store.refresh()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  } finally {
    running.value = false
  }
}

async function runSelected() {
  running.value = true
  try {
    const r = await projectsApi.runSelected(store.current.project.id, selectedJobs.value.map((j) => j.id), onlySelected.value)
    const skipped = r.skipped.length ? `\nBỏ qua ${r.skipped.length}: ${r.skipped.slice(0, 6).join(', ')}${r.skipped.length > 6 ? '…' : ''}` : ''
    store.toast(`Đã cho chạy ${r.queued} cảnh` + (onlySelected.value ? ' (chỉ các cảnh này, xong thì phim tự dừng)' : '') + skipped,
      r.queued ? 'success' : 'error')
    if (r.queued) selected.value = new Set()
    await load()
    store.refresh()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  } finally {
    running.value = false
  }
}
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / PAGE_SIZE)))
const allPageSelected = computed(() => pageItems.value.length > 0 && pageItems.value.every((j) => selected.value.has(j.id)))
const pageItems = computed(() => filtered.value.slice((page.value - 1) * PAGE_SIZE, page.value * PAGE_SIZE))

watch([kind, source, status, q, onlyMissing], () => { page.value = 1 })
watch(() => store.projectId, () => { jobs.value = []; selected.value = new Set(); load() })

function setKind(k) {
  kind.value = k
  source.value = ''
}

async function load() {
  if (!store.projectId) return
  try {
    jobs.value = await jobsApi.list(store.projectId, null, false)
    if (compare.value) {
      const fresh = jobs.value.find((j) => j.id === compare.value.id)
      if (fresh) compare.value = { ...fresh, prompt: compare.value.prompt }
    }
  } catch (e) {
    console.error(e)
  }
}

async function loadPrompt(j) {
  if (prompts[j.id] !== undefined) return
  try {
    prompts[j.id] = (await jobsApi.get(j.id)).prompt
  } catch (e) {
    prompts[j.id] = 'Không tải được prompt: ' + errMsg(e)
  }
}

async function openCompare(j) {
  compare.value = { ...j, prompt: prompts[j.id] }
  if (prompts[j.id] === undefined) {
    await loadPrompt(j)
    if (compare.value?.id === j.id) compare.value = { ...compare.value, prompt: prompts[j.id] }
  }
}

async function refreshCompare() {
  await load()
}

function zoomRef(id) {
  zoom.value = id
}

/** Bấm ô "Ảnh panel / Ảnh sẽ tạo": nhảy sang tab Tạo ảnh, lọc đúng ảnh đó. */
function gotoImage(id) {
  compare.value = null
  kind.value = 'IMAGE'
  source.value = ''
  status.value = ''
  onlyMissing.value = false
  q.value = id
  page.value = 1
  window.scrollTo?.({ top: 0 })
}

async function approve(j) {
  try {
    await jobsApi.approve(j.id)
    store.toast(`Đã lưu ${j.kind === 'VIDEO' ? 'video STT ' + j.seqNo : j.externalId}`, 'success')
    compare.value = null
    await load()
    store.refresh()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

async function runNow(j) {
  try {
    await jobsApi.runNow(j.id)
    store.toast(`Đã đưa ${j.kind === 'VIDEO' ? 'video STT ' + j.seqNo : j.externalId} lên đầu hàng đợi; phim đang chạy`, 'success')
    await load()
    store.refresh()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

async function resumeProject() {
  try {
    await projectsApi.start(store.current.project.id)
    store.toast('Phim chạy tiếp', 'success')
    store.refresh()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

async function pauseProject() {
  try {
    await projectsApi.pause(store.current.project.id)
    store.toast('Đã tạm dừng: cảnh đang làm chạy nốt, không mở cảnh mới', 'success')
    store.refresh()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

async function regenerate(j) {
  try {
    await jobsApi.regenerate(j.id, null, null)
    store.toast(`Đã đưa ${j.externalId} về hàng đợi để tạo lại`, 'success')
    compare.value = null
    await load()
    store.refresh()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

let timer
onMounted(() => {
  load()
  timer = setInterval(load, 10000)
})
onUnmounted(() => clearInterval(timer))
</script>
