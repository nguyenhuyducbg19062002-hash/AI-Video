package com.aifilm.automation.job;

import com.aifilm.automation.account.AccountEntity;
import com.aifilm.automation.account.AccountRepository;
import com.aifilm.automation.prompt.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class JobQueryService {

    private final JobRepository jobs;
    private final PromptRepository prompts;
    private final PromptRevisionRepository revisions;
    private final PromptReferenceRepository references;
    private final AccountRepository accounts;
    private final JobEventRepository events;

    /** Danh sách job của project: ảnh trước video, theo STT. Một phim khoảng 340 dòng nên nạp hết một lần. */
    @Transactional(readOnly = true)
    public List<JobView> list(long projectId, JobStatus status) {
        List<JobEntity> list = status == null ? jobs.findByProjectId(projectId) : jobs.findByProjectIdAndStatus(projectId, status);
        return views(list);
    }

    @Transactional(readOnly = true)
    public JobView view(long jobId) {
        return views(List.of(jobs.findById(jobId).orElseThrow())).get(0);
    }

    @Transactional(readOnly = true)
    public Optional<Path> previewPath(long jobId) {
        return jobs.findById(jobId).map(JobEntity::getPreviewPath).map(Path::of);
    }

    /** Lịch sử chuyển trạng thái + các bản prompt của một job. */
    @Transactional(readOnly = true)
    public Map<String, Object> history(long jobId) {
        JobEntity job = jobs.findById(jobId).orElseThrow();
        List<Map<String, Object>> evts = new ArrayList<>();
        for (JobEventEntity e : events.findByJobIdOrderByIdDesc(jobId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", e.getCreatedAt());
            m.put("from", e.getFromStatus());
            m.put("to", e.getToStatus());
            m.put("message", e.getMessage());
            evts.add(m);
        }
        List<Map<String, Object>> revs = new ArrayList<>();
        for (PromptRevisionEntity r : revisions.findByPromptIdOrderByRevisionNoDesc(job.getPromptId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("revisionNo", r.getRevisionNo());
            m.put("source", r.getSource());
            m.put("note", r.getNote());
            m.put("createdAt", r.getCreatedAt());
            m.put("promptText", r.getPromptText());
            revs.add(m);
        }
        return Map.of("events", evts, "revisions", revs);
    }

    static String previewType(String path) {
        if (path == null) {
            return null;
        }
        String ext = ReviewService.extensionOf(path);
        return switch (ext) {
            case ".mp4", ".webm", ".mov" -> "video";
            default -> "image";
        };
    }

    private List<JobView> views(List<JobEntity> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, PromptEntity> promptById = prompts.findAllById(list.stream().map(JobEntity::getPromptId).toList())
                .stream().collect(Collectors.toMap(PromptEntity::getId, Function.identity()));
        Map<Long, PromptRevisionEntity> revById = revisions.findAllById(promptById.values().stream()
                        .map(PromptEntity::getCurrentRevisionId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(PromptRevisionEntity::getId, Function.identity()));
        Map<Long, List<String>> refsByPrompt = references.findByVideoPromptIdIn(promptById.keySet()).stream()
                .sorted(Comparator.comparingInt(PromptReferenceEntity::getOrdinal))
                .collect(Collectors.groupingBy(PromptReferenceEntity::getVideoPromptId, LinkedHashMap::new,
                        Collectors.mapping(PromptReferenceEntity::getRefExternalId, Collectors.toList())));
        Map<Long, String> emailById = accounts.findAll().stream()
                .collect(Collectors.toMap(AccountEntity::getId, AccountEntity::getEmail));

        return list.stream()
                .map(j -> {
                    PromptEntity p = promptById.get(j.getPromptId());
                    PromptRevisionEntity rev = revById.get(p.getCurrentRevisionId());
                    return new JobView(
                            j.getId(), p.getId(), p.getKind().name(), p.getExternalId(), p.getSeqNo(), j.getStatus(),
                            rev == null ? "" : rev.getPromptText(), rev == null ? 0 : rev.getRevisionNo(),
                            p.getSummaryVi(), refsByPrompt.getOrDefault(p.getId(), List.of()),
                            j.getAccountId() == null ? null : emailById.get(j.getAccountId()),
                            j.getAttempt(), j.getErrorCode(), j.getErrorMessage(), j.getPreviewPath() != null,
                            previewType(j.getPreviewPath()),
                            j.getFinalPath(), j.getRemoteRef(), j.isCancelRequested(), j.getGeneratedAt(), j.getUpdatedAt());
                })
                .sorted(Comparator.comparing((JobView v) -> v.kind().equals("IMAGE") ? 0 : 1).thenComparingInt(JobView::seqNo))
                .toList();
    }
}
