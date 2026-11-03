"""
Driver GIẢ để chạy thử toàn bộ vòng: claim → staging → duyệt → chép vào folder, mà không gọi nền tảng nào.

- IMAGE: tạo một ảnh PNG màu đơn sắc (không cần thư viện ngoài).
- VIDEO: tạo video 2 giây bằng ffmpeg nếu máy có ffmpeg; không có thì tạo ảnh PNG giữ chỗ.
- Tài khoản có email bắt đầu bằng "quota" luôn báo hết quota: dùng để thử cơ chế xoay vòng tài khoản.
- Prompt chứa "[fake:reject]" bị từ chối: dùng để thử nhánh FAILED → sửa prompt → tạo lại.
"""
import hashlib
import shutil
import struct
import subprocess
import time
import zlib
from pathlib import Path

from .errors import Cancelled, GenerationResult, QuotaExceeded, Rejected


def _png(path: Path, width: int, height: int, rgb: tuple[int, int, int]):
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    path.write_bytes(b"\x89PNG\r\n\x1a\n"
                     + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(raw, 9))
                     + chunk(b"IEND", b""))


def _color(text: str) -> tuple[int, int, int]:
    h = hashlib.sha1(text.encode("utf-8")).digest()
    return h[0], h[1], h[2]


class FakeDriver:
    def generate(self, job: dict, should_stop) -> GenerationResult:
        if job["account"]["email"].lower().startswith("quota"):
            raise QuotaExceeded(f"[FAKE] {job['account']['email']} hết quota")
        if "[fake:reject]" in job["prompt"]:
            raise Rejected("[FAKE] prompt bị từ chối")
        for _ in range(6):  # giả lập thời gian render, vẫn phản hồi lệnh hủy
            if should_stop():
                raise Cancelled("Người dùng hủy job")
            time.sleep(0.5)

        staging = Path(job["stagingDir"])
        staging.mkdir(parents=True, exist_ok=True)
        color = _color(job["prompt"])
        if job["kind"] == "IMAGE":
            target = staging / f"{job['externalId']}.png"
            _png(target, 320, 180, color)
            return GenerationResult(staged_file=target, remote_ref="fake://image")

        ffmpeg = shutil.which("ffmpeg")
        if not ffmpeg:
            # Không có ffmpeg: dùng ảnh PNG giữ chỗ để vẫn chạy được trọn vòng duyệt (chỉ ở chế độ FAKE)
            target = staging / f"{job['externalId']}.png"
            _png(target, 640, 360, color)
            return GenerationResult(staged_file=target, remote_ref="fake://video-placeholder")
        target = staging / f"{job['externalId']}.mp4"
        hex_color = "0x%02x%02x%02x" % color
        subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-f", "lavfi", "-i", f"color=c={hex_color}:s=640x360:d=2",
                        "-pix_fmt", "yuv420p", "-c:v", "libx264", str(target)], check=True, timeout=60)
        return GenerationResult(staged_file=target, remote_ref="fake://video")

    def generate_foreground(self, job: dict, should_stop) -> GenerationResult:
        return self.generate(job, should_stop)
