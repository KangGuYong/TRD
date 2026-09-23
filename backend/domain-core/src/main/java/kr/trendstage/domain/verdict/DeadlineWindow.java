package kr.trendstage.domain.verdict;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 유효 판정 기한이 특정 시간창 안에 드는지 판정하는 순수 규칙.
 * VerdictRunner.isDue()와 같은 "override ?? first_seen_at+14일" 규칙을 쓰지만,
 * 배치 코드(VerdictRunner)는 건드리지 않고 ADM-010 집계 전용으로 별도 둔다.
 */
public final class DeadlineWindow {
    private DeadlineWindow() {}

    private static final int JUDGE_WINDOW_DAYS = 14;

    /** 관측 마감의 절대 상한: 최초 제보 + 21일(기본 14일 + 유예 연장 최대 7일). ADM-200 연장과 병합 보장이 공유한다. */
    public static final int MAX_DEADLINE_DAYS = 21;

    /** 병합으로 마감이 당겨졌을 때 병합 시점부터 보장하는 최소 관측 일수(SP2 K7). 운영 안전장치라 파라미터가 아니다. */
    public static final int MERGE_MIN_OBSERVATION_DAYS = 3;

    public static Instant effectiveDeadline(Instant firstSeenAt, Instant override) {
        return override != null ? override : firstSeenAt.plus(Duration.ofDays(JUDGE_WINDOW_DAYS));
    }

    public static boolean fallsWithin(Instant deadline, Instant windowStart, Instant windowEndExclusive) {
        return !deadline.isBefore(windowStart) && deadline.isBefore(windowEndExclusive);
    }

    /** capped = D+21 상한 때문에 최소 관측 3일을 채우지 못함. */
    public record MergeDeadline(Instant override, boolean capped) {}

    /**
     * 병합 뒤 새 최초 제보 시각 기준으로 마감이 병합 시점 + 3일보다 이르면 새 override를 돌려준다.
     * 병합은 마감을 당기지 않는다 — 이미 충분하거나 기존 연장이 더 늦으면 empty.
     * 상한(새 최초 제보 + 21일)이 최소 관측보다 우선한다.
     */
    public static Optional<MergeDeadline> afterMerge(Instant newFirstSeen, Instant currentOverride, Instant now) {
        Instant current = effectiveDeadline(newFirstSeen, currentOverride);
        Instant floor = now.plus(Duration.ofDays(MERGE_MIN_OBSERVATION_DAYS));
        Instant ceiling = newFirstSeen.plus(Duration.ofDays(MAX_DEADLINE_DAYS));
        boolean capped = ceiling.isBefore(floor);
        Instant target = capped ? ceiling : floor;
        if (!current.isBefore(target)) return Optional.empty();
        return Optional.of(new MergeDeadline(target, capped));
    }

    /** 병합 검수 SLA(24h) 초과 시 보장하는 관측 여유(SP3 K10). */
    public static final int MERGE_SLA_GRACE_HOURS = 24;

    /**
     * 병합 대기가 SLA를 넘겼을 때의 새 마감 — max(현재 마감, now + 24h), 상한 최초 제보 + 21일.
     * 당기지 않는다. 늘릴 게 없으면 empty.
     */
    public static Optional<Instant> slaExtended(Instant firstSeenAt, Instant override, Instant now) {
        Instant current = effectiveDeadline(firstSeenAt, override);
        Instant ceiling = firstSeenAt.plus(Duration.ofDays(MAX_DEADLINE_DAYS));
        Instant wanted = now.plus(Duration.ofHours(MERGE_SLA_GRACE_HOURS));
        Instant target = wanted.isAfter(ceiling) ? ceiling : wanted;
        return target.isAfter(current) ? Optional.of(target) : Optional.empty();
    }

    /** 마감이 상한(최초 제보 + 21일)에 닿았는가 — 더 연장할 수 없다(ADM-010 알림). */
    public static boolean ceilingReached(Instant firstSeenAt, Instant override) {
        return !effectiveDeadline(firstSeenAt, override).isBefore(firstSeenAt.plus(Duration.ofDays(MAX_DEADLINE_DAYS)));
    }
}
