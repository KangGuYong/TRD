package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.ScoreLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ScoreLedgerRepository extends JpaRepository<ScoreLedgerEntry, UUID> {

    List<ScoreLedgerEntry> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** ADM-200 재판정/VOID 시 기존 판정분을 상쇄하기 위한 조회. */
    List<ScoreLedgerEntry> findByVerdictId(UUID verdictId);

    /** 재판정·VOID 차액 계산용 — 항목 판정 체인에 귀속된 원장(원 판정 행 + 이전 재판정 ADJ). */
    List<ScoreLedgerEntry> findByVerdictIdIn(Collection<UUID> verdictIds);
}
