package kr.trendstage.persistence.repo;

import kr.trendstage.domain.verdict.VerdictResult;
import kr.trendstage.persistence.entity.Verdict;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VerdictRepository extends JpaRepository<Verdict, UUID> {

    /**
     * verdict_runner 멱등 가드: 원본 판정(supersedes IS NULL)이 이미 있으면 재실행하지 않는다.
     * DB의 부분 유니크 인덱스(verdict_one_original_per_item)와 짝을 이룬다(04 §6).
     */
    boolean existsByTrendItemIdAndSupersedesIsNull(UUID trendItemId);

    /**
     * ADM-200에서 쓰는 "현재 판정" — supersedes IS NULL은 원본만 가리키므로(재판정 후엔 stale),
     * 체인의 끝(아무도 supersede하지 않는 행)을 찾아야 한다.
     */
    @Query("SELECT v FROM Verdict v WHERE v.trendItemId = :trendItemId " +
           "AND NOT EXISTS (SELECT 1 FROM Verdict v2 WHERE v2.supersedes = v.id)")
    Optional<Verdict> findCurrentByTrendItemId(@Param("trendItemId") UUID trendItemId);

    List<Verdict> findByTrendItemIdOrderByCreatedAtAsc(UUID trendItemId);

    /**
     * ADM-600 시뮬레이션용: 최근 N일 내 판정된 "현재" 판정(다른 행에 superseded 안 됨) 중 VOID 제외.
     * VOID는 파라미터(threshold/target)와 무관한 별도 사유(어뷰징·중복)라 재평가 대상이 아니다.
     */
    default List<Verdict> findCurrentNonVoidSince(Instant since) {
        return findCurrentExcludingResultSince(since, VerdictResult.VOID);
    }

    @Query("SELECT v FROM Verdict v WHERE v.judgedAt >= :since AND v.result <> :excluded " +
           "AND NOT EXISTS (SELECT 1 FROM Verdict v2 WHERE v2.supersedes = v.id)")
    List<Verdict> findCurrentExcludingResultSince(@Param("since") Instant since, @Param("excluded") VerdictResult excluded);
}
