package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.TrendRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface TrendReadRepository extends JpaRepository<TrendRead, UUID> {
    List<TrendRead> findByUserId(UUID userId);
    long countByUserId(UUID userId);

    /** 동시 요청 경쟁에도 안전한 멱등 기록 — UNIQUE(user_id, trend_item_id) 충돌 시 조용히 무시. */
    @Modifying
    @Query(value = "INSERT INTO trend_reads (user_id, trend_item_id) VALUES (:userId, :trendItemId) ON CONFLICT (user_id, trend_item_id) DO NOTHING", nativeQuery = true)
    void insertIfAbsent(UUID userId, UUID trendItemId);
}
