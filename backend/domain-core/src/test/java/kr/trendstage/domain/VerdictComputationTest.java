package kr.trendstage.domain;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.SubmissionRef;
import kr.trendstage.domain.score.VerdictComputation;
import kr.trendstage.domain.score.VerdictPlan;
import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.TrendSignal;
import kr.trendstage.domain.verdict.VerdictResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerdictComputationTest {

    private static final Instant T = Instant.parse("2026-09-01T00:00:00Z");
    private final ParameterSet p = ParameterSet.defaults();

    @Test void HIT_클러스터는_선점순위대로_점수가_난다() {
        List<SubmissionRef> refs = refs(12, 0);   // T = 12/20 = 0.6 → HIT L3
        VerdictPlan plan = VerdictComputation.run(signal(refs), refs, p);
        assertEquals(VerdictResult.HIT, plan.result());
        assertEquals(ReachLevel.L3, plan.reach());
        assertEquals(12, plan.ledgerLines().size());
        assertEquals(60.0, plan.ledgerLines().get(0).delta(), 1e-9);   // 30 × 1.0 × 2.0
        assertEquals(36.0, plan.ledgerLines().get(1).delta(), 1e-9);   // 30 × 0.6 × 2.0
    }

    @Test void 제보자가_적으면_MISS로_확신도의_절반을_잃는다() {
        List<SubmissionRef> refs = refs(2, 0);    // T = 0.1
        VerdictPlan plan = VerdictComputation.run(signal(refs), refs, p);
        assertEquals(VerdictResult.MISS, plan.result());
        assertEquals(-15.0, plan.ledgerLines().get(0).delta(), 1e-9);
    }

    @Test void 시딩_4_실유저_3이면_MISS이고_원장은_실유저만() {
        List<SubmissionRef> refs = refs(3, 4);    // n = 3 → T = 0.15 < 0.20 (시딩을 세면 0.35로 HIT였다)
        VerdictPlan plan = VerdictComputation.run(signal(refs), refs, p);
        assertEquals(VerdictResult.MISS, plan.result());
        assertEquals(3, plan.ledgerLines().size());
    }

    @Test void 시딩만_있으면_MISS이고_원장은_없다() {
        List<SubmissionRef> refs = refs(0, 2);
        VerdictPlan plan = VerdictComputation.run(signal(refs), refs, p);
        assertEquals(VerdictResult.MISS, plan.result());
        assertTrue(plan.ledgerLines().isEmpty());
    }

    @Test void 유효_제보가_0건이면_VOID() {
        VerdictPlan plan = VerdictComputation.run(new TrendSignal(T, List.of()), List.of(), p);
        assertEquals(VerdictResult.VOID, plan.result());
        assertTrue(plan.ledgerLines().isEmpty());
    }

    /** 실유저 real명(선점 1..real, 확신도 30) + 시딩 seeds건. */
    private static List<SubmissionRef> refs(int real, int seeds) {
        List<SubmissionRef> out = new ArrayList<>();
        for (int i = 1; i <= real; i++) out.add(new SubmissionRef(UUID.randomUUID(), UUID.randomUUID(), 30, i, false));
        for (int i = 0; i < seeds; i++) out.add(new SubmissionRef(UUID.randomUUID(), UUID.randomUUID(), 30, Integer.MAX_VALUE, true));
        return out;
    }

    private static TrendSignal signal(List<SubmissionRef> refs) {
        return new TrendSignal(T, refs.stream()
                .map(r -> new TrendSignal.Entry(r.submissionId(), r.userId(), r.seed(), T, "X", T)).toList());
    }
}
