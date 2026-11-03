package com.aifilm.automation.job;

import java.time.LocalDateTime;
import java.util.List;

/** Dữ liệu một job cho giao diện Vue. */
public record JobView(
        long id,
        long promptId,
        String kind,
        String externalId,
        int seqNo,
        JobStatus status,
        String prompt,
        int revisionNo,
        String summaryVi,
        List<String> referenceIds,
        /** Từng ảnh reference theo thứ tự upload: đã có trong kho chưa, đến từ Excel hay người dùng thêm bằng "+". */
        List<RefView> references,
        /** File Excel đã nạp cảnh này và thứ tự của file đó. */
        String sourceName,
        int sourceOrder,
        /** Ảnh này (theo ID) đã có trong kho ảnh của phim: ảnh có sẵn người dùng đưa vào, hoặc đã duyệt. */
        boolean inStore,
        String accountEmail,
        int attempt,
        String errorCode,
        String errorMessage,
        boolean hasPreview,
        /** "video" | "image" | null: loại file xem trước (theo đuôi file thật, không theo loại job). */
        String previewType,
        String finalPath,
        String remoteRef,
        boolean cancelRequested,
        /** Có thể "Sửa trên Flow" (video có địa chỉ trên Flow). */
        boolean flowEditable,
        /** Yêu cầu sửa đang chờ chạy (nếu có). */
        String editInstruction,
        LocalDateTime generatedAt,
        LocalDateTime updatedAt) {

    /**
     * producerStatus: ảnh này chưa có nhưng app SẼ TẠO nó (có prompt ở tab Tạo ảnh, ví dụ PANEL-1 từ prompt-anh-panel.xlsx):
     * trạng thái job tạo ảnh đó (PENDING, AWAITING_REVIEW…). null = không có prompt tạo ảnh này.
     */
    public record RefView(String id, boolean available, boolean userAdded, String producerStatus) {}
}
