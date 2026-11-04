package com.aifilm.automation.job;

import com.aifilm.automation.account.AccountEntity;
import com.aifilm.automation.account.AccountRepository;
import com.aifilm.automation.project.ProjectEntity;
import com.aifilm.automation.project.ProjectRepository;
import com.aifilm.automation.project.ProjectStatus;
import com.aifilm.automation.prompt.ProjectAssetRepository;
import com.aifilm.automation.prompt.PromptKind;
import com.aifilm.automation.prompt.PromptReferenceEntity;
import com.aifilm.automation.prompt.PromptReferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobClaimService {

    private final JobRepository jobs;
    private final AccountRepository accounts;
    private final ProjectRepository projects;
    private final JobTransitions transitions;
    private final JobPayloadFactory payloads;
    private final ProjectAssetRepository assets;
    private final PromptReferenceRepository references;
    private final TransactionTemplate tx;
    private final ReentrantLock claimLock = new ReentrantLock();

    /**
     * Giao một job cho worker và thuê một tài khoản cho job đó.
     * Chỉ có một backend nên khóa trong JVM bọc NGOÀI transaction là đủ để hai worker không giành cùng job/tài khoản;
     * khóa được nhả sau khi commit. Một tài khoản chỉ phục vụ một job một lúc (một profile Edge chỉ mở được một lần).
     */
    public Optional<JobPayload> claim(String workerId) {
        claimLock.lock();
        try {
            return tx.execute(status -> claimInTx(workerId));
        } finally {
            claimLock.unlock();
        }
    }

    /**
     * Chạy theo cảnh (phim tắt "duyệt hết ảnh rồi mới tạo video"): đi lần lượt các video theo thứ tự cảnh.
     * - Cảnh đủ ảnh reference (có sẵn hoặc đã duyệt): chạy luôn video.
     * - Cảnh thiếu ảnh: chạy đúng ảnh còn thiếu trước (ảnh đó cần ảnh khác chưa có thì chạy ảnh gốc trước nữa), xong và duyệt
     *   thì lượt sau mới tới video của cảnh.
     * - Ảnh đang tạo / chờ duyệt, hoặc thiếu ảnh mà không có prompt tạo: cảnh chờ, chuyển sang cảnh kế tiếp.
     * Ảnh không cảnh nào cần thì không được tạo (muốn tạo thì bấm "Chạy cảnh này" hoặc bật "duyệt hết ảnh trước").
     */
    Optional<JobEntity> nextBySceneOrder(ProjectEntity p) {
        Set<String> have = new HashSet<>();
        assets.findByProjectId(p.getId()).forEach(a -> have.add(a.getExternalId()));
        for (JobEntity video : jobs.findPendingVideoJobsInOrder(p.getId())) {
            Optional<JobEntity> pick = runnableFor(video, p.getId(), have, new HashSet<>(), 0);
            if (pick.isPresent()) {
                return pick;
            }
        }
        return Optional.empty();
    }

    /** Job nên chạy để đưa `job` tiến lên: chính nó nếu đủ ảnh, ngược lại là job tạo một ảnh còn thiếu của nó. */
    private Optional<JobEntity> runnableFor(JobEntity job, long projectId, Set<String> have, Set<Long> visited, int depth) {
        if (depth > 4 || !visited.add(job.getId())) {
            return Optional.empty();
        }
        List<String> missing = references.findByVideoPromptIdOrderByOrdinalAsc(job.getPromptId()).stream()
                .map(PromptReferenceEntity::getRefExternalId).filter(id -> !have.contains(id)).toList();
        if (missing.isEmpty()) {
            return Optional.of(job);
        }
        List<JobEntity> producers = new ArrayList<>();
        for (String id : missing) {
            Optional<JobEntity> producer = jobs.findImageJob(projectId, id);
            if (producer.isEmpty()) {
                return Optional.empty();   // thiếu ảnh mà không có prompt tạo: cảnh này chưa chạy được, đừng tạo ảnh vô ích
            }
            producers.add(producer.get());
        }
        for (JobEntity producer : producers) {
            if (producer.getStatus() == JobStatus.PENDING) {
                Optional<JobEntity> pick = runnableFor(producer, projectId, have, visited, depth + 1);
                if (pick.isPresent()) {
                    return pick;
                }
            }
        }
        return Optional.empty();   // ảnh đang tạo / chờ duyệt / lỗi: cảnh chờ
    }

    /**
     * Chạy song song: worker đang giữ một phiên Flow (một tài khoản, một lần mở Edge) xin thêm một job cho CHÍNH tài khoản đó
     * trong lúc các job trước còn đang tạo. Chỉ cấp khi:
     *  - tài khoản còn đang LEASED và mọi job đang chạy của nó thuộc đúng worker này (phiên của worker khác thì không đụng);
     *  - chưa đủ số lệnh cùng lúc của tài khoản (maxConcurrent) và của phim (Song song);
     *  - job kế tiếp cùng phim (một phiên = một dự án Flow của tài khoản cho phim đó), không phải việc "Sửa trên Flow".
     */
    public Optional<JobPayload> claimMore(String workerId, long accountId) {
        claimLock.lock();
        try {
            return tx.execute(status -> claimMoreInTx(workerId, accountId));
        } finally {
            claimLock.unlock();
        }
    }

    Optional<JobPayload> claimMoreInTx(String workerId, long accountId) {
        AccountEntity a = accounts.findById(accountId).orElse(null);
        if (a == null || a.getStatus() != com.aifilm.automation.account.AccountStatus.LEASED) {
            return Optional.empty();
        }
        List<JobEntity> mine = jobs.findByAccountIdAndStatus(accountId, JobStatus.GENERATING);
        if (mine.isEmpty() || mine.size() >= a.effectiveMaxConcurrent()
                || mine.stream().anyMatch(j -> !workerId.equals(j.getWorkerId()))) {
            return Optional.empty();
        }
        ProjectEntity p = projects.findById(mine.get(0).getProjectId()).orElse(null);
        if (p == null || p.getStatus() != ProjectStatus.RUNNING
                || p.getRunMode() != com.aifilm.automation.project.RunMode.BACKGROUND
                || (p.getProvider() != com.aifilm.automation.project.Provider.FLOW_BROWSER
                    && p.getProvider() != com.aifilm.automation.project.Provider.FAKE)   // FAKE: để kiểm thử cả vòng không cần Google
                || jobs.countByProjectIdAndStatus(p.getId(), JobStatus.GENERATING) >= p.effectiveParallel()) {
            return Optional.empty();
        }
        Optional<JobEntity> next = pickNext(p);
        if (next.isEmpty() || next.get().getEditInstruction() != null) {
            return Optional.empty();
        }
        return Optional.of(startJob(next.get(), a, p, workerId, LocalDateTime.now()));
    }

    /** Job kế tiếp được phép chạy của một phim (đã tính thứ tự ảnh / video, chạy theo cảnh, cảnh được chọn riêng…). */
    private Optional<JobEntity> pickNext(ProjectEntity p) {
        boolean imagesOnly = !p.isSkipMissingRefsOn() && p.isWaitAllImagesOn() && jobs.countByProjectIdAndKindAndStatusNotIn(
                p.getId(), PromptKind.IMAGE, List.of(JobStatus.SUCCESS, JobStatus.CANCELLED)) > 0;
        Optional<JobEntity> next;
        if (!p.isSkipMissingRefsOn() && !p.isWaitAllImagesOn() && !p.isSelectedOnlyOn()) {
            // Chạy theo cảnh: cảnh người dùng bấm "Chạy cảnh này" đi trước như cũ, rồi tới từng cảnh theo thứ tự
            next = jobs.findNextRunnable(p.getId(), 0, 1, 0);
            if (next.isEmpty()) {
                next = nextBySceneOrder(p);
            }
        } else {
            next = jobs.findNextRunnable(p.getId(), imagesOnly ? 1 : 0, p.isSelectedOnlyOn() ? 1 : 0, p.isSkipMissingRefsOn() ? 1 : 0);
        }
        if (next.isEmpty() && p.isSelectedOnlyOn() && jobs.countSelectedLeft(p.getId()) == 0) {
            p.setSelectedOnly(false);
            p.pause("Đã chạy xong các cảnh bạn chọn");
            log.info("Phim {}: xong các cảnh đã chọn, tạm dừng", p.getCode());
        }
        return next;
    }

    private JobPayload startJob(JobEntity job, AccountEntity a, ProjectEntity p, String workerId, LocalDateTime now) {
        a.lease(job.getId(), now);
        job.setAccountId(a.getId());
        job.setWorkerId(workerId);
        job.setStartedAt(now);
        job.setHeartbeatAt(now);
        job.setCancelRequested(false);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        transitions.move(job, JobStatus.GENERATING, a.getId(), "claim bởi " + workerId + " trên " + a.getEmail());
        log.info("Job {} ({}) → {} trên {}", job.getId(), job.getKind(), workerId, a.getEmail());
        return payloads.forGenerate(job, p, a);
    }

    private Optional<JobPayload> claimInTx(String workerId) {
        LocalDateTime now = LocalDateTime.now();
        for (ProjectEntity p : projects.findByStatusOrderByIdAsc(ProjectStatus.RUNNING)) {
            if (jobs.countByProjectIdAndStatus(p.getId(), JobStatus.GENERATING) >= p.effectiveParallel()) {
                continue;
            }
            Optional<JobEntity> next = pickNext(p);
            if (next.isEmpty()) {
                continue;
            }
            JobEntity candidate = next.get();
            Optional<AccountEntity> acc = candidate.getEditInstruction() != null && candidate.getFlowAccountId() != null
                    // "Sửa trên Flow": video nằm trong dự án Flow của đúng tài khoản đã tạo nó
                    ? accounts.findById(candidate.getFlowAccountId()).filter(a -> a.getStatus() == com.aifilm.automation.account.AccountStatus.READY
                        || (a.getStatus() == com.aifilm.automation.account.AccountStatus.COOLDOWN && a.getCooldownUntil() != null && !a.getCooldownUntil().isAfter(now)))
                    : accounts.findNextAvailable(p.getProvider().name(), now);
            if (acc.isEmpty()) {
                continue;   // job giữ PENDING; UI hiển thị "hết tài khoản"
            }
            return Optional.of(startJob(next.get(), acc.get(), p, workerId, now));
        }
        return Optional.empty();
    }
}
