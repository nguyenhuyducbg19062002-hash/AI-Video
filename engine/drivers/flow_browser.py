"""
Google Flow qua Playwright + Microsoft Edge (profile riêng của từng tài khoản).

BACKGROUND: worker tự chọn chế độ, upload ảnh reference, điền prompt, bấm tạo, chờ kết quả và tải về staging.
FOREGROUND: mở Edge có giao diện, cố gắng điền sẵn prompt + ảnh (không bấm tạo); người dùng tự thao tác trên Flow.
            Khi người dùng bấm Download trên Flow, hook context.on("download") lưu file vào staging với tên = ID.

Selector của Flow nằm trong engine/selectors.json, đọc lại mỗi job: Flow đổi giao diện thì sửa file đó, không cần build lại.
"""
import json
import logging
import time
from contextlib import contextmanager
from pathlib import Path

from playwright.sync_api import Error as PlaywrightError
from playwright.sync_api import TimeoutError as PlaywrightTimeout
from playwright.sync_api import expect, sync_playwright

from .errors import AuthRequired, Cancelled, ConfigError, GenerationResult, QuotaExceeded, Rejected, TransientError

FLOW_URL = "https://labs.google/fx/tools/flow"
log = logging.getLogger("flow")

# Khóa bắt buộc phải cấu hình cho chế độ BACKGROUND (FOREGROUND chỉ cố gắng, thiếu thì người dùng tự làm tay)
BACKGROUND_KEYS = ["prompt_box", "add_reference_button", "reference_thumbnail", "generate_button",
                   "result_card", "result_ready_marker", "download_button"]


def parse_proxy(p: str | None):
    if not p or not p.strip():
        return None
    p = p.strip()
    parts = p.split(":")
    if len(parts) == 4 and "://" not in p:  # ip:port:user:pass
        ip, port, user, pwd = parts
        return {"server": f"http://{ip}:{port}", "username": user, "password": pwd}
    return {"server": p if "://" in p else f"http://{p}"}


def _is_todo(v) -> bool:
    return v is None or (isinstance(v, str) and (not v.strip() or v.startswith("TODO")))


