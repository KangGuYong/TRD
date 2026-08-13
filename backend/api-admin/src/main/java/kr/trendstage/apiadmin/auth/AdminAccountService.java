package kr.trendstage.apiadmin.auth;

import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;

/** 관리자 로그인 검증. 2FA는 이번 단계에서 검사하지 않는다(twofa_enabled 컬럼은 유지, 재검토 필요). */
@Service
public class AdminAccountService {

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

    @Transactional
    public AdminAccount authenticate(String loginId, String rawPassword) {
        AdminAccount account = repository.findByLoginId(loginId)
                .orElseThrow(() -> new InvalidCredentialsException("아이디 또는 비밀번호가 올바르지 않습니다"));

        if (account.getDisabledAt() != null) {
            throw new AccountDisabledException("비활성화된 계정입니다");
        }

        if (!passwordEncoder.matches(rawPassword, account.getPasswordHash())) {
            auditLogService.record(null, null, "LOGIN_FAILED", "ADMIN_ACCOUNT", account.getId(),
                    Map.of("loginId", loginId));
            throw new InvalidCredentialsException("아이디 또는 비밀번호가 올바르지 않습니다");
        }

        account.recordLogin(clock.instant());
        return account;
    }
}
