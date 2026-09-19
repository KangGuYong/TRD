package kr.trendstage.scheduler;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.LedgerLine;
import kr.trendstage.domain.score.SubmissionRef;
import kr.trendstage.domain.score.VerdictComputation;
import kr.trendstage.domain.score.VerdictPlan;
import kr.trendstage.domain.verdict.SubmissionSignal;
import kr.trendstage.domain.verdict.VerdictResult;
import kr.trendstage.persistence.entity.*;
import kr.trendstage.persistence.repo.*;
import kr.trendstage.persistence.type.LedgerKind;
import kr.trendstage.persistence.type.SubmissionResult;
import kr.trendstage.persistence.type.TrendState;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * D+14 판정 배치. 도달 항목에 VerdictEngine→ScoreEngine(순수)을 호출해 verdicts + score_ledger 기록.
 *
 * 멱등: 원본 판정이 이미 있으면 skip(existsBy...SupersedesIsNull) — 재실행이 점수를 두 번 주지 않는다(04 §6).
 * 시딩분(is_seed)은 판정 결과는 매기되 점수 원장에는 반영하지 않는다(01 §8).
 * 선점 순위는 뷰에서 읽어 evidence_json으로 동결한다(03 §3.1).
 */
@Component
public class VerdictRunner {

    private static final Logger log = LoggerFactory.getLogger(VerdictRunner.class);
    private static final int JUDGE_WINDOW_DAYS = 14;

    private final TrendItemRepository trendItems;
    private final SubmissionRepository submissions;
    private final SubmissionOrderRankRepository orderRanks;
    private final VerdictRepository verdicts;
    private final ScoreLedgerRepository ledger;
    private final ParameterSetProvider params;
    private final Clock clock;

    public VerdictRunner(TrendItemRepository trendItems, SubmissionRepository submissions,
                         SubmissionOrderRankRepository orderRanks, VerdictRepository verdicts,
                         ScoreLedgerRepository ledger,
                         ParameterSetProvider params, Clock clock) {
        this.trendItems = trendItems; this.submissions = submissions; this.orderRanks = orderRanks;
        this.verdicts = verdicts; this.ledger = ledger;
        this.params = params; this.clock = clock;
    }

    /** 일 1회 03:00 KST(=UTC 기준 cron은 배포 타임존에 맞춰 조정). */
    @Scheduled(cron = "${jobs.verdict-runner.cron:0 0 18 * * *}")
    @SchedulerLock(name = "verdict_runner", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        Instant now = clock.instant();
        List<TrendItem> candidates = trendItems.findByStateIn(List.of(TrendState.PENDING, TrendState.JUDGING));
        int judged = 0;
        for (TrendItem item : candidates) {
            if (!isDue(item, now)) continue;
            if (verdicts.existsByTrendItemIdAndSupersedesIsNull(item.getId())) continue; // 멱등
            try {
                judgeOne(item, now);
                judged++;
            } catch (Exception e) {
                log.error("판정 실패 item={} : {}", item.getId(), e.getMessage(), e);
            }
        }
        log.info("verdict_runner 완료: 후보 {} · 판정 {}", candidates.size(), judged);
    }

    private boolean isDue(TrendItem item, Instant now) {
        // ADM-200 유예 연장(judgment_deadline_override)이 있으면 그걸 우선한다 — 없으면 기본 D+14.
        Instant deadline = item.getJudgmentDeadlineOverride() != null
                ? item.getJudgmentDeadlineOverride()
                : item.getFirstSeenAt().plus(Duration.ofDays(JUDGE_WINDOW_DAYS));
        return !now.isBefore(deadline);
    }

    @Transactional
    protected void judgeOne(TrendItem item, Instant judgedAt) {
        ParameterSet p = params.current();

        // 1) 선점 순위 동결 + 제보 스냅샷
        Map<UUID, Integer> rankById = new HashMap<>();
        for (SubmissionOrderRank r : orderRanks.findByTrendItemId(item.getId())) {
            rankById.put(r.getSubmissionId(), r.getOrderRank());
        }
        List<Submission> subEntities = submissions.findByTrendItemIdAndResultNot(item.getId(), SubmissionResult.VOID);
        Map<UUID, Submission> subById = new HashMap<>();
        List<SubmissionRef> refs = new ArrayList<>();
        for (Submission s : subEntities) {
            subById.put(s.getId(), s);
            refs.add(new SubmissionRef(
                    s.getId(), s.getUserId(), s.getConfidence(),
                    rankById.getOrDefault(s.getId(), Integer.MAX_VALUE),
                    s.isSeed()));
        }

        // 2) 제보 신호 집계 — 외부 지표 없이 제보 자체가 판정 근거(R1 개정)
        long distinctSubmitters = subEntities.stream().map(Submission::getUserId).distinct().count();
        long distinctPlatforms = subEntities.stream().map(Submission::getSourcePlatform)
                .filter(Objects::nonNull).distinct().count();
        SubmissionSignal signal = new SubmissionSignal((int) distinctSubmitters, (int) distinctPlatforms);

        // 3) 순수 판정 계산
        VerdictPlan plan = VerdictComputation.run(signal, false, refs, p);

        // 4) verdicts 기록 (evidence_json에 순위·신호 동결)
        BigDecimal t = BigDecimal.valueOf(plan.t()).setScale(4, RoundingMode.HALF_UP);
        String evidence = buildEvidence(plan, rankById, signal, t);
        Verdict verdict = verdicts.save(new Verdict(
                item.getId(), plan.result(), plan.reach(),
                plan.result() == VerdictResult.VOID ? null : t,
                judgedAt, evidence, null));

        // 5) score_ledger(시딩 제외는 plan이 보장) + 제보 결과(시딩 포함 전부)
        for (LedgerLine line : plan.ledgerLines()) {
            ledger.save(ScoreLedgerEntry.ofVerdict(
                    line.userId(), line.submissionId(), verdict.getId(),
                    toLedgerKind(line.kind()), BigDecimal.valueOf(line.delta()), line.reason(), p.halflifeDays, judgedAt));
        }
        for (Submission s : subEntities) {
            s.markResult(toSubResult(plan.result()), judgedAt);
        }

        // 6) 항목 상태 전이
        item.transitionTo(plan.result() == VerdictResult.VOID ? TrendState.VOID : TrendState.RESOLVED);
    }

    private String buildEvidence(VerdictPlan plan, Map<UUID, Integer> ranks,
                                 SubmissionSignal signal, BigDecimal t) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"result\":\"").append(plan.result()).append("\",");
        sb.append("\"reach\":").append(plan.reach() == null ? "null" : "\"" + plan.reach() + "\"").append(",");
        sb.append("\"t\":").append(t).append(",");
        sb.append("\"distinctSubmitters\":").append(signal.distinctSubmitters()).append(",");
        sb.append("\"distinctPlatforms\":").append(signal.distinctPlatforms()).append(",");
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

    private static LedgerKind toLedgerKind(VerdictResult r) {
        return switch (r) { case HIT -> LedgerKind.HIT; case MISS -> LedgerKind.MISS; case VOID -> LedgerKind.VOID; };
    }
    private static SubmissionResult toSubResult(VerdictResult r) {
        return switch (r) { case HIT -> SubmissionResult.HIT; case MISS -> SubmissionResult.MISS; case VOID -> SubmissionResult.VOID; };
    }
}
