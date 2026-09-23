package kr.trendstage.apiadmin.approval;

import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.repo.ApprovalRequestRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.ApprovalStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 2인 승인 = 요청자 + 승인자 1명(SP3 K1). 승인 1회로 실행기까지 한 트랜잭션에서 끝난다(PENDING → EXECUTED).
 * 승인자 자격(K2): 활성 ADMIN · 요청자 아님 · 승인권 유예(approver_since) 경과.
 */
@Service
public class ApprovalService {

    private static final DateTimeFormatter KST = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final ApprovalRequestRepository approvals;
    private final AdminAccountRepository accounts;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final Map<String, ApprovalExecutor> executors;

    public ApprovalService(ApprovalRequestRepository approvals, AdminAccountRepository accounts,
                           List<ApprovalExecutor> executorBeans, AuditLogService auditLogService, Clock clock) {
        this.approvals = approvals;
        this.accounts = accounts;
        this.auditLogService = auditLogService;
        this.clock = clock;
        this.executors = executorBeans.stream().collect(Collectors.toMap(ApprovalExecutor::actionType, e -> e));
    }

    public List<ApprovalRequest> listPending() {
        return approvals.findByStatusInOrderByCreatedAtAsc(List.of(ApprovalStatus.PENDING));
    }

    public String describe(ApprovalRequest request) {
        ApprovalExecutor executor = executors.get(request.getActionType());
        return executor == null ? request.getActionType() : executor.describe(request);
    }

    @Transactional
    public ApprovalRequest approve(UUID actorId, AdminRole actorRole, UUID id) {
        ApprovalRequest req = requirePending(id);
        if (req.getRequestedBy().equals(actorId)) {
            throw new ApprovalConflictException("요청자 본인은 승인할 수 없습니다");
        }
        AdminAccount approver = accounts.findById(actorId)
                .orElseThrow(() -> new ApprovalConflictException("승인자 계정을 찾을 수 없습니다"));
        Instant now = clock.instant();
        if (!approver.canApproveAt(now)) {
            boolean inGrace = approver.getRole() == AdminRole.ADMIN && approver.isActive();
            throw new ApprovalConflictException(inGrace
                    ? "승인권은 %s부터 생깁니다".formatted(KST.format(approver.getApproverSince()))
                    : "승인 권한이 없는 계정입니다");
        }

        req.approve(actorId, now);
        Map<String, String> executed = executorFor(req.getActionType()).execute(req);
        req.markExecuted();

        auditLogService.record(actorId, actorRole, "APPROVAL_APPROVE", req.getActionType(), req.getTargetRef(),
                Map.of("approvalRequestId", id.toString()));
        Map<String, Object> detail = new HashMap<>(executed);
        detail.put("approvalRequestId", id.toString());
        auditLogService.record(actorId, actorRole, "APPROVAL_EXECUTE", req.getActionType(), req.getTargetRef(), detail);
        return req;
    }

    @Transactional
    public ApprovalRequest reject(UUID actorId, AdminRole actorRole, UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AdminValidationException("반려 사유는 필수입니다");
        }
        ApprovalRequest req = requirePending(id);
        req.reject(clock.instant());
        ApprovalExecutor executor = executors.get(req.getActionType());
        if (executor != null) executor.onReject(req);
        auditLogService.record(actorId, actorRole, "APPROVAL_REJECT", req.getActionType(), req.getTargetRef(),
                Map.of("approvalRequestId", id.toString(), "reason", reason));
        return req;
    }

    private ApprovalRequest requirePending(UUID id) {
        ApprovalRequest req = approvals.findByIdForUpdate(id)
                .orElseThrow(() -> new AdminValidationException("존재하지 않는 승인 요청입니다"));
        if (req.getStatus() != ApprovalStatus.PENDING) {
            throw new ApprovalConflictException("이미 처리된 요청입니다: " + req.getStatus());
        }
        return req;
    }

    private ApprovalExecutor executorFor(String actionType) {
        ApprovalExecutor executor = executors.get(actionType);
        if (executor == null) {
            throw new IllegalStateException("등록된 실행기가 없는 actionType: " + actionType);
        }
        return executor;
    }
}
