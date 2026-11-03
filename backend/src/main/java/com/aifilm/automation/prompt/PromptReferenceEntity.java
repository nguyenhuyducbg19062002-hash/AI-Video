package com.aifilm.automation.prompt;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Video → ảnh reference, theo đúng thứ tự cột "Reference IDs" (= thứ tự upload lên Flow). */
@Entity
@Table(name = "prompt_references", uniqueConstraints = @UniqueConstraint(columnNames = {"video_prompt_id", "ordinal"}))
@Getter
@Setter
@NoArgsConstructor
public class PromptReferenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long videoPromptId;

    private int ordinal;

    @Column(nullable = false, length = 100)
    private String refExternalId;

    public PromptReferenceEntity(Long videoPromptId, int ordinal, String refExternalId) {
        this.videoPromptId = videoPromptId;
        this.ordinal = ordinal;
        this.refExternalId = refExternalId;
    }
}
