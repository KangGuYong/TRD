package kr.trendstage.scheduler;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.LedgerLine;
import kr.trendstage.domain.score.SubmissionRef;
import kr.trendstage.domain.score.VerdictComputation;
import kr.trendstage.domain.score.VerdictPlan;
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
    private final MetricAggregator aggregator;
    private final ParameterSetProvider params;
    private final Clock clock;

    public VerdictRunner(TrendItemRepository trendItems, SubmissionRepository submissions,
                         SubmissionOrderRankRepository orderRanks, VerdictRepository verdicts,
                         ScoreLedgerRepository ledger, MetricAggregator aggregator,
                         ParameterSetProvider params, Clock clock) {
        this.trendItems = trendItems; this.submissions = submissions; this.orderRanks = orderRanks;
        this.verdicts = verdicts; this.ledger = ledger; this.aggregator = aggregator;
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
        return !now.isBefore(item.getFirstSeenAt().plus(Duration.ofDays(JUDGE_WINDOW_DAYS)));
    }

    @Transactional
    protected void judgeOne(TrendItem item, Instant judgedAt) {
        ParameterSet p = params.current();

        // 1) 지표 정규화
        MetricAggregator.Result agg = aggregator.aggregate(item);

        // 2) 선점 순위 동결 + 제보 스냅샷
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
                    Duration.between(s.getCreatedAt(), judgedAt).toDays()));
        }

        // 3) 순수 판정 계산
        VerdictPlan plan = VerdictComputation.run(agg.signals(), agg.missingCount(), false, refs, p);

        // 4) verdicts 기록 (evidence_json에 순위·신호·결측 동결)
        BigDecimal t = BigDecimal.valueOf(plan.t()).setScale(4, RoundingMode.HALF_UP);
        String evidence = buildEvidence(plan, rankById, agg, t);
        Verdict verdict = verdicts.save(new Verdict(
                item.getId(), plan.result(), plan.reach(),
                plan.result() == VerdictResult.VOID ? null : t,
                judgedAt, evidence, null));

        // 5) score_ledger + 제보 결과 반영 (시딩분은 원장 제외)
        for (LedgerLine line : plan.lines()) {
            Submission sub = subById.get(line.submissionId());
            if (sub == null) continue;
            sub.markResult(toSubResult(line.kind()));
            if (!sub.isSeed() && line.kind() != VerdictResult.VOID) {
                ledger.save(ScoreLedgerEntry.ofVerdict(
                        line.userId(), line.submissionId(), verdict.getId(),
                        toLedgerKind(line.kind()), BigDecimal.valueOf(line.delta()), line.reason()));
            }
            // VOID면 제보권 반환은 QuotaService 도입 후 처리(TODO)
        }

        // 6) 항목 상태 전이
        item.transitionTo(plan.result() == VerdictResult.VOID ? TrendState.VOID : TrendState.RESOLVED);
    }

    private String buildEvidence(VerdictPlan plan, Map<UUID, Integer> ranks,
                                 MetricAggregator.Result agg, BigDecimal t) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"result\":\"").append(plan.result()).append("\",");
        sb.append("\"reach\":").append(plan.reach() == null ? "null" : "\"" + plan.reach() + "\"").append(",");
        sb.append("\"t\":").append(t).append(",");
        sb.append("\"missingSources\":").append(agg.missingCount()).append(",");
        var s = agg.signals();
        sb.append("\"signals\":[").append(s.s1()).append(",").append(s.s2()).append(",")
          .append(s.s3()).append(",").append(s.s4()).append(",").append(s.s5()).append("],");
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
