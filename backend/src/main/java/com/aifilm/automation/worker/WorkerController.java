package com.aifilm.automation.worker;

import com.aifilm.automation.job.JobClaimService;
import com.aifilm.automation.job.JobPayload;
import com.aifilm.automation.job.JobResultService;
import com.aifilm.automation.job.WorkerReports.ClaimRequest;
import com.aifilm.automation.job.WorkerReports.FailureReport;
import com.aifilm.automation.job.WorkerReports.GeneratedReport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** API nội bộ cho worker Python. Bảo vệ bằng WorkerTokenFilter. */
@RestController
@RequestMapping("/api/worker")
@RequiredArgsConstructor
public class WorkerController {

    private final JobClaimService claims;
    private final JobResultService results;

    /** 200 + payload khi có việc; 204 khi chưa có việc. */
    @PostMapping("/claim")
    public ResponseEntity<JobPayload> claim(@RequestBody ClaimRequest req) {
        return claims.claim(req.workerId()).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/jobs/{id}/heartbeat")
    public Map<String, Boolean> heartbeat(@PathVariable long id, @RequestBody ClaimRequest req) {
        return Map.of("cancel", results.heartbeat(id, req.workerId()));
    }

    @PostMapping("/jobs/{id}/generated")
    public void generated(@PathVariable long id, @RequestBody GeneratedReport r) {
        results.onGenerated(id, r);
    }

    @PostMapping("/jobs/{id}/failed")
    public void failed(@PathVariable long id, @RequestBody FailureReport r) {
        results.onFailed(id, r);
    }
}
