package kr.trendstage.domain;

import kr.trendstage.domain.verdict.DeadlineWindow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadlineWindowTest {

    @Test void override_없으면_first_seen_at_14일_후가_기한() {
        Instant firstSeenAt = Instant.parse("2026-08-01T00:00:00Z");
        Instant expected = Instant.parse("2026-08-15T00:00:00Z");
        assertEquals(expected, DeadlineWindow.effectiveDeadline(firstSeenAt, null));
    }

    @Test void override_있으면_override가_기한() {
        Instant firstSeenAt = Instant.parse("2026-08-01T00:00:00Z");
        Instant override = Instant.parse("2026-08-20T00:00:00Z");
        assertEquals(override, DeadlineWindow.effectiveDeadline(firstSeenAt, override));
    }

    @Test void 윈도우_시작_경계값은_포함() {
        Instant deadline = Instant.parse("2026-08-15T00:00:00Z");
        Instant windowStart = Instant.parse("2026-08-15T00:00:00Z");
        Instant windowEnd = Instant.parse("2026-08-16T00:00:00Z");
        assertTrue(DeadlineWindow.fallsWithin(deadline, windowStart, windowEnd));
    }

    @Test void 윈도우_끝_경계값은_배제() {
        Instant deadline = Instant.parse("2026-08-16T00:00:00Z");
        Instant windowStart = Instant.parse("2026-08-15T00:00:00Z");
        Instant windowEnd = Instant.parse("2026-08-16T00:00:00Z");
        assertFalse(DeadlineWindow.fallsWithin(deadline, windowStart, windowEnd));
    }

    @Test void 윈도우_밖이면_false() {
        Instant deadline = Instant.parse("2026-08-20T00:00:00Z");
        Instant now = Instant.parse("2026-08-15T00:00:00Z");
        assertFalse(DeadlineWindow.fallsWithin(deadline, now, now.plus(Duration.ofHours(24))));
    }

    // ── 병합 후 최소 관측 보장(SP2 K7) ─────────────────────────
    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    @Test void 병합으로_마감이_과거가_되면_병합시점_3일후_상한_D21() {
        // 새 최초 제보 T0, 지금 T0+20일 → 자연 마감 T0+14일(과거). 목표 T0+23일이지만 상한 T0+21일.
        var d = DeadlineWindow.afterMerge(T0, null, T0.plus(Duration.ofDays(20)));
        assertEquals(Optional.of(new DeadlineWindow.MergeDeadline(T0.plus(Duration.ofDays(21)), true)), d);
    }

    @Test void 병합으로_마감이_너무_임박하면_3일을_보장() {
        var d = DeadlineWindow.afterMerge(T0, null, T0.plus(Duration.ofDays(12)));
        assertEquals(Optional.of(new DeadlineWindow.MergeDeadline(T0.plus(Duration.ofDays(15)), false)), d);
    }

    @Test void 마감이_충분히_남았으면_바꾸지_않는다() {
        assertEquals(Optional.empty(), DeadlineWindow.afterMerge(T0, null, T0.plus(Duration.ofDays(5))));
    }

    @Test void 기존_연장이_더_늦으면_병합이_당기지_않는다() {
        Instant override = T0.plus(Duration.ofDays(30));
        assertEquals(Optional.empty(), DeadlineWindow.afterMerge(T0, override, T0.plus(Duration.ofDays(12))));
    }
}
