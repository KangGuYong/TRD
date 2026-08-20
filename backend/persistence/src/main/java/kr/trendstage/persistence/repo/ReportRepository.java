package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.Report;
import kr.trendstage.persistence.type.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {

    /** ADM-410 큐 — 처리 중인 것만(DECIDED는 큐에서 빠짐). */
    List<Report> findByStatusInOrderByCreatedAtAsc(List<ReportStatus> statuses);

    /** /v1/reports/me — 내가 접수한 신고. */
    List<Report> findByReporterIdOrderByCreatedAtDesc(UUID reporterId);

    /** /v1/reports/received — 나를 지목해 소명을 요구한 신고(내 submission을 지목한 것들). */
    List<Report> findBySubmissionIdInOrderByCreatedAtDesc(List<UUID> submissionIds);
}
