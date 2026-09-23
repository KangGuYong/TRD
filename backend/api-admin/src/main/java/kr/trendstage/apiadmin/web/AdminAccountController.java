package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.account.AdminAccountManagementService;
import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.apiadmin.auth.SelfModificationException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * 생성·역할 변경은 AdminAccountManagementService가 승인 요청으로 만든다(SP3 §5).
 */
@RestController
@RequestMapping("/admin/accounts")
public class AdminAccountController {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final AdminAccountRepository repository;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final AdminAccountManagementService management;

    public AdminAccountController(AdminAccountRepository repository, AuditLogService auditLogService, Clock clock,
                                   AdminAccountManagementService management) {
        this.repository = repository;
        this.auditLogService = auditLogService;
        this.clock = clock;
        this.management = management;
    }

    public record AdminAccountSummary(String id, String loginId, String displayName, String role,
                                       String lastLoginAt, String disabledAt, String createdAt,
                                       String activatedAt, String approverSince, boolean pendingApproval) {}
    public record CreateAccountRequest(String loginId, String displayName, String role, String password) {}
    public record CreateResponse(String status, AdminAccountSummary account, String approvalRequestId) {}
    public record RoleChangeRequest(String role, String reason) {}
    public record ApprovalRef(String approvalRequestId) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
    public List<AdminAccountSummary> list() {
        return repository.findAll().stream()
                .map(AdminAccountController::toSummary)
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CreateResponse> create(@RequestBody CreateAccountRequest req, @AuthenticationPrincipal AdminPrincipal actor) {
        AdminAccountManagementService.CreateResult r = management.create(req.loginId(), req.displayName(), req.role(),
                req.password(), actor.id(), actor.role());
        return ResponseEntity.status(r.pending() ? HttpStatus.ACCEPTED : HttpStatus.CREATED)
                .body(new CreateResponse(r.status(), toSummary(r.account()), r.approvalRequestId()));
    }

    @PostMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApprovalRef> changeRole(@PathVariable UUID id, @RequestBody RoleChangeRequest req,
                                                  @AuthenticationPrincipal AdminPrincipal actor) {
        ApprovalRequest r = management.requestRoleChange(id, req.role(), req.reason(), actor.id(), actor.role());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ApprovalRef(r.getId().toString()));
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
        if (account.getActivatedAt() == null) {
            throw new AdminValidationException("승인되지 않은 계정은 활성화할 수 없습니다 — 승인 대기면 승인 요청을 처리하세요(반려된 계정은 다시 만드세요)");
        }
        account.enable(clock.instant());

        auditLogService.record(actor.id(), actor.role(), "ACCOUNT_ENABLE", "ADMIN_ACCOUNT", id, Map.of());
        return toSummary(account);
    }

    private static AdminAccountSummary toSummary(AdminAccount a) {
        return new AdminAccountSummary(
                a.getId().toString(), a.getLoginId(), a.getDisplayName(), a.getRole().name(),
                format(a.getLastLoginAt()), format(a.getDisabledAt()), format(a.getCreatedAt()),
                format(a.getActivatedAt()), format(a.getApproverSince()),
                a.getActivatedAt() == null && a.getDisabledAt() == null);   // 반려(비활성화)된 생성 요청은 대기가 아니다
    }

    private static String format(Instant instant) {
        return instant == null ? null : DISPLAY_FORMAT.format(instant);
    }
}
