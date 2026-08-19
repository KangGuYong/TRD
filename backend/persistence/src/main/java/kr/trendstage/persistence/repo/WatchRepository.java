package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.Watch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WatchRepository extends JpaRepository<Watch, UUID> {
    List<Watch> findByUserIdOrderByCreatedAtDesc(UUID userId);
    boolean existsByUserIdAndNormalizedKey(UUID userId, String normalizedKey);
    void deleteByUserIdAndNormalizedKey(UUID userId, String normalizedKey);
}
