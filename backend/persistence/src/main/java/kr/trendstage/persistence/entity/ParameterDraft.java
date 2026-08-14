package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import kr.trendstage.persistence.type.ApplyMode;
import kr.trendstage.persistence.type.ParamStatus;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * ADM-600 파라미터 드래프트. append-only 아님(V7 트리거 대상 외) — 시뮬 전까지 값을 계속 고친다.
 * payload/simResult는 JSON 문자열로 저장(엔진이 읽는 ParameterSet과 별개 — 컨트롤러/서비스에서 변환).
 */
@Entity
@Table(name = "parameter_drafts")
public class ParameterDraft {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ParamStatus status = ParamStatus.DRAFT;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sim_result", columnDefinition = "jsonb")
    private String simResult;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "apply_mode", nullable = false)
    private ApplyMode applyMode = ApplyMode.SCHEDULED;

    @Column(name = "approval_id")
    private UUID approvalId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Long version;

    protected ParameterDraft() {}

    public ParameterDraft(UUID authorId, String payload) {
        this.authorId = authorId;
        this.payload = payload;
    }

    public UUID getId() { return id; }
    public UUID getAuthorId() { return authorId; }
    public ParamStatus getStatus() { return status; }
    public String getPayload() { return payload; }
    public String getSimResult() { return simResult; }
    public ApplyMode getApplyMode() { return applyMode; }
    public UUID getApprovalId() { return approvalId; }
    public Instant getCreatedAt() { return createdAt; }

    public void updatePayload(String payload) {
        this.payload = payload;
        this.simResult = null;
    }

    public void recordSimResult(String simResult) {
        this.simResult = simResult;
    }

    public void moveToReview(UUID approvalId) {
        this.status = ParamStatus.REVIEW;
        this.approvalId = approvalId;
    }
}
