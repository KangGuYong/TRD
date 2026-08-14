package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.apiadmin.auth.DuplicateLoginIdException;
import kr.trendstage.apiadmin.auth.SelfModificationException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.type.AdminRole;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ADM-800 관리자 계정 관리. 권한 매트릭스(02 §1.1): 조회는 ADMIN/AUDITOR, 생성·비활성화는 ADMIN만.
 * 문서상 역할 부여/회수는 2인 승인 대상이지만 그 승인 워크플로 자체가 아직 없어 이번 스코프에서는
 * ADMIN 단독 처리로 둔다(사용자 확정 사항, 2FA와 같은 급의 의도된 축소 — approval_requests가
 * 붙으면 이 컨트롤러가 승인 요청 생성으로 바뀌어야 한다).
 */
@RestController
@RequestMapping("/admin/accounts")
public class AdminAccountController {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final AdminAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public AdminAccountController(AdminAccountRepository repository, PasswordEncoder passwordEncoder,
                                   AuditLogService auditLogService, Clock clock) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    public record AdminAccountSummary(String id, String loginId, String displayName, String role,
                                       String lastLoginAt, String disabledAt, String createdAt) {}
    public record CreateAccountRequest(String loginId, String displayName, String role, String password) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
    public List<AdminAccountSummary> list() {
        return repository.findAll().stream()
                .map(AdminAccountController::toSummary)
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AdminAccountSummary create(@RequestBody CreateAccountRequest req, @AuthenticationPrincipal AdminPrincipal actor) {
        if (isBlank(req.loginId()) || isBlank(req.displayName()) || isBlank(req.password())) {
            throw new AdminValidationException("아이디·이름·비밀번호는 필수입니다");
        }
        if (req.password().length() < 8) {
            throw new AdminValidationException("비밀번호는 8자 이상이어야 합니다");
        }
        AdminRole role = parseRole(req.role());

        AdminAccount created = new AdminAccount(req.loginId(), req.displayName(), role,
                passwordEncoder.encode(req.password()));
        try {
            repository.saveAndFlush(created);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateLoginIdException("이미 존재하는 아이디입니다: " + req.loginId());
        }

        auditLogService.record(actor.id(), actor.role(), "ACCOUNT_CREATE", "ADMIN_ACCOUNT", created.getId(),
                Map.of("loginId", created.getLoginId(), "role", role.name()));
        return toSummary(created);
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AdminAccountSummary disable(@PathVariable UUID id, @AuthenticationPrincipal AdminPrincipal actor) {
        if (id.equals(actor.id())) {
            throw new SelfModificationException("본인 계정은 비활성화할 수 없습니다");
        }
        AdminAccount account = repository.findById(id)
                .orElseThrow(() -> new AdminValidationException("존재하지 않는 계정입니다"));
        account.disable(clock.instant());

        auditLogService.record(actor.id(), actor.role(), "ACCOUNT_DISABLE", "ADMIN_ACCOUNT", id, Map.of());
        return toSummary(account);
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AdminAccountSummary enable(@PathVariable UUID id, @AuthenticationPrincipal AdminPrincipal actor) {
        AdminAccount account = repository.findById(id)
                .orElseThrow(() -> new AdminValidationException("존재하지 않는 계정입니다"));
        account.enable();

        auditLogService.record(actor.id(), actor.role(), "ACCOUNT_ENABLE", "ADMIN_ACCOUNT", id, Map.of());
        return toSummary(account);
    }

    private static AdminRole parseRole(String raw) {
        try {
            return AdminRole.valueOf(raw);
        } catch (Exception e) {
            throw new AdminValidationException("알 수 없는 역할입니다: " + raw);
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static AdminAccountSummary toSummary(AdminAccount a) {
        return new AdminAccountSummary(
                a.getId().toString(), a.getLoginId(), a.getDisplayName(), a.getRole().name(),
                format(a.getLastLoginAt()), format(a.getDisabledAt()), format(a.getCreatedAt()));
    }

    private static String format(Instant instant) {
        return instant == null ? null : DISPLAY_FORMAT.format(instant);
    }
}
