package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

/**
 * 선점 순위(order_rank) 읽기 전용 뷰 매핑 (V8 submission_order_rank).
 * 저장 컬럼이 아니라 created_at 기준 RANK 파생값이다(03 §3.1).
 * 판정 시점에는 이 값을 verdicts.evidence_json으로 동결한다.
 */
@Entity
@Immutable
@Table(name = "submission_order_rank")
public class SubmissionOrderRank {

    @Id
    @Column(name = "id")
    private UUID submissionId;

    @Column(name = "trend_item_id")
    private UUID trendItemId;

    @Column(name = "order_rank")
    private int orderRank;

    protected SubmissionOrderRank() {}

    public UUID getSubmissionId() { return submissionId; }
    public UUID getTrendItemId() { return trendItemId; }
    public int getOrderRank() { return orderRank; }
}
