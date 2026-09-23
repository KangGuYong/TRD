package kr.trendstage.apiadmin.verdict;

import kr.trendstage.apiadmin.approval.ActionType;
import kr.trendstage.apiadmin.approval.ApprovalGate;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.apiadmin.web.AdminConflictException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.domain.verdict.DeadlineWindow;
import kr.trendstage.judge.AdjustmentPolicy;
import kr.trendstage.judge.JudgeOutcome;
import kr.trendstage.judge.JudgeService;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.entity.Verdict;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.VerdictRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * ADM-200. R1: 관리자는 판정 결과를 직접 바꾸지 못한다 — VOID·재판정은 JudgeService가 엔진을 다시 돌려
 * supersede 판정을 쌓을 뿐이다. 재판정은 원 판정 때 동결한 파라미터를 쓴다(J2). 감사 기록은 여기서 남긴다.
 */
@Service
public class VerdictAdminService {

    private static final int JUDGE_WINDOW_DAYS = 14;
    private static final int MAX_GRACE_DAYS = 7;

    private final TrendItemRepository trendItems;
    private final VerdictRepository verdicts;
    private final JudgeService judgeService;
    private final AuditLogService auditLogService;
    private final ApprovalGate gate;
    private final Clock clock;

    public VerdictAdminService(TrendItemRepository trendItems, VerdictRepository verdicts, JudgeService judgeService,
                               AuditLogService auditLogService, ApprovalGate gate, Clock clock) {
        this.trendItems = trendItems;
        this.verdicts = verdicts;
        this.judgeService = judgeService;
        this.auditLogService = auditLogService;
        this.gate = gate;
        this.clock = clock;
    }

    /** ADM-200 VOID·재판정 결과. status = APPLIED(200) | PENDING_APPROVAL(202). */
    public record VerdictActionResult(String status, String verdictId, String approvalRequestId, BigDecimal adjTotal) {
        public boolean pending() { return "PENDING_APPROVAL".equals(status); }
    }

    @Transactional
    public VerdictActionResult voidVerdict(UUID trendItemId, UUID actorId, AdminRole actorRole, String reason) {
        requireReason(reason);
        Verdict previous = lockAndRequireJudged(trendItemId);
        JudgeOutcome outcome = judgeService.voidItem(trendItemId, reason, clock.instant(),
                AdjustmentPolicy.limitedTo(ApprovalGate.THRESHOLD));
        if (outcome instanceof JudgeOutcome.NeedsApproval n) {
            return pending(ActionType.ITEM_VOID, trendItemId, reason, n, actorId, actorRole);
        }
        JudgeOutcome.Applied a = (JudgeOutcome.Applied) outcome;
        Verdict voided = a.verdict().orElseThrow(() -> new IllegalStateException("VOID 판정이 기록되지 않았습니다: " + trendItemId));
        auditLogService.record(actorId, actorRole, "VERDICT_VOID", "TREND_ITEM", trendItemId, Map.of(
                "reason", reason,
                "previousVerdictId", previous.getId().toString(),
                "newVerdictId", voided.getId().toString(),
                "adjTotal", a.adjTotal().toPlainString()));
        return new VerdictActionResult("APPLIED", voided.getId().toString(), null, a.adjTotal());
    }

    @Transactional
    public VerdictActionResult requestRejudge(UUID trendItemId, UUID actorId, AdminRole actorRole, String reason) {
        requireReason(reason);
        Verdict previous = lockAndRequireJudged(trendItemId);
        JudgeOutcome outcome = judgeService.rejudge(trendItemId, reason, clock.instant(),
                AdjustmentPolicy.limitedTo(ApprovalGate.THRESHOLD));
        if (outcome instanceof JudgeOutcome.NeedsApproval n) {
            return pending(ActionType.VERDICT_REJUDGE, trendItemId, reason, n, actorId, actorRole);
        }
        JudgeOutcome.Applied a = (JudgeOutcome.Applied) outcome;
        Verdict next = a.verdict().orElseThrow();
        auditLogService.record(actorId, actorRole, "VERDICT_REJUDGE", "TREND_ITEM", trendItemId, Map.of(
                "reason", reason,
                "previousVerdictId", previous.getId().toString(),
                "newVerdictId", next.getId().toString(),
                "newResult", next.getResult().name(),
                "adjTotal", a.adjTotal().toPlainString()));
        return new VerdictActionResult("APPLIED", next.getId().toString(), null, a.adjTotal());
    }

