package kr.trendstage.judge;

import kr.trendstage.scheduler.VerdictRunner;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

class VerdictRunnerRetryTest extends AbstractIntegrationTest {

    private static final Instant FIRST_SEEN = Instant.parse("2026-07-01T00:00:00Z");

    @SpyBean JudgeService judge;
    @Autowired VerdictRunner runner;

    @Test
    void failedItemStaysJudgingAndIsRetriedNextRun() {
        UUID item = fx.item(FIRST_SEEN);
        fx.submission(fx.user(), item, 30, FIRST_SEEN.plusSeconds(1));
        clock.set(FIRST_SEEN.plus(Duration.ofDays(15)));

        doThrow(new IllegalStateException("주입된 실패")).when(judge).judge(eq(item), any());
        runBatch();
        assertThat(fx.itemState(item)).isEqualTo("JUDGING");

        Mockito.reset(judge);
        runBatch();
        assertThat(fx.itemState(item)).isEqualTo("RESOLVED");
    }

    private void runBatch() {
        releaseBatchLock("verdict_runner");
        runner.run();
    }
}
