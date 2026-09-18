package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import kr.trendstage.persistence.type.AdminRole;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 관리자 계정 (admin_accounts). 앱 users와 분리.
 * twofaEnabled는 "2FA 적용 대상" 표시 — 2FA 검사 자체는 미구현(SP3).
 * 로그인 잠금: 연속 실패가 임계에 닿으면 lockedUntil까지 로그인 거부(V27).
 */
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
    private boolean twofaEnabled = true;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "seed_user_id")
    private UUID seedUserId;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AdminAccount() {}

    public AdminAccount(String loginId, String displayName, AdminRole role, String passwordHash) {
        this.loginId = loginId;
        this.displayName = displayName;
        this.role = role;
        this.passwordHash = passwordHash;
    }

    /** 로그인 성공 — 마지막 로그인 시각 기록, 실패 카운터·잠금 초기화. */
    public void recordLogin(Instant at) {
        this.lastLoginAt = at;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /**
     * 로그인 실패 기록. 임계에 닿으면 now+lockFor까지 잠그고 카운터를 0으로 되돌린다
     * (잠금이 풀린 뒤 다시 maxFailures번의 기회를 준다).
     * @return 이번 실패로 잠금이 걸렸으면 true
     */
    public boolean recordFailedLogin(Instant now, int maxFailures, java.time.Duration lockFor) {
        this.failedLoginCount++;
        if (this.failedLoginCount >= maxFailures) {
            this.lockedUntil = now.plus(lockFor);
            this.failedLoginCount = 0;
            return true;
        }
        return false;
    }

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
    public int getFailedLoginCount() { return failedLoginCount; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getCreatedAt() { return createdAt; }
}
