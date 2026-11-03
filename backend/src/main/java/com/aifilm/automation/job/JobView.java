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
        LocalDateTime generatedAt,
        LocalDateTime updatedAt) {
}
