package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.type.SubmissionResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubmissionRepository extends JpaRepository<Submission, UUID> {

    List<Submission> findByTrendItemIdAndResultNot(UUID trendItemId, SubmissionResult excluded);

    /** 병합 시 VOID 포함 전량 재배정용(감사 추적 연속성, 03 §3). */
    List<Submission> findByTrendItemId(UUID trendItemId);

    /** 서로 다른 최초 목격 플랫폼(제보 자체가 근거, 외부 지표 아님). 홈 카드 경로 표시용. */
    @Query("select distinct s.sourcePlatform from Submission s where s.trendItemId = :id and s.result <> :excluded")
    List<String> findDistinctPlatforms(@Param("id") UUID trendItemId, @Param("excluded") SubmissionResult excluded);

    List<Submission> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** 같은 유저가 같은 클러스터에 이미 유효 제보를 냈는지(중복 dedup, 03 §4.4). */
    boolean existsByTrendItemIdAndUserIdAndResultNot(UUID trendItemId, UUID userId, SubmissionResult excluded);

    /** 대표 제보(최초) — 홈 카드 뜻(one_line) 표시용. */
    Optional<Submission> findFirstByTrendItemIdOrderByCreatedAtAsc(UUID trendItemId);

    /** 등급 재계산용 HIT/MISS 카운트(전체). 180일 창은 서비스 계층에서 필터. */
    long countByUserIdAndResult(UUID userId, SubmissionResult result);

    /** 409 응답의 dupeRank(클러스터 내 현재 제보 수) 계산용. */
    long countByTrendItemIdAndResultNot(UUID trendItemId, SubmissionResult excluded);

    /** ADM-110 목록의 '제보자수' — 판정 신호와 같은 기준(서로 다른 제보자, 시딩·VOID 제외). */
    @Query("select count(distinct s.userId) from Submission s "
            + "where s.trendItemId = :id and s.seed = false and s.result <> :excluded")
    long countDistinctSubmitters(@Param("id") UUID trendItemId, @Param("excluded") SubmissionResult excluded);

    long countByCreatedAtAfter(Instant since);

    long countByCreatedAtAfterAndSeedTrue(Instant since);

    /** 제보권: 이번 주에 낸 제보 수(시딩 제외, VOID 여부 무관 — J4). */
    long countByUserIdAndSeedFalseAndCreatedAtGreaterThanEqual(UUID userId, Instant since);

    /** 제보권: 이번 주에 VOID로 반환된 제보 수(시딩 제외, 지난주에 낸 것 포함 — J4). */
    long countByUserIdAndSeedFalseAndVoidedAtGreaterThanEqual(UUID userId, Instant since);

    /** TI 창(최근 180일) — 처음 판정 시각 기준, 시딩 제외. */
    long countByUserIdAndResultAndSeedFalseAndResolvedAtGreaterThanEqual(UUID userId, SubmissionResult result, Instant since);

    /** 판정완료 건수(전 기간), 시딩 제외. */
    long countByUserIdAndResultAndSeedFalse(UUID userId, SubmissionResult result);
}
