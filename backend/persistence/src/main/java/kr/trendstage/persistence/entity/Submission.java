package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import kr.trendstage.persistence.type.SubmissionResult;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 개별 제보. order_rank는 저장하지 않는다(파생값, SubmissionOrderRank 뷰).
 * created_at은 order_rank 산정 기준이라 변경 불가(updatable=false).
 */
@Entity
@Table(name = "submissions")
public class Submission {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "trend_item_id", nullable = false)
    private UUID trendItemId;

    @Column(name = "raw_input", nullable = false, length = 120)
    private String rawInput;

    @Column(name = "normalized_key", nullable = false, length = 160)
    private String normalizedKey;

    /** 확신도 10/30/50 (DDL CHECK). */
    @Column(nullable = false)
    private short confidence;

    @Column(name = "source_platform", nullable = false, length = 60)
    private String sourcePlatform;

    @Column(name = "evidence_url", nullable = false)
    private String evidenceUrl;

    @Column(name = "one_line", nullable = false, length = 200)
    private String oneLine;

    @Column(nullable = false)
    private boolean disclosure;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private SubmissionResult result = SubmissionResult.PENDING;

    @Column(name = "is_seed", nullable = false)
    private boolean seed;

    /** VOID된 시각 — 이 시각이 속한 주에 제보권이 반환된다(J4). result = VOID와 함께만 존재(DB CHECK). */
    @Column(name = "voided_at")
    private Instant voidedAt;

    /** 처음 HIT/MISS로 판정된 시각 — TI 180일 창의 기준. 재판정해도 유지한다. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Submission() {}

    public Submission(UUID userId, UUID trendItemId, String rawInput, String normalizedKey,
                      short confidence, String sourcePlatform, String evidenceUrl, String oneLine,
                      boolean disclosure, boolean seed) {
        this.userId = userId;
        this.trendItemId = trendItemId;
        this.rawInput = rawInput;
        this.normalizedKey = normalizedKey;
        this.confidence = confidence;
        this.sourcePlatform = sourcePlatform;
        this.evidenceUrl = evidenceUrl;
        this.oneLine = oneLine;
        this.disclosure = disclosure;
        this.seed = seed;
    }

    /** 생성 시각을 명시(Clock). created_at은 order_rank 기준이라 이후 바뀌지 않는다. */
    public Submission(UUID userId, UUID trendItemId, String rawInput, String normalizedKey,
                      short confidence, String sourcePlatform, String evidenceUrl, String oneLine,
                      boolean disclosure, boolean seed, Instant createdAt) {
        this(userId, trendItemId, rawInput, normalizedKey, confidence, sourcePlatform, evidenceUrl, oneLine,
                disclosure, seed);
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getTrendItemId() { return trendItemId; }
    public String getRawInput() { return rawInput; }
    public short getConfidence() { return confidence; }
    public String getSourcePlatform() { return sourcePlatform; }
    public String getOneLine() { return oneLine; }
    public String getEvidenceUrl() { return evidenceUrl; }
    public SubmissionResult getResult() { return result; }
    public boolean isSeed() { return seed; }
    public Instant getCreatedAt() { return createdAt; }

    public Instant getVoidedAt() { return voidedAt; }
    public Instant getResolvedAt() { return resolvedAt; }

    /** 판정 결과 기록. VOID면 {@link #voidOut}. HIT/MISS는 처음 판정된 시각만 남긴다. */
    public void markResult(SubmissionResult r, Instant at) {
        if (r == SubmissionResult.VOID) {
            voidOut(at);
            return;
        }
        this.result = r;
        if (this.resolvedAt == null && (r == SubmissionResult.HIT || r == SubmissionResult.MISS)) {
            this.resolvedAt = at;
        }
    }

    /** VOID 처리(항목 VOID·같은 유저 중복, 03 §4.4). voided_at이 제보권 반환 시점이다(J4). 이미 VOID면 그대로 둔다. */
    public void voidOut(Instant at) {
        if (this.result == SubmissionResult.VOID) return;
        this.result = SubmissionResult.VOID;
        this.voidedAt = at;
    }

    /** 병합 시 패자 클러스터의 제보를 승자로 재배정(03 §3). VOID된 제보도 감사 추적 연속성을 위해 함께 옮긴다. */
    public void reassignTrendItem(UUID survivorTrendItemId) { this.trendItemId = survivorTrendItemId; }
}
