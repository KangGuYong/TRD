package kr.trendstage.domain;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.*;
import kr.trendstage.domain.verdict.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VerdictComputationTest {
    private final ParameterSet p = ParameterSet.defaults();

    @Test void 정규화_8배는_1점_변화없음은_0점() {
        assertEquals(1.0, SignalNormalizer.score(8), 1e-9);
        assertEquals(0.0, SignalNormalizer.score(1), 1e-9);
        assertEquals(1.0 / 3.0, SignalNormalizer.score(2), 1e-6);
        assertEquals(0.0, SignalNormalizer.score(0.5), 1e-9); // 하한 클립
    }

    @Test void HIT_클러스터는_선점순위대로_점수가_난다() {
        var subs = List.of(
                new SubmissionRef(UUID.randomUUID(), UUID.randomUUID(), 30, 1, 0),
                new SubmissionRef(UUID.randomUUID(), UUID.randomUUID(), 30, 2, 0));
        VerdictPlan plan = VerdictComputation.run(new SignalScores(0.88, 0.36, 0.71, 0.50, 0.62), 0, false, subs, p);
        assertEquals(VerdictResult.HIT, plan.result());
        assertEquals(ReachLevel.L3, plan.reach());
        assertEquals(60.0, plan.lines().get(0).delta(), 1e-9);
        assertEquals(36.0, plan.lines().get(1).delta(), 1e-9);
    }

    @Test void 지표_2종_결측이면_VOID_점수변동없음() {
        var subs = List.of(new SubmissionRef(UUID.randomUUID(), UUID.randomUUID(), 50, 1, 0));
        VerdictPlan plan = VerdictComputation.run(new SignalScores(0.9, 0, 0.9, 0, 0.5), 2, false, subs, p);
        assertEquals(VerdictResult.VOID, plan.result());
        assertEquals(0.0, plan.lines().get(0).delta(), 1e-9);
    }

    @Test void 규정위반_플래그면_VOID() {
        var subs = List.of(new SubmissionRef(UUID.randomUUID(), UUID.randomUUID(), 50, 1, 0));
        assertEquals(VerdictResult.VOID,
                VerdictComputation.run(new SignalScores(0.9, 0.9, 0.9, 0.9, 0.9), 0, true, subs, p).result());
    }
}
