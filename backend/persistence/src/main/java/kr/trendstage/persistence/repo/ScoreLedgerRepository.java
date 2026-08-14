package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.ScoreLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface ScoreLedgerRepository extends JpaRepository<ScoreLedgerEntry, UUID> {

    List<ScoreLedgerEntry> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** ADM-200 재판정/VOID 시 기존 판정분을 상쇄하기 위한 조회. */
    List<ScoreLedgerEntry> findByVerdictId(UUID verdictId);

    /**
     * 시간감쇠는 엔진에서 이미 delta에 반영됐으므로 여기선 단순 합. AS 산출용(01 §5.3).
     * (감쇠를 재계산하려면 원장 시각 기준으로 서비스 계층에서 재적용)
     */
    @Query("select coalesce(sum(l.delta), 0) from ScoreLedgerEntry l where l.userId = :userId")
    BigDecimal sumDeltaByUser(@Param("userId") UUID userId);
}
