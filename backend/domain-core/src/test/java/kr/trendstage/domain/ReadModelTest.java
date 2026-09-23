package kr.trendstage.domain;

import kr.trendstage.domain.score.ActiveScore;
import kr.trendstage.domain.trend.DisplayStage;
import kr.trendstage.domain.trend.StageEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReadModelTest {

    @Test void 단계는_전파_채널수로_결정() {
        assertEquals(DisplayStage.SEED, StageEvaluator.fromReach(1, false));
        assertEquals(DisplayStage.RISING, StageEvaluator.fromReach(3, false));
        assertEquals(DisplayStage.PEAK, StageEvaluator.fromReach(5, false));
        assertEquals(DisplayStage.FADING, StageEvaluator.fromReach(6, false));
        assertEquals(DisplayStage.FADING, StageEvaluator.fromReach(2, true)); // resolvedFading 우선
    }

    @Test void 홈_정렬은_RISING_우선() {
        assertTrue(StageEvaluator.actionPriority(DisplayStage.RISING)
                < StageEvaluator.actionPriority(DisplayStage.FADING));
    }

    @Test void 활동점수는_행마다_기록된_반감기로_감쇠한다() {
        assertEquals(60.0, ActiveScore.compute(List.of(new ActiveScore.Aged(60, 0, 90))), 1e-9);
        assertEquals(30.0, ActiveScore.compute(List.of(new ActiveScore.Aged(60, 90, 90))), 1e-9);
        // 반감기 60일로 기록된 행은 60일에 절반 — 현재 파라미터와 무관(J1)
        assertEquals(30.0, ActiveScore.compute(List.of(new ActiveScore.Aged(60, 60, 60))), 1e-9);
        assertEquals(52.5, ActiveScore.compute(List.of(
                new ActiveScore.Aged(60, 0, 90), new ActiveScore.Aged(-15, 90, 90))), 1e-9);
    }

    @Test void 재판정_차액은_원_판정과_같은_기준이면_정확히_상쇄된다() {
        // 원 판정 HIT +50, 재판정으로 MISS(−15) → 차액 −65를 원 판정 기준(같은 경과일·반감기)으로 기록
        double withAdj = ActiveScore.compute(List.of(
                new ActiveScore.Aged(50, 30, 90), new ActiveScore.Aged(-65, 30, 90)));
        assertEquals(ActiveScore.compute(List.of(new ActiveScore.Aged(-15, 30, 90))), withAdj, 1e-9);
    }
}
