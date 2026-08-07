package kr.trendstage.domain.score;

import kr.trendstage.domain.params.ParameterSet;

import java.util.Locale;

/**
 * 건별 점수 Δ를 산출하는 순수 함수. 01 §5.1.
 *
 * <pre>
 *   HIT :  Δ = + c × w_order × (1 + m) × d
 *   MISS:  Δ = − c × 0.5 × d
 *   VOID:  Δ = 0
 *   d = 0.5 ^ (경과일 / 반감기)
 * </pre>
 *
 * 실패 페널티가 이득의 절반(0.5)인 비대칭은 의도된 설계다 — 대칭이면 유저가
 * "이미 뜬 것만" 제보하게 되어 조기경보 목적이 무너진다. 임의 변경 금지.
 */
public final class ScoreEngine {
    private ScoreEngine() {}

    public static ScoreResult compute(ScoreInput in, ParameterSet p) {
        double d = Math.pow(0.5, (double) in.elapsedDays() / p.halflifeDays);

        return switch (in.result()) {
            case VOID -> new ScoreResult(0.0,
                    "VOID · 점수 변동 없음");
            case MISS -> {
                double delta = -in.confidence() * 0.5 * d;
                yield new ScoreResult(delta, String.format(Locale.US,
                        "MISS · 확신도 %d × 0.5 × 감쇠 %.2f = %+.1f",
                        in.confidence(), d, delta));
            }
            case HIT -> {
                double w = p.orderWeight(in.orderRank());
                double m = p.reachMultiplier(in.reach());
                double delta = in.confidence() * w * (1 + m) * d;
                yield new ScoreResult(delta, String.format(Locale.US,
                        "HIT %s · 확신도 %d × 선점 %d위(%.1f) × 확산 ×%.1f × 감쇠 %.2f = %+.1f",
                        in.reach(), in.confidence(), in.orderRank(), w, (1 + m), d, delta));
            }
        };
    }
}
