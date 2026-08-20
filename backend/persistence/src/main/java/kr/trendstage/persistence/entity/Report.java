package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import kr.trendstage.persistence.type.ReportDecision;
import kr.trendstage.persistence.type.ReportReason;
import kr.trendstage.persistence.type.ReportStatus;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 신고. 상태전이는 OPEN → EXPLAINING(1차 처리, submission_id 지목) → DECIDED(최종 결정).
 * 상태 검증은 서비스 계층(ReportAdminService)의 책임 — 다른 엔티티(TrendItem 등)와 동일 관례.
 * reporter_id는 저장하되 관리자 API 응답 DTO에는 절대 노출하지 않는다(신고자 비식별 원칙,
 * 컨트롤러 레이어에서 강제 — V26 컬럼 코멘트 참고).
 */
@Entity
@Table(name = "reports")
public class Report {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trend_item_id", nullable = false)
    private UUID trendItemId;

    @Column(name = "reporter_id", nullable = false)
    private UUID reporterId;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ReportReason reason;

    @Column
    private String detail;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ReportStatus status = ReportStatus.OPEN;

    @Column(name = "submission_id")
    private UUID submissionId;

    @Column(name = "explanation_deadline")
    private Instant explanationDeadline;

    @Column(name = "explanation_text")
    private String explanationText;

    @Column(name = "explanation_submitted_at")
    private Instant explanationSubmittedAt;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column
    private ReportDecision decision;

    @Column(name = "decision_note")
    private String decisionNote;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Report() {}

    public Report(UUID trendItemId, UUID reporterId, ReportReason reason, String detail) {
        this.trendItemId = trendItemId;
        this.reporterId = reporterId;
        this.reason = reason;
        this.detail = detail;
    }

    /** 1차 처리(hide 또는 request-explanation 공통). hide 여부에 따른 TrendItem.visibility 변경은 호출 측(서비스 계층)이 별도로 수행. */
    public void moveToExplaining(UUID submissionId, Instant deadline) {
        this.submissionId = submissionId;
        this.explanationDeadline = deadline;
        this.status = ReportStatus.EXPLAINING;
    }

    public void submitExplanation(String text, Instant now) {
        this.explanationText = text;
        this.explanationSubmittedAt = now;
    }

    public void decide(ReportDecision decision, String note, UUID decidedBy, Instant now) {
        this.decision = decision;
        this.decisionNote = note;
        this.decidedBy = decidedBy;
        this.decidedAt = now;
        this.status = ReportStatus.DECIDED;
    }

    public UUID getId() { return id; }
    public UUID getTrendItemId() { return trendItemId; }
    public UUID getReporterId() { return reporterId; }
    public ReportReason getReason() { return reason; }
    public String getDetail() { return detail; }
    public ReportStatus getStatus() { return status; }
    public UUID getSubmissionId() { return submissionId; }
    public Instant getExplanationDeadline() { return explanationDeadline; }
    public String getExplanationText() { return explanationText; }
    public Instant getExplanationSubmittedAt() { return explanationSubmittedAt; }
    public ReportDecision getDecision() { return decision; }
    public String getDecisionNote() { return decisionNote; }
    public UUID getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
