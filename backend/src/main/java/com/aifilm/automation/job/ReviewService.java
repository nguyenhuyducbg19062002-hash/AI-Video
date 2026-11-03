package com.aifilm.automation.job;

import com.aifilm.automation.project.ProjectEntity;
import com.aifilm.automation.project.ProjectRepository;
import com.aifilm.automation.prompt.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewService {

    private final JobRepository jobs;
    private final PromptRepository prompts;
    private final PromptRevisionRepository revisions;
    private final ProjectRepository projects;
    private final JobTransitions transitions;

    /**
     * Duyệt: chép file staging vào Folder Ảnh / Folder Video, tên file = external_id.
     * Lỗi ghi file → transaction rollback, job vẫn AWAITING_REVIEW, người dùng thấy thông báo lỗi.
     */
    @Transactional
    public JobEntity approve(long jobId) {
        JobEntity job = jobs.findById(jobId).orElseThrow();
        if (job.getStatus() != JobStatus.AWAITING_REVIEW) {
            throw new IllegalStateException("Job " + jobId + " đang ở " + job.getStatus() + ", không ở trạng thái chờ duyệt");
        }
        PromptEntity prompt = prompts.findById(job.getPromptId()).orElseThrow();
        ProjectEntity project = projects.findById(job.getProjectId()).orElseThrow();

        transitions.move(job, JobStatus.APPROVED, job.getAccountId(), "người dùng duyệt");
        job.setReviewedAt(LocalDateTime.now());
        transitions.move(job, JobStatus.DOWNLOADING, job.getAccountId(), "chép vào folder đích");

        Path staged = StagingGuard.requireInside(Path.of(job.getPreviewPath()));
        Path dir = Path.of(prompt.getKind() == PromptKind.IMAGE ? project.getImageDir() : project.getVideoDir());
        Path target = dir.resolve(finalFileName(project, prompt, extensionOf(staged.getFileName().toString())));
        try {
            Files.createDirectories(dir);
            Path part = target.resolveSibling(target.getFileName() + ".part");
            Files.copy(staged, part, StandardCopyOption.REPLACE_EXISTING);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Không ghi được " + target + ": " + e.getMessage(), e);
        }

        job.setFinalPath(target.toString());
        job.setCompletedAt(LocalDateTime.now());
        transitions.move(job, JobStatus.SUCCESS, job.getAccountId(), "đã lưu " + target.getFileName());
        log.info("Job {} đã duyệt → {}", jobId, target);
        return job;
    }

    /**
     * Sửa prompt (nếu có chữ mới) rồi tạo lại. Một prompt mới = một revision mới; lịch sử không bị ghi đè.
     * Dùng cho cả "Thử lại" một job FAILED/CANCELLED (không gửi chữ mới).
     */
    @Transactional
    public JobEntity regenerate(long jobId, String newPromptText, String note) {
        JobEntity job = jobs.findById(jobId).orElseThrow();
        if (job.getStatus() == JobStatus.GENERATING) {
            throw new IllegalStateException("Job " + jobId + " đang chạy; hãy hủy trước rồi tạo lại");
        }
        PromptEntity prompt = prompts.findById(job.getPromptId()).orElseThrow();
        String current = revisions.findById(prompt.getCurrentRevisionId()).map(PromptRevisionEntity::getPromptText).orElse("");

        if (newPromptText != null && !newPromptText.isBlank() && !newPromptText.equals(current)) {
            int nextNo = revisions.maxRevisionNo(prompt.getId()) + 1;
            PromptRevisionEntity rev = revisions.save(new PromptRevisionEntity(
                    prompt.getId(), nextNo, newPromptText, PromptRevisionEntity.SOURCE_USER, note));
            prompt.setCurrentRevisionId(rev.getId());
        }
        job.setPreviewPath(null);   // file staging cũ được giữ lại tới khi dọn theo staging_retention_days
        job.setRemoteRef(null);
        job.setAttempt(0);
        job.setAccountId(null);
        job.setReviewNote(note);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        job.setCancelRequested(false);
        transitions.move(job, JobStatus.PENDING, null, note == null || note.isBlank() ? "tạo lại" : "tạo lại: " + note);
        return job;
    }

    @Transactional
    public JobEntity cancel(long jobId) {
        JobEntity job = jobs.findById(jobId).orElseThrow();
        if (job.getStatus() == JobStatus.GENERATING) {
            job.setCancelRequested(true);   // worker dừng ở heartbeat kế tiếp và báo CANCELLED
        } else {
            transitions.move(job, JobStatus.CANCELLED, null, "người dùng hủy");
        }
        return job;
    }

    /**
     * Tên file cuối cùng:
     *  - ảnh:  đúng ID của Omni, ví dụ CHAR-DOMINIC-1.png (Omni và worker tìm ảnh theo đúng tên này);
     *  - video: mã phim + STT, ví dụ tap-5-ha-cupid_042.mp4 (mã phim là slug không dấu: gộp video nhiều phim
     *    vào một thư mục vẫn không trùng tên).
     */
    public static String finalFileName(ProjectEntity project, PromptEntity prompt, String ext) {
        return prompt.getKind() == PromptKind.IMAGE
                ? prompt.getExternalId() + ext
                : project.getCode() + "_" + prompt.getExternalId() + ext;
    }

    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
    }
}
