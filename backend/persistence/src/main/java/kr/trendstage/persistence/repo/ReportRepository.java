package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.Report;
import kr.trendstage.persistence.type.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
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
}
