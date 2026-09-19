package kr.trendstage.judge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.LedgerLine;
import kr.trendstage.domain.score.SubmissionRef;
import kr.trendstage.domain.score.VerdictComputation;
import kr.trendstage.domain.score.VerdictPlan;
import kr.trendstage.domain.verdict.DeadlineWindow;
import kr.trendstage.domain.verdict.TrendSignal;
import kr.trendstage.domain.verdict.VerdictResult;
import kr.trendstage.persistence.entity.ScoreLedgerEntry;
import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.entity.SubmissionOrderRank;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.entity.UserAccount;
import kr.trendstage.persistence.entity.Verdict;
import kr.trendstage.persistence.params.CurrentParameterSetResolver;
import kr.trendstage.persistence.repo.ScoreLedgerRepository;
import kr.trendstage.persistence.repo.SubmissionOrderRankRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.UserRepository;
import kr.trendstage.persistence.repo.VerdictRepository;
import kr.trendstage.persistence.type.LedgerKind;
import kr.trendstage.persistence.type.SubmissionResult;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 판정 실행의 유일한 경로(P6). 배치(verdict_runner)·관리자 재판정·항목 VOID·ADM-111 미리보기가 공유한다.
 * 공개 메서드마다 트랜잭션이다 — 호출 측은 다른 빈이어야 프록시를 탄다(04 §3, self-invocation 금지).
 * 대상 항목은 행 잠금(SELECT … FOR UPDATE)으로 직렬화한다.
 */
@Service
public class JudgeService {

    private final TrendItemRepository trendItems;
    private final SubmissionRepository submissions;
    private final SubmissionOrderRankRepository orderRanks;
    private final UserRepository users;
    private final VerdictRepository verdicts;
    private final ScoreLedgerRepository ledger;
    private final CurrentParameterSetResolver params;
    private final ObjectMapper objectMapper;

    public JudgeService(TrendItemRepository trendItems, SubmissionRepository submissions,
                        SubmissionOrderRankRepository orderRanks, UserRepository users, VerdictRepository verdicts,
                        ScoreLedgerRepository ledger, CurrentParameterSetResolver params, ObjectMapper objectMapper) {
        this.trendItems = trendItems;
        this.submissions = submissions;
        this.orderRanks = orderRanks;
        this.users = users;
        this.verdicts = verdicts;
        this.ledger = ledger;
        this.params = params;
        this.objectMapper = objectMapper;
    }

    /** 관측 마감이 지난 PENDING 항목을 JUDGING으로 닫는다(J6 — 이후 제보를 받지 않는다). @return 닫은 항목 수 */
    @Transactional
    public int closeDue(Instant now) {
        int closed = 0;
        for (TrendItem item : trendItems.findByStateIn(List.of(TrendState.PENDING))) {
            if (!now.isBefore(deadlineOf(item))) {
                item.transitionTo(TrendState.JUDGING);
                closed++;
            }
        }
        return closed;
    }

    /**
     * 원본 판정. 멱등 — JUDGING이 아니거나 원본 판정이 이미 있으면 아무것도 하지 않는다
     * (최종 방어는 DB의 verdict_one_original_per_item). 유예 연장으로 마감이 다시 미래가 됐으면 PENDING으로 되돌린다.
     *
     * @return 판정했으면 true
     */
    @Transactional
    public boolean judge(UUID itemId, Instant now) {
        TrendItem item = lock(itemId);
        if (item.getState() != TrendState.JUDGING || verdicts.existsByTrendItemIdAndSupersedesIsNull(itemId)) {
            return false;
        }
        Instant deadline = deadlineOf(item);
        if (now.isBefore(deadline)) {
            item.transitionTo(TrendState.PENDING);
            return false;
        }
        ParameterSet p = params.resolve();
        Inputs in = collect(item, deadline);
        VerdictPlan plan = VerdictComputation.run(in.signal(), in.refs(), p);

        Verdict verdict = verdicts.saveAndFlush(new Verdict(itemId, plan.result(), plan.reach(), scoreT(plan),
                now, evidence(plan, in, p, null, null), null));
        for (LedgerLine line : plan.ledgerLines()) {
            ledger.save(ScoreLedgerEntry.ofVerdict(line.userId(), line.submissionId(), verdict.getId(),
                    toLedgerKind(line.kind()), amount(line.delta()), line.reason(), p.halflifeDays, now));
        }
        for (Submission s : in.submissions()) {
            s.markResult(toSubmissionResult(plan.result()), now);
        }
        item.transitionTo(plan.result() == VerdictResult.VOID ? TrendState.VOID : TrendState.RESOLVED);
        return true;
    }

