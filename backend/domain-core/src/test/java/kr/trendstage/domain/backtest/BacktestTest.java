package kr.trendstage.domain.backtest;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.TrendSignal;
import kr.trendstage.domain.verdict.VerdictResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BacktestTest {

    static final Instant DL = Instant.parse("2026-06-15T00:00:00Z");
    final ParameterSet defaults = ParameterSet.defaults();

    @Test
    void confusionPrecisionRecallAndReach() {
        List<BacktestCase> cases = List.of(
                c("tp", VerdictResult.HIT, ReachLevel.L2, users(10, false)),   // T 0.50 → HIT L2
                c("fn", VerdictResult.HIT, null, users(1, false)),             // 0.05 → MISS
                c("fp", VerdictResult.MISS, null, users(8, false)),            // 0.40 → HIT
                c("tn", VerdictResult.MISS, null, users(3, true)));            // 시딩뿐 → 0 → MISS
        BacktestReport r = Backtest.run(cases, defaults, defaults);

        assertEquals(4, r.caseCount());
        assertEquals(new BacktestReport.Confusion(1, 1, 1, 1), r.current().confusion());
        assertEquals(0.5, r.current().precision());
        assertEquals(0.5, r.current().recall());
        assertEquals(1.0, r.current().reachAgreement());
        assertEquals(1, r.current().reachCompared());
        assertEquals(0, r.changedCount());
        assertTrue(r.rows().get(0).current().explain().startsWith("T 0.50 = 제보자 10/20"));
    }

    @Test
    void precisionIsNullWhenNothingPredictedHit() {
        BacktestReport r = Backtest.run(List.of(c("a", VerdictResult.HIT, null, users(1, false))), defaults, defaults);
        assertNull(r.current().precision());
        assertEquals(0.0, r.current().recall());
        assertNull(r.current().reachAgreement());
    }

    @Test
    void changedWhenDraftMovesResultOrReach() {
        ParameterSet d = defaults;
        ParameterSet draft = new ParameterSet(10, d.hitThreshold, d.bandL2, d.bandL3, d.bandL4, d.mL1, d.mL2, d.mL3, d.mL4,
                d.wRank1, d.wRank2, d.wRank3, d.wRankRest, d.halflifeDays, d.tiAlpha, d.tiBeta);
        List<BacktestCase> cases = List.of(
                c("tp", VerdictResult.HIT, null, users(10, false)),   // L2 → L4
                c("fn", VerdictResult.HIT, null, users(1, false)),    // MISS → MISS
                c("fp", VerdictResult.MISS, null, users(8, false)),   // L2 → L4
                c("tn", VerdictResult.MISS, null, users(3, true)));
        BacktestReport r = Backtest.run(cases, defaults, draft);
        assertEquals(2, r.changedCount());
        assertTrue(r.rows().get(0).changed());
        assertFalse(r.rows().get(1).changed());
        assertEquals(ReachLevel.L4, r.rows().get(0).draft().reach());
    }

    static BacktestCase c(String id, VerdictResult label, ReachLevel reach, TrendSignal s) {
        return new BacktestCase(id, "제목 " + id, label, reach, s);
    }

    static TrendSignal users(int n, boolean seed) {
        List<TrendSignal.Entry> es = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            es.add(new TrendSignal.Entry(UUID.randomUUID(), UUID.randomUUID(), seed, DL.minusSeconds(3600L * (i + 1)),
                    null, null, "X", null, null));
        }
        return new TrendSignal(DL, es, null);
    }
}
