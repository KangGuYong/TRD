package kr.trendstage.apiadmin.report;

import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.entity.Report;
import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.ReportRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.ReportDecision;
import kr.trendstage.persistence.type.ReportStatus;
import kr.trendstage.persistence.type.SubmissionResult;
import kr.trendstage.persistence.type.TrendVisibility;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ADM-410 신고 콘텐츠 큐. 4h SLA 초과 시 자동 임시비공개(TEMP_HIDDEN)는 정책으로 확정됐고(R4 개정, 2026-09-17,
 * P1) sla_watch(scheduler)가 수행한다 — 이 서비스는 사람의 결정만 다룬다.
 * visibility는 순수 표시 계층 — 판정/점수와 분리(R2).
 */
@Service
public class ReportAdminService {

    private static final Duration EXPLANATION_WINDOW = Duration.ofHours(48);

    private final ReportRepository reports;
    private final TrendItemRepository trends;
    private final SubmissionRepository submissions;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public ReportAdminService(ReportRepository reports, TrendItemRepository trends, SubmissionRepository submissions,
                               AuditLogService auditLogService, Clock clock) {
        this.reports = reports;
        this.trends = trends;
        this.submissions = submissions;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Report> queue() {
        return reports.findByStatusInOrderByCreatedAtAsc(List.of(ReportStatus.OPEN, ReportStatus.EXPLAINING));
    }

    /** 관리자가 소명 대상을 지목할 후보 — 병합검수 화면과 동일 뷰(자동 추정 없음). */
    @Transactional(readOnly = true)
    public List<Submission> candidateSubmissions(UUID reportId) {
        Report report = requireReport(reportId);
        return submissions.findByTrendItemIdAndResultNot(report.getTrendItemId(), SubmissionResult.VOID).stream()
                .sorted(Comparator.comparing(Submission::getCreatedAt))
                .toList();
    }

    @Transactional
    public Report hide(UUID reportId, UUID submissionId, String note, AdminPrincipal actor) {
        Report report = requireOpen(reportId);
        requireSubmissionBelongsToReport(report, submissionId);

        TrendItem item = trends.findById(report.getTrendItemId())
                .orElseThrow(() -> new AdminValidationException("트렌드 항목을 찾을 수 없습니다: " + report.getTrendItemId()));
        item.applyVisibility(TrendVisibility.TEMP_HIDDEN);

        report.moveToExplaining(submissionId, clock.instant().plus(EXPLANATION_WINDOW));
        auditLogService.record(actor.id(), actor.role(), "REPORT_HIDE", "REPORT", reportId, Map.of(
                "trendItemId", report.getTrendItemId().toString(), "submissionId", submissionId.toString(),
                "note", note == null ? "" : note));
        return report;
    }

    @Transactional
    public Report requestExplanation(UUID reportId, UUID submissionId, String note, AdminPrincipal actor) {
        Report report = requireOpen(reportId);
        requireSubmissionBelongsToReport(report, submissionId);

        report.moveToExplaining(submissionId, clock.instant().plus(EXPLANATION_WINDOW));
        auditLogService.record(actor.id(), actor.role(), "REPORT_REQUEST_EXPLANATION", "REPORT", reportId, Map.of(
                "trendItemId", report.getTrendItemId().toString(), "submissionId", submissionId.toString(),
                "note", note == null ? "" : note));
        return report;
    }

    @Transactional
    public Report decide(UUID reportId, ReportDecision decision, String note, String newCanonicalName, AdminPrincipal actor) {
        // 오신고는 1차 처리 없이 바로 복원할 수 있다(K9). 영구 비공개·수정 후 복원은 소명(EXPLAINING) 뒤에만.
        Report report = decision == ReportDecision.RESTORE ? requireUndecided(reportId) : requireExplaining(reportId);
        if (decision == ReportDecision.EDIT_RESTORE && (newCanonicalName == null || newCanonicalName.isBlank())) {
            throw new AdminValidationException("EDIT_RESTORE는 newCanonicalName이 필수입니다");
        }

        TrendItem item = trends.findById(report.getTrendItemId())
                .orElseThrow(() -> new AdminValidationException("트렌드 항목을 찾을 수 없습니다: " + report.getTrendItemId()));
        switch (decision) {
            case RESTORE -> item.applyVisibility(TrendVisibility.PUBLIC);
            case HIDE_PERMANENT -> item.applyVisibility(TrendVisibility.PERMANENT_HIDDEN);
            case EDIT_RESTORE -> {
                item.setCanonicalName(newCanonicalName);
                item.applyVisibility(TrendVisibility.PUBLIC);
            }
        }

        report.decide(decision, note, actor.id(), clock.instant());
        auditLogService.record(actor.id(), actor.role(), "REPORT_DECIDE", "REPORT", reportId, Map.of(
                "trendItemId", report.getTrendItemId().toString(), "decision", decision.name(),
                "note", note == null ? "" : note));
        return report;
    }

    private Report requireReport(UUID id) {
        return reports.findById(id).orElseThrow(() -> new AdminValidationException("존재하지 않는 신고입니다"));
    }

    private Report requireOpen(UUID id) {
        Report report = requireReport(id);
        if (report.getStatus() != ReportStatus.OPEN) {
            throw new AdminValidationException("이미 1차 처리된 신고입니다: " + report.getStatus());
        }
        return report;
    }

    private Report requireExplaining(UUID id) {
        Report report = requireReport(id);
        if (report.getStatus() != ReportStatus.EXPLAINING) {
            throw new AdminValidationException("소명 대기 상태가 아닙니다: " + report.getStatus());
        }
        return report;
    }

    private Report requireUndecided(UUID id) {
        Report report = requireReport(id);
        if (report.getStatus() == ReportStatus.DECIDED) {
            throw new AdminValidationException("이미 결정된 신고입니다");
        }
        return report;
    }

    private void requireSubmissionBelongsToReport(Report report, UUID submissionId) {
        Submission submission = submissions.findById(submissionId)
                .orElseThrow(() -> new AdminValidationException("존재하지 않는 제보입니다"));
        if (!submission.getTrendItemId().equals(report.getTrendItemId())) {
            throw new AdminValidationException("해당 신고의 트렌드 항목에 속하지 않는 제보입니다");
        }
    }
}
