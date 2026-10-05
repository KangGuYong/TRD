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

    /**
     * 앱 검색(APP-2): 공개된 PENDING·JUDGING·RESOLVED 항목 중 정규화 키나 별칭(병합돼 사라진 이름)에 pattern이 들어간 것.
     * pattern은 호출자가 정규화·LIKE 이스케이프(\)까지 마친 값. 별칭은 NameNormalizer와 같은 규칙(NFC·공백 정리·소문자)으로 맞춰 비교한다.
     */
    @Query(value = "SELECT * FROM trend_items t WHERE t.visibility = 'PUBLIC' AND t.state IN ('PENDING', 'JUDGING', 'RESOLVED') "
            + "AND (t.normalized_key LIKE :pattern ESCAPE '\\' OR EXISTS (SELECT 1 FROM unnest(t.aliases) a "
            + "WHERE lower(regexp_replace(btrim(normalize(a, NFC)), '\\s+', ' ', 'g')) LIKE :pattern ESCAPE '\\'))",
            nativeQuery = true)
    List<TrendItem> searchPublic(String pattern);

    /** 판정·재판정·VOID가 같은 항목을 동시에 건드리지 않게 행을 잠근다(SELECT … FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TrendItem t where t.id = :id")
    Optional<TrendItem> findByIdForUpdate(@Param("id") UUID id);
}
