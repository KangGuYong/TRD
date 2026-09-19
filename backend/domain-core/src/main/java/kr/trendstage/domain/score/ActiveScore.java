package kr.trendstage.domain.score;

import java.util.List;

/**
 * 활동 점수 AS = Σ Δ × 0.5^(경과일 / 반감기). 01 §5.3. 순수 함수.
 * 반감기는 원장 행마다 기록된 값을 쓴다 — 파라미터를 바꿔도 과거 행의 감쇠가 바뀌지 않는다(J1, O11=(b)).
 */
public final class ActiveScore {
    private ActiveScore() {}

    /** 원장 한 행: 원값 Δ, 감쇠 기준 시각(decay_anchor_at)부터의 경과일, 그 행에 기록된 반감기. */
    public record Aged(double delta, long ageDays, int halflifeDays) {}

    public static double compute(List<Aged> entries) {
        double sum = 0;
        for (Aged e : entries) {
            sum += e.delta() * Math.pow(0.5, (double) e.ageDays() / e.halflifeDays());
        }
        return sum;
    }
}
