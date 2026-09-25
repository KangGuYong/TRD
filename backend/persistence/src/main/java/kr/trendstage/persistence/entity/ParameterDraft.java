package kr.trendstage.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import kr.trendstage.domain.params.DraftValues;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.signal.IndependenceMode;
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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "backtest_result", columnDefinition = "jsonb")
    private String backtestResult;

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
    public String getBacktestResult() { return backtestResult; }
    public ApplyMode getApplyMode() { return applyMode; }
    public UUID getApprovalId() { return approvalId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAppliedAt() { return appliedAt; }

    /** 값을 고치면 이전 시뮬레이션·백테스트는 더 이상 이 값의 근거가 아니다(SP4 §5.1). */
    public void updatePayload(String payload) {
        this.payload = payload;
        this.simResult = null;
        this.backtestResult = null;
    }

    public void recordSimResult(String simResult) {
        this.simResult = simResult;
    }

    public void recordBacktestResult(String backtestResult) {
        this.backtestResult = backtestResult;
    }

    public void moveToReview(UUID approvalId) {
        this.status = ParamStatus.REVIEW;
        this.approvalId = approvalId;
    }

    /** 2인 승인 2/2 실행(ApprovalExecutor) 시 호출 — 이후 CurrentParameterSetResolver가 이 값을 읽는다. */
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
     * payload(JSONB) → 편집 값 9개. 없는 키는 기본값(중립) — SP4 이전 payload(submitterTarget·hitThreshold만)도
     * 그대로 읽는다(S8). submitterTarget은 targetFloor의 옛 이름이다.
     */
    public DraftValues toDraftValues(ObjectMapper objectMapper) {
        try {
            JsonNode n = objectMapper.readTree(payload);
            DraftValues d = DraftValues.of(ParameterSet.defaults());
            int floor = n.hasNonNull("targetFloor") ? n.get("targetFloor").asInt()
                    : n.hasNonNull("submitterTarget") ? n.get("submitterTarget").asInt() : d.targetFloor();
            return new DraftValues(floor,
                    dbl(n, "targetRatio", d.targetRatio()),
                    integer(n, "activeWindowDays", d.activeWindowDays()),
                    dbl(n, "hitThreshold", d.hitThreshold()),
                    dbl(n, "persistenceFloor", d.persistenceFloor()),
                    integer(n, "persistenceFullDays", d.persistenceFullDays()),
                    dbl(n, "diversityFloor", d.diversityFloor()),
                    integer(n, "diversityFullPlatforms", d.diversityFullPlatforms()),
                    n.hasNonNull("independenceMode") ? IndependenceMode.valueOf(n.get("independenceMode").asText())
                            : d.independenceMode());
        } catch (Exception e) {
            throw new IllegalStateException("payload 파싱 실패: draft=" + id, e);
        }
    }

    public ParameterSet toParameterSet(ObjectMapper objectMapper) {
        return toDraftValues(objectMapper).toParameterSet();
    }

    private static double dbl(JsonNode n, String key, double fallback) {
        return n.hasNonNull(key) ? n.get(key).asDouble() : fallback;
    }

    private static int integer(JsonNode n, String key, int fallback) {
        return n.hasNonNull(key) ? n.get(key).asInt() : fallback;
    }
}
