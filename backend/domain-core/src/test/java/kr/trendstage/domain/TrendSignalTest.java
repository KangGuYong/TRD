package kr.trendstage.domain;

import kr.trendstage.domain.verdict.TrendSignal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrendSignalTest {

    private static final Instant T = Instant.parse("2026-09-01T00:00:00Z");

    @Test void 시딩은_제보자_수와_플랫폼_수에서_빠지고_유효_제보_수에는_남는다() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), seed = UUID.randomUUID();
        TrendSignal s = new TrendSignal(T, List.of(
                entry(a, false, "X"), entry(a, false, "X"), entry(b, false, "INSTAGRAM"), entry(seed, true, "YOUTUBE")));
        assertEquals(4, s.validCount());
        assertEquals(2, s.distinctSubmitters());
        assertEquals(2, s.distinctPlatforms());
    }

    static TrendSignal.Entry entry(UUID user, boolean seed, String platform) {
        return new TrendSignal.Entry(UUID.randomUUID(), user, seed, T, platform, T);
    }
}
