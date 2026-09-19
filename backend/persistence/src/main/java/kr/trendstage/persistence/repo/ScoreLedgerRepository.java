package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.ScoreLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ScoreLedgerRepository extends JpaRepository<ScoreLedgerEntry, UUID> {

    List<ScoreLedgerEntry> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** ADM-200 재판정/VOID 시 기존 판정분을 상쇄하기 위한 조회. */
    List<ScoreLedgerEntry> findByVerdictId(UUID verdictId);
}
