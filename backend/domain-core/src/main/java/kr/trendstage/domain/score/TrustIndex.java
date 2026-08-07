package kr.trendstage.domain.score;

import kr.trendstage.domain.params.ParameterSet;

/**
 * 신뢰도 지수 TI = (HIT + α) / (HIT + MISS + α + β). 01 §5.2.
 * 최근 180일 판정 완료분 기준. 소표본 왜곡(1승 0패=100%) 방지. 기본 α=2, β=3, 초기값 0.4.
 */
public final class TrustIndex {
    private TrustIndex() {}

    public static double compute(int hit, int miss, ParameterSet p) {
        return (hit + p.tiAlpha) / (hit + miss + p.tiAlpha + p.tiBeta);
    }
}
