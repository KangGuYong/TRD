package kr.trendstage.merge;

import kr.trendstage.apiadmin.merge.IdempotencyKeyMismatchException;
import kr.trendstage.apiadmin.merge.MergeDecisionService;
import kr.trendstage.apiadmin.merge.MergeDecisionService.Decision;
import kr.trendstage.apiadmin.merge.MergeDecisionService.Outcome;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 스펙 §8 6·7·14번(K5): 같은 키는 같은 결과, 다른 결정·다른 후보면 거부, 사라진 후보는 정리. */
class MergeIdempotencyTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    @Autowired MergeDecisionService decisions;

    private UUID admin;

    private record Candidate(UUID queue, UUID newItem, UUID oldItem, UUID newSub) {}

    @BeforeEach
    void setUp() {
        admin = fx.admin();
        clock.set(T0.plus(Duration.ofDays(1)));
    }

    private Candidate candidate() {
        UUID oldItem = fx.item(T0);
        UUID newItem = fx.item(T0.plus(Duration.ofHours(1)));
        fx.submission(fx.user(), oldItem, 30, T0.plusSeconds(1));
        UUID newSub = fx.submission(fx.user(), newItem, 30, T0.plus(Duration.ofHours(1)));
        return new Candidate(fx.mergeQueueEntry(newItem, oldItem, 0.80), newItem, oldItem, newSub);
    }

    private Outcome decide(Candidate c, Decision d, String key) {
        return decisions.decide(c.queue(), d, key, admin, AdminRole.ADMIN, "테스트");
    }

    private static String newKey() {
        return "k-" + UUID.randomUUID();
    }

    @Test
    void sameKeyReplaysPreviousResult() {
        Candidate c = candidate();
        String key = newKey();

        Outcome first = decide(c, Decision.MERGE, key);
        Outcome second = decide(c, Decision.MERGE, key);

        assertThat(first.body().replayed()).isFalse();
        assertThat(second.body().replayed()).isTrue();
        assertThat(second.body().status()).isEqualTo("MERGED");
        assertThat(second.body().survivorId()).isEqualTo(c.oldItem().toString());
        assertThat(second.body().loserId()).isEqualTo(c.newItem().toString());
        assertThat(fx.itemOf(c.newSub())).isEqualTo(c.oldItem());
        assertThat(fx.auditCount("MERGE", c.oldItem())).isEqualTo(1);   // 두 번째는 실행하지 않았다
    }

    @Test
    void sameKeyWithDifferentDecisionIsRejected() {
        Candidate c = candidate();
        String key = newKey();
        decide(c, Decision.MERGE, key);

        assertThatThrownBy(() -> decide(c, Decision.SEPARATE, key))
                .isInstanceOf(IdempotencyKeyMismatchException.class);
    }

    @Test
    void anotherKeyOnDecidedCandidateIsConflict() {
        Candidate c = candidate();
        decide(c, Decision.MERGE, newKey());

        assertThatThrownBy(() -> decide(c, Decision.MERGE, newKey()))
                .isInstanceOfSatisfying(MergeConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(MergeConflictException.Reason.QUEUE_DECIDED));
    }

    @Test
    void keyReusedOnAnotherCandidateIsRejected() {
        Candidate c1 = candidate(), c2 = candidate();
        String key = newKey();
        decide(c1, Decision.SEPARATE, key);

        assertThatThrownBy(() -> decide(c2, Decision.SEPARATE, key))
                .isInstanceOf(IdempotencyKeyMismatchException.class);
        assertThat(fx.queueStatus(c2.queue())).isEqualTo("PENDING");
    }

    @Test
    void candidateWhoseItemWasMergedElsewhereIsCleanedUp() {
        Candidate c = candidate();
        fx.merged(c.newItem(), fx.item(T0));   // 그사이 자동 병합으로 사라짐

        Outcome o = decide(c, Decision.VOID, newKey());

        assertThat(o.stale()).isTrue();
        assertThat(fx.queueStatus(c.queue())).isEqualTo("SKIPPED");
        assertThat(fx.auditCount("MERGE_QUEUE_STALE", c.newItem())).isEqualTo(1);
    }

    @Test
    void judgingTargetIsRejectedAndCandidateStaysPending() {
        Candidate c = candidate();
        fx.setState(c.oldItem(), "JUDGING");

        assertThatThrownBy(() -> decide(c, Decision.MERGE, newKey()))
                .isInstanceOfSatisfying(MergeConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(MergeConflictException.Reason.JUDGING));
        assertThat(fx.queueStatus(c.queue())).isEqualTo("PENDING");   // 롤백 — 재시도 가능
    }
}
