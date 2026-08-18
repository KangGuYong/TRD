package kr.trendstage.domain;

import kr.trendstage.domain.verdict.DeadlineWindow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

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
}
