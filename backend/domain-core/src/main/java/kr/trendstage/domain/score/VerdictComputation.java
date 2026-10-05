package kr.trendstage.domain.score;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.verdict.TrendSignal;
import kr.trendstage.domain.verdict.VerdictEngine;
import kr.trendstage.domain.verdict.VerdictOutcome;
import kr.trendstage.domain.verdict.VerdictResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 한 항목의 판정 + 클러스터 전체 제보의 점수를 산출하는 <b>순수 오케스트레이션</b>.
 * verdict_runner(운영)와 파라미터 스튜디오 시뮬레이션이 공유한다. IO 없음.
 *
 * VOID는 사건(항목 VOID·같은 유저 중복 제보)의 결과이고, 판정 시점의 VOID는 유효 제보 0건뿐이다(P5).
 */
public final class VerdictComputation {
    private VerdictComputation() {}

    /**
     * 판정 + 원장 라인. 유효 제보가 0건이면 VOID(라인 없음) — 판정 시점의 VOID는 이것뿐이다(P5).
     * 원장 라인은 시딩이 아닌 제보만 만든다(P2).
     */
    public static VerdictPlan run(TrendSignal signal, List<SubmissionRef> subs, ParameterSet p) {
        if (signal.validCount() == 0) {
            return new VerdictPlan(VerdictResult.VOID, null, 0.0, List.of(), null);
        }
        VerdictOutcome o = VerdictEngine.evaluate(signal, p);
        return new VerdictPlan(o.result(), o.reach(), o.t(), ledgerLines(o, subs, p), o.breakdown());
    }

    private static List<LedgerLine> ledgerLines(VerdictOutcome o, List<SubmissionRef> subs, ParameterSet p) {
        List<LedgerLine> lines = new ArrayList<>();
        for (SubmissionRef s : subs) {
            if (s.seed()) continue;
            ScoreResult r = ScoreEngine.compute(new ScoreInput(o.result(), s.confidence(), s.orderRank(), o.reach()), p);
            lines.add(new LedgerLine(s.userId(), s.submissionId(), o.result(), r.delta(), r.breakdown()));
        }
        return lines;
    }
}
