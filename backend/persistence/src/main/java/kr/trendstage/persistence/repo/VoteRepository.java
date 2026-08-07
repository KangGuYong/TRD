package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.Vote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VoteRepository extends JpaRepository<Vote, UUID> {
    Optional<Vote> findByUserIdAndTrendItemId(UUID userId, UUID trendItemId);
    long countByTrendItemIdAndWillTrend(UUID trendItemId, boolean willTrend);
}
