package kr.trendstage.apiadmin.approval;

import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.repo.ApprovalRequestRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.ApprovalStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ApprovalService {

    private final ApprovalRequestRepository approvals;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final Map<String, ApprovalExecutor> executors;

    public ApprovalService(ApprovalRequestRepository approvals, List<ApprovalExecutor> executorBeans,
                            AuditLogService auditLogService, Clock clock) {
        this.approvals = approvals;
        this.auditLogService = auditLogService;
        this.clock = clock;
        this.executors = executorBeans.stream()
                .collect(Collectors.toMap(ApprovalExecutor::actionType, e -> e));
    }

    public List<ApprovalRequest> listPending() {
        return approvals.findByStatusInOrderByCreatedAtAsc(List.of(ApprovalStatus.PENDING, ApprovalStatus.PARTIAL));
    }

    @Transactional
    public ApprovalRequest approve(UUID actorId, AdminRole actorRole, UUID id) {
        ApprovalRequest req = requirePending(id);
        if (req.getRequestedBy().equals(actorId)) {
            throw new ApprovalConflictException("요청자 본인은 승인할 수 없습니다");
        }

        if (req.getStatus() == ApprovalStatus.PENDING) {
            req.approveFirst(actorId);
            auditLogService.record(actorId, actorRole, "APPROVAL_APPROVE", req.getActionType(), req.getTargetRef(),
                    Map.of("approvalRequestId", id.toString(), "stage", "1/2"));
            return req;
        }

        // PARTIAL
        if (req.getApprover1().equals(actorId)) {
            throw new ApprovalConflictException("이미 승인했습니다");
        }
        req.approveSecond(actorId, clock.instant());
        auditLogService.record(actorId, actorRole, "APPROVAL_APPROVE", req.getActionType(), req.getTargetRef(),
                Map.of("approvalRequestId", id.toString(), "stage", "2/2"));

        executorFor(req.getActionType()).execute(req);
        req.markExecuted();
        auditLogService.record(actorId, actorRole, "APPROVAL_EXECUTE", req.getActionType(), req.getTargetRef(),
                Map.of("approvalRequestId", id.toString()));
        return req;
    }

    @Transactional
    public ApprovalRequest reject(UUID actorId, AdminRole actorRole, UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AdminValidationException("반려 사유는 필수입니다");
        }
        ApprovalRequest req = requirePending(id);
        req.reject(clock.instant());
        if (executors.containsKey(req.getActionType())) {
            executors.get(req.getActionType()).onReject(req);
        }
        auditLogService.record(actorId, actorRole, "APPROVAL_REJECT", req.getActionType(), req.getTargetRef(),
                Map.of("approvalRequestId", id.toString(), "reason", reason));
        return req;
    }

    private ApprovalRequest requirePending(UUID id) {
        ApprovalRequest req = approvals.findByIdForUpdate(id)
                .orElseThrow(() -> new AdminValidationException("존재하지 않는 승인 요청입니다"));
        if (req.getStatus() != ApprovalStatus.PENDING && req.getStatus() != ApprovalStatus.PARTIAL) {
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
