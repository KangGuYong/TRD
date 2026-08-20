package kr.trendstage.apipublic.service;

import kr.trendstage.apipublic.web.ExplanationConflictException;
import kr.trendstage.apipublic.web.ExplanationForbiddenException;
import kr.trendstage.apipublic.web.ReportCreateRequest;
import kr.trendstage.apipublic.web.ReportMineResponse;
import kr.trendstage.apipublic.web.ReportNotFoundException;
import kr.trendstage.apipublic.web.ReportReceivedResponse;
import kr.trendstage.apipublic.web.TrendNotFoundException;
import kr.trendstage.persistence.entity.Report;
import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.ReportRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.ReportStatus;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 신고 접수/조회/소명 제출. 신고 대상은 TrendItem(유저가 볼 수 있는 유일한 단위) — 개별
 * 제보 원문에는 접근할 수 없다(설계서 §범위). 신고 처리는 관리자 전용이라 여기 없음.
 */
@Service
public class ReportService {

    private final ReportRepository reports;
    private final TrendItemRepository trends;
    private final SubmissionRepository submissions;

    public ReportService(ReportRepository reports, TrendItemRepository trends, SubmissionRepository submissions) {
        this.reports = reports;
        this.trends = trends;
        this.submissions = submissions;
    }

    @Transactional
    public ReportMineResponse create(UUID reporterId, ReportCreateRequest req) {
        TrendItem item = trends.findById(req.trendItemId())
                .filter(i -> i.getState() != TrendState.MERGED)
                .orElseThrow(() -> new TrendNotFoundException("존재하지 않는 항목입니다"));
        Report report = reports.save(new Report(item.getId(), reporterId, req.reason(), req.detail()));
        return toMineResponse(report);
    }

    @Transactional(readOnly = true)
    public List<ReportMineResponse> mine(UUID reporterId) {
        return reports.findByReporterIdOrderByCreatedAtDesc(reporterId).stream()
                .map(this::toMineResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReportReceivedResponse> received(UUID userId) {
        List<UUID> mySubmissionIds = submissions.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(Submission::getId)
                .toList();
        if (mySubmissionIds.isEmpty()) return List.of();
        return reports.findBySubmissionIdInOrderByCreatedAtDesc(mySubmissionIds).stream()
                .map(this::toReceivedResponse)
                .toList();
    }

    @Transactional
    public void submitExplanation(UUID userId, UUID reportId, String text) {
        Report report = reports.findById(reportId)
                .orElseThrow(() -> new ReportNotFoundException("존재하지 않는 신고입니다"));
        if (report.getSubmissionId() == null || !isOwnedBy(report.getSubmissionId(), userId)) {
            throw new ExplanationForbiddenException("소명 대상으로 지목되지 않았습니다");
        }
        if (report.getStatus() != ReportStatus.EXPLAINING) {
            throw new ExplanationConflictException("소명을 제출할 수 있는 상태가 아닙니다: " + report.getStatus());
        }
        report.submitExplanation(text, Instant.now());
    }

    private boolean isOwnedBy(UUID submissionId, UUID userId) {
        return submissions.findById(submissionId).map(Submission::getUserId).map(userId::equals).orElse(false);
    }

    private ReportMineResponse toMineResponse(Report r) {
        return new ReportMineResponse(r.getId(), r.getTrendItemId(), r.getStatus().name(), r.getReason().name(),
                r.getDecision() == null ? null : r.getDecision().name(), r.getDecisionNote(),
                r.getCreatedAt(), r.getDecidedAt());
    }

    private ReportReceivedResponse toReceivedResponse(Report r) {
        return new ReportReceivedResponse(r.getId(), r.getTrendItemId(), r.getReason().name(), r.getDetail(),
                r.getExplanationDeadline(), r.getExplanationSubmittedAt() != null, r.getStatus().name());
    }
}
