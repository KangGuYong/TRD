package kr.trendstage.apiadmin.auth;

import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 관리자 로그인 검증. 2FA는 검사하지 않는다(미구현, SP3).
 * 무차별 대입 방어: 연속 MAX_FAILED_LOGINS회 실패 시 LOCK_DURATION 동안 잠금(V27).
 * 기준값은 도메인 파라미터가 아니므로 파라미터 스튜디오 대상이 아니다.
 */
@Service
public class AdminAccountService {

    static final int MAX_FAILED_LOGINS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    static final String LOCKED_MESSAGE = "로그인 시도 횟수를 초과했습니다. 잠시 후 다시 시도하세요";
    private static final String INVALID_MESSAGE = "아이디 또는 비밀번호가 올바르지 않습니다";

    private final AdminAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public AdminAccountService(AdminAccountRepository repository, PasswordEncoder passwordEncoder,
                                AuditLogService auditLogService, Clock clock) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /**
     * noRollbackFor: 실패는 RuntimeException으로 알리지만, 실패 카운터·잠금 시각 증가는 커밋돼야 한다.
     * 이게 없으면 카운터가 예외와 함께 롤백되어 잠금이 절대 걸리지 않는다.
     * (감사 로그는 AuditLogService가 REQUIRES_NEW라 원래부터 영향 없음.)
     */
    @Transactional(noRollbackFor = {InvalidCredentialsException.class, AccountLockedException.class})
    public AdminAccount authenticate(String loginId, String rawPassword) {
        AdminAccount account = repository.findByLoginIdForUpdate(loginId)
                .orElseThrow(() -> new InvalidCredentialsException(INVALID_MESSAGE));

        if (account.getDisabledAt() != null) {
            throw new AccountDisabledException("비활성화된 계정입니다");
        }

        if (account.getActivatedAt() == null) {
            throw new AccountPendingException("승인 대기 중인 계정입니다 — 다른 ADMIN의 승인 후 로그인할 수 있습니다");
        }

        Instant now = clock.instant();

        // 잠긴 동안에는 비밀번호를 검사하지 않는다 — 맞았는지 틀렸는지 알려주지 않기 위해.
        if (account.isLocked(now)) {
            throw new AccountLockedException(LOCKED_MESSAGE);
        }

        if (!passwordEncoder.matches(rawPassword, account.getPasswordHash())) {
            auditLogService.record(null, null, "LOGIN_FAILED", "ADMIN_ACCOUNT", account.getId(),
                    Map.of("loginId", loginId));
            boolean lockedNow = account.recordFailedLogin(now, MAX_FAILED_LOGINS, LOCK_DURATION);
            if (lockedNow) {
                auditLogService.record(null, null, "LOGIN_LOCKED", "ADMIN_ACCOUNT", account.getId(),
                        Map.of("loginId", loginId,
                               "failedCount", MAX_FAILED_LOGINS,
                               "lockedUntil", account.getLockedUntil().toString()));
            }
            throw new InvalidCredentialsException(INVALID_MESSAGE);
        }

        account.recordLogin(now);
        return account;
    }
}
