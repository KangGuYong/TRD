package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** 관심 카테고리 · 알림 시간. 유저 1명당 1행 — 온보딩에서 생성, 설정 화면에서 갱신. */
@Entity
@Table(name = "user_preferences")
public class UserPreference {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "categories", columnDefinition = "text[]", nullable = false)
    private String[] categories;

    @Column(name = "notify_hour", nullable = false)
    private short notifyHour;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected UserPreference() {}

    public UserPreference(UUID userId, String[] categories, short notifyHour) {
        this.userId = userId;
        this.categories = categories;
        this.notifyHour = notifyHour;
    }

    public UUID getUserId() { return userId; }
    public String[] getCategories() { return categories; }
    public short getNotifyHour() { return notifyHour; }

    public void update(String[] categories, short notifyHour) {
        this.categories = categories;
        this.notifyHour = notifyHour;
        this.updatedAt = Instant.now();
    }
}
