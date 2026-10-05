package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Synchronize;

import java.util.UUID;

/**
 * 선점 순위(order_rank) 읽기 전용 뷰 매핑 (V8 submission_order_rank).
 * 저장 컬럼이 아니라 created_at 기준 RANK 파생값이다(03 §3.1).
 * 판정 시점에는 이 값을 verdicts.evidence_json으로 동결한다.
 * <p>
 * {@code @Synchronize}: 뷰가 submissions를 읽는다는 걸 Hibernate에 알려, 이 엔티티를 조회하기 전에
 * 같은 트랜잭션의 미반영 제보 변경을 자동 flush하게 한다. 없으면 방금 저장한 제보가 순위에서 빠진다(BUG-9).
 */
@Entity
@Immutable
@Synchronize("submissions")
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
