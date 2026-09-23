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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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

    /**
     * 재판정 — 원 판정 때 동결한 파라미터로 다시 계산해 supersede 판정을 쌓고, 원장은 제보 단위 차액만 ADJ로 남긴다(J2).
     * 차액 절댓값 합이 policy 한도를 넘으면 아무것도 쓰지 않고 NeedsApproval(SP3 K4).
     */
    @Transactional
    public JudgeOutcome rejudge(UUID itemId, String reason, Instant now, AdjustmentPolicy policy) {
        TrendItem item = lock(itemId);
        Verdict current = verdicts.findCurrentByTrendItemId(itemId)
                .orElseThrow(() -> new JudgeRejectedException("판정 이력이 없는 항목입니다"));
        if (current.getResult() == VerdictResult.VOID) {
            throw new JudgeConflictException("이미 VOID 처리된 항목은 다시 판정할 수 없습니다");
        }
        Chain chain = chainOf(itemId);
        Inputs in = collect(item, deadlineOf(item));
        VerdictPlan plan = VerdictComputation.run(in.signal(), in.refs(), chain.params());
        Map<UUID, Diff> diffs = diffs(chain, plan.ledgerLines());
        BigDecimal total = total(diffs);
        if (policy.exceededBy(total)) {
            return new JudgeOutcome.NeedsApproval(total, plan.result());
        }

        Verdict next = supersede(new Verdict(itemId, plan.result(), plan.reach(), scoreT(plan), now,
                evidence(plan, in, chain.params(), current.getId(), reason), current.getId()));
        writeAdjustments(diffs, chain, next, "재판정 · " + reason, policy);
        for (Submission s : in.submissions()) {
            s.markResult(toSubmissionResult(plan.result()), now);
        }
        if (plan.result() == VerdictResult.VOID) {
            item.transitionTo(TrendState.VOID);
        }
        return new JudgeOutcome.Applied(Optional.of(next), total);
    }

    /**
     * 항목 VOID(P5) — ADM-100 큐 VOID와 ADM-200 VOID의 공통 경로. 판정된 항목이면 원장을 제보 단위로 전액 상쇄하는데,
     * 그 합이 policy 한도를 넘으면 아무것도 쓰지 않고 NeedsApproval. 판정 전 항목은 변동 0이라 항상 반영.
     */
    @Transactional
    public JudgeOutcome voidItem(UUID itemId, String reason, Instant now, AdjustmentPolicy policy) {
        TrendItem item = lock(itemId);
        if (item.getState() == TrendState.MERGED || item.getState() == TrendState.VOID) {
            throw new JudgeConflictException("이미 병합됐거나 VOID된 항목입니다");
        }
        Optional<Verdict> current = verdicts.findCurrentByTrendItemId(itemId);
        Chain chain = current.isPresent() ? chainOf(itemId) : null;
        Map<UUID, Diff> diffs = chain == null ? Map.of() : diffs(chain, List.of());
        BigDecimal total = total(diffs);
        if (policy.exceededBy(total)) {
            return new JudgeOutcome.NeedsApproval(total, VerdictResult.VOID);
        }

        for (Submission s : submissions.findByTrendItemIdAndResultNot(itemId, SubmissionResult.VOID)) {
            s.voidOut(now);
        }
        Optional<Verdict> voided = Optional.empty();
        if (current.isPresent()) {
            Verdict next = supersede(new Verdict(itemId, VerdictResult.VOID, null, null, now,
                    write(new VerdictEvidence("VOID", null, null, null, null, null, 0, 0, Map.of(),
                            current.get().getId(), reason)),
                    current.get().getId()));
            writeAdjustments(diffs, chain, next, "VOID · " + (reason == null ? "" : reason), policy);
            voided = Optional.of(next);
        }
        item.transitionTo(TrendState.VOID);
        return new JudgeOutcome.Applied(voided, total);
    }

    /** 판정 체인 — 원본의 파라미터·판정 시각이 재판정·VOID 차액의 기준이다(J1·J2). */
    record Chain(List<UUID> verdictIds, ParameterSet params, Instant anchor) {}

    private Chain chainOf(UUID itemId) {
        List<Verdict> all = verdicts.findByTrendItemIdOrderByCreatedAtAsc(itemId);
        Verdict original = all.stream().filter(v -> v.getSupersedes() == null).findFirst()
                .orElseThrow(() -> new IllegalStateException("원본 판정이 없습니다: " + itemId));
        ParamsSnapshot frozen = read(original).params();
        if (frozen == null) {
            throw new IllegalStateException("원본 판정 근거에 파라미터가 없습니다: " + original.getId());
        }
        return new Chain(all.stream().map(Verdict::getId).toList(), frozen.toParameterSet(), original.getJudgedAt());
    }

    /** 제보 단위 차액 한 줄. */
    record Diff(UUID userId, BigDecimal amount) {}

    /** 체인 원장을 제보 단위로 합산해 새 라인과의 차액을 계산한다(쓰기 없음). 0인 제보는 빠진다. */
    private Map<UUID, Diff> diffs(Chain chain, List<LedgerLine> lines) {
        Map<UUID, BigDecimal> before = new TreeMap<>();
        Map<UUID, UUID> owner = new TreeMap<>();
        for (ScoreLedgerEntry e : ledger.findByVerdictIdIn(chain.verdictIds())) {
            if (e.getSubmissionId() == null) continue;
            before.merge(e.getSubmissionId(), e.getDelta(), BigDecimal::add);
            owner.put(e.getSubmissionId(), e.getUserId());
        }
        Map<UUID, BigDecimal> after = new TreeMap<>();
        for (LedgerLine line : lines) {
            after.put(line.submissionId(), amount(line.delta()));
            owner.put(line.submissionId(), line.userId());
        }
        Set<UUID> touched = new java.util.TreeSet<>(before.keySet());
        touched.addAll(after.keySet());
        Map<UUID, Diff> out = new LinkedHashMap<>();
        for (UUID submissionId : touched) {
            BigDecimal diff = after.getOrDefault(submissionId, BigDecimal.ZERO)
                    .subtract(before.getOrDefault(submissionId, BigDecimal.ZERO));
            if (diff.signum() != 0) out.put(submissionId, new Diff(owner.get(submissionId), diff));
        }
        return out;
    }

    /** 차액 절댓값 합(K3) — 원장 자릿수(4). */
    static BigDecimal total(Map<UUID, Diff> diffs) {
        return diffs.values().stream().map(d -> d.amount().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(4, RoundingMode.HALF_UP);
    }

    /** 차액을 ADJ로 남긴다 — 원 판정과 같은 감쇠 기준(J1). 승인 실행이면 승인 정보를 채운다. */
    private void writeAdjustments(Map<UUID, Diff> diffs, Chain chain, Verdict next, String reason, AdjustmentPolicy policy) {
        diffs.forEach((submissionId, d) -> ledger.save(ScoreLedgerEntry.verdictAdjustment(d.userId(), submissionId,
                next.getId(), d.amount(), reason, chain.params().halflifeDays, chain.anchor(),
                policy.approvalId(), policy.approvedBy())));
    }

    /** 체인 분기는 DB(verdict_superseded_once)가 막는다 — 행 잠금 덕에 거의 없지만 마지막 방어선을 409로 옮긴다. */
    private Verdict supersede(Verdict next) {
        try {
            return verdicts.saveAndFlush(next);
        } catch (DataIntegrityViolationException e) {
            throw new JudgeConflictException("다른 요청이 먼저 이 판정을 대체했습니다 — 새로고침 후 다시 시도하세요");
        }
    }

    VerdictEvidence read(Verdict v) {
        try {
            return objectMapper.readValue(v.getEvidenceJson(), VerdictEvidence.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("판정 근거 파싱 실패: " + v.getId(), e);
        }
    }

    /** ADM-111 예상 판정 — 현재 파라미터와 지금까지의 신호로 계산만 한다(저장 없음). */
    @Transactional(readOnly = true)
    public Preview preview(UUID itemId) {
        TrendItem item = trendItems.findById(itemId)
                .orElseThrow(() -> new JudgeRejectedException("존재하지 않는 항목입니다"));
        Inputs in = collect(item, deadlineOf(item));
        return new Preview(in.signal(), VerdictComputation.run(in.signal(), in.refs(), params.resolve()));
    }

    public record Preview(TrendSignal signal, VerdictPlan plan) {}

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
