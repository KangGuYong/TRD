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

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getTrendItemId() { return trendItemId; }
    public String getRawInput() { return rawInput; }
    public short getConfidence() { return confidence; }
    public String getSourcePlatform() { return sourcePlatform; }
    public String getOneLine() { return oneLine; }
    public SubmissionResult getResult() { return result; }
    public boolean isSeed() { return seed; }
    public Instant getCreatedAt() { return createdAt; }

    public void markResult(SubmissionResult r) { this.result = r; }

    /** 같은 유저 중복 병합 시 늦은 쪽 VOID + 제보권 반환(03 §4.4). */
    public void voidOut() { this.result = SubmissionResult.VOID; }

    /** 병합 시 패자 클러스터의 제보를 승자로 재배정(03 §3). VOID된 제보도 감사 추적 연속성을 위해 함께 옮긴다. */
    public void reassignTrendItem(UUID survivorTrendItemId) { this.trendItemId = survivorTrendItemId; }
}
