package kr.trendstage.domain.score;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.verdict.SubmissionSignal;
import kr.trendstage.domain.verdict.VerdictEngine;
import kr.trendstage.domain.verdict.VerdictOutcome;
import kr.trendstage.domain.verdict.VerdictResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 한 항목의 판정 + 클러스터 전체 제보의 점수를 산출하는 <b>순수 오케스트레이션</b>.
 * verdict_runner(운영)와 파라미터 스튜디오 시뮬레이션이 공유한다. IO 없음.
 *
 * VOID 조건(01 §4.3, 개정): 규정 위반(허위 URL·미고지) / 같은 유저 중복 제보.
 * 상위(수집·검수)에서 판단해 voidByRule 플래그로 넘긴다.
 */
public final class VerdictComputation {
    private VerdictComputation() {}

    public static VerdictPlan run(SubmissionSignal signal, boolean voidByRule,
                                  List<SubmissionRef> subs, ParameterSet p) {
        double t = VerdictEngine.computeT(signal, p);

        // ── VOID: 점수 변동 없음, 제보권만 반환(반환은 스케줄러가 처리) ──
        if (voidByRule) {
            String why = "VOID · 규정 위반/중복 제보";
            List<LedgerLine> lines = new ArrayList<>();
            for (SubmissionRef s : subs) {
                lines.add(new LedgerLine(s.userId(), s.submissionId(), VerdictResult.VOID, 0.0, why));
            }
            return new VerdictPlan(VerdictResult.VOID, null, t, lines);
        }

        VerdictOutcome o = VerdictEngine.evaluate(signal, p);
        List<LedgerLine> lines = new ArrayList<>();
        for (SubmissionRef s : subs) {
            ScoreResult r = ScoreEngine.compute(
                    new ScoreInput(o.result(), s.confidence(), s.orderRank(), o.reach()), p);
            lines.add(new LedgerLine(s.userId(), s.submissionId(), o.result(), r.delta(), r.breakdown()));
        }
        return new VerdictPlan(o.result(), o.reach(), o.t(), lines);
    }
}
