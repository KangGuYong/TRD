package kr.trendstage.judge;

import kr.trendstage.scheduler.VerdictRunner;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 스펙 §7.2 1~6: 배치 판정이 결과·상태·원장을 남기고, 멱등이며, 시딩·VOID·관측 마감을 지킨다. */
class JudgePipelineTest extends AbstractIntegrationTest {

    private static final Instant FIRST_SEEN = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant AFTER_DEADLINE = FIRST_SEEN.plus(Duration.ofDays(15));

    @Autowired JudgeService judge;
    @Autowired VerdictRunner runner;

    @Test
    void batchPersistsResultsStateAndRawLedger() {   // #1 — self-invocation 회귀
        UUID item = fx.item(FIRST_SEEN);
        UUID a = fx.submission(fx.user(), item, 30, FIRST_SEEN.plusSeconds(1));
        UUID b = fx.submission(fx.user(), item, 10, FIRST_SEEN.plusSeconds(2));
        UUID c = fx.submission(fx.user(), item, 50, FIRST_SEEN.plusSeconds(3));
        clock.set(AFTER_DEADLINE);

        runBatch();

        assertThat(fx.itemState(item)).isEqualTo("RESOLVED");   // 3명 → T = 0.15 → MISS
        for (UUID s : List.of(a, b, c)) {
            assertThat(fx.submissionResult(s)).isEqualTo("MISS");
            assertThat(jdbc.queryForObject("SELECT resolved_at FROM submissions WHERE id = ?", Timestamp.class, s)
                    .toInstant()).isEqualTo(AFTER_DEADLINE);
        }
        assertThat(ledgerDelta(a)).isEqualByComparingTo("-15");   // 원값 −c × 0.5, 감쇠 없음
        assertThat(ledgerDelta(b)).isEqualByComparingTo("-5");
        assertThat(ledgerDelta(c)).isEqualByComparingTo("-25");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM score_ledger WHERE submission_id IN (?, ?, ?) "
                + "AND halflife_days = 90 AND decay_anchor_at = ?", Integer.class, a, b, c, Timestamp.from(AFTER_DEADLINE)))
                .isEqualTo(3);
    }

    @Test
    void judgingTwiceKeepsOneVerdictAndLedger() {   // #2
        UUID item = fx.item(FIRST_SEEN);
        fx.submission(fx.user(), item, 30, FIRST_SEEN.plusSeconds(1));
        judge.closeDue(AFTER_DEADLINE);

        assertThat(judge.judge(item, AFTER_DEADLINE)).isTrue();
        assertThat(judge.judge(item, AFTER_DEADLINE)).isFalse();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM verdicts WHERE trend_item_id = ?", Integer.class, item))
                .isEqualTo(1);
        assertThat(fx.ledgerRows(item)).isEqualTo(1);
    }

    @Test
    void seedsAreExcludedFromSignalRankAndLedger() {   // #3
        UUID item = fx.item(FIRST_SEEN);
        List<UUID> seeds = new ArrayList<>();
        for (int i = 0; i < 4; i++) seeds.add(fx.seedSubmission(fx.user(), item, FIRST_SEEN.plusSeconds(i)));
        List<UUID> real = new ArrayList<>();
        for (int i = 0; i < 3; i++) real.add(fx.submission(fx.user(), item, 30, FIRST_SEEN.plusSeconds(100 + i)));

        judgeAfterDeadline(item);

        assertThat(fx.itemState(item)).isEqualTo("RESOLVED");
        assertThat(verdictResult(item)).isEqualTo("MISS");   // 시딩을 세면 7명 → 0.35 → HIT였다
        for (UUID s : seeds) {
            assertThat(fx.submissionResult(s)).isEqualTo("MISS");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM score_ledger WHERE submission_id = ?", Integer.class, s)).isZero();
        }
        for (int i = 0; i < real.size(); i++) {
            assertThat(jdbc.queryForObject("SELECT (evidence_json->'orderRanks'->>?)::int FROM verdicts WHERE trend_item_id = ?",
                    Integer.class, real.get(i).toString(), item)).isEqualTo(i + 1);
        }
    }

    @Test
    void fourRealSubmittersHitL1WithRawDelta() {   // #4
        UUID item = fx.item(FIRST_SEEN);
        UUID first = fx.submission(fx.user(), item, 50, FIRST_SEEN.plusSeconds(1));
        for (int i = 0; i < 3; i++) fx.submission(fx.user(), item, 30, FIRST_SEEN.plusSeconds(10 + i));

        judgeAfterDeadline(item);

        assertThat(jdbc.queryForObject("SELECT result::text || ':' || reach_level::text FROM verdicts WHERE trend_item_id = ?",
                String.class, item)).isEqualTo("HIT:L1");
        assertThat(ledgerDelta(first)).isEqualByComparingTo("60");   // 50 × 1.0 × 1.2
    }

    @Test
    void itemWithoutValidSubmissionsBecomesVoid() {   // #5
        UUID item = fx.item(FIRST_SEEN);
        UUID s = fx.submission(fx.user(), item, 30, FIRST_SEEN.plusSeconds(1));
        fx.voidSubmission(s, FIRST_SEEN.plusSeconds(2));

        judgeAfterDeadline(item);

        assertThat(fx.itemState(item)).isEqualTo("VOID");
        assertThat(verdictResult(item)).isEqualTo("VOID");
        assertThat(fx.ledgerRows(item)).isZero();
    }

    @Test
    void submissionsAfterDeadlineAreNotCounted() {   // #6(판정 쪽). 제보 단계 차단은 Task 7
        UUID item = fx.item(FIRST_SEEN);
        for (int i = 0; i < 3; i++) fx.submission(fx.user(), item, 30, FIRST_SEEN.plus(Duration.ofDays(13)));
        UUID late = fx.submission(fx.user(), item, 30, FIRST_SEEN.plus(Duration.ofDays(14)).plusSeconds(1));

        judgeAfterDeadline(item);

        assertThat(verdictResult(item)).isEqualTo("MISS");   // 늦은 제보를 세면 4명 → HIT였다
        assertThat(jdbc.queryForObject("SELECT (evidence_json->>'distinctSubmitters')::int FROM verdicts WHERE trend_item_id = ?",
                Integer.class, item)).isEqualTo(3);
        assertThat(fx.submissionResult(late)).isEqualTo("PENDING");
    }

    @Test
    void graceExtensionAfterClosingReturnsItemToPending() {
        UUID item = fx.item(FIRST_SEEN);
        fx.submission(fx.user(), item, 30, FIRST_SEEN.plusSeconds(1));
        judge.closeDue(AFTER_DEADLINE);
        jdbc.update("UPDATE trend_items SET judgment_deadline_override = ? WHERE id = ?",
                Timestamp.from(AFTER_DEADLINE.plus(Duration.ofDays(3))), item);

        assertThat(judge.judge(item, AFTER_DEADLINE)).isFalse();
        assertThat(fx.itemState(item)).isEqualTo("PENDING");
    }

    private void judgeAfterDeadline(UUID item) {
        judge.closeDue(AFTER_DEADLINE);
        judge.judge(item, AFTER_DEADLINE);
    }

    private void runBatch() {
        releaseBatchLock("verdict_runner");
        runner.run();
    }

    private String verdictResult(UUID item) {
        return jdbc.queryForObject("SELECT result::text FROM verdicts WHERE trend_item_id = ?", String.class, item);
    }

    private BigDecimal ledgerDelta(UUID submission) {
        return jdbc.queryForObject("SELECT delta FROM score_ledger WHERE submission_id = ?", BigDecimal.class, submission);
    }
}
