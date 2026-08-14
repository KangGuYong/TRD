package kr.trendstage.merge;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MergeComputationTest {

    private static MergeComputation.SubmissionInput input(UUID user, String rawInput, Instant createdAt) {
        return new MergeComputation.SubmissionInput(UUID.randomUUID(), user, rawInput, createdAt);
    }

    @Test void 같은_유저가_양쪽에_제보하면_늦은_쪽만_VOID_대상() {
        UUID user = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-01T00:00:00Z");
        var early = input(user, "탕후루 챌린지", t0);
        var late = input(user, "탕후루 챌린지 2", t0.plus(1, ChronoUnit.DAYS));

        Set<UUID> voided = MergeComputation.computeDedup(List.of(early), List.of(late));

        assertEquals(Set.of(late.submissionId()), voided);
    }

    @Test void 서로_다른_유저는_dedup_대상_아님() {
        Instant t0 = Instant.parse("2026-08-01T00:00:00Z");
        var a = input(UUID.randomUUID(), "탕후루 챌린지", t0);
        var b = input(UUID.randomUUID(), "탕후루 챌린지", t0.plus(1, ChronoUnit.DAYS));

        assertTrue(MergeComputation.computeDedup(List.of(a), List.of(b)).isEmpty());
    }

    @Test void 가장_많이_쓰인_표기가_새_대표명() {
        Instant t0 = Instant.parse("2026-08-01T00:00:00Z");
        var subs = List.of(
                input(UUID.randomUUID(), "두바이 초콜릿", t0),
                input(UUID.randomUUID(), "두바이 초콜릿", t0.plus(1, ChronoUnit.DAYS)),
                input(UUID.randomUUID(), "피스타치오 카다이프 초콜릿", t0.plus(2, ChronoUnit.DAYS)));

        assertEquals(Optional.of("두바이 초콜릿"), MergeComputation.computeCanonicalName(subs));
    }

    @Test void 표기_건수가_동률이면_더_이른_쪽이_대표명() {
        Instant t0 = Instant.parse("2026-08-01T00:00:00Z");
        var subs = List.of(
                input(UUID.randomUUID(), "나중 표기", t0.plus(1, ChronoUnit.DAYS)),
                input(UUID.randomUUID(), "먼저 표기", t0));

        assertEquals(Optional.of("먼저 표기"), MergeComputation.computeCanonicalName(subs));
    }

    @Test void 제보가_없으면_대표명_변경_없음() {
        assertEquals(Optional.empty(), MergeComputation.computeCanonicalName(List.of()));
    }

    @Test void 선점_순위는_제보_시각_오름차순() {
        Instant t0 = Instant.parse("2026-08-01T00:00:00Z");
        var s1 = input(UUID.randomUUID(), "a", t0.plus(2, ChronoUnit.DAYS));
        var s2 = input(UUID.randomUUID(), "b", t0);
        var s3 = input(UUID.randomUUID(), "c", t0.plus(1, ChronoUnit.DAYS));

        var order = MergeComputation.computeCombinedOrder(List.of(s1, s2, s3));

        assertEquals(List.of(s2.submissionId(), s3.submissionId(), s1.submissionId()),
                order.stream().map(MergeComputation.OrderComputed::submissionId).toList());
        assertEquals(1, order.get(0).rank());
        assertEquals(2, order.get(1).rank());
        assertEquals(3, order.get(2).rank());
    }
}
