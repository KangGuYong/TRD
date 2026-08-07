package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import kr.trendstage.domain.grade.Grade;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 등급 스냅샷(파생·이력, append-only). grade_recalc가 새 행을 쌓는다. 최신 행은 뷰(user_grade_current). */
@Entity
@Immutable
@Table(name = "user_grades")
public class UserGrade {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private Grade grade;

    @Column(name = "trust_index", nullable = false, precision = 4, scale = 3)
    private BigDecimal trustIndex;

    @Column(name = "active_score", nullable = false, precision = 12, scale = 4)
    private BigDecimal activeScore;

    @Column(name = "judged_count", nullable = false)
    private int judgedCount;

    @Column(name = "computed_at", nullable = false, updatable = false)
    private Instant computedAt = Instant.now();

    protected UserGrade() {}

    public UserGrade(UUID userId, Grade grade, BigDecimal trustIndex, BigDecimal activeScore, int judgedCount) {
        this.userId = userId;
        this.grade = grade;
        this.trustIndex = trustIndex;
        this.activeScore = activeScore;
        this.judgedCount = judgedCount;
    }

    public UUID getUserId() { return userId; }
    public Grade getGrade() { return grade; }
    public BigDecimal getTrustIndex() { return trustIndex; }
    public BigDecimal getActiveScore() { return activeScore; }
    public int getJudgedCount() { return judgedCount; }
    public Instant getComputedAt() { return computedAt; }
}
