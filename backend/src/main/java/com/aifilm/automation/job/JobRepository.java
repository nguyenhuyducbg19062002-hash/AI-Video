package com.aifilm.automation.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<JobEntity, Long> {

    /**
     * Job PENDING kế tiếp của một project: ảnh trước video, theo STT.
     * Video chỉ được chọn khi MỌI ảnh trong "Reference IDs" của nó đã SUCCESS (worker upload chính file trong Folder Ảnh).
     */
    @Query(value = """
            SELECT j.* FROM jobs j
            JOIN prompts p ON p.id = j.prompt_id
            WHERE j.project_id = :projectId AND j.status = 'PENDING'
              AND NOT EXISTS (
                  SELECT 1 FROM prompt_references r
                  LEFT JOIN prompts ip ON ip.project_id = p.project_id AND ip.kind = 'IMAGE'
                                      AND ip.external_id = r.ref_external_id
                  LEFT JOIN jobs ij ON ij.prompt_id = ip.id
                  WHERE r.video_prompt_id = p.id AND (ij.id IS NULL OR ij.status <> 'SUCCESS'))
            ORDER BY CASE p.kind WHEN 'IMAGE' THEN 0 ELSE 1 END, p.seq_no
            LIMIT 1""", nativeQuery = true)
    Optional<JobEntity> findNextRunnable(@Param("projectId") long projectId);

    long countByProjectIdAndStatus(Long projectId, JobStatus status);

    List<JobEntity> findByProjectId(Long projectId);

    List<JobEntity> findByProjectIdAndStatus(Long projectId, JobStatus status);

    List<JobEntity> findByStatus(JobStatus status);

    List<JobEntity> findByStatusAndHeartbeatAtBefore(JobStatus status, LocalDateTime limit);

    Optional<JobEntity> findByPromptId(Long promptId);

    List<JobEntity> findByPromptIdIn(Collection<Long> promptIds);

    @Query("""
            select j from JobEntity j, com.aifilm.automation.prompt.PromptEntity p
            where p.id = j.promptId and p.projectId = :projectId
              and p.kind = com.aifilm.automation.prompt.PromptKind.IMAGE and p.externalId = :externalId""")
    Optional<JobEntity> findImageJob(@Param("projectId") Long projectId, @Param("externalId") String externalId);

    @Query("select j.status, count(j) from JobEntity j where j.projectId = :projectId group by j.status")
    List<Object[]> countByStatus(@Param("projectId") Long projectId);

    /**
     * Heartbeat: cập nhật thẳng bằng câu UPDATE (không tăng @Version) để không đụng độ optimistic lock với
     * lần báo kết quả của cùng worker chạy song song trên luồng khác.
     */
    @Modifying
    @Query("""
            update JobEntity j set j.heartbeatAt = :now
            where j.id = :id and j.workerId = :workerId and j.status = com.aifilm.automation.job.JobStatus.GENERATING""")
    int touchHeartbeat(@Param("id") Long id, @Param("workerId") String workerId, @Param("now") LocalDateTime now);

    @Query("select j.cancelRequested from JobEntity j where j.id = :id")
    Optional<Boolean> isCancelRequested(@Param("id") Long id);

    @Modifying
    @Query("delete from JobEntity j where j.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
