package com.aifilm.automation.project;

import com.aifilm.automation.job.*;
import com.aifilm.automation.prompt.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Kho ảnh của phim (project_assets): ảnh có sẵn người dùng đưa vào, ảnh thêm bằng nút "+", ảnh đã duyệt.
 * Mọi file đều được chép vào Folder Ảnh của phim với tên = ID, để worker upload đúng file và người dùng dễ tìm.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssetService {

    static final Set<String> IMAGE_EXT = Set.of(".png", ".jpg", ".jpeg", ".webp");

    private final ProjectRepository projects;
    private final ProjectAssetRepository assets;
    private final PromptRepository prompts;
    private final PromptReferenceRepository references;
    private final JobRepository jobs;
    private final JobTransitions transitions;

    public record AssetView(String externalId, String source, String fileName) {}

    public record FolderImportResult(int added, int replaced, int notReferenced, int skippedJobs, List<String> notReferencedNames) {}

    /** Ghi / cập nhật một ảnh trong kho. */
    @Transactional
    public ProjectAssetEntity upsert(long projectId, String externalId, Path file, String source) {
        ProjectAssetEntity a = assets.findByProjectIdAndExternalId(projectId, externalId)
                .orElseGet(() -> new ProjectAssetEntity(projectId, externalId, file.toString(), source));
        a.setFilePath(file.toString());
        a.setSource(source);
        return assets.save(a);
    }

    @Transactional
    public void remove(long projectId, String externalId) {
        assets.findByProjectIdAndExternalId(projectId, externalId).ifPresent(assets::delete);
    }

    @Transactional(readOnly = true)
    public List<AssetView> list(long projectId) {
        return assets.findByProjectId(projectId).stream()
                .sorted(Comparator.comparing(ProjectAssetEntity::getExternalId))
                .map(a -> new AssetView(a.getExternalId(), a.getSource(), Path.of(a.getFilePath()).getFileName().toString()))
                .toList();
    }

    /** File ảnh của một ID, chỉ khi nó nằm trong Folder Ảnh của phim (chặn đọc file tùy ý trên máy). */
    @Transactional(readOnly = true)
    public Optional<Path> file(long projectId, String externalId) {
        ProjectEntity p = projects.findById(projectId).orElseThrow();
        Path imageDir = Path.of(p.getImageDir()).toAbsolutePath().normalize();
        return assets.findByProjectIdAndExternalId(projectId, externalId)
                .map(a -> Path.of(a.getFilePath()).toAbsolutePath().normalize())
                .filter(f -> f.startsWith(imageDir) && Files.isRegularFile(f));
    }

    /**
     * "Thêm ảnh có sẵn": quét một thư mục (cả thư mục con), lấy ảnh có tên file trùng một Reference ID mà phim cần
     * (ví dụ CHAR-CALEB-1.png), chép vào Folder Ảnh và ghi vào kho. Ảnh có sẵn cho một ID mà app định tạo thì job
     * tạo ảnh đó được hủy (không tốn lượt tạo).
     */
    @Transactional
    public FolderImportResult importFolder(long projectId, String folder) {
        ProjectEntity p = projects.findById(projectId).orElseThrow();
        Path src = Path.of(folder);
        if (!Files.isDirectory(src)) {
            throw new IllegalArgumentException("Thư mục không tồn tại: " + folder);
        }
        Set<String> wanted = new HashSet<>(references.findDistinctRefIds(projectId));
        Map<String, PromptEntity> imagePrompts = new HashMap<>();
        for (PromptEntity pr : prompts.findByProjectId(projectId)) {
            if (pr.getKind() == PromptKind.IMAGE) {
                imagePrompts.put(pr.getExternalId(), pr);
            }
        }
        int added = 0, replaced = 0, skippedJobs = 0;
        List<String> notReferenced = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(src)) {
            files = walk.filter(Files::isRegularFile).filter(f -> IMAGE_EXT.contains(ext(f))).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được thư mục " + folder, e);
        }
        for (Path f : files) {
            String id = baseName(f);
            if (!wanted.contains(id) && !imagePrompts.containsKey(id)) {
                notReferenced.add(f.getFileName().toString());
                continue;
            }
            boolean existed = assets.findByProjectIdAndExternalId(projectId, id).isPresent();
            Path target = copyIntoImageDir(p, f, id);
            upsert(projectId, id, target, ProjectAssetEntity.SOURCE_USER);
            if (existed) replaced++; else added++;
            PromptEntity pr = imagePrompts.get(id);
            if (pr != null) {
                JobEntity job = jobs.findByPromptId(pr.getId()).orElse(null);
                if (job != null && (job.getStatus() == JobStatus.PENDING || job.getStatus() == JobStatus.FAILED)) {
                    transitions.move(job, JobStatus.CANCELLED, null, "đã có ảnh có sẵn " + f.getFileName() + ", không cần tạo");
                    skippedJobs++;
                }
            }
        }
        log.info("Phim {}: thêm {} ảnh có sẵn (thay {}), bỏ qua {} file không thuộc phim", projectId, added, replaced, notReferenced.size());
        return new FolderImportResult(added, replaced, notReferenced.size(), skippedJobs,
                notReferenced.subList(0, Math.min(notReferenced.size(), 20)));
    }

    /**
     * Nút "+" ở một cảnh: thêm một hoặc nhiều ảnh vào cuối danh sách Reference IDs của cảnh đó.
     * ID của ảnh = tên file (đã làm sạch); trùng ID khác file thì thêm hậu tố -2, -3…
     */
    @Transactional
    public List<String> addToPrompt(long promptId, List<MultipartFile> files) {
        PromptEntity prompt = prompts.findById(promptId).orElseThrow();
        ProjectEntity p = projects.findById(prompt.getProjectId()).orElseThrow();
        List<PromptReferenceEntity> current = references.findByVideoPromptIdOrderByOrdinalAsc(promptId);
        int ordinal = current.stream().mapToInt(PromptReferenceEntity::getOrdinal).max().orElse(-1) + 1;
        Set<String> already = new HashSet<>();
        current.forEach(r -> already.add(r.getRefExternalId()));
        List<String> addedIds = new ArrayList<>();
        for (MultipartFile mf : files) {
            String original = Optional.ofNullable(mf.getOriginalFilename()).orElse("anh.png");
            String extension = ext(Path.of(original));
            if (!IMAGE_EXT.contains(extension)) {
                throw new IllegalArgumentException(original + ": chỉ nhận ảnh .png, .jpg, .jpeg, .webp");
            }
            String id = uniqueId(p.getId(), sanitizeId(baseName(Path.of(original))));
            Path target = Path.of(p.getImageDir()).resolve(id + extension);
            try (InputStream in = mf.getInputStream()) {
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException("Không lưu được " + target, e);
            }
            MediaCheck.problem(target).ifPresent(prob -> {
                throw new IllegalArgumentException(original + ": " + prob);
            });
            upsert(p.getId(), id, target, ProjectAssetEntity.SOURCE_USER);
            if (already.add(id)) {
                references.save(new PromptReferenceEntity(promptId, ordinal++, id, "USER"));
            }
            addedIds.add(id);
        }
        return addedIds;
    }

    /** Bỏ một ảnh người dùng đã thêm bằng "+" khỏi một cảnh (ảnh đến từ Excel thì không xóa ở đây). */
    @Transactional
    public void removeFromPrompt(long promptId, String refId) {
        PromptReferenceEntity ref = references.findByVideoPromptIdOrderByOrdinalAsc(promptId).stream()
                .filter(r -> r.getRefExternalId().equals(refId)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("Cảnh không có ảnh " + refId));
        if (!ref.isUserAdded()) {
            throw new IllegalStateException(refId + " đến từ file Excel; muốn bỏ thì sửa Excel rồi nạp lại");
        }
        references.delete(ref);
    }

    /** Dữ liệu cũ (trước khi có kho ảnh): ảnh đã duyệt được đưa vào kho lúc khởi động. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    @Transactional
    public void backfillApprovedImages() {
        int n = 0;
        for (JobEntity j : jobs.findByStatus(JobStatus.SUCCESS)) {
            if (j.getKind() != PromptKind.IMAGE || j.getFinalPath() == null) {
                continue;
            }
            PromptEntity pr = prompts.findById(j.getPromptId()).orElse(null);
            if (pr != null && assets.findByProjectIdAndExternalId(pr.getProjectId(), pr.getExternalId()).isEmpty()) {
                assets.save(new ProjectAssetEntity(pr.getProjectId(), pr.getExternalId(), j.getFinalPath(), ProjectAssetEntity.SOURCE_APPROVED));
                n++;
            }
        }
        if (n > 0) {
            log.info("Đã đưa {} ảnh đã duyệt từ dữ liệu cũ vào kho ảnh", n);
        }
    }

    Path copyIntoImageDir(ProjectEntity p, Path source, String id) {
        Path target = Path.of(p.getImageDir()).resolve(id + ext(source));
        try {
            Files.createDirectories(target.getParent());
            if (!Files.exists(target) || !Files.isSameFile(source, target)) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Không chép được " + source + " vào " + target, e);
        }
        return target;
    }

    private String uniqueId(long projectId, String base) {
        String id = base;
        for (int i = 2; assets.findByProjectIdAndExternalId(projectId, id).isPresent()
                || prompts.findByProjectIdAndKindAndExternalId(projectId, PromptKind.IMAGE, id).isPresent(); i++) {
            id = base + "-" + i;
        }
        return id;
    }

    /** Tên file → ID an toàn làm tên file Windows: chữ, số, '-', '_'. */
    static String sanitizeId(String name) {
        String s = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("(^[-_]+|[-_]+$)", "");
        if (s.isEmpty()) {
            s = "ANH";
        }
        return s.length() > 90 ? s.substring(0, 90) : s;
    }

    static String baseName(Path f) {
        String n = f.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    static String ext(Path f) {
        String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(dot) : "";
    }
}
