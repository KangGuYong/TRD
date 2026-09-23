package kr.trendstage.apiadmin.account;

import kr.trendstage.apiadmin.approval.ActionType;
import kr.trendstage.apiadmin.approval.ApprovalGate;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.apiadmin.auth.DuplicateLoginIdException;
import kr.trendstage.apiadmin.auth.SelfModificationException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.type.AdminRole;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 관리자 계정 통제(P8, SP3 §5). 생성·역할 변경은 승인을 거친다(K1). 요청자 외 승인 자격자가 0명이면
 * 생성만 단독으로 허용한다(부트스트랩 예외, K6). 새 계정·ADMIN 승격의 승인권은 7일 뒤부터.
 */
@Service
public class AdminAccountManagementService {

    static final int MIN_PASSWORD = 8;

    private final AdminAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final ApprovalGate gate;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public AdminAccountManagementService(AdminAccountRepository accounts, PasswordEncoder passwordEncoder, ApprovalGate gate,
                                         AuditLogService auditLogService, Clock clock) {
        this.accounts = accounts; this.passwordEncoder = passwordEncoder; this.gate = gate;
        this.auditLogService = auditLogService; this.clock = clock;
    }

    public record CreateResult(String status, AdminAccount account, String approvalRequestId) {
        public boolean pending() { return "PENDING_APPROVAL".equals(status); }
    }

    @Transactional
    public CreateResult create(String loginId, String displayName, String role, String password, UUID actorId, AdminRole actorRole) {
        if (isBlank(loginId) || isBlank(displayName) || isBlank(password)) {
            throw new AdminValidationException("아이디·이름·비밀번호는 필수입니다");
        }
        requireStrong(password);
        AdminRole parsed = parseRole(role);
        Instant now = clock.instant();
        boolean bootstrap = gate.eligibleApproverCount(actorId, now) == 0;

        AdminAccount created = AdminAccount.createdByConsole(loginId, displayName, parsed,
                passwordEncoder.encode(password), now, bootstrap);
        try {
            accounts.saveAndFlush(created);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateLoginIdException("이미 존재하는 아이디입니다: " + loginId);
        }

        if (bootstrap) {
            auditLogService.record(actorId, actorRole, "ACCOUNT_CREATE_BOOTSTRAP", "ADMIN_ACCOUNT", created.getId(),
                    Map.of("loginId", loginId, "role", parsed.name()));
            return new CreateResult("CREATED_BOOTSTRAP", created, null);
        }
        ApprovalRequest r = gate.request(ActionType.ACCOUNT_CREATE, created.getId(),
                Map.of("loginId", loginId, "displayName", displayName, "role", parsed.name()), actorId, actorRole);
        return new CreateResult("PENDING_APPROVAL", created, r.getId().toString());
    }

    @Transactional
    public ApprovalRequest requestRoleChange(UUID accountId, String role, String reason, UUID actorId, AdminRole actorRole) {
        if (accountId.equals(actorId)) throw new SelfModificationException("본인 역할은 바꿀 수 없습니다");
        if (isBlank(reason)) throw new AdminValidationException("사유는 필수입니다");
        AdminAccount target = accounts.findById(accountId)
                .orElseThrow(() -> new AdminValidationException("존재하지 않는 계정입니다"));
        AdminRole next = parseRole(role);
        if (target.getRole() == next) throw new AdminValidationException("이미 %s 역할입니다".formatted(next));
        return gate.request(ActionType.ACCOUNT_ROLE_CHANGE, accountId,
                Map.of("from", target.getRole().name(), "to", next.name(), "reason", reason), actorId, actorRole);
    }

    @Transactional
    public void changeOwnPassword(UUID actorId, AdminRole actorRole, String current, String next) {
        AdminAccount me = accounts.findById(actorId).orElseThrow(() -> new AdminValidationException("계정을 찾을 수 없습니다"));
        if (current == null || !passwordEncoder.matches(current, me.getPasswordHash())) {
            throw new AdminValidationException("현재 비밀번호가 맞지 않습니다");
        }
        if (isBlank(next)) throw new AdminValidationException("새 비밀번호를 입력하세요");
        requireStrong(next);
        if (passwordEncoder.matches(next, me.getPasswordHash())) {
            throw new AdminValidationException("새 비밀번호가 현재 비밀번호와 같습니다");
        }
        me.changePasswordHash(passwordEncoder.encode(next));
        auditLogService.record(actorId, actorRole, "ACCOUNT_PASSWORD_CHANGE", "ADMIN_ACCOUNT", actorId, Map.of());
    }

    static AdminRole parseRole(String raw) {
        try {
            return AdminRole.valueOf(raw);
        } catch (Exception e) {
            throw new AdminValidationException("알 수 없는 역할입니다: " + raw);
        }
    }

    private static void requireStrong(String password) {
        if (password.length() < MIN_PASSWORD) throw new AdminValidationException("비밀번호는 8자 이상이어야 합니다");
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
