package kr.trendstage.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** 워치(관심 키워드 추적). 유저 단위 dedup(정규화 키 유니크). */
@Entity
@Table(name = "watches", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "normalized_key"}))
public class Watch {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 120)
    private String keyword;

    @Column(name = "normalized_key", nullable = false, length = 160)
    private String normalizedKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Watch() {}

    public Watch(UUID userId, String keyword, String normalizedKey) {
        this.userId = userId;
        this.keyword = keyword;
        this.normalizedKey = normalizedKey;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getKeyword() { return keyword; }
    public String getNormalizedKey() { return normalizedKey; }
}
