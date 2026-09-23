package kr.trendstage.persistence.repo;

import jakarta.persistence.LockModeType;
import kr.trendstage.persistence.entity.MergeQueueEntry;
import kr.trendstage.persistence.type.MergeQueueStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MergeQueueRepository extends JpaRepository<MergeQueueEntry, UUID> {
    List<MergeQueueEntry> findByStatusOrderByCreatedAtAsc(MergeQueueStatus status);

    boolean existsByNewTrendItemId(UUID newTrendItemId);

    /** 결정 요청이 큐 행을 잠근다 — 검수자 둘이 동시에 같은 후보를 처리하지 못하게(SP2). 잠금 순서: 큐 행 → 항목. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from MergeQueueEntry q where q.id = :id")
    Optional<MergeQueueEntry> findByIdForUpdate(@Param("id") UUID id);

    Optional<MergeQueueEntry> findByDecisionKey(String decisionKey);

    /** sla_watch — 24h를 넘긴 처리 대기 병합 후보. */
    List<MergeQueueEntry> findByStatusAndCreatedAtLessThanEqual(MergeQueueStatus status, Instant cutoff);
}
