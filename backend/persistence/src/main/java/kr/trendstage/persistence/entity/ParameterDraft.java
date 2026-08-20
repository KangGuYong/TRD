package kr.trendstage.persistence.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.persistence.type.ApplyMode;
import kr.trendstage.persistence.type.ParamStatus;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * ADM-600 파라미터 드래프트. append-only 아님(V7 트리거 대상 외) — 시뮬 전까지 값을 계속 고친다.
 * payload/simResult는 JSON 문자열로 저장(엔진이 읽는 ParameterSet과 별개 — toParameterSet()으로 변환).
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

    @Column(name = "applied_at")
    private Instant appliedAt;

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
    public Instant getAppliedAt() { return appliedAt; }

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

    /** 2인 승인 2/2 실행(ApprovalExecutor) 시 호출 — 이후 ParameterSetProvider가 이 값을 읽는다. */
    public void markApplied(Instant appliedAt) {
        this.status = ParamStatus.APPLIED;
        this.appliedAt = appliedAt;
    }

    /** 승인 요청 반려 시 호출 — 재수정 가능하도록 되돌린다. */
    public void returnToDraft() {
        this.status = ParamStatus.DRAFT;
        this.approvalId = null;
    }

    /**
     * payload(JSONB)를 ParameterSet으로 변환. submitterTarget/hitThreshold 2개만 드래프트가
     * 편집하고 나머지 14개 필드는 defaults() 고정값을 쓴다(ADM-600 스코프, ParamSimulation과 동일 가정).
     */
    public ParameterSet toParameterSet(ObjectMapper objectMapper) {
        try {
            var node = objectMapper.readTree(payload);
            ParameterSet d = ParameterSet.defaults();
            return new ParameterSet(
                    node.get("submitterTarget").asInt(), node.get("hitThreshold").asDouble(),
                    d.bandL2, d.bandL3, d.bandL4,
                    d.mL1, d.mL2, d.mL3, d.mL4,
                    d.wRank1, d.wRank2, d.wRank3, d.wRankRest,
                    d.halflifeDays, d.tiAlpha, d.tiBeta);
        } catch (Exception e) {
            throw new IllegalStateException("payload 파싱 실패: draft=" + id, e);
        }
    }
}
