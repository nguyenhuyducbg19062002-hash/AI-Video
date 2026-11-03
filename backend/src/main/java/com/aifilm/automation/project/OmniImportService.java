package com.aifilm.automation.project;

import com.aifilm.automation.job.JobEntity;
import com.aifilm.automation.job.JobRepository;
import com.aifilm.automation.job.JobStatus;
import com.aifilm.automation.job.JobTransitions;
import com.aifilm.automation.prompt.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Nạp 2 file của Omni-Flash-Pipeline (output/<phim>/): reference-prompts.xlsx và all-video-prompts.xlsx.
 * Đọc theo đúng TÊN cột pipeline xuất ra, không đoán theo từ khóa (bản cũ bị cột "Chú thích ID nhân vật" đè lên cột ảnh).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OmniImportService {

    public static final String REF_FILE = "reference-prompts.xlsx";
    public static final String VIDEO_FILE = "all-video-prompts.xlsx";

    static final String REF_STT = "STT";
    static final String REF_ID = "Reference ID";
    static final String REF_PROMPT = "Prompt tạo reference";
    static final String SEG_STT = "STT segment";
    static final String SEG_PROMPT = "Nội dung prompt của segment";
    static final String SEG_REFS = "Reference IDs";
    static final String SEG_SUMMARY = "Tóm tắt segment (tiếng Việt)";

    /** ID dùng làm tên file trên Windows: chỉ chữ, số, gạch nối, gạch dưới. */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$");

    private final ProjectRepository projects;
    private final PromptRepository prompts;
    private final PromptRevisionRepository revisions;
    private final PromptReferenceRepository references;
    private final JobRepository jobs;
    private final JobTransitions transitions;
    private final TransactionTemplate tx;

    public record ImportResult(int images, int videos, int added, int updated, int unchanged, int requeued) {}

    record ImageRow(int stt, String id, String prompt) {}

    record VideoRow(int stt, String prompt, List<String> refs, String summary) {}

    public ImportResult importProject(long projectId) throws IOException {
        ProjectEntity project = projects.findById(projectId).orElseThrow();
        Path dir = Path.of(project.getSourceDir());
        Path refFile = dir.resolve(REF_FILE);
        Path videoFile = dir.resolve(VIDEO_FILE);
        if (!Files.isRegularFile(refFile) || !Files.isRegularFile(videoFile)) {
            throw new IllegalArgumentException("Thư mục " + dir + " phải chứa " + REF_FILE + " và " + VIDEO_FILE
                    + " (thư mục output/<phim>/ của Omni-Flash-Pipeline)");
        }

        // 1. Đọc và kiểm tra toàn bộ trước khi ghi gì vào DB
        List<ImageRow> images = readImages(refFile);
        List<VideoRow> videos = readVideos(videoFile);
        validate(images, videos);

        // 2. Ghi trong một transaction
        return tx.execute(status -> write(project, images, videos));
    }

    private ImportResult write(ProjectEntity project, List<ImageRow> images, List<VideoRow> videos) {
        int[] counts = new int[4];   // added, updated, unchanged, requeued
        for (ImageRow r : images) {
            upsert(project, PromptKind.IMAGE, r.id(), r.stt(), r.prompt(), null, List.of(), counts);
        }
        int maxStt = videos.stream().mapToInt(VideoRow::stt).max().orElse(0);
        int width = Math.max(3, String.valueOf(maxStt).length());
        for (VideoRow r : videos) {
            String extId = String.format("%0" + width + "d", r.stt());
            upsert(project, PromptKind.VIDEO, extId, r.stt(), r.prompt(), r.summary(), r.refs(), counts);
        }
        log.info("Import {}: {} ảnh, {} video (mới {}, đổi {}, giữ nguyên {})",
                project.getCode(), images.size(), videos.size(), counts[0], counts[1], counts[2]);
        return new ImportResult(images.size(), videos.size(), counts[0], counts[1], counts[2], counts[3]);
    }

    private void upsert(ProjectEntity project, PromptKind kind, String extId, int seq, String text, String summary,
                        List<String> refs, int[] counts) {
        PromptEntity prompt = prompts.findByProjectIdAndKindAndExternalId(project.getId(), kind, extId).orElse(null);
        if (prompt == null) {
            prompt = new PromptEntity();
            prompt.setProjectId(project.getId());
            prompt.setKind(kind);
            prompt.setExternalId(extId);
            prompt.setSeqNo(seq);
            prompt.setSummaryVi(summary);
            prompts.save(prompt);
            PromptRevisionEntity rev = revisions.save(new PromptRevisionEntity(prompt.getId(), 1, text, PromptRevisionEntity.SOURCE_IMPORT, null));
            prompt.setCurrentRevisionId(rev.getId());
            jobs.save(new JobEntity(project.getId(), prompt.getId(), kind));
            saveRefs(prompt, refs);
            counts[0]++;
            return;
        }

        prompt.setSeqNo(seq);
        prompt.setSummaryVi(summary);
        saveRefs(prompt, refs);
        // So với bản IMPORT gần nhất (không phải bản hiện tại): nguồn không đổi thì giữ nguyên chỗ người dùng đã sửa tay.
        String lastImported = revisions.findFirstByPromptIdAndSourceOrderByRevisionNoDesc(prompt.getId(), PromptRevisionEntity.SOURCE_IMPORT)
                .map(PromptRevisionEntity::getPromptText).orElse("");
        if (lastImported.equals(text)) {
            counts[2]++;
            return;
        }
        int nextNo = revisions.maxRevisionNo(prompt.getId()) + 1;
        PromptRevisionEntity rev = revisions.save(new PromptRevisionEntity(prompt.getId(), nextNo, text,
                PromptRevisionEntity.SOURCE_IMPORT, "import lại: prompt đổi"));
        prompt.setCurrentRevisionId(rev.getId());
        counts[1]++;

        // Prompt đổi ở nguồn: kết quả cũ không còn khớp, đưa job về hàng đợi (trừ job đang chạy: nó sẽ được tạo lại sau khi xong)
        JobEntity job = jobs.findByPromptId(prompt.getId()).orElseThrow();
        if (job.getStatus() != JobStatus.PENDING && job.getStatus() != JobStatus.GENERATING && job.getStatus().canMoveTo(JobStatus.PENDING)) {
            job.setPreviewPath(null);
            job.setAttempt(0);
            job.setAccountId(null);
            transitions.move(job, JobStatus.PENDING, null, "import lại: prompt đổi ở nguồn");
            counts[3]++;
        }
    }

    private void saveRefs(PromptEntity prompt, List<String> refs) {
        if (prompt.getKind() != PromptKind.VIDEO) {
            return;
        }
        references.deleteByVideoPromptId(prompt.getId());
        references.flush();
        for (int i = 0; i < refs.size(); i++) {
            references.save(new PromptReferenceEntity(prompt.getId(), i, refs.get(i)));
        }
    }

    private void validate(List<ImageRow> images, List<VideoRow> videos) {
        List<String> errors = new ArrayList<>();
        Set<String> imageIds = new HashSet<>();
        for (ImageRow r : images) {
            if (!SAFE_ID.matcher(r.id()).matches()) {
                errors.add(REF_FILE + " dòng STT " + r.stt() + ": ID \"" + r.id() + "\" không dùng làm tên file được");
            }
            if (!imageIds.add(r.id())) {
                errors.add(REF_FILE + ": ID trùng " + r.id());
            }
        }
        Set<Integer> stts = new HashSet<>();
        for (VideoRow r : videos) {
            if (!stts.add(r.stt())) {
                errors.add(VIDEO_FILE + ": STT segment trùng " + r.stt());
            }
            for (String ref : r.refs()) {
                if (!imageIds.contains(ref)) {
                    errors.add(VIDEO_FILE + " segment " + r.stt() + ": Reference ID " + ref + " không có trong " + REF_FILE);
                }
            }
        }
        if (images.isEmpty() && videos.isEmpty()) {
            errors.add("Hai file không có dòng dữ liệu nào");
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", errors.subList(0, Math.min(errors.size(), 20)))
                    + (errors.size() > 20 ? "\n… và " + (errors.size() - 20) + " lỗi khác" : ""));
        }
    }

    private List<ImageRow> readImages(Path file) throws IOException {
        List<ImageRow> out = new ArrayList<>();
        readSheet(file, (h, row, f) -> {
            String id = text(row, col(h, REF_ID, file), f);
            String prompt = text(row, col(h, REF_PROMPT, file), f);
            if (id.isEmpty() && prompt.isEmpty()) {
                return;
            }
            if (id.isEmpty() || prompt.isEmpty()) {
                throw new IllegalArgumentException(file.getFileName() + " dòng " + (row.getRowNum() + 1) + ": thiếu ID hoặc prompt");
            }
            out.add(new ImageRow(intOf(text(row, col(h, REF_STT, file), f), row, file), id, prompt));
        });
        return out;
    }

    private List<VideoRow> readVideos(Path file) throws IOException {
        List<VideoRow> out = new ArrayList<>();
        readSheet(file, (h, row, f) -> {
            String prompt = text(row, col(h, SEG_PROMPT, file), f);
            String sttText = text(row, col(h, SEG_STT, file), f);
            if (prompt.isEmpty() && sttText.isEmpty()) {
                return;
            }
            if (prompt.isEmpty()) {
                throw new IllegalArgumentException(file.getFileName() + " dòng " + (row.getRowNum() + 1) + ": thiếu prompt");
            }
            List<String> refs = new ArrayList<>();
            for (String part : text(row, col(h, SEG_REFS, file), f).split(";")) {
                if (!part.isBlank()) {
                    refs.add(part.trim());
                }
            }
            Integer summaryCol = h.get(SEG_SUMMARY);   // cột tùy chọn (pipeline chạy với --no-summaries thì không có)
            String summary = summaryCol == null ? null : text(row, summaryCol, f);
            out.add(new VideoRow(intOf(sttText, row, file), prompt, refs, summary == null || summary.isEmpty() ? null : summary));
        });
        return out;
    }

    interface RowHandler {
        void handle(Map<String, Integer> header, Row row, DataFormatter f);
    }

    private static void readSheet(Path file, RowHandler handler) throws IOException {
        DataFormatter f = new DataFormatter();
        try (InputStream in = Files.newInputStream(file); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new IllegalArgumentException(file.getFileName() + ": không có dòng tiêu đề");
            }
            Map<String, Integer> header = new HashMap<>();
            for (Cell c : headerRow) {
                header.put(f.formatCellValue(c).trim(), c.getColumnIndex());
            }
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row != null) {
                    handler.handle(header, row, f);
                }
            }
        }
    }

    private static int col(Map<String, Integer> header, String name, Path file) {
        Integer i = header.get(name);
        if (i == null) {
            throw new IllegalArgumentException(file.getFileName() + ": thiếu cột \"" + name
                    + "\". File này có phải bản xuất của Omni-Flash-Pipeline không? Các cột đang có: " + header.keySet());
        }
        return i;
    }

    private static String text(Row row, int col, DataFormatter f) {
        Cell c = row.getCell(col);
        return c == null ? "" : f.formatCellValue(c).trim();
    }

    private static int intOf(String s, Row row, Path file) {
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(file.getFileName() + " dòng " + (row.getRowNum() + 1) + ": STT \"" + s + "\" không phải số");
        }
    }
}
