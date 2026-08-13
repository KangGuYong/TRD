package kr.trendstage.domain.verdict;

import kr.trendstage.domain.params.ParameterSet;

/**
 * 종합 점수 T와 판정 결과를 산출하는 순수 함수.
 *
 * <pre>
 *   r = distinctSubmitters / submitterTarget   (서로 다른 제보자 유입 — 증가분 성격, R5)
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
 * VOID(규정 위반·중복 제보)는 이 엔진 밖에서 판단한다.
 */
public final class VerdictEngine {
    private VerdictEngine() {}

    public static double computeT(SubmissionSignal sig, ParameterSet p) {
        double r = sig.distinctSubmitters() / (double) Math.max(p.submitterTarget, 1);
        return Math.max(0.0, Math.min(1.0, r));
    }

    public static VerdictOutcome evaluate(SubmissionSignal sig, ParameterSet p) {
        double t = computeT(sig, p);
        if (t < p.hitThreshold) return new VerdictOutcome(VerdictResult.MISS, null, t);

        ReachLevel reach;
        if (t < p.bandL2) reach = ReachLevel.L1;
        else if (t < p.bandL3) reach = ReachLevel.L2;
        else if (t < p.bandL4) reach = ReachLevel.L3;
        else reach = ReachLevel.L4;

        return new VerdictOutcome(VerdictResult.HIT, reach, t);
    }
}
