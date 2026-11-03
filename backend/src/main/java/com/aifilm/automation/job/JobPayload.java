package com.aifilm.automation.job;

import java.util.List;
import java.util.Map;

/** Toàn bộ dữ liệu worker Python cần cho một lần tạo, gói một lần. Python không đọc DB. */
public record JobPayload(
        long jobId,
        String kind,
        String provider,
        String runMode,
        String externalId,
        String prompt,
        List<String> referenceImages,
        Map<String, Object> account,
        String stagingDir,
        String flowProjectUrl,
        String aspectRatio,
        int timeoutSeconds,
        boolean headless) {
}
