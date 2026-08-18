package kr.trendstage.domain.verdict;

import java.time.Duration;
import java.time.Instant;

/**
 * 유효 판정 기한이 특정 시간창 안에 드는지 판정하는 순수 규칙.
 * VerdictRunner.isDue()와 같은 "override ?? first_seen_at+14일" 규칙을 쓰지만,
 * 배치 코드(VerdictRunner)는 건드리지 않고 ADM-010 집계 전용으로 별도 둔다.
 */
public final class DeadlineWindow {
    private DeadlineWindow() {}

    private static final int JUDGE_WINDOW_DAYS = 14;

    public static Instant effectiveDeadline(Instant firstSeenAt, Instant override) {
        return override != null ? override : firstSeenAt.plus(Duration.ofDays(JUDGE_WINDOW_DAYS));
    }

    public static boolean fallsWithin(Instant deadline, Instant windowStart, Instant windowEndExclusive) {
        return !deadline.isBefore(windowStart) && deadline.isBefore(windowEndExclusive);
    }
}
