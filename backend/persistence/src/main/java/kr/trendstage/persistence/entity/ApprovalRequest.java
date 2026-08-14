package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import kr.trendstage.persistence.type.ApprovalStatus;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 2인 승인 요청. DB CHECK 제약(approver1_not_requester 등)이 요청자≠승인자를 강제한다(V6).
 * 이번 스코프는 요청 생성(PENDING)까지만 — 실제 승인 클릭 플로우는 범위 밖.
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

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ApprovalStatus status = ApprovalStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ApprovalRequest() {}

    public ApprovalRequest(String actionType, UUID targetRef, UUID requestedBy, String payload) {
        this.actionType = actionType;
        this.targetRef = targetRef;
        this.requestedBy = requestedBy;
        this.payload = payload;
    }

    public UUID getId() { return id; }
    public String getActionType() { return actionType; }
    public UUID getTargetRef() { return targetRef; }
    public UUID getRequestedBy() { return requestedBy; }
    public ApprovalStatus getStatus() { return status; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
}
