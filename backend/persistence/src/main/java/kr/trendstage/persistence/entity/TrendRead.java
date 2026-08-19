package kr.trendstage.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** 완독 기록. 유저×트렌드 유니크 — 같은 걸 두 번 읽어도 한 행. */
@Entity
@Table(name = "trend_reads", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "trend_item_id"}))
public class TrendRead {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "trend_item_id", nullable = false)
    private UUID trendItemId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected TrendRead() {}

    public TrendRead(UUID userId, UUID trendItemId) {
        this.userId = userId;
        this.trendItemId = trendItemId;
    }

    public UUID getTrendItemId() { return trendItemId; }
}
