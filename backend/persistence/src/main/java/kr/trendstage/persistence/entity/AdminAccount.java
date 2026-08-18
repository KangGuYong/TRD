package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import kr.trendstage.persistence.type.AdminRole;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** 관리자 계정 (admin_accounts). 앱 users와 분리. 2FA는 컬럼만 유지, 이번 단계 미검사. */
@Entity
@Table(name = "admin_accounts")
public class AdminAccount {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "login_id", nullable = false, unique = true, length = 60)
    private String loginId;

    @Column(name = "display_name", nullable = false, length = 60)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private AdminRole role;

    @Column(name = "twofa_enabled", nullable = false)
    private boolean twofaEnabled = false;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "seed_user_id")
    private UUID seedUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AdminAccount() {}

    public AdminAccount(String loginId, String displayName, AdminRole role, String passwordHash) {
        this.loginId = loginId;
        this.displayName = displayName;
        this.role = role;
        this.passwordHash = passwordHash;
    }

    public void recordLogin(Instant at) { this.lastLoginAt = at; }
    public void disable(Instant at) { this.disabledAt = at; }
    public void enable() { this.disabledAt = null; }

    /** ADM-500: 최초 시딩 등록 시 자동 생성된 합성 유저 계정과 연결. */
    public void linkSeedUser(UUID seedUserId) { this.seedUserId = seedUserId; }

    public UUID getId() { return id; }
    public String getLoginId() { return loginId; }
    public String getDisplayName() { return displayName; }
    public AdminRole getRole() { return role; }
    public boolean isTwofaEnabled() { return twofaEnabled; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getDisabledAt() { return disabledAt; }
    public UUID getSeedUserId() { return seedUserId; }
    public Instant getCreatedAt() { return createdAt; }
}
