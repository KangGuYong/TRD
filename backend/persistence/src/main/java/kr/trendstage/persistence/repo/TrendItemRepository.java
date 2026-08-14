package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TrendItemRepository extends JpaRepository<TrendItem, UUID> {

    /** 완전일치 병합 (03 §2②). */
    Optional<TrendItem> findByNormalizedKey(String normalizedKey);

    /** 배치 대상: 관측 중 항목만. */
    List<TrendItem> findByStateIn(List<TrendState> states);

    /** cluster_merge 배치 대상: 아직 임베딩 유사도 비교를 안 한 활성 항목(03 §2③). */
    List<TrendItem> findByStateInAndMergeCheckedAtIsNull(List<TrendState> states);
}
