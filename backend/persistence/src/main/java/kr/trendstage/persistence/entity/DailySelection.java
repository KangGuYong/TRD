package kr.trendstage.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** "오늘의 5개" 하루 고정 배정 한 행 = 유저×날짜의 순위 하나. trend_reads와 함께 완독 추적의 기준이 되는 셋. */
@Entity
@Table(name = "daily_selections", uniqueConstraints = {
        @UniqueConstraint(name = "uq_daily_selections_rank", columnNames = {"user_id", "selection_date", "rank"}),
        @UniqueConstraint(name = "uq_daily_selections_item", columnNames = {"user_id", "selection_date", "trend_item_id"})
})
public class DailySelection {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "selection_date", nullable = false)
    private LocalDate selectionDate;

    @Column(name = "trend_item_id", nullable = false)
    private UUID trendItemId;

    @Column(name = "rank", nullable = false)
    private short rank;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected DailySelection() {}

    public DailySelection(UUID userId, LocalDate selectionDate, UUID trendItemId, short rank) {
        this.userId = userId;
        this.selectionDate = selectionDate;
        this.trendItemId = trendItemId;
        this.rank = rank;
    }

    public UUID getTrendItemId() { return trendItemId; }
    public short getRank() { return rank; }
}
