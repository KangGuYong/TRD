package kr.trendstage.domain.verdict;

/**
 * 원시 지표 → 정규화 점수 s_i. 01 §4.2. 순수 함수.
 *
 * <pre>
 *   r_i = (관측기간 평균 일별값) / max(기준선 평균 일별값, ε)
 *   s_i = clip( log2(r_i) / 3 , 0, 1 )      # 8배 증가 시 1.0
 * </pre>
 *
 * 누적치가 아니라 <b>일별 평균(증가분 성격)</b>을 넣어야 한다(R5). 누적값을 그대로 넣으면 조용히 틀린다.
 */
public final class SignalNormalizer {
    private SignalNormalizer() {}

    public static final double EPS = 1e-6;

    public static double ratio(double obsDailyAvg, double baselineDailyAvg) {
        return obsDailyAvg / Math.max(baselineDailyAvg, EPS);
    }

    public static double score(double ratio) {
        if (ratio <= 0) return 0.0;
        double s = (Math.log(ratio) / Math.log(2)) / 3.0;   // log2(r)/3
        return Math.max(0.0, Math.min(1.0, s));
    }

    /** 소스별 비율 5개(S1..S5) → SignalScores. */
    public static SignalScores fromRatios(double r1, double r2, double r3, double r4, double r5) {
        return new SignalScores(score(r1), score(r2), score(r3), score(r4), score(r5));
    }
}
