package kr.trendstage.domain.verdict;

import kr.trendstage.domain.params.ParameterSet;

/**
 * 종합 점수 T와 판정 결과를 산출하는 순수 함수.
 *
 * <pre>
 *   r = distinctSubmitters(시딩 제외) / submitterTarget   (서로 다른 제보자 유입 — 증가분 성격, R5)
 *   T = clip(r, 0, 1)
 *   T < hitThreshold      → MISS
 *   hitThreshold ≤ T < bandL2  → HIT L1
 *   bandL2 ≤ T < bandL3        → HIT L2
 *   bandL3 ≤ T < bandL4        → HIT L3
 *   T ≥ bandL4                 → HIT L4
 * </pre>
 *
 * submitterTarget은 근거 없는 초기 추정치다. Phase 0 백테스트로 보정한다(O1).
 * 이전에는 외부 지표(X/네이버/인스타/디시) 가중합이었으나, 제보 기반 운영으로 전환하며
 * 후속 제보자 수만을 판정 근거로 쓴다(CLAUDE.md R1 개정 — 유저 투표가 아니라 관측 가능한
 * 제보 시계열이므로 R1의 "운영자가 임의로 못 바꾼다"는 정신은 그대로 유지).
 * 시딩은 T에서 빠진다(J3). VOID(항목 VOID·같은 유저 중복)는 이 엔진 밖의 사건이고, 판정 시점 VOID는 유효 제보 0건뿐이다(P5).
 */
public final class VerdictEngine {
    private VerdictEngine() {}

    /** T = clip(시딩 제외 제보자 수 / submitterTarget, 0, 1). */
    public static double computeT(TrendSignal sig, ParameterSet p) {
        return ratio(sig.distinctSubmitters(), p);
    }

    public static VerdictOutcome evaluate(TrendSignal sig, ParameterSet p) {
        return classify(computeT(sig, p), p);
    }

    /** @deprecated Task 6에서 삭제 — {@link #computeT(TrendSignal, ParameterSet)}를 쓴다. */
    @Deprecated(forRemoval = true)
    public static double computeT(SubmissionSignal sig, ParameterSet p) {
        return ratio(sig.distinctSubmitters(), p);
    }

    /** @deprecated Task 6에서 삭제 — {@link #evaluate(TrendSignal, ParameterSet)}를 쓴다. */
    @Deprecated(forRemoval = true)
    public static VerdictOutcome evaluate(SubmissionSignal sig, ParameterSet p) {
        return classify(ratio(sig.distinctSubmitters(), p), p);
    }

    private static double ratio(int distinctSubmitters, ParameterSet p) {
        double r = distinctSubmitters / (double) Math.max(p.submitterTarget, 1);
        return Math.max(0.0, Math.min(1.0, r));
    }

    private static VerdictOutcome classify(double t, ParameterSet p) {
        if (t < p.hitThreshold) return new VerdictOutcome(VerdictResult.MISS, null, t);
        ReachLevel reach;
        if (t < p.bandL2) reach = ReachLevel.L1;
        else if (t < p.bandL3) reach = ReachLevel.L2;
        else if (t < p.bandL4) reach = ReachLevel.L3;
        else reach = ReachLevel.L4;
        return new VerdictOutcome(VerdictResult.HIT, reach, t);
    }
}
