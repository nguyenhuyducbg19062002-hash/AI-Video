package com.aifilm.automation.project;

import com.aifilm.automation.account.AccountRepository;
import com.aifilm.automation.account.AccountStatus;
import com.aifilm.automation.job.*;
import com.aifilm.automation.prompt.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private final ProjectRepository projects;
    private final JobRepository jobs;
    private final JobEventRepository events;
    private final PromptRepository prompts;
    private final PromptRevisionRepository revisions;
    private final PromptReferenceRepository references;
    private final AccountRepository accounts;
    private final ProjectAssetRepository assets;
    private final JobTransitions transitions;
    private final OmniImportService importer;
    private final FlowLinkRepository flowLinks;

    public record ProjectForm(String name, String code, String sourceDir, String imageDir, String videoDir,
                              RunMode runMode, Provider provider, String flowProjectUrl, String aspectRatio,
                              Integer maxParallel, Integer maxAttempts, Boolean waitAllImages,
                              Integer videoDuration, String videoQuality, Boolean skipMissingRefs) {}

    /** missingRefs: Reference ID phim cần mà chưa có prompt tạo ảnh và chưa có ảnh trong kho (ảnh gốc cần "Thêm ảnh có sẵn"). */
    public record ProjectView(ProjectEntity project, Map<String, Long> counts, long total, long imagesTotal,
                              long imagesDone, long readyAccounts, long assetsCount, List<String> missingRefs) {}

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
        p.setSelectedOnly(false);   // "Chạy" = chạy cả phim
        verifyFiles(id);   // file đã duyệt mà bị mất/hỏng thì cho chạy lại luôn trong lượt này
        return view(p);
    }

    public record VerifyResult(int checked, int missing, int broken, int requeued, List<String> details) {}

    /**
     * Bước 6 của quy trình: tìm ảnh/video ĐÃ DUYỆT mà file trong Folder Ảnh / Folder Video bị mất hoặc hỏng,
     * rồi đưa đúng những mục đó về hàng đợi để tạo lại. Video phụ thuộc vào một ảnh bị tạo lại sẽ tự chờ ảnh mới.
     */
    @Transactional
    public VerifyResult verifyFiles(long id) {
        int checked = 0, missing = 0, broken = 0;
        List<String> details = new ArrayList<>();
        for (JobEntity j : jobs.findByProjectIdAndStatus(id, JobStatus.SUCCESS)) {
            checked++;
            Path file = j.getFinalPath() == null ? null : Path.of(j.getFinalPath());
            Optional<String> problem = MediaCheck.problem(file);
            if (problem.isEmpty()) {
                continue;
            }
            boolean gone = file == null || !Files.exists(file);
            if (gone) missing++; else broken++;
            String name = file == null ? "job " + j.getId() : file.getFileName().toString();
            details.add(name + ": " + problem.get());
            j.setPreviewPath(null);
            j.setFinalPath(null);
            j.setAttempt(0);
            j.setAccountId(null);
            j.setErrorCode(null);
            j.setErrorMessage((gone ? "File đã duyệt bị mất" : "File đã duyệt bị hỏng") + ", tạo lại tự động");
            transitions.move(j, JobStatus.PENDING, null, "kiểm tra file: " + problem.get() + " → tạo lại");
            if (j.getKind() == PromptKind.IMAGE) {
                // rút ảnh khỏi kho: panel / video dùng ảnh này chờ ảnh mới
                prompts.findById(j.getPromptId()).flatMap(pr -> assets.findByProjectIdAndExternalId(id, pr.getExternalId()))
                        .filter(a -> ProjectAssetEntity.SOURCE_APPROVED.equals(a.getSource()))
                        .ifPresent(assets::delete);
            }
        }
        int requeued = missing + broken;
        // Ảnh có sẵn (người dùng đưa vào) bị mất/hỏng: không tạo lại được, rút khỏi kho và báo để người dùng thêm lại
        for (ProjectAssetEntity a : assets.findByProjectId(id)) {
            if (!ProjectAssetEntity.SOURCE_USER.equals(a.getSource())) {
                continue;
            }
            checked++;
            Optional<String> problem = MediaCheck.problem(Path.of(a.getFilePath()));
            if (problem.isPresent()) {
                if (Files.exists(Path.of(a.getFilePath()))) broken++; else missing++;
                details.add(a.getExternalId() + " (ảnh có sẵn): " + problem.get() + " → cần thêm lại");
                assets.delete(a);
            }
        }
        if (missing + broken > 0) {
            log.warn("Phim {}: {} file mất, {} file hỏng", id, missing, broken);
        }
        return new VerifyResult(checked, missing, broken, requeued, details.subList(0, Math.min(details.size(), 20)));
    }

    /** Quét định kỳ các phim đang chạy (người dùng có thể xóa/ghi đè file trong folder bất cứ lúc nào). */
    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    @Transactional
    public void verifyRunningProjects() {
        for (ProjectEntity p : projects.findByStatusOrderByIdAsc(ProjectStatus.RUNNING)) {
            verifyFiles(p.getId());
        }
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
        Set<String> provided = new HashSet<>();
        assets.findByProjectId(id).stream().filter(a -> ProjectAssetEntity.SOURCE_USER.equals(a.getSource()))
                .forEach(a -> provided.add(a.getExternalId()));
        for (JobStatus s : List.of(JobStatus.FAILED, JobStatus.CANCELLED)) {
            for (JobEntity j : jobs.findByProjectIdAndStatus(id, s)) {
                if (j.getKind() == PromptKind.IMAGE && prompts.findById(j.getPromptId())
                        .map(pr -> provided.contains(pr.getExternalId())).orElse(false)) {
                    continue;   // ảnh này đã có sẵn (người dùng đưa vào): không tạo lại
                }
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
        flowLinks.deleteByProjectId(id);
        assets.deleteByProjectId(id);   // chỉ xóa bản ghi kho; file ảnh/video trên đĩa giữ nguyên
        jobs.deleteByProjectId(id);
        references.deleteByProjectId(id);
        revisions.deleteByProjectId(id);
        prompts.deleteByProjectId(id);
        projects.deleteById(id);
    }

    private void apply(ProjectEntity p, ProjectForm f) {
        require(f.name(), "Tên phim");
        require(f.imageDir(), "Folder Ảnh");
        require(f.videoDir(), "Folder Video");
        p.setName(f.name().trim());
        // Thư mục nguồn Omni là tùy chọn: có thể nạp từng file Excel bằng "Thêm Excel ảnh" / "Thêm Excel video"
        p.setSourceDir(f.sourceDir() == null ? "" : f.sourceDir().trim());
        p.setImageDir(f.imageDir().trim());
        p.setVideoDir(f.videoDir().trim());
        // thư mục chưa có thì tạo luôn, người dùng không phải tự tay tạo rồi gán
        ensureDir(p.getImageDir(), "Folder Ảnh");
        ensureDir(p.getVideoDir(), "Folder Video");
        if (f.runMode() != null) p.setRunMode(f.runMode());
        if (f.provider() != null) p.setProvider(f.provider());
        p.setFlowProjectUrl(f.flowProjectUrl() == null || f.flowProjectUrl().isBlank() ? null : f.flowProjectUrl().trim());
        if (f.aspectRatio() != null && !f.aspectRatio().isBlank()) p.setAspectRatio(f.aspectRatio().trim());
        if (f.maxParallel() != null) p.setMaxParallel(Math.max(1, f.maxParallel()));
        if (f.maxAttempts() != null) p.setMaxAttempts(Math.max(1, f.maxAttempts()));
        if (f.waitAllImages() != null) p.setWaitAllImages(f.waitAllImages());
        if (f.skipMissingRefs() != null) p.setSkipMissingRefs(f.skipMissingRefs());
        p.setVideoDuration(f.videoDuration() == null || f.videoDuration() <= 0 ? null : f.videoDuration());
        if (f.videoQuality() != null) {
            String q = f.videoQuality().trim().toLowerCase(Locale.ROOT);
            if (!List.of("480p", "720p", "1080p").contains(q)) {
                throw new IllegalArgumentException("Chất lượng video phải là 480p, 720p hoặc 1080p");
            }
            p.setVideoQuality(q);
        }
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
        // ảnh đã hủy (thường vì đã có ảnh có sẵn) không chặn giai đoạn video
        long imagesDone = imageJobs.stream().filter(j -> j.getStatus() == JobStatus.SUCCESS || j.getStatus() == JobStatus.CANCELLED).count();
        long ready = accounts.findAll().stream()
                .filter(a -> a.getProvider() == p.getProvider())
                .filter(a -> a.getStatus() == AccountStatus.READY || a.getStatus() == AccountStatus.LEASED
                        || (a.getStatus() == AccountStatus.COOLDOWN && a.getCooldownUntil() != null
                            && a.getCooldownUntil().isBefore(java.time.LocalDateTime.now())))
                .count();
        return new ProjectView(p, counts, total, imageJobs.size(), imagesDone, ready,
                assets.findByProjectId(p.getId()).size(), importer.missingRefs(p.getId()));
    }

    private static void ensureDir(String dir, String label) {
        try {
            Path path = Path.of(dir);
            if (!Files.isDirectory(path)) {
                Files.createDirectories(path);
                log.info("Đã tạo {}: {}", label, path);
            }
        } catch (java.io.IOException | java.nio.file.InvalidPathException | UnsupportedOperationException e) {
            throw new IllegalArgumentException("Không tạo được " + label + " \"" + dir + "\": " + e.getMessage());
        }
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
