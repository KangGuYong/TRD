package kr.trendstage.domain;

import kr.trendstage.domain.params.ParameterSet;
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

    @Test void 활동점수는_경과일로_감쇠_재적용() {
        ParameterSet p = ParameterSet.defaults();
        assertEquals(60.0, ActiveScore.compute(List.of(new ActiveScore.Aged(60, 0)), p), 1e-9);
        assertEquals(30.0, ActiveScore.compute(List.of(new ActiveScore.Aged(60, 90)), p), 1e-9);
        assertEquals(52.5, ActiveScore.compute(
                List.of(new ActiveScore.Aged(60, 0), new ActiveScore.Aged(-15, 90)), p), 1e-9);
    }
}
