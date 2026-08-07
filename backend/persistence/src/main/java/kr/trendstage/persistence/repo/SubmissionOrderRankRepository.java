package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.SubmissionOrderRank;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** 읽기 전용. 선점 순위 조회 — 판정 시점에 evidence_json으로 동결(03 §3.1). */
public interface SubmissionOrderRankRepository extends JpaRepository<SubmissionOrderRank, UUID> {
    List<SubmissionOrderRank> findByTrendItemId(UUID trendItemId);
}
