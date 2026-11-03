package com.aifilm.automation.account;

import com.aifilm.automation.project.Provider;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
public class AccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Provider provider = Provider.FLOW_BROWSER;

    /** FLOW_BROWSER: thư mục profile Edge (cookie nằm ở đây, không lưu vào DB). */
    @Column(length = 1000)
    private String profileDir;

    /** VERTEX_API: đường dẫn file service-account JSON (không lưu nội dung). */
    @Column(length = 1000)
    private String credentialFile;

    private String gcpProject;
    private String gcpLocation;
    private String proxy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status = AccountStatus.NEED_LOGIN;

    private int priority = 100;
    private LocalDateTime cooldownUntil;
    private Long leasedJobId;
    private int consecutiveFailures;

    @Column(length = 2000)
    private String lastErrorMessage;

    @Column(length = 1000)
    private String notes;

    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private int version;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void lease(long jobId, LocalDateTime now) {
        status = AccountStatus.LEASED;
        leasedJobId = jobId;
        lastUsedAt = now;
        cooldownUntil = null;
    }

    public void releaseOk() {
        status = AccountStatus.READY;
        leasedJobId = null;
        consecutiveFailures = 0;
    }

    /** Lỗi tạm thời: 3 lần liên tiếp thì cho nghỉ 15 phút để không đốt hết job vào một tài khoản hỏng. */
    public void releaseAfterError(String msg) {
        leasedJobId = null;
        lastErrorMessage = msg;
        if (++consecutiveFailures >= 3) {
            status = AccountStatus.COOLDOWN;
            cooldownUntil = LocalDateTime.now().plusMinutes(15);
            consecutiveFailures = 0;
        } else {
            status = AccountStatus.READY;
        }
    }

    public void cooldown(LocalDateTime until, String msg) {
        status = AccountStatus.COOLDOWN;
        cooldownUntil = until;
        leasedJobId = null;
        lastErrorMessage = msg;
    }

    public void needLogin(String msg) {
        status = AccountStatus.NEED_LOGIN;
        leasedJobId = null;
        lastErrorMessage = msg;
    }
}
