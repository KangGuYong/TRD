package kr.trendstage.judge;

import kr.trendstage.domain.params.ParameterSet;

/**
 * ParameterSet의 JSON 형태. 판정 근거에 동결했다가 재판정에서 되살린다(J2).
 * domain-core에 Jackson을 들이지 않으려고 여기 둔다.
 */
public record ParamsSnapshot(int submitterTarget, double hitThreshold, double bandL2, double bandL3, double bandL4,
                             double mL1, double mL2, double mL3, double mL4,
                             double wRank1, double wRank2, double wRank3, double wRankRest,
                             int halflifeDays, double tiAlpha, double tiBeta) {

    public static ParamsSnapshot of(ParameterSet p) {
        return new ParamsSnapshot(p.targetFloor, p.hitThreshold, p.bandL2, p.bandL3, p.bandL4,
                p.mL1, p.mL2, p.mL3, p.mL4, p.wRank1, p.wRank2, p.wRank3, p.wRankRest,
                p.halflifeDays, p.tiAlpha, p.tiBeta);
    }

    public ParameterSet toParameterSet() {
        return new ParameterSet(submitterTarget, hitThreshold, bandL2, bandL3, bandL4, mL1, mL2, mL3, mL4,
                wRank1, wRank2, wRank3, wRankRest, halflifeDays, tiAlpha, tiBeta);
    }
}
