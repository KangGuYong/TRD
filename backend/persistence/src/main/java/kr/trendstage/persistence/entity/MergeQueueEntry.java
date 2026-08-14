package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import kr.trendstage.persistence.type.MergeQueueStatus;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 임베딩 유사도 회색지대(0.75~0.85) 관리자 검수 큐 항목(03 §2③④, ADM-100). */
@Entity
@Table(name = "merge_queue")
public class MergeQueueEntry {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "new_trend_item_id", nullable = false)
    private UUID newTrendItemId;

    @Column(name = "old_trend_item_id", nullable = false)
    private UUID oldTrendItemId;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal similarity;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private MergeQueueStatus status = MergeQueueStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    protected MergeQueueEntry() {}

    public MergeQueueEntry(UUID newTrendItemId, UUID oldTrendItemId, BigDecimal similarity) {
        this.newTrendItemId = newTrendItemId;
        this.oldTrendItemId = oldTrendItemId;
        this.similarity = similarity;
    }

    public void resolve(MergeQueueStatus status, UUID resolvedBy, Instant at) {
        this.status = status;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = at;
    }

    public UUID getId() { return id; }
    public UUID getNewTrendItemId() { return newTrendItemId; }
    public UUID getOldTrendItemId() { return oldTrendItemId; }
    public BigDecimal getSimilarity() { return similarity; }
    public MergeQueueStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public UUID getResolvedBy() { return resolvedBy; }
}
