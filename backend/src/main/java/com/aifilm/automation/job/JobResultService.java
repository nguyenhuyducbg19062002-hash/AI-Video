package com.aifilm.automation.job;

import com.aifilm.automation.account.AccountEntity;
import com.aifilm.automation.account.AccountRepository;
import com.aifilm.automation.account.AccountStatus;
import com.aifilm.automation.job.WorkerReports.FailureReport;
import com.aifilm.automation.job.WorkerReports.GeneratedReport;
import com.aifilm.automation.project.ProjectEntity;
import com.aifilm.automation.project.ProjectRepository;
import com.aifilm.automation.service.AppSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobResultService {

    private final JobRepository jobs;
    private final AccountRepository accounts;
    private final ProjectRepository projects;
    private final JobTransitions transitions;
    private final AppSettingsService settings;

    @Transactional
    public void onGenerated(long jobId, GeneratedReport r) {
        JobEntity job = owned(jobId, r.workerId());
        Path staged = StagingGuard.requireInside(Path.of(r.stagedFile()));
        if (!Files.isRegularFile(staged)) {
            throw new IllegalArgumentException("Worker báo file không tồn tại: " + staged);
        }
        job.setPreviewPath(staged.toString());
        job.setRemoteRef(r.remoteRef());
        job.setGeneratedAt(LocalDateTime.now());
        job.setErrorCode(null);
        job.setErrorMessage(null);
        job.setWorkerId(null);
        transitions.move(job, JobStatus.AWAITING_REVIEW, job.getAccountId(), "có kết quả, chờ duyệt");
        accounts.findById(job.getAccountId()).ifPresent(AccountEntity::releaseOk);
    }

    @Transactional
    public void onFailed(long jobId, FailureReport r) {
        JobEntity job = owned(jobId, r.workerId());
        AccountEntity acc = accounts.findById(job.getAccountId()).orElseThrow();
        ErrorCode code = ErrorCode.parse(r.errorCode());
        job.setErrorCode(code.name());
        job.setErrorMessage(r.message());

        switch (code) {
            case QUOTA_EXCEEDED -> {
                int minutes = settings.getIntSetting("quota_cooldown_minutes", 360);
                acc.cooldown(LocalDateTime.now().plusMinutes(minutes), r.message());
                requeue(job, acc, "hết quota trên " + acc.getEmail() + " → xoay sang tài khoản khác");   // không tính attempt
            }
            case AUTH_REQUIRED -> {
                acc.needLogin(r.message());
                requeue(job, acc, "phiên đăng nhập của " + acc.getEmail() + " hỏng → xoay sang tài khoản khác");
            }
            case TRANSIENT -> {
                acc.releaseAfterError(r.message());
                job.setAttempt(job.getAttempt() + 1);
                int max = projects.findById(job.getProjectId()).map(ProjectEntity::getMaxAttempts).orElse(3);
                if (job.getAttempt() < max) {
                    requeue(job, acc, "lỗi tạm thời, thử lại " + job.getAttempt() + "/" + max);
                } else {
                    fail(job, acc, "hết lượt thử (" + max + ")");
                }
            }
            case REJECTED -> {
                acc.releaseOk();
                fail(job, acc, "nền tảng từ chối: cần sửa prompt");
            }
            case CONFIG_ERROR -> {
                acc.releaseOk();
                requeue(job, acc, "lỗi cấu hình worker");
                projects.findById(job.getProjectId()).ifPresent(p -> p.pause("CONFIG_ERROR: " + r.message()));
            }
            case CANCELLED -> {
                acc.releaseOk();
                job.setWorkerId(null);
                transitions.move(job, JobStatus.CANCELLED, acc.getId(), "người dùng hủy");
            }
        }
        log.info("Job {} báo lỗi {} → {} ({})", jobId, code, job.getStatus(), r.message());
    }

    /** Trả về true nếu người dùng đã yêu cầu hủy job. */
    @Transactional
    public boolean heartbeat(long jobId, String workerId) {
        if (jobs.touchHeartbeat(jobId, workerId, LocalDateTime.now()) == 0) {
            throw new JobNotOwnedException(jobId, workerId);
        }
        return jobs.isCancelRequested(jobId).orElse(false);
    }

    /** Worker im lặng quá 5 phút (crash, máy ngủ): coi như lỗi tạm thời để job và tài khoản không kẹt mãi. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    @Transactional
    public void reapStaleJobs() {
        LocalDateTime limit = LocalDateTime.now().minusMinutes(5);
        for (JobEntity job : jobs.findByStatusAndHeartbeatAtBefore(JobStatus.GENERATING, limit)) {
            log.warn("Job {}: worker {} mất liên lạc, thu hồi", job.getId(), job.getWorkerId());
            onFailed(job.getId(), new FailureReport(job.getWorkerId(), "TRANSIENT", "Worker mất liên lạc quá 5 phút"));
        }
    }

    /** Lúc khởi động: mọi việc dở dang từ lần chạy trước được trả về hàng đợi (chạy trước khi bật worker). */
    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    @Transactional
    public void recoverAfterRestart() {
        for (JobEntity job : jobs.findByStatus(JobStatus.GENERATING)) {
            job.setAccountId(null);
            job.setWorkerId(null);
            transitions.move(job, JobStatus.PENDING, null, "khôi phục sau khi khởi động lại");
        }
        for (JobEntity job : jobs.findByStatus(JobStatus.DOWNLOADING)) {
            transitions.move(job, JobStatus.APPROVED, null, "khôi phục sau khi khởi động lại");
        }
        accounts.findByStatus(AccountStatus.LEASED).forEach(AccountEntity::releaseOk);
    }

    private void requeue(JobEntity job, AccountEntity acc, String why) {
        job.setAccountId(null);
        job.setWorkerId(null);
        transitions.move(job, JobStatus.PENDING, acc.getId(), why);
    }

    private void fail(JobEntity job, AccountEntity acc, String why) {
        job.setWorkerId(null);
        transitions.move(job, JobStatus.FAILED, acc.getId(), why);
    }

    private JobEntity owned(long jobId, String workerId) {
        JobEntity job = jobs.findById(jobId).orElseThrow();
        if (job.getStatus() != JobStatus.GENERATING || !Objects.equals(job.getWorkerId(), workerId)) {
            throw new JobNotOwnedException(jobId, workerId);
        }
        return job;
    }
}
