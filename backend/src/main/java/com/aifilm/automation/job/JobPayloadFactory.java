package com.aifilm.automation.job;

import com.aifilm.automation.account.AccountEntity;
import com.aifilm.automation.project.ProjectEntity;
import com.aifilm.automation.prompt.*;
import com.aifilm.automation.service.AppSettingsService;
import com.aifilm.automation.system.AppPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JobPayloadFactory {

    private final PromptRepository prompts;
    private final PromptRevisionRepository revisions;
    private final PromptReferenceRepository references;
    private final JobRepository jobs;
    private final AppSettingsService settings;

    /** Gọi trong transaction của claim: gắn revision hiện tại vào job và gói dữ liệu cho worker. */
    public JobPayload forGenerate(JobEntity job, ProjectEntity project, AccountEntity account) {
        PromptEntity prompt = prompts.findById(job.getPromptId()).orElseThrow();
        PromptRevisionEntity rev = revisions.findById(prompt.getCurrentRevisionId()).orElseThrow();
        job.setRevisionId(rev.getId());

        List<String> refs = prompt.getKind() == PromptKind.VIDEO
                ? references.findByVideoPromptIdOrderByOrdinalAsc(prompt.getId()).stream()
                    .map(r -> jobs.findImageJob(project.getId(), r.getRefExternalId())
                            .map(JobEntity::getFinalPath)
                            .orElseThrow(() -> new IllegalStateException("Ảnh reference " + r.getRefExternalId() + " chưa có file")))
                    .toList()
                : List.of();

        Path staging = AppPaths.staging().resolve("job-" + job.getId()).resolve("rev-" + rev.getId());

        Map<String, Object> acc = new LinkedHashMap<>();   // HashMap: cho phép giá trị null
        acc.put("id", account.getId());
        acc.put("email", account.getEmail());
        acc.put("profileDir", account.getProfileDir());
        acc.put("proxy", account.getProxy());
        acc.put("credentialFile", account.getCredentialFile());
        acc.put("gcpProject", account.getGcpProject());
        acc.put("gcpLocation", account.getGcpLocation());

        return new JobPayload(
                job.getId(),
                prompt.getKind().name(),
                project.getProvider().name(),
                project.getRunMode().name(),
                prompt.getExternalId(),
                rev.getPromptText(),
                refs,
                acc,
                staging.toString(),
                project.getFlowProjectUrl(),
                project.getAspectRatio(),
                settings.getIntSetting("job_timeout_minutes", 15) * 60,
                Boolean.parseBoolean(settings.getSetting("headless", "false")));
    }
}
