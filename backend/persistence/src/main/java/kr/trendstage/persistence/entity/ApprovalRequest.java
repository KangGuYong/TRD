package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import kr.trendstage.persistence.type.ApprovalStatus;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 2인 승인 요청. DB CHECK 제약(approver1_not_requester 등)이 요청자≠승인자를 강제한다(V6).
 * 상태 전이는 ApprovalService(api-admin)가 행 잠금(findByIdForUpdate) 하에서만 호출한다.
 */
@Entity
@Table(name = "approval_requests")
public class ApprovalRequest {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "action_type", nullable = false, length = 40)
    private String actionType;

    @Column(name = "target_ref", nullable = false)
    private UUID targetRef;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "approver_1")
    private UUID approver1;

    @Column(name = "approver_2")
    private UUID approver2;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ApprovalStatus status = ApprovalStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected ApprovalRequest() {}

    public ApprovalRequest(String actionType, UUID targetRef, UUID requestedBy, String payload) {
        this.actionType = actionType;
        this.targetRef = targetRef;
        this.requestedBy = requestedBy;
        this.payload = payload;
    }

    /** 승인(SP3 K1 — 요청자 + 승인자 1명). 자격 검사는 ApprovalService가 먼저 한다. */
    public void approve(UUID approverId, Instant now) {
        this.approver1 = approverId;
        this.status = ApprovalStatus.APPROVED;
        this.resolvedAt = now;
    }

    /** 2/2 승인 후 대상 작업 실행까지 성공했을 때. */
    public void markExecuted() {
        this.status = ApprovalStatus.EXECUTED;
    }

    public void reject(Instant now) {
        this.status = ApprovalStatus.REJECTED;
        this.resolvedAt = now;
    }

    public UUID getId() { return id; }
    public String getActionType() { return actionType; }
    public UUID getTargetRef() { return targetRef; }
    public UUID getRequestedBy() { return requestedBy; }
    public UUID getApprover1() { return approver1; }
    public UUID getApprover2() { return approver2; }
    public ApprovalStatus getStatus() { return status; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
