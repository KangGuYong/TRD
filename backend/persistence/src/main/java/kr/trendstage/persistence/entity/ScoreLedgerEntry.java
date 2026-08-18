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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ScoreLedgerEntry() {}

    /** 판정 발생 원장(HIT/MISS/VOID). */
    public static ScoreLedgerEntry ofVerdict(UUID userId, UUID submissionId, UUID verdictId,
                                             LedgerKind kind, BigDecimal delta, String reason) {
        ScoreLedgerEntry e = new ScoreLedgerEntry();
        e.userId = userId; e.submissionId = submissionId; e.verdictId = verdictId;
        e.kind = kind; e.delta = delta; e.reason = reason;
        return e;
    }

    /** 상쇄 원장(ADJ). 사유 필수, 승인자 표시. */
    public static ScoreLedgerEntry adjustment(UUID userId, BigDecimal delta, String reason,
                                              UUID approvedBy, UUID approvalId) {
        ScoreLedgerEntry e = new ScoreLedgerEntry();
        e.userId = userId; e.kind = LedgerKind.ADJ; e.delta = delta; e.reason = reason;
        e.approvedBy = approvedBy; e.approvalId = approvalId;
        return e;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getSubmissionId() { return submissionId; }
    public LedgerKind getKind() { return kind; }
    public BigDecimal getDelta() { return delta; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
