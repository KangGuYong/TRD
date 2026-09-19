package kr.trendstage.apiadmin.bootstrap;

import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.type.AdminRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 2인 승인 체계가 성립하기 전, 최초 관리자 계정을 환경변수로 자동 생성한다.
 * admin_accounts가 비어있고 ADMIN_BOOTSTRAP_LOGIN_ID/PASSWORD가 둘 다 설정된 경우에만 동작 — 그 외엔 조용히 스킵.
 * 이미 계정이 있으면 재기동해도 아무 일도 하지 않는다(idempotent).
 */
@Component
public class AdminAccountBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountBootstrapRunner.class);

    private final AdminAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    @Value("${ADMIN_BOOTSTRAP_LOGIN_ID:}")
    private String bootstrapLoginId;

    @Value("${ADMIN_BOOTSTRAP_PASSWORD:}")
    private String bootstrapPassword;

    public AdminAccountBootstrapRunner(AdminAccountRepository repository, PasswordEncoder passwordEncoder,
                                        AuditLogService auditLogService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    /**
     * 트랜잭션을 걸지 않는다. 감사 기록({@link AuditLogService#record})은 REQUIRES_NEW라 별도 트랜잭션에서
     * actor_id → admin_accounts FK를 검사하는데, 계정 저장이 바깥 트랜잭션에 묶여 커밋 전이면 그 트랜잭션에서
     * 계정 행이 보이지 않아 FK 위반으로 기동이 실패한다(빈 DB 첫 기동). 계정을 먼저 커밋한 뒤 기록한다.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (bootstrapLoginId.isBlank() || bootstrapPassword.isBlank()) {
            return;
        }
        if (repository.count() > 0) {
            return;
        }

        AdminAccount created = repository.save(new AdminAccount(bootstrapLoginId, bootstrapLoginId,
                AdminRole.ADMIN, passwordEncoder.encode(bootstrapPassword)));
        auditLogService.record(created.getId(), AdminRole.ADMIN, "ADMIN_BOOTSTRAP", "ADMIN_ACCOUNT",
                created.getId(), Map.of("loginId", bootstrapLoginId));

        log.warn("최초 관리자 계정을 부트스트랩했습니다: {}. 온보딩 후 임시 비밀번호를 변경하세요.", bootstrapLoginId);
    }
}
