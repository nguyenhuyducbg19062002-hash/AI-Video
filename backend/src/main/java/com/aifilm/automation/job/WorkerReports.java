package com.aifilm.automation.job;

/** Các gói tin worker Python gửi về. */
public final class WorkerReports {

    private WorkerReports() {}

    public record ClaimRequest(String workerId) {}

    public record GeneratedReport(String workerId, String stagedFile, String remoteRef) {}

    public record FailureReport(String workerId, String errorCode, String message) {}
}
