package com.aifilm.automation.job;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService review;
    private final JobQueryService query;

    @GetMapping("/projects/{projectId}/jobs")
    public List<JobView> list(@PathVariable long projectId, @RequestParam(required = false) JobStatus status) {
        return query.list(projectId, status);
    }

    @GetMapping("/jobs/{id}")
    public JobView get(@PathVariable long id) {
        return query.view(id);
    }

    @GetMapping("/jobs/{id}/history")
    public Map<String, Object> history(@PathVariable long id) {
        return query.history(id);
    }

    @PostMapping("/jobs/{id}/approve")
    public JobView approve(@PathVariable long id) {
        return query.view(review.approve(id).getId());
    }

    /** Duyệt nhiều mục: mỗi job một transaction; mục lỗi không chặn các mục còn lại. */
    @PostMapping("/jobs/approve-batch")
    public Map<String, Object> approveBatch(@RequestBody List<Long> ids) {
        List<Long> ok = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Long id : ids) {
            try {
                review.approve(id);
                ok.add(id);
            } catch (RuntimeException e) {
                errors.add("Job " + id + ": " + e.getMessage());
            }
        }
        return Map.of("approved", ok, "errors", errors);
    }

    @PostMapping("/jobs/{id}/regenerate")
    public JobView regenerate(@PathVariable long id, @RequestBody(required = false) RegenerateRequest r) {
        RegenerateRequest req = r == null ? new RegenerateRequest(null, null) : r;
        return query.view(review.regenerate(id, req.promptText(), req.note()).getId());
    }

    @PostMapping("/jobs/{id}/cancel")
    public JobView cancel(@PathVariable long id) {
        return query.view(review.cancel(id).getId());
    }

    /** Xem trước. Trả Resource để Spring tự hỗ trợ HTTP Range (tua được video). */
    @GetMapping("/jobs/{id}/preview")
    public ResponseEntity<Resource> preview(@PathVariable long id) throws IOException {
        Path p = query.previewPath(id).map(StagingGuard::requireInside).orElse(null);
        if (p == null || !Files.isRegularFile(p)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .contentType(mediaTypeOf(p))
                .body(new FileSystemResource(p));
    }

    /** Không dựa vào registry của Windows (Files.probeContentType có thể trả null cho .mp4). */
    static MediaType mediaTypeOf(Path p) {
        return switch (ReviewService.extensionOf(p.getFileName().toString())) {
            case ".mp4" -> MediaType.parseMediaType("video/mp4");
            case ".webm" -> MediaType.parseMediaType("video/webm");
            case ".png" -> MediaType.IMAGE_PNG;
            case ".jpg", ".jpeg" -> MediaType.IMAGE_JPEG;
            case ".webp" -> MediaType.parseMediaType("image/webp");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    public record RegenerateRequest(String promptText, String note) {}
}
