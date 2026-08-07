package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import kr.trendstage.persistence.type.TrendCategory;
import kr.trendstage.persistence.type.TrendState;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 병합된 트렌드 항목(클러스터) = 판정 단위.
 * 낙관적 락(version)으로 검수자 동시 병합 충돌 감지(03 §6).
 * first_seen_at 변경 시 baseline 재계산을 반드시 트리거(03 §3.2) — 서비스 계층 책임.
 * embedding(vector)은 JPA로 매핑하지 않고 MergeService의 네이티브 pgvector 쿼리로 다룬다.
 */
@Entity
@Table(name = "trend_items")
public class TrendItem {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "canonical_name", nullable = false, length = 120)
    private String canonicalName;

    @Column(name = "normalized_key", nullable = false, length = 160)
    private String normalizedKey;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "aliases", columnDefinition = "text[]", nullable = false)
    private String[] aliases = new String[0];

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private TrendCategory category;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private TrendState state = TrendState.DRAFT;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    /** 병합 패자 → 승자 리다이렉트(tombstone). DELETE 금지(03 §4.2). */
    @Column(name = "merged_into")
    private UUID mergedInto;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected TrendItem() {}

    public TrendItem(String canonicalName, String normalizedKey, TrendCategory category, Instant firstSeenAt) {
        this.canonicalName = canonicalName;
        this.normalizedKey = normalizedKey;
        this.category = category;
        this.firstSeenAt = firstSeenAt;
    }

    public UUID getId() { return id; }
    public String getCanonicalName() { return canonicalName; }
    public String getNormalizedKey() { return normalizedKey; }
    public String[] getAliases() { return aliases; }
    public TrendCategory getCategory() { return category; }
    public TrendState getState() { return state; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public UUID getMergedInto() { return mergedInto; }
    public int getVersion() { return version; }

    public void transitionTo(TrendState next) { this.state = next; }

    /** 병합 패자로 표기. state=MERGED와 merged_into는 함께여야 한다(DDL CHECK). */
    public void mergeInto(UUID survivorId) {
        this.state = TrendState.MERGED;
        this.mergedInto = survivorId;
    }

    /** first_seen_at 앞당김(병합) — 호출 측이 baseline 재계산 잡을 트리거해야 함. */
    public void pullForwardFirstSeen(Instant earlier) {
        if (earlier.isBefore(this.firstSeenAt)) this.firstSeenAt = earlier;
    }

    public void setAliases(String[] aliases) { this.aliases = aliases; }
    public void setCanonicalName(String name) { this.canonicalName = name; }
}
