package kr.trendstage.judge;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.signal.SignalAxes;

/**
 * 판정 근거에 동결하는 파라미터(J2 — 재판정·VOID 차액의 기준). 컴포넌트 이름 submitterTarget은 SP4 이전 JSON과
 * 맞추려고 그대로 둔다 — 값은 목표치 하한(targetFloor). axes가 없으면(SP4 이전 근거) 중립으로 읽는다(S8).
 */
public record ParamsSnapshot(int submitterTarget, double hitThreshold, double bandL2, double bandL3, double bandL4,
                             double mL1, double mL2, double mL3, double mL4,
                             double wRank1, double wRank2, double wRank3, double wRankRest,
                             int halflifeDays, double tiAlpha, double tiBeta, SignalAxes axes) {

    public static ParamsSnapshot of(ParameterSet p) {
        return new ParamsSnapshot(p.targetFloor, p.hitThreshold, p.bandL2, p.bandL3, p.bandL4,
                p.mL1, p.mL2, p.mL3, p.mL4, p.wRank1, p.wRank2, p.wRank3, p.wRankRest,
                p.halflifeDays, p.tiAlpha, p.tiBeta, p.axes);
    }

    public ParameterSet toParameterSet() {
        return new ParameterSet(submitterTarget, hitThreshold, bandL2, bandL3, bandL4, mL1, mL2, mL3, mL4,
                wRank1, wRank2, wRank3, wRankRest, halflifeDays, tiAlpha, tiBeta,
                axes == null ? SignalAxes.NEUTRAL : axes);
    }
}
