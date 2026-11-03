package com.aifilm.automation.project;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
public class ProjectEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Khóa phim. Không dùng tên file xlsx: mọi phim của Omni-Flash-Pipeline đều tên all-video-prompts.xlsx. */
    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    /** output/<phim>/ của Omni-Flash-Pipeline. */
    @Column(nullable = false, length = 1000)
    private String sourceDir;

    /** Folder Ảnh do người dùng chọn. */
    @Column(nullable = false, length = 1000)
    private String imageDir;

    /** Folder Video do người dùng chọn. */
    @Column(nullable = false, length = 1000)
    private String videoDir;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunMode runMode = RunMode.BACKGROUND;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Provider provider = Provider.FLOW_BROWSER;

    @Column(length = 1000)
    private String flowProjectUrl;

    private String aspectRatio = "16:9";
    private int maxParallel = 3;
    private int maxAttempts = 3;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectStatus status = ProjectStatus.DRAFT;

    @Column(length = 2000)
    private String pausedReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void pause(String reason) {
        status = ProjectStatus.PAUSED;
        pausedReason = reason;
    }

    public int effectiveParallel() {
        return runMode == RunMode.FOREGROUND ? 1 : Math.max(1, maxParallel);
    }
}
