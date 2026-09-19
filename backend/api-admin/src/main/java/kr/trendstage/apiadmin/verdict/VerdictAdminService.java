package kr.trendstage.apiadmin.verdict;

import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.LedgerLine;
import kr.trendstage.domain.score.SubmissionRef;
import kr.trendstage.domain.score.VerdictComputation;
import kr.trendstage.domain.score.VerdictPlan;
import kr.trendstage.domain.verdict.SubmissionSignal;
import kr.trendstage.domain.verdict.VerdictResult;
import kr.trendstage.persistence.entity.*;
import kr.trendstage.persistence.repo.*;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.SubmissionResult;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * ADM-200. R1: 관리자는 판정 결과를 직접 바꾸지 못한다 — VOID·재판정 모두 엔진(VerdictComputation)을
 * 다시 돌려서 나온 결과를 supersede 행으로 쌓을 뿐이다. VOID는 그 엔진을 voidByRule=true로 돌리는
 * 특수 케이스라 재판정과 같은 경로(applySupersede)를 공유한다.
 *
 * VerdictRunner(배치)의 판정 로직을 여기서 일부 중복한다 — 별도 트랜잭션/공유 모듈로 추출하는 대신
 * 이미 검증된 배치 코드를 건드리지 않는 쪽을 택했다(점수 계산은 Phase 0에서 가장 위험도가 높은 영역).
 */
@Service
public class VerdictAdminService {

    private static final int JUDGE_WINDOW_DAYS = 14;
    private static final int MAX_GRACE_DAYS = 7;

