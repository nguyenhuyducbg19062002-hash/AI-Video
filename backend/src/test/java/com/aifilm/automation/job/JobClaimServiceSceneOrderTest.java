package com.aifilm.automation.job;

import com.aifilm.automation.project.ProjectEntity;
import com.aifilm.automation.prompt.ProjectAssetEntity;
import com.aifilm.automation.prompt.ProjectAssetRepository;
import com.aifilm.automation.prompt.PromptKind;
import com.aifilm.automation.prompt.PromptReferenceEntity;
import com.aifilm.automation.prompt.PromptReferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Chạy theo cảnh: ảnh có sẵn bỏ qua; cảnh thiếu ảnh thì tạo đúng ảnh đó trước rồi mới tới video. */
class JobClaimServiceSceneOrderTest {

    private JobRepository jobs;
    private ProjectAssetRepository assets;
    private PromptReferenceRepository references;
    private JobClaimService service;
    private ProjectEntity project;

    private JobEntity video1, video2, panel1, charB;

    @BeforeEach
    void setUp() {
        jobs = mock(JobRepository.class);
        assets = mock(ProjectAssetRepository.class);
        references = mock(PromptReferenceRepository.class);
        service = new JobClaimService(jobs, null, null, null, null, assets, references, null);
        project = new ProjectEntity();
        project.setId(1L);

        video1 = job(100L, 10L, PromptKind.VIDEO);   // cảnh 1 cần CHAR-A (có sẵn) + PANEL-1 (chưa có)
        video2 = job(101L, 11L, PromptKind.VIDEO);   // cảnh 2 chỉ cần CHAR-A
        panel1 = job(200L, 20L, PromptKind.IMAGE);   // PANEL-1 tạo từ CHAR-A
        charB = job(201L, 21L, PromptKind.IMAGE);

        when(jobs.findPendingVideoJobsInOrder(1L)).thenReturn(List.of(video1, video2));
        when(jobs.findImageJob(eq(1L), eq("PANEL-1"))).thenReturn(Optional.of(panel1));
        when(assets.findByProjectId(1L)).thenReturn(List.of(asset("CHAR-A")));
        refs(10L, "CHAR-A", "PANEL-1");
        refs(11L, "CHAR-A");
        refs(20L, "CHAR-A");
        refs(21L);
    }

    @Test
    void sceneMissingImage_generatesThatImageFirst() {
        assertEquals(200L, service.nextBySceneOrder(project).orElseThrow().getId());
    }

    @Test
    void imageWaitingReview_sceneWaits_nextSceneRuns() {
        panel1.setStatus(JobStatus.AWAITING_REVIEW);
        assertEquals(101L, service.nextBySceneOrder(project).orElseThrow().getId());
    }

    @Test
    void allImagesPresent_noImageIsGenerated_videoRunsInOrder() {
        when(assets.findByProjectId(1L)).thenReturn(List.of(asset("CHAR-A"), asset("PANEL-1")));
        assertEquals(100L, service.nextBySceneOrder(project).orElseThrow().getId());
    }

    @Test
    void missingImageWithoutPrompt_sceneSkipped() {
        when(jobs.findImageJob(eq(1L), eq("PANEL-1"))).thenReturn(Optional.empty());
        assertEquals(101L, service.nextBySceneOrder(project).orElseThrow().getId());
    }

    @Test
    void panelNeedsMissingBaseImage_generatesBaseImageFirst() {
        refs(20L, "CHAR-B");   // PANEL-1 giờ cần CHAR-B (chưa có) thay vì CHAR-A
        when(jobs.findImageJob(eq(1L), eq("CHAR-B"))).thenReturn(Optional.of(charB));
        assertEquals(201L, service.nextBySceneOrder(project).orElseThrow().getId());
    }

    @Test
    void imageNeededByMostVideos_isGeneratedFirst() {
        JobEntity a = job(301L, 31L, PromptKind.IMAGE), b = job(302L, 32L, PromptKind.IMAGE), c = job(303L, 33L, PromptKind.IMAGE);
        when(assets.findByProjectId(1L)).thenReturn(List.of());
        when(jobs.findPendingVideoJobsInOrder(anyLong())).thenReturn(List.of(video1, video2));
        refs(10L, "A", "B", "C");   // video 1 cần ảnh 1, 2, 3
        refs(11L, "C");             // video 2 chỉ cần ảnh 3
        refs(31L); refs(32L); refs(33L);
        when(jobs.findImageJob(eq(1L), eq("A"))).thenReturn(Optional.of(a));
        when(jobs.findImageJob(eq(1L), eq("B"))).thenReturn(Optional.of(b));
        when(jobs.findImageJob(eq(1L), eq("C"))).thenReturn(Optional.of(c));
        assertEquals(303L, service.nextBySceneOrder(project).orElseThrow().getId());   // ảnh C mở khóa 2 video
    }

    @Test
    void selectedVideosMissingImages_runTheirImagesInsteadOfDeadlocking() {
        // người dùng chọn 2 video; ảnh PANEL-1 của video 1 không được chọn nhưng phải tạo trước thì video mới chạy được
        when(jobs.findSelectedPendingVideoJobsInOrder(1L)).thenReturn(List.of(video1, video2));
        assertEquals(200L, service.imageNeededBySelected(project).orElseThrow().getId());
    }

    @Test
    void selectedVideosWithAllImages_needNoExtraImage() {
        when(jobs.findSelectedPendingVideoJobsInOrder(1L)).thenReturn(List.of(video2));
        assertTrue(service.imageNeededBySelected(project).isEmpty());
    }

    @Test
    void videoWithoutAnyReference_neverRuns() {
        when(jobs.findPendingVideoJobsInOrder(anyLong())).thenReturn(List.of(video2));
        refs(11L);   // cảnh 2 không có ảnh tham chiếu nào: bắt buộc phải có ảnh mới tạo video
        assertTrue(service.nextBySceneOrder(project).isEmpty());
    }

    @Test
    void nothingToDo_whenOnlyImagesPendingAndNoSceneNeedsThem() {
        when(jobs.findPendingVideoJobsInOrder(anyLong())).thenReturn(List.of());
        assertTrue(service.nextBySceneOrder(project).isEmpty());
    }

    private void refs(long promptId, String... ids) {
        java.util.ArrayList<PromptReferenceEntity> list = new java.util.ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            list.add(new PromptReferenceEntity(promptId, i, ids[i], "IMPORT"));
        }
        when(references.findByVideoPromptIdOrderByOrdinalAsc(promptId)).thenReturn(list);
    }

    private static JobEntity job(long id, long promptId, PromptKind kind) {
        JobEntity j = new JobEntity(1L, promptId, kind);
        j.setId(id);
        return j;
    }

    private static ProjectAssetEntity asset(String id) {
        return new ProjectAssetEntity(1L, id, "x.png", ProjectAssetEntity.SOURCE_USER);
    }
}
