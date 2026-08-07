package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.VerdictResult;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 판정 결과 (append-only, R2). {@link Immutable} + setter 부재로 UPDATE 불가.
 * DB 트리거(V7)가 이중 방어한다. 재판정은 supersedes를 채운 새 행으로 쌓는다.
 * evidence_json에 order_rank·baseline 스냅샷을 동결(03 §3.1).
 */
@Entity
@Immutable
@Table(name = "verdicts")
public class Verdict {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trend_item_id", nullable = false)
    private UUID trendItemId;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private VerdictResult result;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "reach_level")
    private ReachLevel reachLevel;      // HIT만

    @Column(name = "score_t", precision = 5, scale = 4)
    private BigDecimal scoreT;

    @Column(name = "judged_at", nullable = false)
    private Instant judgedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", nullable = false, columnDefinition = "jsonb")
    private String evidenceJson;

    /** 재판정 시 이전 판정 참조. null이면 원본(멱등 유니크 인덱스 대상). */
    @Column(name = "supersedes")
    private UUID supersedes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Verdict() {}

    public Verdict(UUID trendItemId, VerdictResult result, ReachLevel reachLevel, BigDecimal scoreT,
                   Instant judgedAt, String evidenceJson, UUID supersedes) {
        this.trendItemId = trendItemId;
        this.result = result;
        this.reachLevel = reachLevel;
        this.scoreT = scoreT;
        this.judgedAt = judgedAt;
        this.evidenceJson = evidenceJson;
        this.supersedes = supersedes;
    }

    public UUID getId() { return id; }
    public UUID getTrendItemId() { return trendItemId; }
    public VerdictResult getResult() { return result; }
    public ReachLevel getReachLevel() { return reachLevel; }
    public BigDecimal getScoreT() { return scoreT; }
    public Instant getJudgedAt() { return judgedAt; }
    public String getEvidenceJson() { return evidenceJson; }
    public UUID getSupersedes() { return supersedes; }
}
