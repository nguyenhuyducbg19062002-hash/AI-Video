package com.aifilm.automation.job;

import com.aifilm.automation.account.AccountEntity;
import com.aifilm.automation.account.AccountRepository;
import com.aifilm.automation.project.ProjectEntity;
import com.aifilm.automation.project.ProjectRepository;
import com.aifilm.automation.project.ProjectStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
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

    private Optional<JobPayload> claimInTx(String workerId) {
        LocalDateTime now = LocalDateTime.now();
        for (ProjectEntity p : projects.findByStatusOrderByIdAsc(ProjectStatus.RUNNING)) {
            if (jobs.countByProjectIdAndStatus(p.getId(), JobStatus.GENERATING) >= p.effectiveParallel()) {
                continue;
            }
            Optional<JobEntity> next = jobs.findNextRunnable(p.getId());
            if (next.isEmpty()) {
                continue;
            }
            Optional<AccountEntity> acc = accounts.findNextAvailable(p.getProvider().name(), now);
            if (acc.isEmpty()) {
                continue;   // job giữ PENDING; UI hiển thị "hết tài khoản"
            }
            JobEntity job = next.get();
            AccountEntity a = acc.get();
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
            return Optional.of(payloads.forGenerate(job, p, a));
        }
        return Optional.empty();
    }
}
