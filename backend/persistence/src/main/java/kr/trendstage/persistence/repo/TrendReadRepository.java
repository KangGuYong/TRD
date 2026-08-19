package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.TrendRead;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TrendReadRepository extends JpaRepository<TrendRead, UUID> {
    boolean existsByUserIdAndTrendItemId(UUID userId, UUID trendItemId);
    List<TrendRead> findByUserId(UUID userId);
    long countByUserId(UUID userId);
}
