package kr.trendstage.merge;

import kr.trendstage.merge.ClusterMergeCandidateService.ProcessOutcome;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 스펙 §8 5번(K4): 판정된 항목과 닮으면 병합도 큐도 없이 감사 로그만. */
class ClusterMergeGuardTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    @Autowired ClusterMergeDecider decider;

    private int queueRows(UUID newItem) {
        return jdbc.queryForObject("SELECT count(*) FROM merge_queue WHERE new_trend_item_id = ?", Integer.class, newItem);
    }

    @Test
    void judgedLookalikeIsOnlyAudited() {
        UUID candidate = fx.item(T0.plus(Duration.ofDays(1)));
        UUID judged = fx.item(T0);
        fx.setState(judged, "RESOLVED");
        Instant now = T0.plus(Duration.ofDays(2));

        ProcessOutcome outcome = decider.decide(candidate, judged, 0.90, now);

        assertThat(outcome).isEqualTo(ProcessOutcome.SKIPPED_JUDGED);
        assertThat(fx.itemState(candidate)).isEqualTo("PENDING");
        assertThat(fx.itemState(judged)).isEqualTo("RESOLVED");
        assertThat(queueRows(candidate)).isZero();
        assertThat(fx.auditCount("MERGE_SKIPPED_JUDGED", candidate)).isEqualTo(1);
        assertThat(fx.mergeCheckedAt(candidate)).isEqualTo(now);
    }

    @Test
    void pendingLookalikeAboveThresholdIsAutoMerged() {
        UUID candidate = fx.item(T0.plus(Duration.ofDays(1)));
        UUID older = fx.item(T0);
        clock.set(T0.plus(Duration.ofDays(2)));

        ProcessOutcome outcome = decider.decide(candidate, older, 0.90, clock.instant());

        assertThat(outcome).isEqualTo(ProcessOutcome.AUTO_MERGED);
        assertThat(fx.itemState(candidate)).isEqualTo("MERGED");   // 늦게 생긴 쪽이 패자
    }

    @Test
    void grayZoneIsQueued() {
        UUID candidate = fx.item(T0.plus(Duration.ofDays(1)));
        UUID older = fx.item(T0);

        ProcessOutcome outcome = decider.decide(candidate, older, 0.80, T0.plus(Duration.ofDays(2)));

        assertThat(outcome).isEqualTo(ProcessOutcome.QUEUED);
        assertThat(queueRows(candidate)).isEqualTo(1);
    }
}
