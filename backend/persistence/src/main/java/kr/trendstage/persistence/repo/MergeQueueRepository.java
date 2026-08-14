package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.MergeQueueEntry;
import kr.trendstage.persistence.type.MergeQueueStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MergeQueueRepository extends JpaRepository<MergeQueueEntry, UUID> {
    List<MergeQueueEntry> findByStatusOrderByCreatedAtAsc(MergeQueueStatus status);

    boolean existsByNewTrendItemId(UUID newTrendItemId);

    Optional<MergeQueueEntry> findByNewTrendItemId(UUID newTrendItemId);
}
