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

    @Test void HIT_클러스터는_선점순위대로_점수가_난다() {
        var subs = List.of(
                new SubmissionRef(UUID.randomUUID(), UUID.randomUUID(), 30, 1, false),
                new SubmissionRef(UUID.randomUUID(), UUID.randomUUID(), 30, 2, false));
        // submitterTarget=20, distinctSubmitters=12 → T=0.6 → HIT L3
        VerdictPlan plan = VerdictComputation.run(new SubmissionSignal(12, 3), false, subs, p);
        assertEquals(VerdictResult.HIT, plan.result());
        assertEquals(ReachLevel.L3, plan.reach());
        assertEquals(60.0, plan.lines().get(0).delta(), 1e-9);
        assertEquals(36.0, plan.lines().get(1).delta(), 1e-9);
    }

    @Test void 제보자가_적으면_MISS_점수변동없이_감점() {
        var subs = List.of(new SubmissionRef(UUID.randomUUID(), UUID.randomUUID(), 30, 1, false));
        // distinctSubmitters=2 → T=0.1 < hitThreshold 0.2 → MISS
        VerdictPlan plan = VerdictComputation.run(new SubmissionSignal(2, 1), false, subs, p);
        assertEquals(VerdictResult.MISS, plan.result());
        assertEquals(-15.0, plan.lines().get(0).delta(), 1e-9);
    }

    @Test void 규정위반_플래그면_VOID() {
        var subs = List.of(new SubmissionRef(UUID.randomUUID(), UUID.randomUUID(), 50, 1, false));
        assertEquals(VerdictResult.VOID,
                VerdictComputation.run(new SubmissionSignal(20, 5), true, subs, p).result());
    }
}
