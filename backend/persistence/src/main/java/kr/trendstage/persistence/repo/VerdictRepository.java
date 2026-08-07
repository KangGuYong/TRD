package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.Verdict;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VerdictRepository extends JpaRepository<Verdict, UUID> {

    /**
     * verdict_runner 멱등 가드: 원본 판정(supersedes IS NULL)이 이미 있으면 재실행하지 않는다.
     * DB의 부분 유니크 인덱스(verdict_one_original_per_item)와 짝을 이룬다(04 §6).
     */
    boolean existsByTrendItemIdAndSupersedesIsNull(UUID trendItemId);
}
