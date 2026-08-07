package kr.trendstage.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** 동의(중복 제보 전환). 유저 단위 dedup(유니크). 제보권 미소모. */
@Entity
@Table(name = "endorsements", uniqueConstraints = @UniqueConstraint(columnNames = {"trend_item_id", "user_id"}))
public class Endorsement {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trend_item_id", nullable = false)
    private UUID trendItemId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "src_submission")
    private UUID srcSubmission;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Endorsement() {}

    public Endorsement(UUID trendItemId, UUID userId, UUID srcSubmission) {
        this.trendItemId = trendItemId;
        this.userId = userId;
        this.srcSubmission = srcSubmission;
    }

    public UUID getId() { return id; }
    public UUID getTrendItemId() { return trendItemId; }
    public UUID getUserId() { return userId; }
}
