"""Mã lỗi dùng chung với backend Java (com.aifilm.automation.job.ErrorCode)."""
from dataclasses import dataclass
from pathlib import Path


class WorkerError(Exception):
    code = "TRANSIENT"


class TransientError(WorkerError):
    """Lỗi tạm thời (mạng, timeout, trang chưa tải xong): backend thử lại tới max_attempts."""
    code = "TRANSIENT"


class QuotaExceeded(WorkerError):
    """Hết quota/credit: backend cho tài khoản nghỉ và chuyển job sang tài khoản khác."""
    code = "QUOTA_EXCEEDED"


class AuthRequired(WorkerError):
    """Phiên đăng nhập hỏng / checkpoint: tài khoản cần đăng nhập lại."""
    code = "AUTH_REQUIRED"


class Rejected(WorkerError):
    """Nền tảng từ chối chính prompt/ảnh này (chính sách nội dung, tham số sai). Đổi tài khoản không giúp gì."""
    code = "REJECTED"


class Cancelled(WorkerError):
    code = "CANCELLED"


class ConfigError(WorkerError):
    """Worker chưa được cấu hình đúng (selector Flow...): backend dừng project thay vì đốt hết job."""
    code = "CONFIG_ERROR"


@dataclass
class GenerationResult:
    staged_file: Path
    remote_ref: str | None = None
