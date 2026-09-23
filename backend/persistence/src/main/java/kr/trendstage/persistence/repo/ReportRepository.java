package kr.trendstage.persistence.repo;

import jakarta.persistence.LockModeType;
import kr.trendstage.persistence.entity.Report;
import kr.trendstage.persistence.type.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {

    /** ADM-410 큐 — 처리 중인 것만(DECIDED는 큐에서 빠짐). */
    List<Report> findByStatusInOrderByCreatedAtAsc(List<ReportStatus> statuses);

    /** /v1/reports/me — 내가 접수한 신고. */
    List<Report> findByReporterIdOrderByCreatedAtDesc(UUID reporterId);

    /** /v1/reports/received — 나를 지목해 소명을 요구한 신고(내 submission을 지목한 것들). */
    List<Report> findBySubmissionIdInOrderByCreatedAtDesc(List<UUID> submissionIds);

    /** ADM-010 큐 요약 — 결정이 남은 신고 수(OPEN + EXPLAINING). */
    long countByStatusIn(List<ReportStatus> statuses);

    /** ADM-010 SLA — 1차 처리(OPEN) 대기 중인 신고, 오래된 순. */
    List<Report> findByStatusOrderByCreatedAtAsc(ReportStatus status);

    /** sla_watch — 4h를 넘긴 OPEN 신고 중 아직 자동 처리하지 않은 것. */
    List<Report> findByStatusAndAutoHiddenAtIsNullAndCreatedAtLessThanEqual(ReportStatus status, Instant cutoff);

    /** sla_watch 자동 숨김 — 사람의 결정과 겹치지 않게 신고 행을 잠근다. 잠금 순서: 신고 → 항목. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Report r where r.id = :id")
    Optional<Report> findByIdForUpdate(@Param("id") UUID id);

    /** OPEN 신고의 RESTORE 차단 — 같은 항목의 다른 신고가 해당 상태(예: EXPLAINING)인지. */
    boolean existsByTrendItemIdAndStatusAndIdNot(UUID trendItemId, ReportStatus status, UUID id);
}