    private final TrendItemRepository trendItems;
    private final SubmissionRepository submissions;
    private final SubmissionOrderRankRepository orderRanks;
    private final VerdictRepository verdicts;
    private final ScoreLedgerRepository ledger;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public VerdictAdminService(TrendItemRepository trendItems, SubmissionRepository submissions,
                                SubmissionOrderRankRepository orderRanks, VerdictRepository verdicts,
                                ScoreLedgerRepository ledger, AuditLogService auditLogService, Clock clock) {
        this.trendItems = trendItems;
        this.submissions = submissions;
        this.orderRanks = orderRanks;
        this.verdicts = verdicts;
        this.ledger = ledger;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Transactional
    public void voidVerdict(UUID trendItemId, UUID actorId, AdminRole actorRole, String reason) {
        requireReason(reason);
        applySupersede(trendItemId, actorId, actorRole, reason, true, "VERDICT_VOID");
    }

    @Transactional
    public void requestRejudge(UUID trendItemId, UUID actorId, AdminRole actorRole, String reason) {
        requireReason(reason);
        applySupersede(trendItemId, actorId, actorRole, reason, false, "VERDICT_REJUDGE");
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
        Instant ceiling = originalDeadline.plus(Duration.ofDays(MAX_GRACE_DAYS));
        Instant requested = currentDeadline.plus(Duration.ofDays(days));
        Instant capped = requested.isAfter(ceiling) ? ceiling : requested;

        if (!capped.isAfter(currentDeadline)) {
            throw new AdminValidationException("이미 최대 유예 기간(D+%d)에 도달했습니다".formatted(JUDGE_WINDOW_DAYS + MAX_GRACE_DAYS));
        }
        item.extendJudgmentDeadline(capped);

        auditLogService.record(actorId, actorRole, "VERDICT_GRACE_EXTEND", "TREND_ITEM", trendItemId, Map.of(
                "reason", reason == null ? "" : reason,
                "newDeadline", capped.toString()
        ));
    }

    /** VOID·재판정 공유 경로: 새 플랜을 계산해 supersede 행을 쌓고, 원장을 차액만큼만 보정한다. */
    private void applySupersede(UUID trendItemId, UUID actorId, AdminRole actorRole, String reason,
                                 boolean voidByRule, String auditAction) {
        Verdict current = verdicts.findCurrentByTrendItemId(trendItemId)
                .orElseThrow(() -> new AdminValidationException("판정 이력이 없는 항목입니다"));
        // VOID는 종결 상태다 — 관련 제보가 이미 VOID 마킹되어 제보권이 반환됐으므로 재판정 대상 신호가 남아있지 않다.
        if (current.getResult() == VerdictResult.VOID) {
            throw new AdminValidationException("이미 VOID 처리된 항목은 다시 처리할 수 없습니다");
        }
        TrendItem item = trendItems.findById(trendItemId)
                .orElseThrow(() -> new AdminValidationException("존재하지 않는 항목입니다"));
        Instant now = clock.instant();

        Map<UUID, Integer> rankById = new HashMap<>();
        for (SubmissionOrderRank r : orderRanks.findByTrendItemId(trendItemId)) {
            rankById.put(r.getSubmissionId(), r.getOrderRank());
        }
        List<Submission> subEntities = submissions.findByTrendItemIdAndResultNot(trendItemId, SubmissionResult.VOID);
        Map<UUID, Submission> subById = new HashMap<>();
        List<SubmissionRef> refs = new ArrayList<>();
        for (Submission s : subEntities) {
            subById.put(s.getId(), s);
            refs.add(new SubmissionRef(
                    s.getId(), s.getUserId(), s.getConfidence(),
                    rankById.getOrDefault(s.getId(), Integer.MAX_VALUE),
                    Duration.between(s.getCreatedAt(), now).toDays()));
        }

        long distinctSubmitters = subEntities.stream().map(Submission::getUserId).distinct().count();
        long distinctPlatforms = subEntities.stream().map(Submission::getSourcePlatform)
                .filter(Objects::nonNull).distinct().count();
        SubmissionSignal signal = new SubmissionSignal((int) distinctSubmitters, (int) distinctPlatforms);

        // TODO: ADM-600 파라미터 스튜디오가 붙으면 ParameterSetProvider(현재는 scheduler 모듈 전용,
        // 내용은 어차피 defaults() 고정)를 통해 읽도록 교체.
        ParameterSet p = ParameterSet.defaults();
        VerdictPlan plan = VerdictComputation.run(signal, voidByRule, refs, p);

        BigDecimal t = BigDecimal.valueOf(plan.t()).setScale(4, RoundingMode.HALF_UP);
        String evidence = buildEvidence(plan, rankById, signal, t, current.getId(), reason);
        Verdict newVerdict = verdicts.save(new Verdict(
                trendItemId, plan.result(), plan.reach(),
                plan.result() == VerdictResult.VOID ? null : t,
                now, evidence, current.getId()));

        reconcileLedger(current, plan, "%s: %s".formatted(auditAction, reason), p.halflifeDays, now);

        for (LedgerLine line : plan.lines()) {
            Submission sub = subById.get(line.submissionId());
            if (sub != null) sub.markResult(toSubResult(line.kind()), now);
        }

        item.transitionTo(plan.result() == VerdictResult.VOID ? TrendState.VOID : TrendState.RESOLVED);

        auditLogService.record(actorId, actorRole, auditAction, "TREND_ITEM", trendItemId, Map.of(
                "reason", reason,
                "previousVerdictId", current.getId().toString(),
                "newVerdictId", newVerdict.getId().toString(),
                "newResult", plan.result().name()
        ));
    }

    /**
     * 기존 판정분(verdictId 귀속 원장)과 새 플랜을 유저별로 비교해 차액만 ADJ로 남긴다. 0이면 아무것도 안 남김.
     * approved_by는 users(id) FK라 관리자(admin_accounts.id)를 넣을 수 없다 — 승인자는 감사 로그에만 남긴다.
     */
    private void reconcileLedger(Verdict oldVerdict, VerdictPlan newPlan, String reason,
                                 int halflifeDays, Instant anchor) {
        Map<UUID, BigDecimal> oldByUser = new HashMap<>();
        for (ScoreLedgerEntry old : ledger.findByVerdictId(oldVerdict.getId())) {
            oldByUser.merge(old.getUserId(), old.getDelta(), BigDecimal::add);
        }
        Map<UUID, BigDecimal> newByUser = new HashMap<>();
        for (LedgerLine line : newPlan.lines()) {
            if (line.kind() == VerdictResult.VOID) continue; // VOID 라인은 항상 delta 0
            newByUser.merge(line.userId(), BigDecimal.valueOf(line.delta()), BigDecimal::add);
        }
        Set<UUID> allUsers = new HashSet<>();
        allUsers.addAll(oldByUser.keySet());
        allUsers.addAll(newByUser.keySet());
        for (UUID userId : allUsers) {
            BigDecimal diff = newByUser.getOrDefault(userId, BigDecimal.ZERO)
                    .subtract(oldByUser.getOrDefault(userId, BigDecimal.ZERO));
            if (diff.signum() != 0) {
                ledger.save(ScoreLedgerEntry.adjustment(userId, diff, reason, null, null, halflifeDays, anchor));
            }
        }
    }

    private String buildEvidence(VerdictPlan plan, Map<UUID, Integer> ranks, SubmissionSignal signal,
                                  BigDecimal t, UUID supersededVerdictId, String reason) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"result\":\"").append(plan.result()).append("\",");
        sb.append("\"reach\":").append(plan.reach() == null ? "null" : "\"" + plan.reach() + "\"").append(",");
        sb.append("\"t\":").append(t).append(",");
        sb.append("\"distinctSubmitters\":").append(signal.distinctSubmitters()).append(",");
        sb.append("\"distinctPlatforms\":").append(signal.distinctPlatforms()).append(",");
        sb.append("\"supersededVerdictId\":\"").append(supersededVerdictId).append("\",");
        sb.append("\"adminReason\":\"").append(reason == null ? "" : reason.replace("\"", "'")).append("\",");
        sb.append("\"orderRanks\":{");
        boolean first = true;
        for (var e : ranks.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":").append(e.getValue());
            first = false;
        }
        sb.append("}}");
        return sb.toString();
    }

    private static SubmissionResult toSubResult(VerdictResult r) {
        return switch (r) { case HIT -> SubmissionResult.HIT; case MISS -> SubmissionResult.MISS; case VOID -> SubmissionResult.VOID; };
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AdminValidationException("사유는 필수입니다");
        }
    }
}
