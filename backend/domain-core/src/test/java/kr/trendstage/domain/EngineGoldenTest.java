package kr.trendstage.domain;

import kr.trendstage.domain.grade.*;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.*;
import kr.trendstage.domain.verdict.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 설계문서(01)의 원장 예시를 고정한 골든 테스트.
 * 이 값들이 바뀌면 점수 체계의 의미가 바뀌는 것이므로 리뷰 없이 수정 금지.
 */
class EngineGoldenTest {
    private final ParameterSet p = ParameterSet.defaults();

    @Test void hit_L3_c30_order1_은_60점() {
        assertEquals(60.0, ScoreEngine.compute(
                new ScoreInput(VerdictResult.HIT, 30, 1, ReachLevel.L3), p).delta(), 1e-9);
    }

    @Test void hit_L2_c10_order2_는_9점() {
        assertEquals(9.0, ScoreEngine.compute(
                new ScoreInput(VerdictResult.HIT, 10, 2, ReachLevel.L2), p).delta(), 1e-9);
    }

    @Test void miss_c30_은_마이너스15점_이득의_절반() {
        assertEquals(-15.0, ScoreEngine.compute(
                new ScoreInput(VerdictResult.MISS, 30, 2, null), p).delta(), 1e-9);
    }

    @Test void void_는_점수변동_없음() {
        assertEquals(0.0, ScoreEngine.compute(
                new ScoreInput(VerdictResult.VOID, 50, 4, null), p).delta(), 1e-9);
    }

    @Test void 원장_Δ에는_감쇠가_없고_근거에도_감쇠항이_없다() {
        ScoreResult r = ScoreEngine.compute(new ScoreInput(VerdictResult.HIT, 30, 1, ReachLevel.L3), p);
        assertEquals(60.0, r.delta(), 1e-9);
        assertEquals("HIT L3 · 확신도 30 × 선점 1위(1.0) × 확산 ×2.0 = +60.0", r.breakdown());
        assertEquals("MISS · 확신도 30 × 0.5 = -15.0",
                ScoreEngine.compute(new ScoreInput(VerdictResult.MISS, 30, 2, null), p).breakdown());
    }

    @Test void 스냅샷_등급_기준으로_다음_등급_진행상황을_낸다() {
        assertEquals(Grade.L1, GradePolicy.nextOf(Grade.L0));
        assertEquals(Grade.L4, GradePolicy.nextOf(Grade.L4));
        List<GradeRequirement> reqs = GradePolicy.progressToward(Grade.L1, 6, 0.40, 35);
        assertEquals(3, reqs.size());
        assertTrue(reqs.stream().allMatch(GradeRequirement::met));
        assertTrue(GradePolicy.progressToward(Grade.L4, 100, 0.9, 999).isEmpty()); // L4는 정원제 — 사다리 밖
    }
    @Test void trustIndex_초기값은_0_4() {
        assertEquals(0.4, TrustIndex.compute(0, 0, p), 1e-9);
    }

    @Test void verdict_밴드는_distinctSubmitters_비율로_정해진다() {
        // submitterTarget=20(기본). distinctSubmitters=12 → T=0.6 → HIT L3
        VerdictOutcome o = VerdictEngine.evaluate(signalOf(12), p);
        assertEquals(VerdictResult.HIT, o.result());
        assertEquals(ReachLevel.L3, o.reach());
        // distinctSubmitters=20 → T=1.0 → HIT L4
        assertEquals(ReachLevel.L4, VerdictEngine.evaluate(signalOf(20), p).reach());
        // distinctSubmitters=2 → T=0.1 < hitThreshold → MISS
        assertEquals(VerdictResult.MISS, VerdictEngine.evaluate(signalOf(2), p).result());
    }

    @Test void grade_는_AS와_TI를_모두_요구() {
        GradeStatus gs = GradePolicy.evaluate(17, 0.48, 186);
        assertEquals(Grade.L2, gs.current());
        assertEquals(Grade.L3, gs.next());
        // TI만 충분하고 AS가 부족하면 승급 불가
        assertEquals(Grade.L1, GradePolicy.evaluate(50, 0.60, 100).current());
    }

    @Test void grade_요구항목은_kind로_구분된다() {
        GradeStatus gs = GradePolicy.evaluate(17, 0.48, 186); // L2 → L3 요구
        assertEquals(3, gs.requirements().size());
        assertEquals(GradeRequirementKind.JUDGED_COUNT, gs.requirements().get(0).kind());
        assertEquals(GradeRequirementKind.TRUST_INDEX, gs.requirements().get(1).kind());
        assertEquals(GradeRequirementKind.ACTIVE_SCORE, gs.requirements().get(2).kind());
    }

    private static TrendSignal signalOf(int distinctSubmitters) {
        Instant t = Instant.parse("2026-09-01T00:00:00Z");
        return new TrendSignal(t, IntStream.range(0, distinctSubmitters)
                .mapToObj(i -> new TrendSignal.Entry(UUID.randomUUID(), UUID.randomUUID(), false, t, "X", t)).toList());
    }
}
