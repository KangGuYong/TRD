package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import kr.trendstage.persistence.type.LedgerKind;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 점수 원장 (append-only, R2). 정정은 이 엔티티의 ADJ 행을 새로 INSERT 할 뿐,
 * 기존 행 수정은 불가(setter 부재 + @Immutable + DB 트리거 V7).
 */
@Entity
@Immutable
@Table(name = "score_ledger")
public class ScoreLedgerEntry {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "submission_id")
    private UUID submissionId;     // ADJ 행은 null 가능

    @Column(name = "verdict_id")
    private UUID verdictId;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private LedgerKind kind;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal delta;

    @Column(nullable = false)
    private String reason;

    @Column(name = "approved_by")
    private UUID approvedBy;       // ADJ·100점 초과 2인 승인자

    @Column(name = "approval_id")
    private UUID approvalId;

    /** 이 행을 기록할 때의 반감기(일). AS 조회 감쇠에 쓴다 — 파라미터가 바뀌어도 과거 행은 그대로(J1). */
    @Column(name = "halflife_days", nullable = false)
    private int halflifeDays;

    /** 감쇠 기준 시각. 판정 행은 판정 시각, 재판정·VOID 차액은 원 판정 시각(J1). */
    @Column(name = "decay_anchor_at", nullable = false)
    private Instant decayAnchorAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ScoreLedgerEntry() {}

    /** 판정 발생 원장(HIT/MISS). delta는 원값 — 감쇠는 AS 조회 때(J1). */
    public static ScoreLedgerEntry ofVerdict(UUID userId, UUID submissionId, UUID verdictId, LedgerKind kind,
                                             BigDecimal delta, String reason, int halflifeDays, Instant decayAnchorAt) {
        ScoreLedgerEntry e = new ScoreLedgerEntry();
        e.userId = userId; e.submissionId = submissionId; e.verdictId = verdictId;
        e.kind = kind; e.delta = delta; e.reason = reason;
        e.halflifeDays = halflifeDays; e.decayAnchorAt = decayAnchorAt;
        return e;
    }

    /** 재판정·항목 VOID의 제보 단위 차액(ADJ). 원 판정과 같은 감쇠 기준이어야 AS에서 정확히 상쇄된다. */
    public static ScoreLedgerEntry verdictAdjustment(UUID userId, UUID submissionId, UUID verdictId, BigDecimal delta,
                                                     String reason, int halflifeDays, Instant decayAnchorAt) {
        return ofVerdict(userId, submissionId, verdictId, LedgerKind.ADJ, delta, reason, halflifeDays, decayAnchorAt);
    }

    /** 수동 상쇄 원장(ADJ, ADM-311 — SP3). 사유 필수, 승인자 표시. */
    public static ScoreLedgerEntry adjustment(UUID userId, BigDecimal delta, String reason,
                                              UUID approvedBy, UUID approvalId, int halflifeDays, Instant decayAnchorAt) {
        ScoreLedgerEntry e = new ScoreLedgerEntry();
        e.userId = userId; e.kind = LedgerKind.ADJ; e.delta = delta; e.reason = reason;
        e.approvedBy = approvedBy; e.approvalId = approvalId;
        e.halflifeDays = halflifeDays; e.decayAnchorAt = decayAnchorAt;
        return e;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getSubmissionId() { return submissionId; }
    public UUID getVerdictId() { return verdictId; }
    public int getHalflifeDays() { return halflifeDays; }
    public Instant getDecayAnchorAt() { return decayAnchorAt; }
    public LedgerKind getKind() { return kind; }
    public BigDecimal getDelta() { return delta; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
