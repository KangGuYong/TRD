package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.Endorsement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EndorsementRepository extends JpaRepository<Endorsement, UUID> {
    boolean existsByTrendItemIdAndUserId(UUID trendItemId, UUID userId);
    long countByTrendItemId(UUID trendItemId);
}
