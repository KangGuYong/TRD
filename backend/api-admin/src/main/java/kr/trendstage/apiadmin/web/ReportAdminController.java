package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.apiadmin.report.ReportAdminService;
import kr.trendstage.persistence.entity.Report;
import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.entity.UserAccount;
import kr.trendstage.persistence.repo.UserRepository;
import kr.trendstage.persistence.type.ReportDecision;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * ADM-410 신고 콘텐츠 큐. 권한(02 §1.1): 1차 처리(hide/request-explanation)는 REVIEWER 이상,
 * 최종 결정(decide)은 OPERATOR 이상. 신고자 비식별 원칙 — 응답 DTO에 reporterId를 절대 넣지 않는다.
 */
@RestController
@RequestMapping("/admin/reports")
public class ReportAdminController {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final ReportAdminService service;
    private final UserRepository users;

    public ReportAdminController(ReportAdminService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    public record ReportResponse(String id, String trendItemId, String reason, String detail, String status,
                                  String submissionId, String explanationDeadline, String explanationText,
                                  String decision, String decisionNote, String createdAt) {}
    public record SubmissionCandidate(String submissionId, String handle, String rawInput, String oneLine,
                                       String evidenceUrl, String createdAt) {}
    public record TriageRequest(UUID submissionId, String note) {}
    public record DecideRequest(ReportDecision decision, String note, String newCanonicalName) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN')")
    public List<ReportResponse> queue() {
        return service.queue().stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}/submissions")
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN')")
    public List<SubmissionCandidate> submissions(@PathVariable UUID id) {
        return service.candidateSubmissions(id).stream().map(this::toCandidate).toList();
    }

    @PostMapping("/{id}/hide")
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN')")
    public ReportResponse hide(@PathVariable UUID id, @RequestBody TriageRequest req,
                                @AuthenticationPrincipal AdminPrincipal actor) {
        requireSubmissionId(req);
        return toResponse(service.hide(id, req.submissionId(), req.note(), actor));
    }

    @PostMapping("/{id}/request-explanation")
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN')")
    public ReportResponse requestExplanation(@PathVariable UUID id, @RequestBody TriageRequest req,
                                              @AuthenticationPrincipal AdminPrincipal actor) {
        requireSubmissionId(req);
        return toResponse(service.requestExplanation(id, req.submissionId(), req.note(), actor));
    }

    @PostMapping("/{id}/decide")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ReportResponse decide(@PathVariable UUID id, @RequestBody DecideRequest req,
                                  @AuthenticationPrincipal AdminPrincipal actor) {
        if (req.decision() == null) {
            throw new AdminValidationException("decision은 필수입니다");
        }
        return toResponse(service.decide(id, req.decision(), req.note(), req.newCanonicalName(), actor));
    }

    private void requireSubmissionId(TriageRequest req) {
        if (req.submissionId() == null) {
            throw new AdminValidationException("submissionId는 필수입니다 — 신고 대상 제보를 지목해야 합니다");
        }
    }

    private ReportResponse toResponse(Report r) {
        return new ReportResponse(
                r.getId().toString(), r.getTrendItemId().toString(), r.getReason().name(), r.getDetail(),
                r.getStatus().name(), r.getSubmissionId() == null ? null : r.getSubmissionId().toString(),
                r.getExplanationDeadline() == null ? null : DISPLAY_FORMAT.format(r.getExplanationDeadline()),
                r.getExplanationText(),
                r.getDecision() == null ? null : r.getDecision().name(), r.getDecisionNote(),
                DISPLAY_FORMAT.format(r.getCreatedAt()));
    }

    private SubmissionCandidate toCandidate(Submission s) {
        String handle = users.findById(s.getUserId()).map(UserAccount::getHandle).orElse("(탈퇴)");
        return new SubmissionCandidate(s.getId().toString(), handle, s.getRawInput(), s.getOneLine(),
                s.getEvidenceUrl(), DISPLAY_FORMAT.format(s.getCreatedAt()));
    }
}
