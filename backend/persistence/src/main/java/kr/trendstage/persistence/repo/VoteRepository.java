package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.Vote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface VoteRepository extends JpaRepository<Vote, UUID> {
    /** 첫 투표는 넣고 재투표는 토글 — UNIQUE(user_id, trend_item_id) 충돌을 DB가 처리해 동시 첫 요청도 500 없이 1행. */
    @Modifying
    @Query(value = "INSERT INTO votes (user_id, trend_item_id, will_trend) VALUES (:userId, :trendItemId, :willTrend) "
            + "ON CONFLICT (user_id, trend_item_id) DO UPDATE SET will_trend = EXCLUDED.will_trend, updated_at = now()",
            nativeQuery = true)
    void upsert(UUID userId, UUID trendItemId, boolean willTrend);

    long countByTrendItemIdAndWillTrend(UUID trendItemId, boolean willTrend);
    List<Vote> findByUserId(UUID userId);
}
