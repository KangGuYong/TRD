package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.type.SubmissionResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubmissionRepository extends JpaRepository<Submission, UUID> {

    List<Submission> findByTrendItemIdAndResultNot(UUID trendItemId, SubmissionResult excluded);

    /** 서로 다른 최초 목격 플랫폼(제보 자체가 근거, 외부 지표 아님). 홈 카드 경로 표시용. */
    @Query("select distinct s.sourcePlatform from Submission s where s.trendItemId = :id and s.result <> kr.trendstage.persistence.type.SubmissionResult.VOID")
    List<String> findDistinctPlatforms(@Param("id") UUID trendItemId);

    List<Submission> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** 같은 유저가 같은 클러스터에 이미 유효 제보를 냈는지(중복 dedup, 03 §4.4). */
    boolean existsByTrendItemIdAndUserIdAndResultNot(UUID trendItemId, UUID userId, SubmissionResult excluded);

    /** 대표 제보(최초) — 홈 카드 뜻(one_line) 표시용. */
    Optional<Submission> findFirstByTrendItemIdOrderByCreatedAtAsc(UUID trendItemId);

    /** 등급 재계산용 HIT/MISS 카운트(전체). 180일 창은 서비스 계층에서 필터. */
    long countByUserIdAndResult(UUID userId, SubmissionResult result);
}
