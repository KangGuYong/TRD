package kr.trendstage.domain;

import kr.trendstage.domain.grade.*;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.*;
import kr.trendstage.domain.verdict.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 설계문서(01)의 원장 예시를 고정한 골든 테스트.
 * 이 값들이 바뀌면 점수 체계의 의미가 바뀌는 것이므로 리뷰 없이 수정 금지.
 */
class EngineGoldenTest {
    private final ParameterSet p = ParameterSet.defaults();

    @Test void hit_L3_c30_order1_은_60점() {
        assertEquals(60.0, ScoreEngine.compute(
                new ScoreInput(VerdictResult.HIT, 30, 1, ReachLevel.L3, 0), p).delta(), 1e-9);
    }

    @Test void hit_L2_c10_order2_는_9점() {
        assertEquals(9.0, ScoreEngine.compute(
                new ScoreInput(VerdictResult.HIT, 10, 2, ReachLevel.L2, 0), p).delta(), 1e-9);
    }

    @Test void miss_c30_은_마이너스15점_이득의_절반() {
        assertEquals(-15.0, ScoreEngine.compute(
                new ScoreInput(VerdictResult.MISS, 30, 2, null, 0), p).delta(), 1e-9);
    }

    @Test void void_는_점수변동_없음() {
        assertEquals(0.0, ScoreEngine.compute(
                new ScoreInput(VerdictResult.VOID, 50, 4, null, 0), p).delta(), 1e-9);
    }

    @Test void 반감기_90일이면_절반() {
        assertEquals(30.0, ScoreEngine.compute(
                new ScoreInput(VerdictResult.HIT, 30, 1, ReachLevel.L3, 90), p).delta(), 1e-9);
    }

    @Test void trustIndex_초기값은_0_4() {
        assertEquals(0.4, TrustIndex.compute(0, 0, p), 1e-9);
    }

    @Test void verdict_밴드와_S5게이트() {
        VerdictOutcome o = VerdictEngine.evaluate(new SignalScores(0.88, 0.36, 0.71, 0.50, 0.62), p);
        assertEquals(VerdictResult.HIT, o.result());
        assertEquals(ReachLevel.L3, o.reach());
        // S5(파생 생성)가 0이면 reach를 L1로 캡
        assertEquals(ReachLevel.L1, VerdictEngine.evaluate(new SignalScores(0.9, 0, 0.9, 0, 0), p).reach());
        assertEquals(VerdictResult.MISS, VerdictEngine.evaluate(new SignalScores(0.1, 0, 0.1, 0, 0.1), p).result());
    }

    @Test void grade_는_AS와_TI를_모두_요구() {
        GradeStatus gs = GradePolicy.evaluate(17, 0.48, 186);
        assertEquals(Grade.L2, gs.current());
        assertEquals(Grade.L3, gs.next());
        // TI만 충분하고 AS가 부족하면 승급 불가
        assertEquals(Grade.L1, GradePolicy.evaluate(50, 0.60, 100).current());
    }
}
