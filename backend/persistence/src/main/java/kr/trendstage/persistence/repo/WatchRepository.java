package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.Watch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface WatchRepository extends JpaRepository<Watch, UUID> {
    List<Watch> findByUserIdOrderByCreatedAtDesc(UUID userId);
    boolean existsByUserIdAndNormalizedKey(UUID userId, String normalizedKey);
    void deleteByUserIdAndNormalizedKey(UUID userId, String normalizedKey);

    /** 동시 요청 경쟁에도 안전한 멱등 추가 — UNIQUE(user_id, normalized_key) 충돌 시 조용히 무시. */
    @Modifying
    @Query(value = "INSERT INTO watches (user_id, keyword, normalized_key) VALUES (:userId, :keyword, :normalizedKey) ON CONFLICT (user_id, normalized_key) DO NOTHING", nativeQuery = true)
    void insertIfAbsent(UUID userId, String keyword, String normalizedKey);
}
