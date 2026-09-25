package kr.trendstage.domain.params;

import kr.trendstage.domain.signal.SignalAxes;
import kr.trendstage.domain.verdict.ReachLevel;

/**
 * 판정·점수·등급 계산에 주입되는 파라미터 묶음.
 *
 * <p>모든 엔진은 하드코딩 대신 이 객체를 받는다. 그래서 verdict_runner(운영)와
 * 파라미터 스튜디오 ADM-600 시뮬레이션이 <b>같은 엔진</b>을 서로 다른 값으로 호출할 수 있다.
 * (04-development-plan.md §1, §4)
 *
 * <p>여기 기본값은 근거 없는 초기 추정치다. Phase 0 백테스트로 보정한다(O1).
 * <p>SP4: 목표치 하한·비율과 지속성·다양성·독립성 축(axes). 기본은 중립 — 판정이 SP4 이전과 같다.
 */
public final class ParameterSet {

    // 목표치 하한(구 submitterTarget) — 목표치 = max(하한, ⌈활성 제보자 × 비율⌉) (SP4 S2)
    public final int targetFloor;
    // HIT 컷 및 reach 밴드 상한 — 01 §4.3
    public final double hitThreshold;   // < 이 값이면 MISS
    public final double bandL2;         // [.., bandL2) = L1
    public final double bandL3;
    public final double bandL4;
    // 확산배수 m (reach별) — 01 §4.3 / §5.1
    public final double mL1, mL2, mL3, mL4;
    // 선점 가중 w_order (1/2/3/4위이하) — 01 §5.1
    public final double wRank1, wRank2, wRank3, wRankRest;
    // 시간감쇠 반감기(일) — 01 §5.1
    public final int halflifeDays;
    // TI 베이지안 평활 — 01 §5.2
    public final double tiAlpha, tiBeta;
    // SP4가 더한 판정 축(지속성·다양성·독립성·상대 목표치 비율)
    public final SignalAxes axes;

    /** SP4 이전 16인자 — 새 축은 중립(S6·S8). */
    public ParameterSet(int targetFloor, double hitThreshold, double bandL2, double bandL3, double bandL4,
                        double mL1, double mL2, double mL3, double mL4,
                        double wRank1, double wRank2, double wRank3, double wRankRest,
                        int halflifeDays, double tiAlpha, double tiBeta) {
        this(targetFloor, hitThreshold, bandL2, bandL3, bandL4, mL1, mL2, mL3, mL4,
                wRank1, wRank2, wRank3, wRankRest, halflifeDays, tiAlpha, tiBeta, SignalAxes.NEUTRAL);
    }

    public ParameterSet(int targetFloor, double hitThreshold, double bandL2, double bandL3, double bandL4,
                        double mL1, double mL2, double mL3, double mL4,
                        double wRank1, double wRank2, double wRank3, double wRankRest,
                        int halflifeDays, double tiAlpha, double tiBeta, SignalAxes axes) {
        if (targetFloor < 1) throw new IllegalArgumentException("targetFloor: 1 이상이어야 합니다 (입력 " + targetFloor + ")");
        this.targetFloor = targetFloor;
        this.hitThreshold = hitThreshold; this.bandL2 = bandL2; this.bandL3 = bandL3; this.bandL4 = bandL4;
        this.mL1 = mL1; this.mL2 = mL2; this.mL3 = mL3; this.mL4 = mL4;
        this.wRank1 = wRank1; this.wRank2 = wRank2; this.wRank3 = wRank3; this.wRankRest = wRankRest;
        this.halflifeDays = halflifeDays; this.tiAlpha = tiAlpha; this.tiBeta = tiBeta;
        this.axes = java.util.Objects.requireNonNull(axes, "axes");
    }

    /** 설계서 초기 추정치. */
    public static ParameterSet defaults() {
        return new ParameterSet(
                20,
                0.20, 0.35, 0.55, 0.75,
                0.2, 0.5, 1.0, 1.5,
                1.0, 0.6, 0.4, 0.2,
                90, 2.0, 3.0);
    }

    /** 선점 순위 → 가중치. 4위 이하는 동일. */
    public double orderWeight(int orderRank) {
        return switch (orderRank) {
            case 1 -> wRank1;
            case 2 -> wRank2;
            case 3 -> wRank3;
            default -> wRankRest;
        };
    }

    /** reach_level → 확산배수 m. */
    public double reachMultiplier(ReachLevel reach) {
        return switch (reach) {
            case L1 -> mL1;
            case L2 -> mL2;
            case L3 -> mL3;
            case L4 -> mL4;
        };
    }
}
