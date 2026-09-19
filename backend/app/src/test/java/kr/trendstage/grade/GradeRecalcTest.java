package kr.trendstage.grade;

import kr.trendstage.apipublic.service.MeService;
import kr.trendstage.apipublic.service.QuotaService;
import kr.trendstage.scheduler.GradeRecalcJob;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 스펙 §7.2 14~16: TI 180일 창, 공식 등급 = 주간 스냅샷(J5), 강등 없음(J8). */
class GradeRecalcTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");

    @Autowired GradeRecalcJob job;
    @Autowired MeService me;
    @Autowired QuotaService quota;

    @Test
    void trustIndexCountsOnlyLast180Days() {   // #14
        clock.set(NOW);
        UUID u = fx.user();
        for (int i = 0; i < 5; i++) fx.judgedSubmission(u, "MISS", NOW.minus(Duration.ofDays(200)));
        for (int i = 0; i < 5; i++) fx.judgedSubmission(u, "HIT", NOW.minus(Duration.ofDays(10)));

        runGradeRecalc();

        assertThat(latest(u, "trust_index", BigDecimal.class)).isEqualByComparingTo("0.700");   // (5+2)/(5+0+5)
        assertThat(latest(u, "judged_count", Integer.class)).isEqualTo(10);                    // 판정완료는 전 기간
    }

    @Test
    void snapshotIsTheOfficialGradeForMeAndQuota() {   // #15
        clock.set(NOW);
        UUID u = fx.user();
        for (int i = 0; i < 5; i++) fx.judgedSubmission(u, "HIT", NOW.minus(Duration.ofDays(1)));
        fx.ledgerRow(u, 40, 90, NOW);   // AS 40

        assertThat(me.grade(u).grade()).isEqualTo("L0");          // 요건은 채웠지만 스냅샷 전
        assertThat(me.grade(u).note()).contains("월요일");
        assertThat(quota.of(u, NOW).max()).isEqualTo(2);

        runGradeRecalc();

        assertThat(me.grade(u).grade()).isEqualTo("L1");
        assertThat(quota.of(u, NOW).max()).isEqualTo(3);
    }

    @Test
    void recalcNeverLowersGradeBeforeDemotionRules() {   // #16
        clock.set(NOW);
        UUID u = fx.user();
        fx.gradeSnapshot(u, "L1", NOW.minus(Duration.ofDays(7)));

        runGradeRecalc();   // 지금 값으로는 L0

        assertThat(latest(u, "grade::text", String.class)).isEqualTo("L1");
        assertThat(latest(u, "trust_index", BigDecimal.class)).isEqualByComparingTo("0.400");
    }

    private void runGradeRecalc() {
        releaseBatchLock("grade_recalc");
        job.run();
    }

    private <T> T latest(UUID userId, String column, Class<T> type) {
        return jdbc.queryForObject("SELECT " + column + " FROM user_grade_current WHERE user_id = ?", type, userId);
    }
}
