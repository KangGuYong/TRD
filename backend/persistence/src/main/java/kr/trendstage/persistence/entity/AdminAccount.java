package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import kr.trendstage.persistence.type.AdminRole;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
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

    /** 신규 계정·ADMIN 승격 후 승인권이 생기기까지의 유예(P8, SP3 K2). */
    public static final Duration APPROVER_GRACE = Duration.ofDays(7);

    @Column(name = "approver_since", nullable = false)
    private Instant approverSince;

    /** NULL = 계정 생성 승인 대기 — 로그인 불가(SP3 §5). */
    @Column(name = "activated_at")
    private Instant activatedAt;

    /** 마지막 재활성화 시각 — sla_watch 미접속 판단에 들어간다(재활성화 직후 다시 꺼지지 않게). */
    @Column(name = "last_enabled_at")
    private Instant lastEnabledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AdminAccount() {}

    public AdminAccount(String loginId, String displayName, AdminRole role, String passwordHash) {
        this.loginId = loginId;
        this.displayName = displayName;
        this.role = role;
        this.passwordHash = passwordHash;
        // 부트스트랩·시딩 계정: 만들자마자 활성, 승인권 유예 없음(K6). 콘솔 생성은 createdByConsole을 쓴다.
        this.approverSince = this.createdAt;
        this.activatedAt = this.createdAt;
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
    public void enable(Instant at) {
        this.disabledAt = null;
        this.lastEnabledAt = at;
    }

    /** 마지막 활동 시각 — 로그인·활성화(승인)·재활성화·생성 중 가장 늦은 것(sla_watch 90일 미접속 기준). */
    public Instant lastActiveAt() {
        return java.util.stream.Stream.of(lastLoginAt, activatedAt, lastEnabledAt, createdAt)
                .filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(createdAt);
    }

    /** ADM-500: 최초 시딩 등록 시 자동 생성된 합성 유저 계정과 연결. */
    public void linkSeedUser(UUID seedUserId) { this.seedUserId = seedUserId; }

    /** 콘솔에서 만드는 계정 — 승인권은 now + 7일부터. activate=false면 승인 대기(로그인 불가). */
    public static AdminAccount createdByConsole(String loginId, String displayName, AdminRole role, String passwordHash,
                                                Instant now, boolean activate) {
        AdminAccount a = new AdminAccount(loginId, displayName, role, passwordHash);
        a.createdAt = now;
        a.approverSince = now.plus(APPROVER_GRACE);
        a.activatedAt = activate ? now : null;
        return a;
    }

    /** 계정 생성 승인(ACCOUNT_CREATE). 이미 활성이면 그대로. */
    public void activate(Instant at) {
        if (this.activatedAt == null) this.activatedAt = at;
    }

    /** 역할 변경(ACCOUNT_ROLE_CHANGE). ADMIN으로 승격되면 승인권은 now + 7일부터(P8). */
    public void changeRole(AdminRole next, Instant now) {
        if (next == AdminRole.ADMIN && this.role != AdminRole.ADMIN) {
            this.approverSince = now.plus(APPROVER_GRACE);
        }
        this.role = next;
    }

    public void changePasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    /** 로그인·세션 유지 가능: 비활성화되지 않았고 승인 대기도 아님. */
    public boolean isActive() { return disabledAt == null && activatedAt != null; }

    /** 2인 승인의 승인자 자격(K2) — 요청자와 다른지는 호출 측이 본다. */
    public boolean canApproveAt(Instant now) {
        return role == AdminRole.ADMIN && isActive() && !now.isBefore(approverSince);
    }

    public Instant getApproverSince() { return approverSince; }
    public Instant getActivatedAt() { return activatedAt; }
    public Instant getLastEnabledAt() { return lastEnabledAt; }

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
