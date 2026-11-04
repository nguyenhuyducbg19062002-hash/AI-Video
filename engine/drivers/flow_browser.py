"""
Google Flow qua Playwright + Microsoft Edge (profile riêng của từng tài khoản).

Flow 11/2026 (đã chạy thật): trong một dự án, ô prompt là ô chat của "Tác nhân". Ảnh reference là "thành phần" của
dự án (giữ nguyên tên file, ví dụ CHAR-CALEB-1.png); gõ "@" + ID rồi bấm đúng tên file → chèn chip mention.
Tác nhân đôi khi lập kế hoạch rồi hỏi "Would you like me to proceed…?": driver tự trả lời đồng ý (agent_auto_reply).

BACKGROUND: worker tự upload ảnh còn thiếu, gõ prompt + chip @tag, bấm tạo, chờ ô kết quả mới, tải về staging.
FOREGROUND: mở Edge có giao diện, tự upload ảnh + gõ prompt có chip @tag (không bấm tạo); người dùng kiểm tra,
            bấm Tạo rồi Tải xuống.
            Khi người dùng bấm Download trên Flow, hook context.on("download") lưu file vào staging với tên = ID.

Selector của Flow nằm trong engine/selectors.json, đọc lại mỗi job: Flow đổi giao diện thì sửa file đó, không cần build lại.
"""
import json
import logging
import os
import re
import time
import unicodedata
from contextlib import contextmanager
from pathlib import Path
from urllib.parse import unquote, urlparse

from playwright.sync_api import Error as PlaywrightError
from playwright.sync_api import TimeoutError as PlaywrightTimeout
from playwright.sync_api import sync_playwright

from .errors import (AccountBroken, AuthRequired, Cancelled, ConfigError, GenerationResult, PausedByUser, QuotaExceeded,
                     Rejected, TransientError, proxy_error)
from .helper_overlay import open_in_explorer, overlay_script, prepare_refs_folder

FLOW_URL = "https://flow.google.com/"
VIDEO_QUALITIES = ["1080p", "720p", "480p", "360p"]   # từ cao xuống thấp
DEFAULT_VIDEO_QUALITY = "720p"   # phim không đặt chất lượng thì dùng 720p; người dùng đổi ở phần Phim
log = logging.getLogger("flow")

# Khóa bắt buộc phải cấu hình cho chế độ BACKGROUND (FOREGROUND chỉ cố gắng, thiếu thì người dùng tự làm tay)
BACKGROUND_KEYS = ["prompt_box", "add_reference_button", "upload_button", "ingredient_listbox", "generate_button",
                   "result_card", "tile_menu_button", "download_menu_item"]


def parse_proxy(p: str | None):
    if not p or not p.strip():
        return None
    p = p.strip()
    parts = p.split(":")
    if len(parts) == 4 and "://" not in p:  # ip:port:user:pass
        ip, port, user, pwd = parts
        return {"server": f"http://{ip}:{port}", "username": user, "password": pwd}
    u = urlparse(p if "://" in p else f"http://{p}")
    try:
        port = u.port
    except ValueError:
        port = None
    if not u.hostname:
        return {"server": p if "://" in p else f"http://{p}"}
    cfg = {"server": f"{u.scheme}://{u.hostname}" + (f":{port}" if port else "")}
    if u.username:
        cfg["username"] = unquote(u.username)
        cfg["password"] = unquote(u.password or "")
    return cfg


def proxy_label(p: str | None) -> str:
    """Chỉ host:port của proxy để ghi log / báo lỗi (không bao giờ có user:pass)."""
    cfg = parse_proxy(p)
    return cfg["server"].split("://", 1)[-1] if cfg else ""


_PROJECT_RE = re.compile(r"^(https://flow\.google\.com/project/[0-9a-fA-F-]{36})")
_EDIT_RE = re.compile(r"/project/[0-9a-fA-F-]{36}/edit/([0-9a-fA-F-]{36})")


def project_url(url: str | None) -> str:
    """https://flow.google.com/project/<uuid>/edit/<media>?x=1 -> https://flow.google.com/project/<uuid>"""
    m = _PROJECT_RE.match(url or "")
    return m.group(1) if m else (url or "").split("?")[0]


def edit_media_id(url: str | None) -> str | None:
    """Flow tự mở màn chỉnh sửa video vừa tạo: /project/<uuid>/edit/<media id>"""
    m = _EDIT_RE.search(url or "")
    return m.group(1) if m else None


def _status(page, text: str):
    """Dòng trạng thái trên hộp hướng dẫn (FOREGROUND): người dùng biết app đang làm gì, đừng đóng cửa sổ giữa chừng."""
    try:
        page.evaluate("t => { window.__afmLastStatus = t; if (window.__afmStatus) window.__afmStatus(t); }", text)
    except PlaywrightError:
        pass


