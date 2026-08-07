package kr.trendstage.domain.score;

import kr.trendstage.domain.params.ParameterSet;

import java.util.List;

/**
 * 활동 점수 AS = Σ (Δ × 시간감쇠). 01 §5.3. 순수 함수.
 *
 * <p>원장 delta는 판정 시점 기준으로 계산됐으므로, 등급 재계산(grade_recalc) 시점에는
 * 각 원장의 경과일로 감쇠를 다시 적용해 "현재 가치"로 합산한다. 반감기는 주입(기본 90일).
 */
public final class ActiveScore {
    private ActiveScore() {}

    /** @param entry (delta, 원장 기록 후 경과일수) */
    public record Aged(double delta, long ageDays) {}

    public static double compute(List<Aged> entries, ParameterSet p) {
        double sum = 0;
        for (Aged e : entries) {
            sum += e.delta() * Math.pow(0.5, (double) e.ageDays() / p.halflifeDays);
        }
        return sum;
    }
}
