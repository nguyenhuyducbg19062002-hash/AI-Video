"""
Phiên Flow chạy SONG SONG nhiều lệnh trên một tài khoản (một lần mở Edge): trong lúc lệnh A còn đang tạo thì gửi tiếp B, C…;
lệnh nào xong trước thì tải về và báo ngay (chuyển "Chờ duyệt"), không đợi các lệnh khác. Xem docs/SONG-SONG.md.

File này chỉ chứa LOGIC (không dính Playwright) để test được bằng một trang Flow giả. FlowBrowserDriver.run_session dựng
FlowOps thật (đọc lưới ô, gửi lệnh, tải về) rồi giao cho SessionRunner.

Cách gắn kết quả với đúng lệnh (đã kiểm chứng trên Flow thật): dấu gắn lên ô KHÔNG bền (Flow vẽ lại ô), nhưng thứ tự ô thì ổn
định — ô mới nhất ở đầu danh sách. Lúc bắt đầu phiên có n0 ô do Flow tạo; lệnh gửi thứ k (rank = k) luôn là ô thứ
(tổng − n0 − 1 − k) trong danh sách. Mỗi lần gửi, FlowOps.submit xác nhận số ô tăng đúng 1; nếu số ô không còn khớp
n0 + số lệnh đã gửi (ai đó tạo / xóa ô bằng tay) thì phiên LỆCH PHA: ngừng, các lệnh chưa xong báo lỗi tạm thời để thử lại.
"""
import logging
import time
from dataclasses import dataclass
from typing import Callable, Optional, Protocol

from .errors import (AccountBroken, AuthRequired, Cancelled, ConfigError, GenerationResult, PausedByUser, QuotaExceeded,
                     Rejected, TransientError, WorkerError)

log = logging.getLogger("flow.session")


@dataclass
class Tile:
    key: Optional[str]    # media id (ảnh) / khóa băm ảnh thu nhỏ (video); None khi chưa xong
    label: str
    pct: Optional[int]    # % đang tạo; None khi không còn chạy
    ready: bool           # đã xong, tải về được


class FlowOps(Protocol):
    def generated_tiles(self) -> list[Tile]:
        """Các ô do Flow TẠO RA (bỏ ô ảnh người dùng tải lên), mới nhất ở đầu."""

    def submit(self, job: dict) -> None:
        """Tải ảnh tham chiếu còn thiếu, gõ prompt, bấm Tạo; chỉ trả về khi số ô đã tăng đúng 1. Ném WorkerError nếu thất bại."""

    def download(self, job: dict, tile: Tile) -> GenerationResult:
        """Tải kết quả của ô về thư mục staging của job."""

    def page_error(self) -> Optional[tuple]:
        """("quota" | "policy", chữ đang hiện) nếu trang đang báo hết lượt / vi phạm chính sách, ngược lại None."""

    def ensure_grid(self) -> None:
        """Đang ở màn chỉnh sửa (Flow tự mở sau khi tạo video) thì quay lại lưới ô của dự án."""


@dataclass
class Slot:
    job: dict
    rank: int
    submitted_at: float


# Lỗi làm cả phiên vô dụng (tài khoản hỏng / cấu hình sai): báo đúng lỗi đó cho MỌI lệnh đang chạy để backend xử lý từng job
_FATAL = (AuthRequired, AccountBroken, ConfigError, PausedByUser)


