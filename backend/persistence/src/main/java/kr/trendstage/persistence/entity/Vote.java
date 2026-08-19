package kr.trendstage.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** "이거 더 뜰까요" 투표. 판정과 물리적으로 분리(R1). 유저당 1표, 토글 가능. */
@Entity
@Table(name = "votes", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "trend_item_id"}))
public class Vote {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "trend_item_id", nullable = false)
    private UUID trendItemId;

    @Column(name = "will_trend", nullable = false)
    private boolean willTrend;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Vote() {}

    public Vote(UUID userId, UUID trendItemId, boolean willTrend) {
        this.userId = userId;
        this.trendItemId = trendItemId;
        this.willTrend = willTrend;
    }

    public UUID getId() { return id; }
    public UUID getTrendItemId() { return trendItemId; }
    public boolean isWillTrend() { return willTrend; }

    public void toggleTo(boolean willTrend) {
        this.willTrend = willTrend;
        this.updatedAt = Instant.now();
    }
}
