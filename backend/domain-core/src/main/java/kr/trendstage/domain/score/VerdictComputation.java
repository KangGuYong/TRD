package kr.trendstage.domain.score;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.verdict.SignalScores;
import kr.trendstage.domain.verdict.VerdictEngine;
import kr.trendstage.domain.verdict.VerdictOutcome;
import kr.trendstage.domain.verdict.VerdictResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 한 항목의 판정 + 클러스터 전체 제보의 점수를 산출하는 <b>순수 오케스트레이션</b>.
 * verdict_runner(운영)와 파라미터 스튜디오 시뮬레이션이 공유한다. IO 없음.
 *
 * VOID 조건(01 §4.3): 지표 2종 이상 결측 / 제보 시점 이미 대중화 / 규정 위반.
 * 규정 위반(허위 URL·미고지)은 상위(수집·검수)에서 판단해 alreadyMainstream처럼 플래그로 넘긴다.
 */
public final class VerdictComputation {
    private VerdictComputation() {}

    public static VerdictPlan run(SignalScores signals, int missingSourceCount, boolean voidByRule,
                                  List<SubmissionRef> subs, ParameterSet p) {
        double t = VerdictEngine.computeT(signals, p);

        // ── VOID: 점수 변동 없음, 제보권만 반환(반환은 스케줄러가 처리) ──
        if (missingSourceCount >= 2 || voidByRule) {
            String why = voidByRule ? "VOID · 규정 위반/기준선 초과"
                                    : "VOID · 지표 " + missingSourceCount + "종 결측";
            List<LedgerLine> lines = new ArrayList<>();
            for (SubmissionRef s : subs) {
                lines.add(new LedgerLine(s.userId(), s.submissionId(), VerdictResult.VOID, 0.0, why));
            }
            return new VerdictPlan(VerdictResult.VOID, null, t, lines);
        }

        VerdictOutcome o = VerdictEngine.evaluate(signals, p);
        List<LedgerLine> lines = new ArrayList<>();
        for (SubmissionRef s : subs) {
            ScoreResult r = ScoreEngine.compute(
                    new ScoreInput(o.result(), s.confidence(), s.orderRank(), o.reach(), s.elapsedDays()), p);
            lines.add(new LedgerLine(s.userId(), s.submissionId(), o.result(), r.delta(), r.breakdown()));
        }
        return new VerdictPlan(o.result(), o.reach(), o.t(), lines);
    }
}
