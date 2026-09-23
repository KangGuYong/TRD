package kr.trendstage.apiadmin.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.trendstage.apiadmin.web.AdminConflictException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.repo.ApprovalRequestRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.ApprovalStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 2인 승인의 입구(P7, SP3 §2). 원장을 100점 넘게 움직이는 관리자 작업·계정 생성·역할 변경은 여기서 승인 요청이 된다.
 * 실행은 ApprovalService가 승인 1회 뒤 해당 ApprovalExecutor에 맡긴다. 호출 측 트랜잭션 안에서 쓴다.
 */
@Service
public class ApprovalGate {

    /** 원장 변동 절댓값 합이 이 값을 넘으면 승인 대상(K3). 정확히 100은 통과. */
    public static final BigDecimal THRESHOLD = new BigDecimal("100");

    static final List<String> VERDICT_CHANGE_TYPES = List.of(ActionType.VERDICT_REJUDGE.name(), ActionType.ITEM_VOID.name());

    private final ApprovalRequestRepository approvals;
    private final AdminAccountRepository accounts;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public ApprovalGate(ApprovalRequestRepository approvals, AdminAccountRepository accounts,
                        AuditLogService auditLogService, ObjectMapper objectMapper) {
        this.approvals = approvals;
        this.accounts = accounts;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    public boolean exceeds(BigDecimal amount) {
        return amount.abs().compareTo(THRESHOLD) > 0;
    }

    public boolean hasPendingVerdictChange(UUID itemId) {
        return approvals.existsByTargetRefAndStatusAndActionTypeIn(itemId, ApprovalStatus.PENDING, VERDICT_CHANGE_TYPES);
    }

    public long eligibleApproverCount(UUID excludingAccountId, Instant now) {
        return accounts.countEligibleApprovers(excludingAccountId, now);
    }

    /** 승인 요청 생성. 같은 항목에 대기 중인 재판정·VOID가 있으면 DB 부분 UNIQUE가 막고 409(K5). */
    public ApprovalRequest request(ActionType type, UUID targetRef, Map<String, String> payload,
                                   UUID requesterId, AdminRole requesterRole) {
        ApprovalRequest saved;
        try {
            saved = approvals.saveAndFlush(new ApprovalRequest(type.name(), targetRef, requesterId, write(payload)));
        } catch (DataIntegrityViolationException e) {
            throw new AdminConflictException("approval-pending", "이 항목에 대기 중인 승인 요청이 있습니다 — 승인·반려 후 다시 시도하세요");
        }
        Map<String, Object> detail = new HashMap<>(payload);
        detail.put("approvalRequestId", saved.getId().toString());
        auditLogService.record(requesterId, requesterRole, "APPROVAL_REQUEST", type.name(), targetRef, detail);
        return saved;
    }

    public Map<String, String> payload(ApprovalRequest request) {
        try {
            return objectMapper.readValue(request.getPayload(), new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("승인 요청 payload 파싱 실패: " + request.getId(), e);
        }
    }

    private String write(Map<String, String> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("승인 요청 payload 직렬화 실패", e);
        }
    }
}
