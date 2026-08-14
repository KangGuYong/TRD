package kr.trendstage.domain.params;

import kr.trendstage.domain.verdict.SubmissionSignal;
import kr.trendstage.domain.verdict.VerdictEngine;
import kr.trendstage.domain.verdict.VerdictOutcome;
import kr.trendstage.domain.verdict.VerdictResult;

import java.util.List;

/**
 * ADM-600 시뮬레이션 순수 함수. 운영이 쓰는 VerdictEngine을 그대로 재사용해 과거 판정을
 * 새 파라미터로 재평가하고 실제 결과와 비교한다 — 드리프트 방지(운영/시뮬레이션 같은 엔진).
 */
public final class ParamSimulation {
    private ParamSimulation() {}

    public static SimulationSummary run(List<VerdictSnapshot> snapshots, ParameterSet p) {
        int missToHit = 0, hitToMiss = 0, reachChanged = 0;
        for (VerdictSnapshot v : snapshots) {
            SubmissionSignal sig = new SubmissionSignal(v.distinctSubmitters(), v.distinctPlatforms());
            VerdictOutcome after = VerdictEngine.evaluate(sig, p);
            if (v.result() == VerdictResult.MISS && after.result() == VerdictResult.HIT) {
                missToHit++;
            } else if (v.result() == VerdictResult.HIT && after.result() == VerdictResult.MISS) {
                hitToMiss++;
            } else if (v.result() == VerdictResult.HIT && after.result() == VerdictResult.HIT
                    && v.reach() != after.reach()) {
                reachChanged++;
            }
        }
        int changed = missToHit + hitToMiss + reachChanged;
        return new SimulationSummary(changed, snapshots.size(), missToHit, hitToMiss, reachChanged);
    }
}
