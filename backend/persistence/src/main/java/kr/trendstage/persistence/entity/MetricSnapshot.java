package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import kr.trendstage.persistence.type.MetricSource;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 지표 시계열 원시값. 시간당 배치 재실행 멱등(유니크 제약). 병합 시 합산 금지(03 §4.5). */
@Entity
@Immutable
@Table(name = "metric_snapshots")
public class MetricSnapshot {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trend_item_id", nullable = false)
    private UUID trendItemId;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private MetricSource source;

    @Column(nullable = false, length = 60)
    private String metric;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal value;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected MetricSnapshot() {}

    public MetricSnapshot(UUID trendItemId, MetricSource source, String metric, BigDecimal value, Instant capturedAt) {
        this.trendItemId = trendItemId;
        this.source = source;
        this.metric = metric;
        this.value = value;
        this.capturedAt = capturedAt;
    }

    public UUID getTrendItemId() { return trendItemId; }
    public MetricSource getSource() { return source; }
    public String getMetric() { return metric; }
    public BigDecimal getValue() { return value; }
    public Instant getCapturedAt() { return capturedAt; }
}
