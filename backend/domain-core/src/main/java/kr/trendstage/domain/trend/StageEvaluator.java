package kr.trendstage.domain.trend;

/**
 * 제보에 언급된 서로 다른 플랫폼 수 → 표시 단계. 순수 함수.
 *
 * <p>앱 카드의 단계는 "얼마나 퍼졌나"(전파 경로)와 직결된다. 외부 지표 대신, 제보 자체에
 * 적힌 최초 목격 플랫폼(source_platform)이 몇 곳에서 서로 다르게 보고됐는지로 근사한다.
 * 정밀 매핑(궤적·정점 통과 판정)은 Phase 0 보정 대상(O1).
 *
 * <pre>
 *   도달 ≤1 → SEED     (커뮤니티 한 곳)
 *   2~3    → RISING    (대중화 직전 · 지금이 적기)
 *   4~5    → PEAK      (곧 흔해짐)
 *   ≥6     → FADING    (완전 확산 → 식는 중)
 * </pre>
 * 이미 판정에서 식는 것으로 확인된 경우(resolvedFading) 도달 수와 무관하게 FADING.
 */
public final class StageEvaluator {
    private StageEvaluator() {}

    public static DisplayStage fromReach(int reachedChannels, boolean resolvedFading) {
        if (resolvedFading) return DisplayStage.FADING;
        if (reachedChannels <= 1) return DisplayStage.SEED;
        if (reachedChannels <= 3) return DisplayStage.RISING;
        if (reachedChannels <= 5) return DisplayStage.PEAK;
        return DisplayStage.FADING;
    }

    /** 홈 정렬 우선순위: "지금 행동해야 하는 순"(인기순 아님). 낮을수록 먼저. */
    public static int actionPriority(DisplayStage s) {
        return switch (s) {
            case RISING -> 0;   // 지금이 적기
            case PEAK -> 1;     // 곧 흔해짐 — 서둘러
            case SEED -> 2;     // 지켜볼 것
            case FADING -> 3;   // 늦음
        };
    }
}
