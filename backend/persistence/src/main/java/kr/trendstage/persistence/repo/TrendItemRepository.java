package kr.trendstage.persistence.repo;

import jakarta.persistence.LockModeType;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.type.TrendState;
import kr.trendstage.persistence.type.TrendVisibility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** api-public 공개 조회 필터링용 — visibility=PUBLIC만 노출(신고 처리 결과 반영, ADM-410). */
    List<TrendItem> findByStateInAndVisibility(List<TrendState> states, TrendVisibility visibility);

    /** 판정·재판정·VOID가 같은 항목을 동시에 건드리지 않게 행을 잠근다(SELECT … FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TrendItem t where t.id = :id")
    Optional<TrendItem> findByIdForUpdate(@Param("id") UUID id);
}
