package com.aifilm.automation.job;

import com.aifilm.automation.account.AccountEntity;
import com.aifilm.automation.account.AccountRepository;
import com.aifilm.automation.account.AccountStatus;
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
        return nextBySceneOrder(p, false, Set.of());
    }

    Optional<JobEntity> nextBySceneOrder(ProjectEntity p, boolean selectedOnly) {
        return nextBySceneOrder(p, selectedOnly, Set.of());
    }

    /**
     * selectedOnly: chỉ xét các video người dùng đã chọn; ảnh còn thiếu của chúng vẫn được tạo trước.
     * skip: job đã xét trong lượt này mà không giao được (ví dụ tài khoản đích của "Sửa trên Flow" đang bận).
     */
    Optional<JobEntity> nextBySceneOrder(ProjectEntity p, boolean selectedOnly, Set<Long> skip) {
        Set<String> have = new HashSet<>();
        assets.findByProjectId(p.getId()).forEach(a -> have.add(a.getExternalId()));
        int scanned = 0;
        // Lần lượt từng cảnh: cảnh đầu tiên chưa xong là cảnh duy nhất được làm (ảnh của nó rồi tới video của nó).
        // Cảnh kế tiếp chỉ bắt đầu khi video cảnh trước đã tạo xong. Không làm hàng loạt ảnh của nhiều cảnh trước.
        for (JobEntity video : selectedOnly ? jobs.findOpenSelectedVideoJobsInOrder(p.getId()) : jobs.findOpenVideoJobsInOrder(p.getId())) {
            if (++scanned > SCENE_SCAN_LIMIT) {
                break;
            }
            if (video.getStatus() == JobStatus.GENERATING) {
                return Optional.empty();   // video của cảnh này đang tạo: chờ xong mới sang cảnh sau
            }
            Optional<JobEntity> pick = runnableFor(video, p.getId(), have, new HashSet<>(), 0).filter(j -> !skip.contains(j.getId()));
            if (pick.isPresent()) {
                return pick;
            }
            if (waitingOnImages(video, p.getId(), have, new HashSet<>(), 0)) {
                return Optional.empty();   // ảnh của cảnh này đang tạo / chờ duyệt: chờ, không nhảy sang cảnh khác
            }
            // cảnh không chạy được hẳn (thiếu ảnh mà không có prompt tạo, chưa khai báo ảnh…): bỏ qua, xét cảnh sau
        }
        return Optional.empty();
    }

    /** Cảnh đang chờ một ảnh đang trong quy trình (tạo / chờ duyệt / tải) - khác với cảnh bị kẹt vĩnh viễn. */
    private boolean waitingOnImages(JobEntity job, long projectId, Set<String> have, Set<Long> visited, int depth) {
        if (depth > 4 || !visited.add(job.getId())) {
            return false;
        }
        for (PromptReferenceEntity r : references.findByVideoPromptIdOrderByOrdinalAsc(job.getPromptId())) {
            if (have.contains(r.getRefExternalId())) {
                continue;
            }
            Optional<JobEntity> producer = jobs.findImageJob(projectId, r.getRefExternalId());
            if (producer.isEmpty()) {
                continue;
            }
            JobStatus st = producer.get().getStatus();
            if (st == JobStatus.GENERATING || st == JobStatus.AWAITING_REVIEW || st == JobStatus.APPROVED || st == JobStatus.DOWNLOADING) {
                return true;
            }
            if (st == JobStatus.PENDING && waitingOnImages(producer.get(), projectId, have, visited, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /** Số video tối đa xét mỗi lần chọn việc (phim hàng nghìn cảnh không được quét hết mỗi lần claim). */
    private static final int SCENE_SCAN_LIMIT = 60;

    private Optional<JobEntity> runnableFor(JobEntity job, long projectId, Set<String> have, Set<Long> visited, int depth) {
        if (depth > 4 || !visited.add(job.getId())) {
            return Optional.empty();
        }
        List<PromptReferenceEntity> declared = references.findByVideoPromptIdOrderByOrdinalAsc(job.getPromptId());
        if (depth == 0 && declared.isEmpty()) {
            return Optional.empty();   // BẮT BUỘC: video phải có ảnh tham chiếu mới được tạo
        }
        List<String> missing = declared.stream()
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
        Optional<JobEntity> next = pickNext(p, new HashSet<>(Set.of(-1L)));
        if (next.isEmpty() || next.get().getEditInstruction() != null || next.get().isCancelRequested()) {
            return Optional.empty();
        }
        return Optional.of(startJob(next.get(), a, p, workerId, LocalDateTime.now()));
    }

    /**
     * Cảnh kế tiếp của phim theo đúng thứ tự worker sẽ chạy, nhưng KHÔNG nhận job, không thuê tài khoản, không đổi trạng thái
     * phim. Dùng cho chế độ "Tự làm trên Flow": app chỉ bày cảnh ra, người dùng tự tạo trên Flow. Gọi trong transaction.
     */
    public Optional<JobEntity> peekNext(ProjectEntity p, Set<Long> skip) {
        return peekNext(p, skip, false);
    }

    /**
     * imagesOnly: "Chỉ làm ảnh" - mọi ảnh còn chờ tạo của phim (nhân vật, bối cảnh, panel), kể cả ảnh của cảnh chưa tới lượt,
     * theo thứ tự file (ảnh gốc trước ảnh panel). Ảnh cần ảnh khác chưa có (panel cần ảnh nhân vật) chỉ ra sau khi đủ ảnh đó.
     */
    public Optional<JobEntity> peekNext(ProjectEntity p, Set<Long> skip, boolean imagesOnly) {
        Set<Long> s = new HashSet<>(skip);
        s.add(-1L);   // NOT IN không được rỗng
        if (!imagesOnly) {
            return jobs.findNextRunnable(p.getId(), 0, 1, 0, s).or(() -> nextBySceneOrder(p, p.isSelectedOnlyOn(), s));
        }
        for (int guard = 0; guard < 20; guard++) {
            Optional<JobEntity> next = jobs.findNextRunnable(p.getId(), 1, 0, 0, s);
            if (next.isEmpty() || next.get().getKind() == PromptKind.IMAGE) {
                return next;
            }
            s.add(next.get().getId());   // video được bấm "Chạy cảnh này" (ưu tiên) vẫn lọt qua imagesOnly: bỏ qua
        }
        return Optional.empty();
    }

    /**
     * Job kế tiếp được phép chạy của một phim (đã tính thứ tự ảnh / video, chạy theo cảnh, cảnh được chọn riêng…).
     * skip: job đã xét trong lượt này mà không giao được (phải chứa ít nhất một phần tử: NOT IN không được rỗng).
     */
    private Optional<JobEntity> pickNext(ProjectEntity p, Set<Long> skip) {
        // Luôn theo cảnh (kể cả khi chọn riêng video/cảnh): cảnh nào đủ ảnh thì làm video cảnh đó. Cảnh người dùng bấm
        // "Chạy cảnh này" đã đủ ảnh thì chạy ngay; cảnh thiếu ảnh thì tạo đúng ảnh còn thiếu của nó rồi tới video.
        Optional<JobEntity> next = jobs.findNextRunnable(p.getId(), 0, 1, 0, skip);
        if (next.isEmpty()) {
            next = nextBySceneOrder(p, p.isSelectedOnlyOn(), skip);
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
            Set<Long> skip = new HashSet<>(Set.of(-1L));   // job đã xét trong lượt này (-1: để NOT IN không rỗng)
            for (int guard = 0; guard < 50; guard++) {
                Optional<JobEntity> next = pickNext(p, skip);
                if (next.isEmpty()) {
                    break;
                }
                JobEntity candidate = next.get();
                skip.add(candidate.getId());
                if (candidate.isCancelRequested()) {
                    candidate.setCancelRequested(false);
                    candidate.setRunPriority(null);
                    transitions.move(candidate, JobStatus.CANCELLED, null, "người dùng hủy");
                    continue;
                }
                Optional<AccountEntity> acc;
                if (candidate.getEditInstruction() != null && candidate.getFlowAccountId() != null) {
                    // "Sửa trên Flow": video nằm trong dự án Flow của đúng tài khoản đã tạo nó
                    Optional<AccountEntity> owner = accounts.findById(candidate.getFlowAccountId());
                    if (owner.isEmpty() || owner.get().getStatus() == AccountStatus.DISABLED) {
                        dropFlowEdit(candidate, owner.isEmpty());
                        continue;
                    }
                    acc = owner.filter(o -> o.getStatus() == AccountStatus.READY
                            || (o.getStatus() == AccountStatus.COOLDOWN && o.getCooldownUntil() != null && !o.getCooldownUntil().isAfter(now)));
                    if (acc.isEmpty()) {
                        continue;   // tài khoản đích đang bận / cần đăng nhập: job này chờ, lấy job kế tiếp
                    }
                } else {
                    acc = accounts.findNextAvailable(p.getProvider().name(), now);
                    if (acc.isEmpty()) {
                        break;   // job giữ PENDING; UI hiển thị "hết tài khoản"
                    }
                }
                return Optional.of(startJob(candidate, acc.get(), p, workerId, now));
            }
        }
        return Optional.empty();
    }

    /** Tài khoản đã tạo video trên Flow không còn dùng được: hủy lượt sửa, báo rõ cho người dùng thay vì chặn cả phim. */
    private void dropFlowEdit(JobEntity job, boolean deleted) {
        String msg = deleted
                ? "Tài khoản tạo video này trên Flow đã bị xóa: đã hủy lượt \"Sửa trên Flow\". Dùng \"Tạo lại\""
                : "Tài khoản tạo video này trên Flow đang tắt: đã hủy lượt \"Sửa trên Flow\". Bật lại tài khoản rồi sửa lại, hoặc dùng \"Tạo lại\"";
        if (deleted) {
            job.setFlowMediaUrl(null);
            job.setFlowAccountId(null);
        }
        job.setEditInstruction(null);
        job.setRunPriority(null);
        job.setErrorMessage(msg);
        transitions.move(job, JobStatus.CANCELLED, null, msg);
        log.warn("Job {}: {}", job.getId(), msg);
    }
}
