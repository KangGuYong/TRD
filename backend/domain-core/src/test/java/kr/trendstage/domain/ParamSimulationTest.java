package kr.trendstage.domain;

import kr.trendstage.domain.params.ParamSimulation;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.params.SimulationSummary;
import kr.trendstage.domain.params.VerdictSnapshot;
import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.VerdictResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParamSimulationTest {
    private final ParameterSet defaults = ParameterSet.defaults(); // submitterTarget=20, hitThreshold=0.20

    @Test void 변동_없으면_모두_0() {
        // distinctSubmitters=12 → T=0.6 → HIT L3 (기존과 동일 파라미터)
        var snap = new VerdictSnapshot(VerdictResult.HIT, ReachLevel.L3, 12, 2);
        SimulationSummary r = ParamSimulation.run(List.of(snap), defaults);
        assertEquals(0, r.changed());
        assertEquals(1, r.total());
        assertEquals(0, r.missToHit());
        assertEquals(0, r.hitToMiss());
        assertEquals(0, r.reachChanged());
    }

    @Test void 임계값을_낮추면_MISS가_HIT으로_바뀐다() {
        // 원래 distinctSubmitters=3 → T=0.15 → MISS(threshold 0.20 기준)
        var snap = new VerdictSnapshot(VerdictResult.MISS, null, 3, 1);
        ParameterSet lowered = new ParameterSet(20, 0.10, 0.35, 0.55, 0.75,
                0.2, 0.5, 1.0, 1.5, 1.0, 0.6, 0.4, 0.2, 90, 2.0, 3.0); // hitThreshold만 0.10으로
        SimulationSummary r = ParamSimulation.run(List.of(snap), lowered);
        assertEquals(1, r.changed());
        assertEquals(1, r.missToHit());
        assertEquals(0, r.hitToMiss());
    }

    @Test void target을_높이면_HIT이_MISS로_바뀐다() {
        // 원래 distinctSubmitters=5, target=20 → T=0.25 → HIT L1
        var snap = new VerdictSnapshot(VerdictResult.HIT, ReachLevel.L1, 5, 1);
        ParameterSet raised = new ParameterSet(40, 0.20, 0.35, 0.55, 0.75,
                0.2, 0.5, 1.0, 1.5, 1.0, 0.6, 0.4, 0.2, 90, 2.0, 3.0); // target 40 → T=0.125 < 0.20
        SimulationSummary r = ParamSimulation.run(List.of(snap), raised);
        assertEquals(1, r.changed());
        assertEquals(1, r.hitToMiss());
    }

    @Test void target을_낮추면_reach가_상승한다() {
        // 원래 distinctSubmitters=12, target=20 → T=0.6 → L3
        var snap = new VerdictSnapshot(VerdictResult.HIT, ReachLevel.L3, 12, 2);
        ParameterSet lowered = new ParameterSet(10, 0.20, 0.35, 0.55, 0.75,
                0.2, 0.5, 1.0, 1.5, 1.0, 0.6, 0.4, 0.2, 90, 2.0, 3.0); // target 10 → T=1.0 → L4
        SimulationSummary r = ParamSimulation.run(List.of(snap), lowered);
        assertEquals(1, r.changed());
        assertEquals(1, r.reachChanged());
        assertEquals(0, r.missToHit());
        assertEquals(0, r.hitToMiss());
    }

    @Test void 빈_목록이면_전부_0() {
        SimulationSummary r = ParamSimulation.run(List.of(), defaults);
        assertEquals(0, r.changed());
        assertEquals(0, r.total());
        assertEquals(0, r.missToHit());
        assertEquals(0, r.hitToMiss());
        assertEquals(0, r.reachChanged());
    }
}
