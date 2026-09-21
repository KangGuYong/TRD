package kr.trendstage.merge;

import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 스펙 §8 3·4번: 판정 전 상태가 아니거나 판정 행이 있으면 병합하지 않는다(K1·K2). */
class MergeGuardTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    @Autowired MergeService merge;

    private void assertRejected(UUID survivor, UUID loser, MergeConflictException.Reason reason) {
        assertThatThrownBy(() -> merge.merge(survivor, loser, null, null, "테스트"))
                .isInstanceOfSatisfying(MergeConflictException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    @Test
    void judgingItemIsRejectedAsRetryable() {
        UUID s = fx.item(T0), l = fx.item(T0.plus(Duration.ofHours(1)));
        UUID sub = fx.submission(fx.user(), l, 30, T0.plus(Duration.ofHours(1)));
        fx.setState(l, "JUDGING");

        assertRejected(s, l, MergeConflictException.Reason.JUDGING);
        assertThat(fx.itemOf(sub)).isEqualTo(l);   // 아무것도 옮기지 않았다
        assertThat(fx.itemState(l)).isEqualTo("JUDGING");
    }

    @Test
    void resolvedItemIsRejected() {
        UUID s = fx.item(T0), l = fx.item(T0.plus(Duration.ofHours(1)));
        fx.setState(s, "RESOLVED");
        assertRejected(s, l, MergeConflictException.Reason.RESOLVED);
    }

    @Test
    void voidItemIsRejected() {
        UUID s = fx.item(T0), l = fx.item(T0.plus(Duration.ofHours(1)));
        fx.setState(l, "VOID");
        assertRejected(s, l, MergeConflictException.Reason.RESOLVED);
    }

    @Test
    void pendingItemWithVerdictRowIsRejected() {   // 상태와 판정 행이 어긋나도 막힌다
        UUID s = fx.item(T0), l = fx.item(T0.plus(Duration.ofHours(1)));
        fx.verdictRow(l);
        assertRejected(s, l, MergeConflictException.Reason.RESOLVED);
    }

    @Test
    void alreadyMergedLoserIsRejected() {
        UUID s = fx.item(T0), l = fx.item(T0.plus(Duration.ofHours(1))), other = fx.item(T0);
        fx.merged(l, other);
        assertRejected(s, l, MergeConflictException.Reason.TARGET_MERGED);
    }
}
