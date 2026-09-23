package kr.trendstage.merge;

import kr.trendstage.judge.AdjustmentPolicy;
import kr.trendstage.judge.JudgeConflictException;
import kr.trendstage.judge.JudgeService;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스펙 §8 1·2번. 서비스 빈을 직접 호출한다(MockMvc는 스레드 간 공유가 불안정).
 * 행 잠금이 없으면 두 작업이 같은 옛 상태를 읽고 둘 다 성공한다.
 */
class MergeConcurrencyTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    @Autowired MergeService merge;
    @Autowired JudgeService judge;

    @Test
    void twoConcurrentMergesOfSamePairSucceedOnce() throws Exception {
        UUID s = fx.item(T0), l = fx.item(T0.plusSeconds(60));
        UUID sub = fx.submission(fx.user(), l, 30, T0.plusSeconds(60));
        clock.set(T0.plus(Duration.ofDays(1)));

        List<Throwable> outcomes = runConcurrently(
                () -> merge.merge(s, l, null, null, "A"),
                () -> merge.merge(s, l, null, null, "B"));

        assertThat(outcomes).filteredOn(Objects::isNull).hasSize(1);
        assertThat(outcomes).filteredOn(Objects::nonNull).singleElement()
                .isInstanceOfSatisfying(MergeConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(MergeConflictException.Reason.TARGET_MERGED));
        assertThat(fx.itemOf(sub)).isEqualTo(s);
        assertThat(fx.itemState(l)).isEqualTo("MERGED");
        assertThat(fx.auditCount("MERGE", s)).isEqualTo(1);
    }

    @Test
    void mergeAndVoidOfSameItemAreSerialized() throws Exception {
        UUID s = fx.item(T0), l = fx.item(T0.plusSeconds(60));
        UUID sub = fx.submission(fx.user(), l, 30, T0.plusSeconds(60));
        clock.set(T0.plus(Duration.ofDays(1)));
        Instant now = clock.instant();

        List<Throwable> outcomes = runConcurrently(
                () -> merge.merge(s, l, null, null, "병합"),
                () -> judge.voidItem(l, "VOID", now, AdjustmentPolicy.limitedTo(new BigDecimal("999999"))));

        assertThat(outcomes).filteredOn(Objects::isNull).hasSize(1);
        if (fx.itemState(l).equals("MERGED")) {          // 병합이 먼저 → VOID는 409
            assertThat(outcomes.get(1)).isInstanceOf(JudgeConflictException.class);
            assertThat(fx.itemOf(sub)).isEqualTo(s);
            assertThat(fx.submissionResult(sub)).isEqualTo("PENDING");
        } else {                                          // VOID가 먼저 → 병합은 409
            assertThat(fx.itemState(l)).isEqualTo("VOID");
            assertThat(outcomes.get(0)).isInstanceOfSatisfying(MergeConflictException.class,
                    e -> assertThat(e.reason()).isEqualTo(MergeConflictException.Reason.RESOLVED));
            assertThat(fx.itemOf(sub)).isEqualTo(l);
            assertThat(fx.submissionResult(sub)).isEqualTo("VOID");
        }
    }

    /** 두 작업을 CountDownLatch로 동시에 출발시키고, 각 작업의 예외(성공이면 null)를 순서대로 돌려준다. */
    private static List<Throwable> runConcurrently(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (Runnable task : List.of(first, second)) {
                futures.add(pool.submit((Callable<Throwable>) () -> {
                    start.await();
                    try {
                        task.run();
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            start.countDown();
            List<Throwable> outcomes = new ArrayList<>();
            for (Future<Throwable> f : futures) outcomes.add(f.get(30, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
