package kr.trendstage.domain.verdict;

import kr.trendstage.domain.params.ParameterSet;

/**
 * 종합 점수 T와 판정 결과를 산출하는 순수 함수. 01 §4.2, §4.3.
 *
 * <pre>
 *   T = 0.25·s1 + 0.20·s2 + 0.25·s3 + 0.15·s4 + 0.15·s5   (가중치는 주입)
 *   T < 0.20            → MISS
 *   0.20 ≤ T < 0.35     → HIT L1 (m 0.2)
 *   0.35 ≤ T < 0.55     → HIT L2 (m 0.5)
 *   0.55 ≤ T < 0.75     → HIT L3 (m 1.0)
 *   T ≥ 0.75            → HIT L4 (m 1.5)
 * </pre>
 *
 * S5(파생 생성) 게이트: s5 == 0 이면 reach 를 L2 이상으로 올리지 않는다(L1로 캡). 01 §4.2.
 * VOID(지표 결측·기준선 초과·규정위반)는 이 엔진 밖에서 판단한다.
 */
public final class VerdictEngine {
    private VerdictEngine() {}

    public static double computeT(SignalScores s, ParameterSet p) {
        double[] w = p.weights;
        return w[0]*s.s1() + w[1]*s.s2() + w[2]*s.s3() + w[3]*s.s4() + w[4]*s.s5();
    }

    public static VerdictOutcome evaluate(SignalScores s, ParameterSet p) {
        double t = computeT(s, p);
        if (t < p.hitThreshold) return new VerdictOutcome(VerdictResult.MISS, null, t);

        ReachLevel reach;
        if (t < p.bandL2) reach = ReachLevel.L1;
        else if (t < p.bandL3) reach = ReachLevel.L2;
        else if (t < p.bandL4) reach = ReachLevel.L3;
        else reach = ReachLevel.L4;

        // S5 게이트: 파생 생성이 전무하면 대중 확산으로 인정하지 않는다.
        if (s.s5() == 0.0 && reach.ordinal() > ReachLevel.L1.ordinal()) {
            reach = ReachLevel.L1;
        }
        return new VerdictOutcome(VerdictResult.HIT, reach, t);
    }
}