    // ── 공용 ──────────────────────────────────────────────────────────────

    TrendItem lock(UUID itemId) {
        return trendItems.findByIdForUpdate(itemId)
                .orElseThrow(() -> new JudgeRejectedException("존재하지 않는 항목입니다"));
    }

    static Instant deadlineOf(TrendItem item) {
        return DeadlineWindow.effectiveDeadline(item.getFirstSeenAt(), item.getJudgmentDeadlineOverride());
    }

    /** 관측 마감 전 비VOID 제보 → 판정 신호·점수 입력. 선점 순위는 시딩을 뺀 뷰(J3). */
    Inputs collect(TrendItem item, Instant deadline) {
        List<Submission> subs = submissions.findByTrendItemIdAndResultNot(item.getId(), SubmissionResult.VOID).stream()
                .filter(s -> s.getCreatedAt().isBefore(deadline))
                .sorted(Comparator.comparing(Submission::getCreatedAt))
                .toList();
        Map<UUID, Instant> joinedAt = users.findAllById(subs.stream().map(Submission::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getJoinedAt));
        Map<UUID, Integer> ranks = orderRanks.findByTrendItemId(item.getId()).stream()
                .collect(Collectors.toMap(SubmissionOrderRank::getSubmissionId, SubmissionOrderRank::getOrderRank));

        List<TrendSignal.Entry> entries = subs.stream().map(s -> new TrendSignal.Entry(
                s.getId(), s.getUserId(), s.isSeed(), s.getCreatedAt(), s.getSourcePlatform(),
                joinedAt.get(s.getUserId()))).toList();
        List<SubmissionRef> refs = subs.stream().map(s -> new SubmissionRef(
                s.getId(), s.getUserId(), s.getConfidence(),
                ranks.getOrDefault(s.getId(), Integer.MAX_VALUE), s.isSeed())).toList();
        return new Inputs(new TrendSignal(deadline, entries), refs, subs, ranks);
    }

    String evidence(VerdictPlan plan, Inputs in, ParameterSet p, UUID supersededVerdictId, String adminReason) {
        return write(new VerdictEvidence(plan.result().name(), plan.reach() == null ? null : plan.reach().name(),
                scoreT(plan), in.signal().deadline(), ParamsSnapshot.of(p), in.signal(),
                in.signal().distinctSubmitters(), in.signal().distinctPlatforms(), in.ranks(),
                supersededVerdictId, adminReason));
    }

    String write(VerdictEvidence ev) {
        try {
            return objectMapper.writeValueAsString(ev);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("판정 근거 직렬화 실패", e);
        }
    }

    static BigDecimal scoreT(VerdictPlan plan) {
        return plan.result() == VerdictResult.VOID ? null
                : BigDecimal.valueOf(plan.t()).setScale(4, RoundingMode.HALF_UP);
    }

    /** 원장 금액 — score_ledger.delta(NUMERIC(10,4))와 같은 자릿수. 재판정 차액 비교에 부동소수 잡음이 끼지 않게 한다. */
    static BigDecimal amount(double delta) {
        return BigDecimal.valueOf(delta).setScale(4, RoundingMode.HALF_UP);
    }

    static LedgerKind toLedgerKind(VerdictResult r) {
        return switch (r) {
            case HIT -> LedgerKind.HIT;
            case MISS -> LedgerKind.MISS;
            case VOID -> throw new IllegalStateException("VOID 판정에는 원장 라인이 없다");
        };
    }

    static SubmissionResult toSubmissionResult(VerdictResult r) {
        return switch (r) {
            case HIT -> SubmissionResult.HIT;
            case MISS -> SubmissionResult.MISS;
            case VOID -> SubmissionResult.VOID;
        };
    }

    record Inputs(TrendSignal signal, List<SubmissionRef> refs, List<Submission> submissions, Map<UUID, Integer> ranks) {}
}
