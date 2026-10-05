package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** ADM-600 백테스트 사례 파일(SP4 S11). 불변 — DB 트리거가 UPDATE/DELETE를 막는다. */
@Entity
@Immutable
@Table(name = "backtest_datasets")
public class BacktestDataset {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 64, unique = true)
    private String sha256;

    @Column(name = "case_count", nullable = false)
    private int caseCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BacktestDataset() {}

    public BacktestDataset(String name, String sha256, int caseCount, String payload, UUID uploadedBy, Instant createdAt) {
        this.name = name;
        this.sha256 = sha256;
        this.caseCount = caseCount;
        this.payload = payload;
        this.uploadedBy = uploadedBy;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getSha256() { return sha256; }
    public int getCaseCount() { return caseCount; }
    public String getPayload() { return payload; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
