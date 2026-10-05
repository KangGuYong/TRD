package kr.trendstage.domain.signal;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.TrendSignal;
import kr.trendstage.domain.verdict.VerdictEngine;
import kr.trendstage.domain.verdict.VerdictOutcome;
import kr.trendstage.domain.verdict.VerdictResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SignalAxesEngineTest {

    static final Instant DEADLINE = Instant.parse("2026-06-15T00:00:00Z");

    // ── 기본값 = 옛 공식 ────────────────────────────────────────────────────

    @Test
    void defaultsMatchLegacyFormulaBitForBit() {
        Random rnd = new Random(42);
        ParameterSet d = ParameterSet.defaults();
        List<UUID> pool = new ArrayList<>();
        for (int i = 0; i < 30; i++) pool.add(UUID.randomUUID());
        Platform[] platforms = Platform.values();
        for (int n = 0; n < 200; n++) {
            List<TrendSignal.Entry> entries = new ArrayList<>();
            int size = rnd.nextInt(41);
            for (int i = 0; i < size; i++) {
                entries.add(new TrendSignal.Entry(UUID.randomUUID(), pool.get(rnd.nextInt(pool.size())),
                        rnd.nextInt(10) == 0, DEADLINE.minus(Duration.ofMinutes(rnd.nextInt(14 * 24 * 60) + 1)),
                        null, null,
                        rnd.nextInt(5) == 0 ? null : platforms[rnd.nextInt(platforms.length)].name(),
                        rnd.nextBoolean() ? null : rnd.nextInt(4) + 1,
                        rnd.nextBoolean() ? null : rnd.nextInt(4) + 1));
            }
            Integer active = rnd.nextBoolean() ? null : rnd.nextInt(501);
            TrendSignal sig = new TrendSignal(DEADLINE, entries, active);

            double legacyT = Math.max(0.0, Math.min(1.0, sig.distinctSubmitters() / 20.0));
            VerdictOutcome o = VerdictEngine.evaluate(sig, d);
            assertEquals(legacyT, o.t(), 0.0, "signal #" + n);
            assertEquals(legacyClassify(legacyT), o.result(), "signal #" + n);
            assertEquals(legacyReach(legacyT), o.reach(), "signal #" + n);
        }
    }

    static VerdictResult legacyClassify(double t) {
        return t < 0.20 ? VerdictResult.MISS : VerdictResult.HIT;
    }

    static ReachLevel legacyReach(double t) {
        if (t < 0.20) return null;
        if (t < 0.35) return ReachLevel.L1;
        if (t < 0.55) return ReachLevel.L2;
        if (t < 0.75) return ReachLevel.L3;
        return ReachLevel.L4;
    }

    // ── 목표치(S2) ──────────────────────────────────────────────────────────

    @Test
    void targetIsFloorWhenActiveMissing() {
        TBreakdown b = VerdictEngine.breakdown(signal(users(5), null), params(20, axes(0.5, 1.0, 5, 1.0, 3, IndependenceMode.OFF)));
        assertEquals(20, b.target());
        assertNull(b.activeSubmitters());
    }

    @Test
    void targetUsesCeilOfActiveTimesRatio() {
        ParameterSet p = params(20, axes(0.5, 1.0, 5, 1.0, 3, IndependenceMode.OFF));
        assertEquals(31, VerdictEngine.breakdown(signal(users(5), 61), p).target());   // ⌈30.5⌉
        assertEquals(20, VerdictEngine.breakdown(signal(users(5), 10), p).target());   // 5 < 하한
    }

    @Test
    void ceilIgnoresFloatNoise() {   // Review Focus 2 — 60 × 0.1 = 6.000000000000001
        ParameterSet p = params(1, axes(0.1, 1.0, 5, 1.0, 3, IndependenceMode.OFF));
        assertEquals(6, VerdictEngine.breakdown(signal(users(5), 60), p).target());
        assertEquals(40, VerdictEngine.breakdown(signal(users(5), 400), p).target());
    }

    // ── 지속성(S3) ──────────────────────────────────────────────────────────

    @Test
    void persistenceCountsKstCalendarDays() {
        ParameterSet p = params(20, axes(0.0, 0.5, 4, 1.0, 3, IndependenceMode.OFF));
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), s = UUID.randomUUID();
        TrendSignal twoDays = new TrendSignal(DEADLINE, List.of(
                entry(a, false, Instant.parse("2026-06-01T14:59:59Z"), "X", null, null),   // KST 6/1 23:59:59
                entry(b, false, Instant.parse("2026-06-01T15:00:00Z"), "X", null, null),   // KST 6/2 00:00
                entry(s, true, Instant.parse("2026-06-05T03:00:00Z"), "X", null, null)),   // 시딩 날짜는 안 셈
                null);
        TBreakdown two = VerdictEngine.breakdown(twoDays, p);
        assertEquals(2, two.activeDays());
        assertEquals(0.75, two.persistence(), 1e-12);

        TrendSignal oneDay = new TrendSignal(DEADLINE, List.of(
                entry(a, false, Instant.parse("2026-06-01T00:00:00Z"), "X", null, null),
                entry(b, false, Instant.parse("2026-06-01T14:59:00Z"), "X", null, null)), null);
        assertEquals(1, VerdictEngine.breakdown(oneDay, p).activeDays());
        assertEquals(0.625, VerdictEngine.breakdown(oneDay, p).persistence(), 1e-12);
    }

    @Test
    void persistenceCapsAtOne() {
        ParameterSet p = params(20, axes(0.0, 0.5, 4, 1.0, 3, IndependenceMode.OFF));
        List<TrendSignal.Entry> es = new ArrayList<>();
        for (int d = 0; d < 6; d++) es.add(entry(UUID.randomUUID(), false, Instant.parse("2026-06-01T03:00:00Z").plus(Duration.ofDays(d)), "X", null, null));
        assertEquals(1.0, VerdictEngine.breakdown(new TrendSignal(DEADLINE, es, null), p).persistence(), 0.0);
    }

    // ── 다양성(S4) ──────────────────────────────────────────────────────────

    @Test
    void diversityFromPlatformCodes() {
        ParameterSet p = params(20, axes(0.0, 1.0, 5, 0.5, 3, IndependenceMode.OFF));
        assertEquals(0.5, VerdictEngine.breakdown(codes("DCINSIDE", "DCINSIDE"), p).diversity(), 1e-12);
        assertEquals(0.75, VerdictEngine.breakdown(codes("DCINSIDE", "X"), p).diversity(), 1e-12);
        assertEquals(1.0, VerdictEngine.breakdown(codes("DCINSIDE", "X", "INSTAGRAM", "YOUTUBE"), p).diversity(), 0.0);
        TBreakdown etc = VerdictEngine.breakdown(codes("ETC", "ETC", "DCINSIDE"), p);   // 기타는 1종
        assertEquals(2, etc.platforms());
    }

    @Test
    void diversityIsNeutralWhenAnyCodeMissing() {   // S8 — SP4 이전 제보가 섞이면 판별하지 않는다
        ParameterSet p = params(20, axes(0.0, 1.0, 5, 0.5, 3, IndependenceMode.OFF));
        TBreakdown b = VerdictEngine.breakdown(codes("DCINSIDE", null), p);
        assertEquals(1.0, b.diversity(), 0.0);
        assertFalse(b.diversityApplied());
    }

    // ── 독립성(S5) ──────────────────────────────────────────────────────────

    @Test
    void deviceModeMergesSharedDevice() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        List<TrendSignal.Entry> es = List.of(e(a, 1, null), e(b, 1, null), e(c, 2, null));
        assertEquals(2, Independence.count(es, IndependenceMode.DEVICE));
        assertEquals(3, Independence.count(es, IndependenceMode.OFF));
    }

    @Test
    void deviceOrIpIsTransitive() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        List<TrendSignal.Entry> es = List.of(e(a, 1, 5), e(b, 1, 7), e(c, 3, 7));   // a–b 기기, b–c IP
        assertEquals(1, Independence.count(es, IndependenceMode.DEVICE_OR_IP));
        assertEquals(2, Independence.count(es, IndependenceMode.DEVICE));
    }

    @Test
    void nullGroupsNeverLink() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertEquals(2, Independence.count(List.of(e(a, null, null), e(b, null, null)), IndependenceMode.DEVICE_OR_IP));
    }

    @Test
    void sameUserManyEntriesCountsOnceAndSeedsAreExcluded() {
        UUID a = UUID.randomUUID(), seed = UUID.randomUUID();
        TrendSignal sig = new TrendSignal(DEADLINE, List.of(
                entry(a, false, DEADLINE.minusSeconds(10), "X", 1, null),
                entry(a, false, DEADLINE.minusSeconds(5), "X", 2, null),
                entry(seed, true, DEADLINE.minusSeconds(3), "X", 1, null)), null);
        TBreakdown b = VerdictEngine.breakdown(sig, params(20, axes(0.0, 1.0, 5, 1.0, 3, IndependenceMode.DEVICE)));
        assertEquals(1, b.accounts());
        assertEquals(1, b.independent());
    }

    // ── 범위 검증 ───────────────────────────────────────────────────────────

    @Test
    void axesValidateRangesWithFieldNameFirst() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> axes(0.0, 1.5, 5, 1.0, 3, IndependenceMode.OFF))
                .getMessage().startsWith("persistenceFloor"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> axes(0.0, 1.0, 5, 1.0, 1, IndependenceMode.OFF))
                .getMessage().startsWith("diversityFullPlatforms"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new SignalAxes(0.0, 6, 1.0, 5, 1.0, 3, IndependenceMode.OFF))
                .getMessage().startsWith("activeWindowDays"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> axes(Double.NaN, 1.0, 5, 1.0, 3, IndependenceMode.OFF))
                .getMessage().startsWith("targetRatio"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> axes(0.0, 1.0, 15, 1.0, 3, IndependenceMode.OFF))
                .getMessage().startsWith("persistenceFullDays"));
    }

    // ── 산정 근거 문자열(§2.3) ─────────────────────────────────────────────

    @Test
    void describeFormat() {
        TBreakdown plain = new TBreakdown(15, 15, 20, 150, 0.75, 2, 5, 0.80, 2, 3, true, 0.80, 0.48);
        assertEquals("T 0.48 = 제보자 15/20 (0.75) × 지속성 0.80 (2일 · 기준 5일) × 다양성 0.80 (2곳 · 기준 3곳)", plain.describe());
        TBreakdown merged = new TBreakdown(15, 13, 20, null, 0.65, 3, 5, 1.0, 1, 3, false, 1.0, 0.65);
        assertEquals("T 0.65 = 제보자 13/20 (0.65 · 계정 15 → 독립 13) × 지속성 1.00 (3일 · 기준 5일) × 다양성 1.00 (플랫폼 판별 없음)",
                merged.describe());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────

    static SignalAxes axes(double ratio, double pFloor, int pFull, double dFloor, int dFull, IndependenceMode mode) {
        return new SignalAxes(ratio, 28, pFloor, pFull, dFloor, dFull, mode);
    }

    static ParameterSet params(int floor, SignalAxes axes) {
        ParameterSet d = ParameterSet.defaults();
        return new ParameterSet(floor, d.hitThreshold, d.bandL2, d.bandL3, d.bandL4, d.mL1, d.mL2, d.mL3, d.mL4,
                d.wRank1, d.wRank2, d.wRank3, d.wRankRest, d.halflifeDays, d.tiAlpha, d.tiBeta, axes);
    }

    static TrendSignal.Entry entry(UUID user, boolean seed, Instant at, String code, Integer device, Integer ip) {
        return new TrendSignal.Entry(UUID.randomUUID(), user, seed, at, null, null, code, device, ip);
    }

    static TrendSignal.Entry e(UUID user, Integer device, Integer ip) {
        return entry(user, false, DEADLINE.minusSeconds(60), "X", device, ip);
    }

    static TrendSignal signal(List<TrendSignal.Entry> es, Integer active) {
        return new TrendSignal(DEADLINE, es, active);
    }

    static List<TrendSignal.Entry> users(int n) {
        List<TrendSignal.Entry> es = new ArrayList<>();
        for (int i = 0; i < n; i++) es.add(e(UUID.randomUUID(), null, null));
        return es;
    }

    static TrendSignal codes(String... codes) {
        List<TrendSignal.Entry> es = new ArrayList<>();
        for (String c : codes) es.add(entry(UUID.randomUUID(), false, DEADLINE.minusSeconds(60), c, null, null));
        return new TrendSignal(DEADLINE, es, null);
    }
}