class FlowBrowserDriver:
    def __init__(self, selectors_file: Path):
        self.selectors_file = selectors_file

    def _sel(self, required: list[str]) -> dict:
        try:
            s = json.loads(self.selectors_file.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            raise ConfigError(f"Không đọc được {self.selectors_file}: {e}") from e
        missing = [k for k in required if _is_todo(s.get(k))]
        if missing:
            raise ConfigError(f"selectors.json chưa cấu hình cho chế độ chạy tự động: {', '.join(missing)}. "
                              "Ghi lại bằng: python -m playwright codegen --channel msedge " + FLOW_URL)
        return s

    @contextmanager
    def _browser(self, account: dict, headless: bool):
        if not account.get("profileDir"):
            raise ConfigError(f"Tài khoản {account.get('email')} chưa có thư mục profile")
        with sync_playwright() as pw:
            try:
                ctx = pw.chromium.launch_persistent_context(
                    user_data_dir=account["profileDir"],
                    channel="msedge",            # Edge có sẵn trên Windows: không phải đóng gói Chromium
                    headless=headless,
                    proxy=parse_proxy(account.get("proxy")),
                    accept_downloads=True,
                    viewport=None,
                    args=["--start-maximized"],
                )
            except PlaywrightError as e:
                msg = str(e)
                if "ProcessSingleton" in msg or "user data directory is already in use" in msg.lower():
                    raise TransientError(f"Profile của {account['email']} đang mở ở cửa sổ Edge khác; hãy đóng cửa sổ đó") from e
                raise TransientError(f"Không mở được Edge: {msg[:500]}") from e
            try:
                yield ctx
            finally:
                try:
                    ctx.close()
                except PlaywrightError:
                    pass

    def _open_flow(self, ctx, job: dict):
        page = ctx.pages[0] if ctx.pages else ctx.new_page()
        page.set_default_timeout(45_000)
        try:
            page.goto(job.get("flowProjectUrl") or FLOW_URL, wait_until="domcontentloaded")
            page.wait_for_load_state("networkidle", timeout=20_000)
        except PlaywrightTimeout:
            pass  # trang Flow giữ kết nối mở lâu; domcontentloaded là đủ để tiếp tục
        if "accounts.google.com" in page.url:
            raise AuthRequired(f"{job['account']['email']}: chưa đăng nhập hoặc phiên đã hết hạn")
        return page

    @staticmethod
    def _select_mode(page, s: dict, kind: str):
        sel = (s.get("mode_buttons") or {}).get(kind)
        if not _is_todo(sel):
            page.locator(sel).first.click()

    @staticmethod
    def _upload_refs(page, s: dict, images: list[str]):
        thumbs = page.locator(s["reference_thumbnail"])
        start = thumbs.count()
        for i, img in enumerate(images, start=1):
            if not Path(img).is_file():
                raise Rejected(f"Thiếu file ảnh reference: {img}")
            with page.expect_file_chooser() as fc:
                page.locator(s["add_reference_button"]).first.click()
            fc.value.set_files(img)
            expect(thumbs).to_have_count(start + i, timeout=90_000)  # chờ upload xong từng ảnh, giữ đúng thứ tự

    @staticmethod
    def _fill_prompt(page, s: dict, prompt: str):
        box = page.locator(s["prompt_box"]).first
        box.click()
        box.fill(prompt)

    @staticmethod
    def _raise_if_error(page, s: dict):
        for text in s.get("quota_texts") or []:
            if not _is_todo(text) and page.get_by_text(text, exact=False).first.is_visible():
                raise QuotaExceeded(f"Flow báo: {text}")
        for text in s.get("policy_texts") or []:
            if not _is_todo(text) and page.get_by_text(text, exact=False).first.is_visible():
                raise Rejected(f"Flow từ chối: {text}")

    def _wait_result(self, page, s: dict, before: int, should_stop, timeout_s: int):
        """Giả định: thẻ kết quả MỚI NHẤT đứng đầu danh sách (kiểm lại trên giao diện thật)."""
        cards = page.locator(s["result_card"])
        deadline = time.monotonic() + timeout_s
        last_log = 0.0
        while time.monotonic() < deadline:
            if should_stop():
                raise Cancelled("Người dùng hủy job")
            self._raise_if_error(page, s)
            if cards.count() > before and cards.first.locator(s["result_ready_marker"]).count() > 0:
                return cards.first
            if time.monotonic() - last_log > 60:
                log.info("Đang chờ Flow render...")
                last_log = time.monotonic()
            page.wait_for_timeout(5_000)
        raise TransientError(f"Quá {timeout_s}s chưa có kết quả")

    @staticmethod
    def _download(page, card, s: dict, staging: Path, stem: str, kind: str) -> Path:
        card.hover()
        with page.expect_download(timeout=180_000) as dl:
            card.locator(s["download_button"]).first.click()
            if not _is_todo(s.get("download_quality_option")):
                page.locator(s["download_quality_option"]).first.click()
        d = dl.value
        suffix = Path(d.suggested_filename).suffix or (".mp4" if kind == "VIDEO" else ".png")
        target = staging / f"{stem}{suffix}"
        d.save_as(target)
        return target

    # ---------- BACKGROUND: worker tự làm hết ----------
    def generate(self, job: dict, should_stop) -> GenerationResult:
        s = self._sel(BACKGROUND_KEYS)
        staging = Path(job["stagingDir"])
        staging.mkdir(parents=True, exist_ok=True)
        with self._browser(job["account"], headless=job.get("headless", False)) as ctx:
            try:
                page = self._open_flow(ctx, job)
                self._select_mode(page, s, job["kind"])
                before = page.locator(s["result_card"]).count()
                self._upload_refs(page, s, job["referenceImages"])
                self._fill_prompt(page, s, job["prompt"])
                page.locator(s["generate_button"]).first.click()
                card = self._wait_result(page, s, before, should_stop, job["timeoutSeconds"])
                staged = self._download(page, card, s, staging, job["externalId"], job["kind"])
                return GenerationResult(staged_file=staged, remote_ref=page.url)
            except (PlaywrightTimeout, AssertionError) as e:  # AssertionError: expect(...) hết giờ
                raise TransientError(f"Hết thời gian chờ một phần tử trên Flow: {str(e)[:500]}") from e

    # ---------- FOREGROUND: điền sẵn, người dùng tự bấm; hook bắt file tải về ----------
    def generate_foreground(self, job: dict, should_stop) -> GenerationResult:
        s = self._sel([])
        staging = Path(job["stagingDir"])
        staging.mkdir(parents=True, exist_ok=True)
        captured: list[Path] = []

        def on_download(d):
            suffix = Path(d.suggested_filename).suffix or (".mp4" if job["kind"] == "VIDEO" else ".png")
            target = staging / f"{job['externalId']}{suffix}"
            d.save_as(target)
            captured.append(target)
            log.info("Bắt được file người dùng tải: %s", target)

        with self._browser(job["account"], headless=False) as ctx:
            ctx.on("download", on_download)  # mọi lần bấm Download trên Flow trong cửa sổ này
            page = self._open_flow(ctx, job)
            self._prefill_best_effort(page, s, job)
            try:
                while not captured:
                    if should_stop():
                        raise Cancelled("Người dùng hủy job")
                    if not ctx.pages:
                        raise Cancelled("Người dùng đã đóng trình duyệt trước khi tải kết quả")
                    ctx.pages[0].wait_for_timeout(1_000)  # để Playwright xử lý sự kiện download trong lúc chờ
            except PlaywrightError as e:
                raise Cancelled(f"Trình duyệt đã đóng trước khi có file: {str(e)[:300]}") from e
            return GenerationResult(staged_file=captured[-1], remote_ref=page.url if not page.is_closed() else None)

    def _prefill_best_effort(self, page, s: dict, job: dict):
        """Điền sẵn những gì selector cho phép; phần nào lỗi thì người dùng tự làm (prompt hiển thị trên trang Duyệt)."""
        steps = [
            ("chọn chế độ", lambda: self._select_mode(page, s, job["kind"])),
            ("upload ảnh reference", lambda: self._upload_refs(page, s, job["referenceImages"])
                if job["referenceImages"] and not _is_todo(s.get("add_reference_button"))
                and not _is_todo(s.get("reference_thumbnail")) else None),
            ("điền prompt", lambda: self._fill_prompt(page, s, job["prompt"])
                if not _is_todo(s.get("prompt_box")) else None),
        ]
        for name, step in steps:
            try:
                step()
            except (PlaywrightError, AssertionError, Rejected) as e:
                log.warning("FOREGROUND: không tự %s được, người dùng làm tay: %s", name, str(e)[:200])
