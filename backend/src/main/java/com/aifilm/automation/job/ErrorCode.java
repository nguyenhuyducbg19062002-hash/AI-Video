package com.aifilm.automation.job;

/** Mã lỗi worker gửi về; trùng tên với engine/drivers/errors.py. */
public enum ErrorCode {
    /** Hết quota/credit: tài khoản nghỉ, job sang tài khoản khác, không tính attempt. */
    QUOTA_EXCEEDED,
    /** Phiên đăng nhập hỏng / checkpoint: tài khoản cần đăng nhập lại, job sang tài khoản khác. */
    AUTH_REQUIRED,
    /** Nền tảng từ chối chính prompt/ảnh này: đổi tài khoản không giúp gì, cần người sửa. */
    REJECTED,
    /** Lỗi tạm thời: thử lại tới max_attempts. */
    TRANSIENT,
    CANCELLED,
    /** Worker chưa được cấu hình đúng (selector Flow…): dừng project, không đốt hết job. */
    CONFIG_ERROR;

    public static ErrorCode parse(String s) {
        try {
            return valueOf(s);
        } catch (RuntimeException e) {
            return TRANSIENT;
        }
    }
}
