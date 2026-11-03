package com.aifilm.automation.job;

/** Các gói tin worker Python gửi về. */
public final class WorkerReports {

    private WorkerReports() {}

    public record ClaimRequest(String workerId) {}

    /** mediaUrl: địa chỉ màn chỉnh sửa kết quả trên Flow (để "Sửa trên Flow" sau này). */
    public record GeneratedReport(String workerId, String stagedFile, String remoteRef, String mediaUrl) {}

    public record FlowProjectReport(String workerId, String url) {}

    public record FailureReport(String workerId, String errorCode, String message) {}
}
