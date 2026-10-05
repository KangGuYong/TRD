package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.Endorsement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface EndorsementRepository extends JpaRepository<Endorsement, UUID> {
    /** UNIQUE(trend_item_id, user_id) 충돌 시 아무것도 안 넣는다. 넣은 행 수(0이면 이미 동의). */
    @Modifying
    @Query(value = "INSERT INTO endorsements (trend_item_id, user_id) VALUES (:trendItemId, :userId) "
            + "ON CONFLICT (trend_item_id, user_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(UUID trendItemId, UUID userId);
    long countByTrendItemId(UUID trendItemId);
}