class SessionRunner:
    def __init__(self, ops: FlowOps, capacity: int, claim_more: Callable[[], Optional[dict]],
                 on_start: Callable[[dict], None], on_done: Callable[[dict, GenerationResult], None],
                 on_fail: Callable[[dict, WorkerError], None], stop_of: Callable[[dict], Callable[[], bool]],
                 poll_s: float = 1.5, gap_s: float = 2.0, claim_every_s: float = 3.0,
                 desync_polls: int = 3, clock=time.monotonic, sleep=time.sleep):
        self.ops, self.capacity = ops, max(1, capacity)
        self.claim_more, self.on_start, self.on_done, self.on_fail, self.stop_of = claim_more, on_start, on_done, on_fail, stop_of
        self.poll_s, self.gap_s, self.claim_every_s, self.desync_polls = poll_s, gap_s, claim_every_s, desync_polls
        self.clock, self.sleep = clock, sleep
        self.slots: list[Slot] = []
        self.submitted = 0
        self.n0 = 0
        self._claiming = True
        self._next_claim_at = 0.0
        self._mismatch = 0
        self._handled_error: Optional[tuple] = None
        self._pending: list = []

    # ---------- vòng chính ----------
    def run(self, first_job: dict) -> None:
        self._pending = [first_job]   # đã nhận từ backend nhưng chưa gửi lên Flow
        try:
            self.n0 = len(self.ops.generated_tiles())
            while True:
                self._fill(self._pending)
                if not self.slots:
                    break
                if not self._poll():
                    break
                self.sleep(self.poll_s)
        except Exception as e:   # trình duyệt chết / lỗi không lường trước: không để job nào treo ở GENERATING
            log.exception("Phiên Flow lỗi")
            self._fail_all(e if isinstance(e, WorkerError) else TransientError(f"{type(e).__name__}: {e}"[:500]))
        # còn job nhận rồi mà chưa gửi được (lệch pha, lỗi nặng…): trả lại cho backend, không để treo
        while self._pending:
            self._finish(self._pending.pop(0), TransientError("Phiên Flow kết thúc trước khi gửi lệnh này"))

    # ---------- gửi lệnh ----------
    def _fill(self, pending: list) -> None:
        while self._claiming and len(self.slots) < self.capacity:
            job = None
            if pending:
                job = pending.pop(0)
            elif self.clock() >= self._next_claim_at:
                job = self.claim_more()
                if job is None:
                    self._next_claim_at = self.clock() + self.claim_every_s
                    return
            else:
                return
            self.on_start(job)
            try:
                self.ops.submit(job)
            except QuotaExceeded as e:
                self._claiming = False
                self._finish(job, e)
                return
            except _FATAL as e:
                self._claiming = False
                self._finish(job, e)
                self._fail_all(e)
                return
            except WorkerError as e:   # prompt bị từ chối, lỗi tạm thời…: chỉ riêng lệnh này
                self._finish(job, e)
                continue
            except Exception as e:
                self._finish(job, TransientError(f"{type(e).__name__}: {e}"[:500]))
                continue
            self.slots.append(Slot(job, self.submitted, self.clock()))
            self.submitted += 1
            self._handled_error = None
            if self.gap_s and len(self.slots) < self.capacity:
                self.sleep(self.gap_s)   # giãn nhịp giữa các lần gửi

    # ---------- theo dõi kết quả ----------
    def _poll(self) -> bool:
        """False = phiên phải dừng (lệch pha)."""
        self.ops.ensure_grid()
        tiles = self.ops.generated_tiles()
        expected = self.n0 + self.submitted
        if len(tiles) != expected:
            self._mismatch += 1
            if self._mismatch >= self.desync_polls:
                log.warning("Phiên Flow lệch pha: %d ô, dự kiến %d", len(tiles), expected)
                self._claiming = False
                self._fail_all(TransientError(
                    f"Lưới Flow lệch so với phiên ({len(tiles)} ô, dự kiến {expected}): có ai tạo / xóa ô bằng tay? Sẽ thử lại"))
                return False
            return True
        self._mismatch = 0

        for slot in list(self.slots):
            job = slot.job
            if self.stop_of(job)():
                self._finish(job, Cancelled("Người dùng hủy job"), slot)
                continue
            tile = tiles[len(tiles) - self.n0 - 1 - slot.rank]
            if tile.ready:
                try:
                    result = self.ops.download(job, tile)
                except WorkerError as e:
                    self._finish(job, e, slot)
                except Exception as e:
                    self._finish(job, TransientError(f"{type(e).__name__}: {e}"[:500]), slot)
                else:
                    self._finish(job, None, slot, result)
                continue
            limit = job.get("timeoutSeconds") or 900
            if self.clock() - slot.submitted_at > limit:
                self._finish(job, TransientError(f"Quá {limit}s chưa có kết quả"), slot)

        self._check_page_error()
        return True

    def _check_page_error(self) -> None:
        err = self.ops.page_error()
        if err is None:
            self._handled_error = None
            return
        if err == self._handled_error or not self.slots:
            return
        self._handled_error = err
        kind, text = err
        slot = max(self.slots, key=lambda s: s.rank)   # thông báo hiện ngay sau lần gửi gần nhất
        if kind == "quota":
            self._claiming = False
            self._finish(slot.job, QuotaExceeded(f"Flow báo: {text}"), slot)
        else:
            self._finish(slot.job, Rejected(f"Flow từ chối: {text}"), slot)

    # ---------- báo kết quả ----------
    def _finish(self, job: dict, err: Optional[WorkerError], slot: Optional[Slot] = None, result=None) -> None:
        if slot is not None and slot in self.slots:
            self.slots.remove(slot)
        if err is None:
            self.on_done(job, result)
        else:
            self.on_fail(job, err)

    def _fail_all(self, err: WorkerError) -> None:
        for slot in list(self.slots):
            self._finish(slot.job, err, slot)