    /** 항목 행 잠금 → 대기 요청 확인(K5) → 현행 판정. 잠금 아래에서 확인해야 요청 생성과 직접 반영이 엇갈리지 않는다. */
    private Verdict lockAndRequireJudged(UUID trendItemId) {
        trendItems.findByIdForUpdate(trendItemId)
                .orElseThrow(() -> new AdminValidationException("존재하지 않는 항목입니다"));
        if (gate.hasPendingVerdictChange(trendItemId)) {
            throw new AdminConflictException("approval-pending", "이 항목에 대기 중인 승인 요청이 있습니다 — 승인·반려 후 다시 시도하세요");
        }
        return verdicts.findCurrentByTrendItemId(trendItemId)
                .orElseThrow(() -> new AdminValidationException("판정 이력이 없는 항목입니다"));
    }

    private VerdictActionResult pending(ActionType type, UUID trendItemId, String reason, JudgeOutcome.NeedsApproval n,
                                        UUID actorId, AdminRole actorRole) {
        ApprovalRequest r = gate.request(type, trendItemId, Map.of(
                "reason", reason,
                "expectedAdjTotal", n.adjTotal().toPlainString(),
                "expectedResult", n.expectedResult().name()), actorId, actorRole);
        return new VerdictActionResult("PENDING_APPROVAL", null, r.getId().toString(), n.adjTotal());
    }

    @Transactional
    public void extendGrace(UUID trendItemId, int days, UUID actorId, AdminRole actorRole, String reason) {
        if (days < 1 || days > MAX_GRACE_DAYS) {
            throw new AdminValidationException("연장 일수는 1~%d일 사이여야 합니다".formatted(MAX_GRACE_DAYS));
        }
        if (verdicts.existsByTrendItemIdAndSupersedesIsNull(trendItemId)) {
            throw new AdminValidationException("이미 판정된 항목은 유예 연장 대상이 아닙니다");
        }
        TrendItem item = trendItems.findById(trendItemId)
                .orElseThrow(() -> new AdminValidationException("존재하지 않는 항목입니다"));

        Instant originalDeadline = item.getFirstSeenAt().plus(Duration.ofDays(JUDGE_WINDOW_DAYS));
        Instant currentDeadline = item.getJudgmentDeadlineOverride() != null
                ? item.getJudgmentDeadlineOverride() : originalDeadline;
        // 상한 = 최초 제보 + 21일. 병합 후 최소 관측 보장(MergeService)과 같은 상수를 쓴다.
        Instant ceiling = item.getFirstSeenAt().plus(Duration.ofDays(DeadlineWindow.MAX_DEADLINE_DAYS));
        Instant requested = currentDeadline.plus(Duration.ofDays(days));
        Instant capped = requested.isAfter(ceiling) ? ceiling : requested;

        if (!capped.isAfter(currentDeadline)) {
            throw new AdminValidationException("이미 최대 유예 기간(D+%d)에 도달했습니다".formatted(JUDGE_WINDOW_DAYS + MAX_GRACE_DAYS));
        }
        item.extendJudgmentDeadline(capped);
        // 마감이 지나 JUDGING으로 닫혔던 항목도 새 마감이 미래면 다시 관측 중으로 — 그동안 제보를 받는다(J6)
        if (item.getState() == TrendState.JUDGING && capped.isAfter(clock.instant())) {
            item.transitionTo(TrendState.PENDING);
        }

        auditLogService.record(actorId, actorRole, "VERDICT_GRACE_EXTEND", "TREND_ITEM", trendItemId, Map.of(
                "reason", reason == null ? "" : reason,
                "newDeadline", capped.toString()
        ));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AdminValidationException("사유는 필수입니다");
        }
    }
}
