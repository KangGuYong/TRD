package kr.trendstage.merge;

import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 스펙 §8 8·9·13번: 병합 뒤 순위 재계산, 최소 관측 보장, 중복 VOID의 제보권 반환. */
class MergeRecomputeTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    @Autowired MergeService merge;

    @Test
    void absorbedEarlierSubmitterTakesFirstRankAndSeedIsExcluded() {
        UUID s = fx.item(T0);
        UUID l = fx.item(T0.plus(Duration.ofHours(1)));
        UUID s1 = fx.submission(fx.user(), s, 30, T0.plus(Duration.ofHours(2)));
        UUID l1 = fx.submission(fx.user(), l, 30, T0.plus(Duration.ofHours(1)));
        UUID seed = fx.seedSubmission(fx.user(), l, T0.plus(Duration.ofMinutes(30)));
        clock.set(T0.plus(Duration.ofHours(3)));

        merge.merge(s, l, null, null, "테스트");

        // 흡수된 쪽 제보가 먼저라 1위 — "기존 최대값 + 1"로 붙이면 이 테스트가 깨진다
        assertThat(fx.orderRank(l1)).isEqualTo(1);
        assertThat(fx.orderRank(s1)).isEqualTo(2);
        assertThat(fx.orderRank(seed)).isNull();
    }

    @Test
    void deadlinePulledIntoThePastGetsMinimumObservationCappedAtD21() {
        UUID s = fx.item(T0.plus(Duration.ofDays(10)));
        UUID l = fx.item(T0);
        clock.set(T0.plus(Duration.ofDays(20)));

        merge.merge(s, l, null, null, "테스트");

        assertThat(fx.deadlineOverride(s)).isEqualTo(T0.plus(Duration.ofDays(21)));
    }

    @Test
    void deadlineTooCloseGetsThreeMoreDays() {
        UUID s = fx.item(T0.plus(Duration.ofDays(10)));
        UUID l = fx.item(T0);
        clock.set(T0.plus(Duration.ofDays(12)));

        merge.merge(s, l, null, null, "테스트");

        assertThat(fx.deadlineOverride(s)).isEqualTo(T0.plus(Duration.ofDays(15)));
    }

    @Test
    void mergeNeverShortensAnExistingLaterDeadline() {
        UUID s = fx.item(T0.plus(Duration.ofDays(10)));
        UUID l = fx.item(T0);
        fx.setDeadlineOverride(s, T0.plus(Duration.ofDays(30)));
        clock.set(T0.plus(Duration.ofDays(12)));

        merge.merge(s, l, null, null, "테스트");

        assertThat(fx.deadlineOverride(s)).isEqualTo(T0.plus(Duration.ofDays(30)));
    }

    @Test
    void mergeWithoutPullForwardLeavesDeadlineAlone() {
        UUID s = fx.item(T0);
        UUID l = fx.item(T0.plus(Duration.ofHours(1)));
        clock.set(T0.plus(Duration.ofDays(1)));

        merge.merge(s, l, null, null, "테스트");

        assertThat(fx.deadlineOverride(s)).isNull();
    }

    @Test
    void dedupVoidRefundsQuotaAndPreviewListsItButNotSeeds() {
        UUID s = fx.item(T0);
        UUID l = fx.item(T0.plus(Duration.ofHours(1)));
        UUID u = fx.user();
        UUID keep = fx.submission(u, s, 30, T0.plus(Duration.ofHours(2)));
        UUID hedge = fx.submission(u, l, 30, T0.plus(Duration.ofHours(3)));
        UUID seedUser = fx.user();
        fx.seedSubmission(seedUser, s, T0.plus(Duration.ofHours(2)));
        UUID seedHedge = fx.seedSubmission(seedUser, l, T0.plus(Duration.ofHours(4)));
        Instant now = T0.plus(Duration.ofHours(5));
        clock.set(now);

        MergeService.PreviewResult preview = merge.preview(s, l);
        assertThat(preview.dedupVoidedSubmissionIds()).containsExactlyInAnyOrder(hedge, seedHedge);
        assertThat(preview.quotaRefundSubmissionIds()).containsExactly(hedge);

        merge.merge(s, l, null, null, "테스트");

        assertThat(fx.submissionResult(hedge)).isEqualTo("VOID");
        assertThat(fx.voidedAt(hedge)).isEqualTo(now);   // voided_at이 속한 주에 제보권 1장 반환(QuotaService)
        assertThat(fx.submissionResult(keep)).isEqualTo("PENDING");
    }
}