class StepTimer:
    """Đo thời gian từng bước của một job để biết chỗ nào chậm. Cuối job ghi một dòng log: `[thời gian] tên=giây …`."""

    def __init__(self):
        self.t0 = time.monotonic()
        self.steps: list[tuple[str, float]] = []

    @contextmanager
    def step(self, name: str):
        t = time.monotonic()
        try:
            yield
        finally:
            self.steps.append((name, time.monotonic() - t))

    def summary(self) -> str:
        parts = " ".join(f"{n}={d:.1f}s" for n, d in self.steps)
        return f"[{time.monotonic() - self.t0:.1f}s] {parts}"


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
        cfg = parse_proxy(account.get("proxy"))
        if cfg and cfg["server"].startswith("socks5") and cfg.get("username"):
            raise AccountBroken(f"Proxy \"{proxy_label(account.get('proxy'))}\": Chromium không hỗ trợ socks5 có user/pass. "
                                "Đổi sang proxy http hoặc socks5 không mật khẩu")
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
                    chromium_sandbox=True,   # không thêm cờ --no-sandbox (Edge hiện cảnh báo "unsupported command-line flag")
                )
            except PlaywrightError as e:
                msg = str(e)
                if "ProcessSingleton" in msg or "user data directory is already in use" in msg.lower():
                    # thường là cửa sổ đăng nhập của tài khoản còn mở: không tính lượt thử, đưa tài khoản về "Cần đăng nhập"
                    raise AuthRequired(f"Profile của {account['email']} đang mở ở một cửa sổ Edge khác (cửa sổ đăng nhập?). "
                                       "Đóng cửa sổ đó rồi bấm \"Đã đăng nhập xong\"") from e
                if proxy_error(msg):
                    raise AccountBroken(f"Proxy \"{proxy_label(account.get('proxy'))}\" không dùng được: {proxy_error(msg)}") from e
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
            # Flow giữ kết nối mở nên "networkidle" luôn chạy hết giờ: chỉ chờ domcontentloaded, ô prompt được chờ riêng sau đó
            page.goto(project_url(job.get("flowProjectUrl")) or FLOW_URL, wait_until="domcontentloaded")
        except PlaywrightTimeout:
            pass
        except PlaywrightError as e:
            code = proxy_error(str(e))
            if code:
                # proxy của tài khoản sai / chết: thử lại trên tài khoản này vô ích → backend tắt tài khoản, xoay tài khoản khác
                raise AccountBroken(f"Proxy \"{proxy_label(job['account'].get('proxy'))}\" không kết nối được ({code}). "
                                    "Sửa hoặc xóa proxy của tài khoản") from e
            raise TransientError(f"Không mở được trang Flow: {str(e)[:300]}") from e
        if "accounts.google.com" in page.url:
            raise AuthRequired(f"{job['account']['email']}: chưa đăng nhập hoặc phiên đã hết hạn")
        return page

    # ---------- các bước trên trang dự án Flow ----------
    @staticmethod
    def _select_mode(page, s: dict, kind: str):
        sel = (s.get("mode_buttons") or {}).get(kind)
        if not _is_todo(sel):
            page.locator(sel).first.click()

    @staticmethod
    def _select_duration(page, s: dict, job: dict):
        """Chọn độ dài video trên Flow (cài đặt phim). Selector "duration_option" có chỗ {seconds}."""
        seconds = job.get("durationSeconds")
        if job["kind"] != "VIDEO" or not seconds:
            return
        option = s.get("duration_option")
        if _is_todo(option):
            log.warning("Chưa cấu hình duration_option trong selectors.json: giữ độ dài mặc định của Flow")
            return
        if not _is_todo(s.get("duration_menu")):
            page.locator(s["duration_menu"]).first.click()
        page.locator(option.replace("{seconds}", str(int(seconds)))).first.click()

    @staticmethod
    def _chip_matches(btn, job: dict) -> bool:
        """
        Flow nhớ cài đặt theo tài khoản và hiện tóm tắt trên chip ("Video · 720p · 8s ▭ x1"). Chip đã có đủ chế độ video,
        độ phân giải, độ dài (nếu phim đặt) và x1 thì khỏi mở popover. Không đọc được chip hoặc thiếu mục nào → đặt như thường.
        """
        try:
            text = btn.last.inner_text(timeout=2_000).lower()
        except PlaywrightError:
            return False
        tokens = set(re.split(r"[\s·]+", text))
        need = ["video", str(job.get("videoQuality") or DEFAULT_VIDEO_QUALITY).lower(), "x1"]
        if job.get("durationSeconds"):
            need.append(f"{int(job['durationSeconds'])}s")
        return all(n in tokens for n in need)

    @staticmethod
    def _apply_settings(page, s: dict, kind: str, job: dict | None = None):
        """
        Bảng cài đặt cạnh ô prompt. Flow có 2 bố cục:
        - Tác nhân (bảng chat bên phải): "Xác nhận trước khi tạo" → "Không bao giờ" (Tác nhân khỏi hỏi lại), số bản tạo
          ảnh / video = x1, rồi bấm Lưu.
        - Tạo trực tiếp (chip "Nano Banana 2.1 · 16:9 · x2"): chọn Image / Video, x1; bảng tự áp dụng, Esc để đóng.
        Mọi bước là "cố gắng": không có nút nào thì bỏ qua, không chờ lâu.
        """
        btn = page.locator(s["settings_button"])
        try:
            btn.first.wait_for(state="attached", timeout=8_000)
        except PlaywrightError:
            pass
        if btn.count() == 0:
            log.warning("Không thấy nút cài đặt cạnh ô prompt: giữ cài đặt hiện tại của Flow")
            return
        if kind == "VIDEO" and job and FlowBrowserDriver._chip_matches(btn, job):
            log.info("Cài đặt Flow đã đúng (video, %s, x1): bỏ qua bước mở popover", job.get("videoQuality") or DEFAULT_VIDEO_QUALITY)
            return
        try:
            btn.last.click(timeout=5_000)

            def radio(pattern: str):
                return page.get_by_role("radio", name=re.compile(pattern, re.I))

            try:
                page.get_by_role("radio").first.wait_for(state="visible", timeout=3_000)   # popover mở xong
            except PlaywrightTimeout:
                pass
            page.wait_for_timeout(200)

            never = radio(s.get("agent_never_confirm") or r"^\s*(Không bao giờ|Never)(\s|$)")
            if never.count():
                never.first.click(timeout=5_000)
            mode = radio(r"^\W*(image\s+)?(Hình ảnh|Image)\s*$" if kind == "IMAGE" else r"^\W*(videocam\s+)?Video\s*$")
            if mode.count():
                mode.first.click(timeout=5_000)
                page.wait_for_timeout(300)
            if kind == "VIDEO" and job:
                # Popover bố cục tạo trực tiếp (xác nhận 2026-11-04): nhóm radio độ phân giải (360p/720p) và độ dài (4s/6s/8s/10s).
                # Giá trị Flow không có thì giữ nguyên lựa chọn hiện tại.
                wanted = []
                quality = job.get("videoQuality") or DEFAULT_VIDEO_QUALITY
                wanted.append(("độ phân giải", rf"^\s*{re.escape(str(quality))}\s*$"))
                if job.get("durationSeconds"):
                    wanted.append(("độ dài", rf"^\s*{int(job['durationSeconds'])}\s*s\s*$"))
                for label, pattern in wanted:
                    opt = radio(pattern)
                    if opt.count():
                        opt.first.click(timeout=5_000)
                        page.wait_for_timeout(300)
                    else:
                        log.warning("Flow không có lựa chọn %s %s: giữ mặc định", label, pattern)
            x1 = radio(r"^\s*x1\s*$")
            if x1.count():
                # bố cục Tác nhân có 2 nhóm x1: nhóm đầu cho ảnh, nhóm sau cho video
                (x1.nth(1) if kind == "VIDEO" and x1.count() > 1 else x1.first).click(timeout=5_000)
            page.wait_for_timeout(300)
            save = page.locator(s.get("settings_save") or "button:has-text('Lưu'), button:has-text('Save')")
            if save.count() and save.first.is_visible():
                save.first.click(timeout=5_000)
            else:
                page.keyboard.press("Escape")
            page.wait_for_timeout(600)
        except PlaywrightError as e:
            log.warning("Không đặt được cài đặt Flow (x1 / chế độ): %s", str(e)[:200])
            page.keyboard.press("Escape")

    @staticmethod
    def _listbox_text(page, s: dict) -> str:
        lb = page.locator(s["ingredient_listbox"])
        return lb.first.inner_text() if lb.count() else ""

    @staticmethod
    def _nfc(text: str) -> str:
        return unicodedata.normalize("NFC", text).strip()

    def _listbox_names(self, page, s: dict) -> set[str]:
        lb = page.locator(s["ingredient_listbox"])
        return {self._nfc(x) for x in lb.first.locator("span").all_inner_texts()} if lb.count() else set()

    def _upload_refs(self, page, s: dict, images: list[str], should_stop=None) -> bool:
        """
        Tải lên dự án Flow những ảnh reference CHƯA có (so theo tên file; Flow giữ nguyên tên file khi tải lên).
        Nút "+" cạnh ô prompt mở bảng thành phần; "Tải nội dung nghe nhìn lên" mở hộp chọn file (chọn nhiều file một lần).
        """
        for img in images:
            if not Path(img).is_file():
                raise Rejected(f"Thiếu file ảnh reference: {img}")
        if not images:
            return False
        if should_stop and should_stop():
            raise Cancelled("Người dùng hủy job")
        page.locator(s["add_reference_button"]).first.click()
        try:
            page.locator(s["ingredient_listbox"]).first.wait_for(state="visible", timeout=4_000)
        except PlaywrightTimeout:
            pass
        page.wait_for_timeout(500)   # danh sách thành phần nạp nội dung chậm hơn khung một chút
        have = self._listbox_names(page, s)
        need = [img for img in images if self._nfc(Path(img).name) not in have]
        if need:
            log.info("Tải lên Flow %d ảnh: %s", len(need), ", ".join(Path(i).name for i in need))
            with page.expect_file_chooser() as fc:
                page.locator(s["upload_button"]).first.click()
            fc.value.set_files(need)
            uploading = s.get("uploading_texts") or ["Đang tải lên", "Uploading"]
            deadline = time.monotonic() + 180
            while True:
                if should_stop and should_stop():
                    raise Cancelled("Người dùng hủy job")
                page.wait_for_timeout(1_000)
                text = self._listbox_text(page, s)
                names = self._listbox_names(page, s)
                if not any(u in text for u in uploading) and all(self._nfc(Path(i).name) in names for i in need):
                    break
                if time.monotonic() > deadline:
                    raise TransientError("Quá 180s chưa tải xong ảnh reference lên Flow")
        else:
            log.info("Ảnh reference đã có sẵn trong dự án Flow, không tải lại")
        page.keyboard.press("Escape")
        page.wait_for_timeout(300)
        return bool(need)

    @staticmethod
    def _ref_pattern(ids: list[str]):
        return re.compile(r"@(" + "|".join(re.escape(i) for i in ids) + r")(?:\.(?:png|jpe?g|webp))?(?![A-Za-z0-9_-])")

    @classmethod
    def _needed_refs(cls, prompt: str, ref_ids: list[str]) -> set[str]:
        ids = sorted({i for i in ref_ids if i}, key=len, reverse=True)
        return set(cls._ref_pattern(ids).findall(prompt)) if ids else set()

    def _compose_prompt(self, page, s: dict, prompt: str, ref_ids: list[str]) -> set[str]:
        """
        Gõ prompt vào ô của Flow; mỗi "@ID" của ảnh reference thành chip mention: gõ "@", gõ ID, bấm đúng mục có tên
        file "ID.png/.jpg" (không bấm Enter: "CHAR-CALEB-1" sẽ khớp nhầm "CHAR-CALEB-10"). Trả về tập ID đã gắn chip.
        """
        box = page.locator(s["prompt_box"]).first
        try:
            box.click(timeout=5_000)
        except PlaywrightError:
            box.focus()   # bị lớp khác che (hộp hướng dẫn, popup): đặt con trỏ thẳng vào ô
        page.keyboard.press("Control+A")
        page.keyboard.press("Delete")
        ids = sorted({i for i in ref_ids if i}, key=len, reverse=True)
        if not ids:
            page.keyboard.insert_text(prompt)
            return set()
        pattern = self._ref_pattern(ids)
        option_sel = s.get("ingredient_option") or "button[role=option]"
        pos, attached = 0, set()
        for m in pattern.finditer(prompt):
            if m.start() > pos:
                page.keyboard.insert_text(prompt[pos:m.start()])
            ref = m.group(1)
            page.keyboard.type("@")
            page.wait_for_timeout(250)
            page.keyboard.insert_text(ref)
            opt = page.locator(", ".join(f"{lb.strip()} {option_sel}" for lb in s["ingredient_listbox"].split(","))).filter(
                has=page.locator("span", has_text=re.compile(r"^\s*" + re.escape(ref) + r"\.(png|jpe?g|webp)\s*$", re.I)))
            try:
                opt.first.wait_for(state="visible", timeout=8_000)
                opt.first.click()
                attached.add(ref)
                page.wait_for_timeout(150)
                self._caret_to_end(page, box)   # bố cục tạo trực tiếp: bấm chọn ảnh xong, con trỏ không về ô prompt
            except PlaywrightError:
                # không thấy ảnh trong dự án: bỏ ô tìm kiếm, để lại chữ @ID thường
                log.warning("Không thấy ảnh %s trong dự án Flow: để lại @%s dạng chữ", ref, ref)
                page.keyboard.press("Escape")
                page.wait_for_timeout(300)
                self._caret_to_end(page, box, force=True)
                page.keyboard.insert_text("@" + ref)
            page.wait_for_timeout(100)
            pos = m.end()
        if pos < len(prompt):
            page.keyboard.insert_text(prompt[pos:])
        log.info("Đã gõ prompt (%d ký tự), gắn chip @tag cho %d ảnh", len(prompt), len(attached))
        return attached

    @staticmethod
    def _caret_to_end(page, box, force: bool = False):
        """Đưa con trỏ về cuối ô prompt (nếu đang ở chỗ khác, ví dụ ô tìm kiếm ảnh)."""
        if force or not box.evaluate("b => b.contains(document.activeElement)"):
            box.focus()
            box.evaluate("""b => { const r = document.createRange(); r.selectNodeContents(b); r.collapse(false);
                const sel = window.getSelection(); sel.removeAllRanges(); sel.addRange(r); }""")
            page.keyboard.press("Control+End")

    @staticmethod
    def _error_counts(page, s: dict) -> dict:
        """Số chỗ đang HIỆN từng câu báo hết lượt / vi phạm trên trang (đếm trước khi bấm Tạo để bỏ qua chữ cũ)."""
        counts = {}
        for key in ("quota_texts", "policy_texts"):
            for text in s.get(key) or []:
                if _is_todo(text):
                    continue
                try:
                    counts[(key, text)] = page.get_by_text(text, exact=False).filter(visible=True).count()
                except PlaywrightError:
                    pass
        return counts

    @classmethod
    def _raise_if_error(cls, page, s: dict, baseline: dict):
        """Chỉ báo lỗi khi câu báo MỚI xuất hiện so với baseline (chữ cũ trong lịch sử chat không tính)."""
        now = cls._error_counts(page, s)
        for (key, text), n in now.items():
            if n > baseline.get((key, text), 0):
                if key == "quota_texts":
                    raise QuotaExceeded(f"Flow báo: {text}")
                raise Rejected(f"Flow từ chối: {text}")

    # Mỗi ô kết quả có một "khoá": ô ảnh có data-media-id; ô VIDEO của Flow KHÔNG có data-media-id (chỉ có
    # <flow-video-tile> + ảnh thu nhỏ "Generated video thumbnail") -> khoá = "v:" + băm địa chỉ ảnh thu nhỏ.
    # Khoá được gắn lên ô (data-afm-key) để tìm lại ô đó khi bấm tải về.
    _TILE_KEYS_JS = """
        const hash = (t) => { let h = 0; for (let i = 0; i < t.length; i++) { h = (h * 31 + t.charCodeAt(i)) | 0; } return (h >>> 0).toString(36); };
        const keyOf = (c) => {
            const m = c.querySelector('[data-media-id]');
            if (m) return m.getAttribute('data-media-id');
            const th = c.querySelector('flow-video-tile img, img.thumbnail');
            return th && th.getAttribute('src') ? 'v:' + hash(th.getAttribute('src')) : null;
        };
    """

    @staticmethod
    def _media_ids(page, s: dict, kind: str | None = None) -> list[str]:
        """
        Các ô KẾT QUẢ do Flow tạo trong lưới của dự án (khoá của từng ô). Bỏ qua ô ảnh người dùng tải lên
        (tên ô là tên file, ví dụ "CHAR-CALEB-1.png"). kind = "VIDEO": chỉ ô video; "IMAGE": chỉ ô ảnh.
        """
        return page.evaluate("""([card, kind]) => {""" + FlowBrowserDriver._TILE_KEYS_JS + """
            return [...document.querySelectorAll(card)].filter(c => {
                const label = (c.getAttribute('aria-label') || '').trim();
                if (/[.](png|jpe?g|webp|gif|bmp|mp4|mov|webm)$/i.test(label)) return false;   // ảnh / file tải lên
                const isVideo = !!c.querySelector('video, flow-video-tile');
                return kind === 'VIDEO' ? isVideo : kind === 'IMAGE' ? !isVideo : true;
            }).map(c => { const k = keyOf(c); if (k) c.setAttribute('data-afm-key', k); return k; }).filter(Boolean); }""",
                             [s["result_card"], kind])

    @staticmethod
    def _card(page, s: dict, key: str):
        """Ô kết quả theo khoá (gắn lại khoá trước khi tìm: lưới của Flow vẽ lại liên tục)."""
        FlowBrowserDriver._media_ids(page, s)
        return page.locator(f"{s['result_card']}[data-afm-key={json.dumps(key)}]").first

    @staticmethod
    def _media_ready(page, key: str) -> bool:
        """Ảnh / ảnh thu nhỏ của video đã hiện đủ."""
        return page.evaluate("""(key) => { const c = document.querySelector('[data-afm-key="' + CSS.escape(key) + '"]');
            if (!c) return false;
            const img = c.querySelector('img');
            return !!img && img.complete && img.naturalWidth > 0; }""", key)

    def _send(self, page, s: dict, text: str):
        box = page.locator(s["prompt_box"]).first
        box.click()
        page.keyboard.insert_text(text)
        page.wait_for_timeout(300)
        page.locator(s["generate_button"]).first.click()

    def _wait_result(self, page, s: dict, before: set, errors: dict, should_stop, timeout_s: int,
                     kind: str | None = None) -> str:
        """
        Chờ một ô kết quả MỚI (data-media-id chưa có trước khi bấm tạo) hiện đủ ảnh/video. Nếu Tác nhân trả lời xong mà
        chưa tạo (thường là hỏi xác nhận kế hoạch) thì tự gửi agent_auto_reply MỘT lần (không gửi nếu đã có ô mới).
        """
        # Ảnh: chỉ ô ảnh. Video: cấu trúc ô video của Flow chưa chắc chắn -> nhận mọi ô kết quả mới (không phải ảnh
        # tải lên), ưu tiên ô có video; sau khi tải về mới kiểm tra đúng là file video.
        want = "IMAGE" if kind == "IMAGE" else None
        reply_sel = s.get("agent_reply_marker")
        has_reply_sel = not _is_todo(reply_sel)
        replies = page.locator(reply_sel).count() if has_reply_sel else 0
        auto_reply = s.get("agent_auto_reply")
        nudges, idle_since = 0, None
        first_seen: dict[str, float] = {}
        logged = False
        started = time.monotonic()
        deadline = time.monotonic() + timeout_s
        last_log = 0.0
        while time.monotonic() < deadline:
            if should_stop():
                raise Cancelled("Người dùng hủy job")
            self._raise_if_error(page, s, errors)
            # Video xong: Flow tự chuyển sang /project/<id>/edit/<media id> (không còn lưới ô) -> media id lấy từ địa chỉ
            edit_id = edit_media_id(page.url)
            if edit_id and edit_id not in before:
                log.info("Flow mở màn chỉnh sửa kết quả mới: %s", edit_id)
                page.wait_for_timeout(1_500)
                return edit_id
            new = [m for m in self._media_ids(page, s, want) if m and m not in before]
            if kind == "VIDEO" and new:
                videos = [m for m in self._media_ids(page, s, "VIDEO") if m in new]
                new = videos or new
            any_new = new or [m for m in self._media_ids(page, s) if m and m not in before]
            for m in new:
                first_seen.setdefault(m, time.monotonic())
            if not logged and time.monotonic() - started > 180:
                logged = True
                self._log_tiles(page, s, before)   # chẩn đoán: chờ lâu mà chưa nhận kết quả
            if new:
                # video: thẻ <video> chỉ nạp file khi người dùng bấm xem -> ô mới đứng yên 30s, không còn % tiến độ = xong
                settled = (kind == "VIDEO" and time.monotonic() - first_seen[new[0]] > 30
                           and not self._tile_busy(page, s, new[0]))
                if settled or self._media_ready(page, new[0]):
                    page.wait_for_timeout(1_500 if kind == "VIDEO" else 1_000)
                    log.info("Có kết quả mới trên Flow: %s", new[0])
                    return new[0]
            elif not any_new and has_reply_sel and auto_reply and page.locator(reply_sel).count() > replies:
                # Tác nhân vừa trả lời xong một lượt mà chưa có ô kết quả mới
                idle_since = idle_since or time.monotonic()
                if time.monotonic() - idle_since > 8:
                    if nudges >= 1:
                        idle_since = None
                        replies = page.locator(reply_sel).count()
                        page.wait_for_timeout(4_000)
                        continue
                    log.info("Tác nhân Flow hỏi lại: tự trả lời \"%s\"", auto_reply)
                    self._send(page, s, auto_reply)
                    nudges += 1
                    replies, idle_since = page.locator(reply_sel).count(), None
            if time.monotonic() - last_log > 60:
                log.info("Đang chờ Flow tạo...")
                last_log = time.monotonic()
            page.wait_for_timeout(1_500)
        self._log_tiles(page, s, before)
        raise TransientError(f"Quá {timeout_s}s chưa có kết quả")

    @staticmethod
    def _tile_busy(page, s: dict, media_id: str) -> bool:
        """Ô đang tạo: có chữ phần trăm / thanh tiến độ."""
        try:
            return page.evaluate("""([card, id]) => { const c = document.querySelector('[data-afm-key="' + CSS.escape(id) + '"]');
                if (!c) return true;
                return /[0-9]+ *%/.test(c.innerText || '') || !!c.querySelector('[role=progressbar], mat-progress-spinner, mat-spinner'); }""",
                                 [s["result_card"], media_id])
        except PlaywrightError:
            return True

    @staticmethod
    def _log_tiles(page, s: dict, before: set):
        """Ghi cấu trúc các ô trong lưới vào log (để sửa selector khi Flow đổi giao diện)."""
        try:
            tiles = page.evaluate("""(card) => [...document.querySelectorAll(card)].map(c => {
                const m = c.querySelector('[data-media-id]');
                return {label: c.getAttribute('aria-label'), media: m && m.getAttribute('data-media-id'), mediaTag: m && m.tagName,
                        tags: [...new Set([...c.querySelectorAll('*')].map(e => e.tagName.toLowerCase()).filter(t => t.includes('-') || t === 'video'))].slice(0, 12)};
            })""", s["result_card"])
            for t in tiles:
                log.warning("Ô trên Flow: %s%s", t, "" if t.get("media") in before else "  <- MỚI")
        except PlaywrightError as e:
            log.warning("Không đọc được lưới Flow: %s", e)

    def _click_download(self, page, s: dict, media_id: str, kind: str, quality: str | None):
        """
        Ô kết quả → "Tuỳ chọn khác" → "Tải xuống" → chọn chất lượng (ảnh: 1K = kích thước gốc; video: 480p/720p/1080p).
        Đang ở màn chỉnh sửa (/edit/<media id>, Flow tự mở sau khi tạo video): nút tải ở thanh trên cùng.
        """
        q = quality if kind == "VIDEO" else (s.get("image_download_quality") or "1K")
        option_tpl = s.get("download_quality_option")
        if edit_media_id(page.url) == media_id:
            btn = page.locator(s.get("editor_download_button")
                               or "button:has(mat-icon:text-is('download')), [aria-label='Tải xuống'], [aria-label='Download']").first
            btn.wait_for(state="visible", timeout=15_000)
            btn.click()
            page.wait_for_timeout(1_000)
            item = None
        else:
            card = self._card(page, s, media_id)
            card.scroll_into_view_if_needed()
            card.hover()
            page.wait_for_timeout(500)
            card.locator(s["tile_menu_button"]).first.click()
            page.wait_for_timeout(800)
            item = page.locator(s["download_menu_item"]).first
            item.hover()
            page.wait_for_timeout(800)
        if _is_todo(option_tpl) or not q:
            if item is not None:
                item.click()  # không cấu hình menu chất lượng: bấm thẳng "Tải xuống"
            return   # ở màn chỉnh sửa: bấm nút tải là tải luôn
        levels = VIDEO_QUALITIES[VIDEO_QUALITIES.index(q):] if q in VIDEO_QUALITIES else [q]   # không có mức chọn thì thử mức thấp hơn
        for level in levels:
            option = page.locator(option_tpl.replace("{quality}", level)).first
            if option.count():
                if level != q:
                    log.warning("Menu tải về không có '%s': tải mức '%s'", q, level)
                option.click()
                return
        if item is None:
            return   # ở màn chỉnh sửa không có menu chất lượng
        first = page.locator("[role=menu]").last.locator("[role=menuitem]:not([disabled])").filter(has=page.locator(".label")).first
        if not first.count():
            raise TransientError(f"Menu tải về của Flow không có mục nào (muốn '{q}')")
        log.warning("Menu tải về không có '%s' hay mức thấp hơn: bấm mục đầu tiên của menu con", q)
        first.click()

    @staticmethod
    def _save_download(d, target: Path):
        """Ghi vào .part rồi đổi tên: không để lại file dở dang; file rỗng coi là lỗi."""
        part = target.with_name(target.name + ".part")
        try:
            d.save_as(part)
            if not part.is_file() or part.stat().st_size == 0:
                raise TransientError("File tải về từ Flow bị rỗng")
            os.replace(part, target)
        finally:
            part.unlink(missing_ok=True)

    def _download(self, page, s: dict, media_id: str, staging: Path, stem: str, kind: str, quality: str | None,
                  should_stop=None) -> Path:
        got: list = []
        on_dl = got.append
        page.on("download", on_dl)
        try:
            self._click_download(page, s, media_id, kind, quality)
            deadline = time.monotonic() + 180
            while not got:
                if should_stop and should_stop():
                    raise Cancelled("Người dùng hủy job")
                if time.monotonic() > deadline:
                    raise TransientError("Quá 180s Flow chưa bắt đầu tải file")
                page.wait_for_timeout(500)
        finally:
            page.remove_listener("download", on_dl)
        d = got[0]
        suffix = Path(d.suggested_filename).suffix or (".mp4" if kind == "VIDEO" else ".png")
        is_video_file = suffix.lower() in (".mp4", ".mov", ".webm")
        if (kind == "VIDEO") != is_video_file:
            raise TransientError(f"Flow trả về file {suffix} cho cảnh {'video' if kind == 'VIDEO' else 'ảnh'}: tạo lại")
        target = staging / f"{stem}{suffix.lower()}"
        self._save_download(d, target)
        return target

    # ---------- nhớ lần bấm Tạo dở dang (đóng cửa sổ / mất kết nối khi Flow đang tạo) ----------
    @staticmethod
    def _pending_file(job: dict) -> Path:
        # một file cho mỗi tài khoản: mỗi tài khoản chỉ chạy một cảnh một lúc, lần bấm Tạo mới nhất ghi đè lần trước
        return Path(job["stagingDir"]).parents[1] / f"flow-pending-acc{job['account'].get('id')}.json"

    def _save_pending(self, page, job: dict, before: set):
        try:
            self._pending_file(job).write_text(json.dumps({
                "jobId": job.get("jobId"), "revStaging": job["stagingDir"], "project": project_url(page.url),
                "before": sorted(before), "at": time.time()}), encoding="utf-8")
        except OSError as e:
            log.warning("Không ghi được file nhớ lần tạo: %s", e)

    def _clear_pending(self, job: dict):
        f = self._pending_file(job)
        try:
            if f.exists() and json.loads(f.read_text(encoding="utf-8")).get("revStaging") == job["stagingDir"]:
                f.unlink()
        except (OSError, ValueError):
            pass

    def _pending_result(self, page, s: dict, job: dict) -> str | None:
        """
        Lần trước của CHÍNH cảnh này (cùng revision prompt) đã bấm Tạo trên dự án này nhưng chưa kịp tải về, và từ đó
        tài khoản chưa tạo gì khác: ô kết quả mới trong dự án là của cảnh này -> tải về, không tạo lại (đỡ tốn lượt).
        """
        f = self._pending_file(job)
        try:
            d = json.loads(f.read_text(encoding="utf-8")) if f.exists() else None
        except (OSError, ValueError):
            return None
        if d and d.get("revStaging") != job["stagingDir"]:
            # tài khoản này đã sang cảnh khác: ô mới trong dự án có thể là của cảnh đó, bỏ dấu cũ cho an toàn
            try:
                f.unlink()
            except OSError:
                pass
            return None
        if (not d or d.get("project") != project_url(page.url)
                or time.time() - d.get("at", 0) > 24 * 3600):
            return None
        page.wait_for_timeout(2_000)   # chờ lưới ảnh của dự án hiện ra
        new = [m for m in self._media_ids(page, s, "IMAGE" if job["kind"] == "IMAGE" else None) if m and m not in set(d.get("before") or [])]
        if new and self._media_ready(page, new[0]):
            log.info("Cảnh %s: lần tạo trước đã có kết quả trên Flow (%s), tải về thay vì tạo lại", job["externalId"], new[0])
            return new[0]
        return None

    @staticmethod
    def _ref_ids(job: dict) -> list[str]:
        return [Path(p).stem for p in job.get("referenceImages") or []]

    @staticmethod
    def _raise_if_login(page, job: dict):
        """Phiên hết hạn phát hiện muộn: Flow chuyển sang trang đăng nhập Google sau khi mở dự án."""
        if "accounts.google.com" in page.url:
            raise AuthRequired(f"{job['account']['email']}: chưa đăng nhập hoặc phiên đã hết hạn")

    @staticmethod
    def _wait_prompt_box(page, s: dict):
        try:
            page.locator(s["prompt_box"]).first.wait_for(state="visible", timeout=30_000)
        except PlaywrightTimeout as e:
            raise TransientError("Không thấy ô prompt trong dự án Flow") from e

    # ---------- BACKGROUND: worker tự làm hết ----------
    def generate(self, job: dict, should_stop) -> GenerationResult:
        s = self._sel(BACKGROUND_KEYS)
        staging = Path(job["stagingDir"])
        staging.mkdir(parents=True, exist_ok=True)
        tm = StepTimer()
        try:
            t_launch = time.monotonic()
            with self._browser(job["account"], headless=job.get("headless", False)) as ctx:
                tm.steps.append(("mo_edge", time.monotonic() - t_launch))
                try:
                    with tm.step("vao_du_an"):
                        page = self._open_flow(ctx, job)
                        try:
                            self._ensure_project(page, job)
                            if "/project/" not in page.url:
                                raise TransientError("Chưa vào được dự án trên Flow")
                            self._wait_prompt_box(page, s)
                        except TransientError:
                            self._raise_if_login(page, job)
                            raise
                    if job.get("editInstruction") and job.get("editSourceUrl"):
                        return self._edit_on_flow(page, s, job, staging, should_stop)
                    with tm.step("cai_dat"):
                        self._apply_settings(page, s, job["kind"], job)
                        self._select_mode(page, s, job["kind"])
                    with tm.step("kiem_tra_lan_truoc"):
                        media_id = self._pending_result(page, s, job)
                    if not media_id:
                        with tm.step("upload_anh"):
                            uploaded = self._upload_refs(page, s, job["referenceImages"], should_stop)
                            # ô ảnh vừa tải lên hiện trong lưới chậm vài giây; không upload gì thì không cần chờ
                            page.wait_for_timeout(4_000 if uploaded else 300)
                        before = set(self._media_ids(page, s))
                        errors = self._error_counts(page, s)
                        with tm.step("go_prompt"):
                            attached = self._compose_prompt(page, s, job["prompt"], self._ref_ids(job))
                        need = self._needed_refs(job["prompt"], self._ref_ids(job))
                        if need - attached:
                            raise TransientError(f"Chỉ gắn được {len(attached & need)}/{len(need)} ảnh @tag vào prompt: không bấm Tạo")
                        if should_stop():
                            raise Cancelled("Người dùng hủy job")
                        self._save_pending(page, job, before)
                        page.locator(s["generate_button"]).first.click()
                        with tm.step("cho_google"):
                            media_id = self._wait_result(page, s, before, errors, should_stop, job["timeoutSeconds"], job["kind"])
                    with tm.step("tai_ve"):
                        staged = self._download(page, s, media_id, staging, job["externalId"], job["kind"],
                                                job.get("videoQuality") or DEFAULT_VIDEO_QUALITY, should_stop)
                    self._clear_pending(job)
                    with tm.step("lay_link_video"):
                        media_url = self._media_url(page, s, media_id) if job["kind"] == "VIDEO" else None
                    return GenerationResult(staged_file=staged, remote_ref=project_url(page.url), media_url=media_url)
                except (PlaywrightTimeout, AssertionError) as e:  # AssertionError: expect(...) hết giờ
                    raise TransientError(f"Hết thời gian chờ một phần tử trên Flow: {str(e)[:500]}") from e
        finally:
            log.info("Thời gian job %s %s: %s", job.get("kind"), job.get("externalId"), tm.summary())

    def _media_url(self, page, s: dict, media_id: str) -> str | None:
        """Địa chỉ màn chỉnh sửa của kết quả (…/project/<id>/edit/<media>): mở ô kết quả rồi đọc địa chỉ, quay lại lưới."""
        if edit_media_id(page.url):
            return page.url.split("?")[0]
        try:
            grid = page.url
            self._card(page, s, media_id)   # gắn lại khoá lên các ô
            opened = page.evaluate("""(key) => { const c = document.querySelector('[data-afm-key="' + CSS.escape(key) + '"]');
                if (!c) return false; const t = c.querySelector('img') || c; t.click(); return true; }""", media_id)
            if not opened:
                return None
            page.wait_for_url(re.compile(r"/edit/"), timeout=15_000)
            url = page.url.split("?")[0]
            page.goto(grid, wait_until="domcontentloaded")
            return url
        except PlaywrightError as e:
            log.warning("Không lấy được địa chỉ chỉnh sửa trên Flow: %s", str(e)[:200])
            return None

    def _edit_on_flow(self, page, s: dict, job: dict, staging: Path, should_stop) -> GenerationResult:
        """
        "Sửa trên Flow": mở màn chỉnh sửa của video cũ, gõ yêu cầu sửa vào ô "Mô tả cách chỉnh sửa video này",
        bấm tạo; bản sửa xuất hiện thành một ô video mới trong lưới của dự án -> tải về như video thường.
        """
        grid = project_url(page.url)
        page.wait_for_timeout(3_000)
        before = set(self._media_ids(page, s))
        errors = self._error_counts(page, s)
        page.goto(job["editSourceUrl"], wait_until="domcontentloaded")
        box = page.locator(s.get("edit_prompt_box") or "[role=textbox][contenteditable=true]").first
        try:
            box.wait_for(state="visible", timeout=30_000)
        except PlaywrightTimeout as e:
            raise TransientError("Không mở được màn chỉnh sửa video trên Flow (video cũ đã bị xóa?)") from e
        box.click()
        page.keyboard.press("Control+A")
        page.keyboard.press("Delete")
        page.keyboard.insert_text(job["editInstruction"])
        page.wait_for_timeout(500)
        log.info("Sửa trên Flow %s: %s", job["externalId"], job["editInstruction"])
        self._save_pending(page, job, before)
        page.locator(s["generate_button"]).last.click()
        page.wait_for_timeout(15_000)   # để Flow nhận yêu cầu rồi mới rời màn chỉnh sửa
        page.goto(grid, wait_until="domcontentloaded")
        page.wait_for_timeout(5_000)
        media_id = self._wait_result(page, s, before, errors, should_stop, job["timeoutSeconds"], "VIDEO")
        staged = self._download(page, s, media_id, staging, job["externalId"], "VIDEO", job.get("videoQuality") or DEFAULT_VIDEO_QUALITY,
                                should_stop)
        self._clear_pending(job)
        return GenerationResult(staged_file=staged, remote_ref=grid, media_url=self._media_url(page, s, media_id))

    # ---------- FOREGROUND: tự upload + gõ prompt có @tag, người dùng bấm Tạo; hook bắt file tải về ----------
    def generate_foreground(self, job: dict, should_stop) -> GenerationResult:
        s = self._sel([])
        staging = Path(job["stagingDir"])
        staging.mkdir(parents=True, exist_ok=True)
        captured: list[Path] = []
        failures: list[str] = []
        page_ref: list = []

        def on_download(d):
            try:
                handle(d)
            except Exception as e:  # lỗi trong handler không được làm vòng chờ treo mãi
                log.warning("Lưu file tải về lỗi: %s", e)
                failures.append(str(e)[:200] or type(e).__name__)

        def handle(d):
            suffix = Path(d.suggested_filename).suffix or (".mp4" if job["kind"] == "VIDEO" else ".png")
            if (job["kind"] == "VIDEO") != (suffix.lower() in (".mp4", ".mov", ".webm")):
                log.warning("Bỏ qua file %s: cảnh %s cần %s", d.suggested_filename, job["externalId"],
                            "video" if job["kind"] == "VIDEO" else "ảnh")
                _status(page_ref[0], "File vừa tải không phải " + ("video" if job["kind"] == "VIDEO" else "ảnh")
                        + ": hãy tải đúng kết quả của cảnh này") if page_ref else None
                return
            target = staging / f"{job['externalId']}{suffix.lower()}"
            self._save_download(d, target)
            captured.append(target)
            log.info("Bắt được file người dùng tải: %s", target)

        # Dự phòng khi tự gắn ảnh lỗi: thư mục ảnh đánh số theo thứ tự gắn + hộp nổi trên trang Flow
        refs_folder = prepare_refs_folder(job)
        # Nút trên hộp hướng dẫn gọi về Python qua binding: "pause" = tạm dừng phim, "skip" = bỏ qua cảnh
        user_action: list[str] = []
        with self._browser(job["account"], headless=False) as ctx:
            ctx.on("download", on_download)  # mọi lần bấm Download trên Flow trong cửa sổ này
            try:
                ctx.grant_permissions(["clipboard-read", "clipboard-write"], origin="https://flow.google.com")
            except PlaywrightError:
                pass
            ctx.expose_binding("afmAction", lambda source, action: user_action.append(str(action)))
            ctx.add_init_script(overlay_script(job, refs_folder))
            page = self._open_flow(ctx, job)
            page_ref.append(page)
            self._ensure_project(page, job)
            self._raise_if_login(page, job)
            resume = None
            if "/project/" in page.url:
                try:
                    self._wait_prompt_box(page, s)
                    resume = self._pending_result(page, s, job)
                except (TransientError, PlaywrightError):
                    resume = None
            if resume:
                filled = True
            else:
                _status(page, "App đang tự điền, đừng đóng cửa sổ…")
                filled = self._prefill_best_effort(page, s, job, should_stop)
                if not filled and refs_folder:
                    open_in_explorer(refs_folder)  # tự gắn không được: mở thư mục ảnh để người dùng kéo tay
            # Ảnh: điền đủ prompt + @tag thì app tự bấm Tạo và tự tải về (người dùng vẫn duyệt ở màn Duyệt).
            # Video, hoặc khi điền chưa đủ: người dùng tự kiểm tra, bấm Tạo và Tải xuống.
            auto = bool(resume) or (filled and job["kind"] == "IMAGE")
            state = {"last_check": time.monotonic(), "errors": self._error_counts(page, s)}

            def check():
                if should_stop():
                    raise Cancelled("Người dùng hủy job")
                if "pause" in user_action:
                    raise PausedByUser(f"Bạn bấm \"Tạm dừng phim\" ở cảnh {job['externalId']}")
                if "skip" in user_action:
                    raise Cancelled(f"Bạn bấm \"Bỏ qua cảnh này\" ở cảnh {job['externalId']}")
                if "quota" in user_action:
                    raise QuotaExceeded(f"Bạn báo tài khoản {job['account']['email']} hết lượt ở cảnh {job['externalId']}")
                if not ctx.pages:
                    raise PausedByUser(f"Bạn đã đóng cửa sổ Flow ở cảnh {job['externalId']}")
                if failures:
                    raise TransientError(f"Không lưu được file tải về: {failures[0]}")
                if time.monotonic() - state["last_check"] > 5:
                    state["last_check"] = time.monotonic()
                    self._raise_if_error(ctx.pages[0], s, state["errors"])   # Flow báo hết lượt → tự đổi tài khoản

            try:
                if auto:
                    page.set_default_timeout(45_000)
                    try:
                        if resume:
                            media_id = resume
                            _status(page, "✓ Ảnh của lần trước đã tạo xong trên Flow, đang tải về (không tạo lại)…")
                        else:
                            page.wait_for_timeout(4_000)
                            check()
                            before = set(self._media_ids(page, s))
                            log.info("FOREGROUND %s: đã điền đủ, tự bấm Tạo", job["externalId"])
                            _status(page, "Đang tạo ảnh… thường mất 1–2 phút. ĐỪNG đóng cửa sổ, app tự tải về khi xong")
                            self._save_pending(page, job, before)
                            page.locator(s["generate_button"]).first.click()
                            media_id = self._wait_result(page, s, before, state["errors"], lambda: check() or False, job["timeoutSeconds"],
                                                       job["kind"])
                        _status(page, "✓ Có ảnh, đang tải về…")
                        self._click_download(page, s, media_id, job["kind"], job.get("videoQuality") or DEFAULT_VIDEO_QUALITY)
                    except (TransientError, PlaywrightTimeout) as e:
                        # chưa thấy kết quả / không bấm tải được: để người dùng tự tải trong cửa sổ này
                        log.warning("FOREGROUND %s: không tự lấy được kết quả: %s", job["externalId"], e)
                        _status(page, "Chưa tự lấy được ảnh: khi có kết quả, bấm ⋮ → Tải xuống")
                else:
                    log.info("FOREGROUND %s: đã mở Flow, chờ người dùng tạo và bấm Tải xuống", job["externalId"])
                deadline = time.monotonic() + 120 if auto else None
                while not captured:
                    check()
                    ctx.pages[0].wait_for_timeout(1_000)  # để Playwright xử lý sự kiện download / nút bấm trong lúc chờ
                    if deadline and time.monotonic() > deadline:
                        deadline = None
                        _status(page, "Chưa thấy file tải về: bấm ⋮ → Tải xuống trên ảnh vừa tạo")
            except PlaywrightError as e:
                # chỉ khi cửa sổ thật sự đóng mới tạm dừng phim (cảnh này quay về "Chờ chạy"); lỗi khác là lỗi tạm thời
                if page.is_closed() or not ctx.pages:
                    raise PausedByUser(f"Bạn đã đóng cửa sổ Flow ở cảnh {job['externalId']}") from e
                raise TransientError(f"Lỗi trình duyệt khi chờ tải về: {str(e)[:300]}") from e
            self._clear_pending(job)
            return GenerationResult(staged_file=captured[-1], remote_ref=project_url(page.url) if not page.is_closed() else None)

    @staticmethod
    def _ensure_project(page, job: dict):
        """
        Flow mở ra màn hình chính (danh sách dự án): chưa có ô prompt. Phim đã có "Link dự án trên Flow" thì vào thẳng
        dự án đó; chưa có thì bấm "Dự án mới" / "New project". Dự án tạo ra được backend nhớ lại cho các cảnh sau.
        """
        try:
            FlowBrowserDriver._enter_project(page)
        finally:
            url = page.url if not page.is_closed() else ""
            if "/project/" in url and project_url(url) != project_url(job.get("flowProjectUrl")) and callable(job.get("_on_project")):
                job["_on_project"](project_url(url))

    @staticmethod
    def _enter_project(page):
        if "/project/" in page.url:
            # link đã nhớ có thể trỏ tới dự án đã bị xóa: Flow chuyển sang /404?reason=project sau khi tải trang
            # chờ tới khi ô prompt hiện (dự án còn: vào thẳng) hoặc Flow chuyển sang /404 (dự án đã xóa); tối đa 6s
            deadline = time.monotonic() + 6
            while "/404" not in page.url:
                try:
                    if page.locator("[role=textbox][contenteditable=true]").first.is_visible():
                        return
                except PlaywrightError:
                    pass
                if time.monotonic() > deadline:
                    return
                page.wait_for_timeout(150)
        if "/404" in page.url:
            log.info("Dự án Flow đã lưu không còn (%s): tạo dự án mới", page.url)
            page.goto(FLOW_URL, wait_until="domcontentloaded")
            page.wait_for_timeout(3_000)
        try:
            name = re.compile(r"Dự án mới|New project", re.I)
            target = page.get_by_role("button", name=name)
            if target.count() == 0:
                target = page.get_by_text(name)
            if target.count() > 0:
                target.first.click()
                page.wait_for_url(re.compile(r"/project/"), timeout=20_000)
                page.wait_for_timeout(3_000)
                log.info("Đã bấm 'Dự án mới' trên Flow → %s", page.url)
        except PlaywrightError as e:
            log.warning("Không tự bấm 'Dự án mới' được, người dùng làm tay: %s", str(e)[:200])

    def _prefill_best_effort(self, page, s: dict, job: dict, should_stop=None) -> bool:
        """Tự upload ảnh còn thiếu + gõ prompt có chip @tag (không bấm Tạo). True nếu gắn được đủ ảnh."""
        if "/project" not in page.url:
            # chưa vào dự án: ô tìm kiếm ở màn hình chính có thể khớp selector prompt_box, đừng điền nhầm
            log.info("FOREGROUND: chưa vào dự án Flow, bỏ qua bước tự điền")
            return False
        try:
            self._wait_prompt_box(page, s)
        except TransientError:
            return False
        page.set_default_timeout(10_000)   # bước "cố gắng": không treo 45s ở một nút không có
        try:
            return self._prefill_steps(page, s, job, should_stop)
        finally:
            if not page.is_closed():
                page.set_default_timeout(45_000)

    def _prefill_steps(self, page, s: dict, job: dict, should_stop=None) -> bool:
        uploaded = True
        for name, step in [("đặt cài đặt (x1, độ dài, độ phân giải)", lambda: self._apply_settings(page, s, job["kind"], job)),
                           ("chọn chế độ", lambda: self._select_mode(page, s, job["kind"])),
                           ("upload ảnh reference", lambda: self._upload_refs(page, s, job["referenceImages"], should_stop))]:
            _status(page, "Đang " + name + "…")
            try:
                step()
            except (PlaywrightError, AssertionError, Rejected, TransientError) as e:
                _status(page, "Không tự " + name + " được: " + str(e)[:120])
                log.warning("FOREGROUND: không tự %s được, người dùng làm tay: %s", name, str(e)[:200])
                if name == "upload ảnh reference":
                    uploaded = False
        ids = self._ref_ids(job)
        _status(page, "Đang gõ prompt và gắn @tag…")
        try:
            attached = self._compose_prompt(page, s, job["prompt"], ids if uploaded else [])
        except PlaywrightError as e:
            log.warning("FOREGROUND: không tự điền prompt được: %s", str(e)[:200])
            _status(page, "Không tự gõ được prompt: dùng nút Copy prompt")
            return False
        need = self._needed_refs(job["prompt"], ids)
        ok = uploaded and need <= attached
        _status(page, ("✓ Đã điền xong prompt" + (f" + {len(attached)} ảnh @tag" if attached else "") + ". Kiểm tra rồi bấm Tạo.")
                if ok else f"Chỉ gắn được {len(attached & need)}/{len(need)} ảnh: gắn tay ảnh còn thiếu (thư mục ảnh đã mở)")
        return ok
