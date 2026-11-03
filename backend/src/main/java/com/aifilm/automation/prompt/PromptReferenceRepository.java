package com.aifilm.automation.prompt;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PromptReferenceRepository extends JpaRepository<PromptReferenceEntity, Long> {

    List<PromptReferenceEntity> findByVideoPromptIdOrderByOrdinalAsc(Long videoPromptId);

    List<PromptReferenceEntity> findByVideoPromptIdIn(Collection<Long> videoPromptIds);

    @Modifying
    @Query("delete from PromptReferenceEntity r where r.videoPromptId = :videoPromptId")
    void deleteByVideoPromptId(@Param("videoPromptId") Long videoPromptId);

    @Modifying
    @Query("delete from PromptReferenceEntity r where r.videoPromptId in (select p.id from PromptEntity p where p.projectId = :projectId)")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
