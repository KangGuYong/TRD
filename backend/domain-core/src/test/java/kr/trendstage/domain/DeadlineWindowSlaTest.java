package kr.trendstage.domain;

import kr.trendstage.domain.verdict.DeadlineWindow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DeadlineWindowSlaTest {

    private static final Instant FIRST = Instant.parse("2026-09-01T00:00:00Z");   // 기본 마감 09-15, 상한 09-22

    @Test void 마감이_24시간_안이면_지금_더하기_24시간으로_민다() {
        Instant now = Instant.parse("2026-09-14T12:00:00Z");
        assertEquals(now.plus(Duration.ofHours(24)), DeadlineWindow.slaExtended(FIRST, null, now).orElseThrow());
    }

    @Test void 이미_충분히_늦으면_그대로() {
        Instant now = Instant.parse("2026-09-10T00:00:00Z");
        assertTrue(DeadlineWindow.slaExtended(FIRST, null, now).isEmpty());
    }

    @Test void 상한_최초_제보_더하기_21일을_넘지_않는다() {
        Instant now = Instant.parse("2026-09-21T12:00:00Z");
        assertEquals(Instant.parse("2026-09-22T00:00:00Z"), DeadlineWindow.slaExtended(FIRST, null, now).orElseThrow());
        assertTrue(DeadlineWindow.slaExtended(FIRST, Instant.parse("2026-09-22T00:00:00Z"), now).isEmpty());
        assertTrue(DeadlineWindow.ceilingReached(FIRST, Instant.parse("2026-09-22T00:00:00Z")));
        assertFalse(DeadlineWindow.ceilingReached(FIRST, null));
    }
}
