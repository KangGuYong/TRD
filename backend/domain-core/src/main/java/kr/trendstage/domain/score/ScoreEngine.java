package kr.trendstage.domain.score;

import kr.trendstage.domain.params.ParameterSet;

import java.util.Locale;

/**
 * 건별 점수 Δ(원값)를 산출하는 순수 함수. 01 §5.1.
 *
 * <pre>
 *   HIT :  Δ = + c × w_order × (1 + m)
 *   MISS:  Δ = − c × 0.5
 *   VOID:  Δ = 0
 * </pre>
 *
 * 시간감쇠는 여기서 곱하지 않는다 — 원장에는 원값을 기록하고 감쇠는 {@link ActiveScore}가 조회 때 적용한다
 * (J1. 양쪽에 걸면 이중 적용).
 * 실패 페널티가 이득의 절반(0.5)인 비대칭은 의도된 설계다 — 대칭이면 유저가
 * "이미 뜬 것만" 제보하게 되어 조기경보 목적이 무너진다. 임의 변경 금지.
 */
public final class ScoreEngine {
    private ScoreEngine() {}

    public static ScoreResult compute(ScoreInput in, ParameterSet p) {
        return switch (in.result()) {
            case VOID -> new ScoreResult(0.0, "VOID · 점수 변동 없음");
            case MISS -> {
                double delta = -in.confidence() * 0.5;
                yield new ScoreResult(delta, String.format(Locale.US,
                        "MISS · 확신도 %d × 0.5 = %+.1f", in.confidence(), delta));
            }
            case HIT -> {
                double w = p.orderWeight(in.orderRank());
                double m = p.reachMultiplier(in.reach());
                double delta = in.confidence() * w * (1 + m);
                yield new ScoreResult(delta, String.format(Locale.US,
                        "HIT %s · 확신도 %d × 선점 %d위(%.1f) × 확산 ×%.1f = %+.1f",
                        in.reach(), in.confidence(), in.orderRank(), w, (1 + m), delta));
            }
        };
    }
}
