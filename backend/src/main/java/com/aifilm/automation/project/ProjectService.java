package com.aifilm.automation.project;

import com.aifilm.automation.account.AccountRepository;
import com.aifilm.automation.account.AccountStatus;
import com.aifilm.automation.job.*;
import com.aifilm.automation.prompt.PromptReferenceRepository;
import com.aifilm.automation.prompt.PromptRepository;
import com.aifilm.automation.prompt.PromptRevisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projects;
    private final JobRepository jobs;
    private final JobEventRepository events;
    private final PromptRepository prompts;
    private final PromptRevisionRepository revisions;
    private final PromptReferenceRepository references;
    private final AccountRepository accounts;
    private final JobTransitions transitions;

    public record ProjectForm(String name, String code, String sourceDir, String imageDir, String videoDir,
                              RunMode runMode, Provider provider, String flowProjectUrl, String aspectRatio,
                              Integer maxParallel, Integer maxAttempts) {}

    public record ProjectView(ProjectEntity project, Map<String, Long> counts, long total, long imagesTotal,
                              long imagesDone, long readyAccounts) {}

    @Transactional(readOnly = true)
    public List<ProjectView> list() {
        return projects.findAllByOrderByIdDesc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public ProjectView get(long id) {
        return view(projects.findById(id).orElseThrow());
    }

    @Transactional
    public ProjectView create(ProjectForm f) {
        ProjectEntity p = new ProjectEntity();
        apply(p, f);
        String code = f.code() == null || f.code().isBlank() ? slug(f.name()) : slug(f.code());
        if (projects.findByCode(code).isPresent()) {
            throw new IllegalArgumentException("Mã phim \"" + code + "\" đã tồn tại");
        }
        p.setCode(code);
        return view(projects.save(p));
    }

    @Transactional
    public ProjectView update(long id, ProjectForm f) {
        ProjectEntity p = projects.findById(id).orElseThrow();
        apply(p, f);
        return view(p);
    }

    @Transactional
    public ProjectView start(long id) {
        ProjectEntity p = projects.findById(id).orElseThrow();
        if (jobs.findByProjectId(id).isEmpty()) {
            throw new IllegalStateException("Phim chưa có prompt nào: hãy bấm Import trước");
        }
        p.setStatus(ProjectStatus.RUNNING);
        p.setPausedReason(null);
        return view(p);
    }

    @Transactional
    public ProjectView pause(long id) {
        ProjectEntity p = projects.findById(id).orElseThrow();
        p.pause("người dùng tạm dừng");
        return view(p);
    }

    /** Đưa mọi job FAILED / CANCELLED về hàng đợi. */
    @Transactional
    public int retryFailed(long id) {
        int n = 0;
        for (JobStatus s : List.of(JobStatus.FAILED, JobStatus.CANCELLED)) {
            for (JobEntity j : jobs.findByProjectIdAndStatus(id, s)) {
                j.setAttempt(0);
                j.setErrorCode(null);
                j.setErrorMessage(null);
                j.setAccountId(null);
                transitions.move(j, JobStatus.PENDING, null, "thử lại hàng loạt");
                n++;
            }
        }
        return n;
    }

    @Transactional
    public void delete(long id) {
        if (jobs.countByProjectIdAndStatus(id, JobStatus.GENERATING) > 0) {
            throw new IllegalStateException("Phim đang có job chạy: tạm dừng và chờ các job đang chạy xong trước khi xóa");
        }
        events.deleteByProjectId(id);
        jobs.deleteByProjectId(id);
        references.deleteByProjectId(id);
        revisions.deleteByProjectId(id);
        prompts.deleteByProjectId(id);
        projects.deleteById(id);
    }

    private void apply(ProjectEntity p, ProjectForm f) {
        require(f.name(), "Tên phim");
        require(f.sourceDir(), "Thư mục output của Omni-Flash-Pipeline");
        require(f.imageDir(), "Folder Ảnh");
        require(f.videoDir(), "Folder Video");
        p.setName(f.name().trim());
        p.setSourceDir(f.sourceDir().trim());
        p.setImageDir(f.imageDir().trim());
        p.setVideoDir(f.videoDir().trim());
        if (f.runMode() != null) p.setRunMode(f.runMode());
        if (f.provider() != null) p.setProvider(f.provider());
        p.setFlowProjectUrl(f.flowProjectUrl() == null || f.flowProjectUrl().isBlank() ? null : f.flowProjectUrl().trim());
        if (f.aspectRatio() != null && !f.aspectRatio().isBlank()) p.setAspectRatio(f.aspectRatio().trim());
        if (f.maxParallel() != null) p.setMaxParallel(Math.max(1, f.maxParallel()));
        if (f.maxAttempts() != null) p.setMaxAttempts(Math.max(1, f.maxAttempts()));
    }

    private ProjectView view(ProjectEntity p) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (JobStatus s : JobStatus.values()) {
            counts.put(s.name(), 0L);
        }
        long total = 0;
        for (Object[] row : jobs.countByStatus(p.getId())) {
            counts.put(((JobStatus) row[0]).name(), (Long) row[1]);
            total += (Long) row[1];
        }
        List<JobEntity> imageJobs = jobs.findByProjectId(p.getId()).stream()
                .filter(j -> j.getKind() == com.aifilm.automation.prompt.PromptKind.IMAGE).toList();
        long imagesDone = imageJobs.stream().filter(j -> j.getStatus() == JobStatus.SUCCESS).count();
        long ready = accounts.findAll().stream()
                .filter(a -> a.getProvider() == p.getProvider())
                .filter(a -> a.getStatus() == AccountStatus.READY || a.getStatus() == AccountStatus.LEASED
                        || (a.getStatus() == AccountStatus.COOLDOWN && a.getCooldownUntil() != null
                            && a.getCooldownUntil().isBefore(java.time.LocalDateTime.now())))
                .count();
        return new ProjectView(p, counts, total, imageJobs.size(), imagesDone, ready);
    }

    private static void require(String v, String label) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(label + " không được để trống");
        }
    }

    /** "Tập 5. Hà - Cupid" → "tap-5-ha-cupid". */
    static String slug(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (n.isEmpty()) {
            throw new IllegalArgumentException("Tên phim phải có ít nhất một chữ hoặc số");
        }
        return n.length() > 80 ? n.substring(0, 80) : n;
    }
}
