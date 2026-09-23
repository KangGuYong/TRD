package kr.trendstage.judge;

import kr.trendstage.apiadmin.params.ParamStudioService;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 판정 근거에 동결한 신호로 시뮬레이션이 돈다(스펙 §2.6). 공유 DB라 개수는 보지 않는다. */
class SimulationFromEvidenceTest extends AbstractIntegrationTest {

    @Autowired JudgeService judge;
    @Autowired ParamStudioService studio;

    @Test
    void simulationReadsSignalFromEvidence() {
        Instant firstSeen = Instant.parse("2026-09-01T00:00:00Z");
        Instant judgedAt = firstSeen.plus(Duration.ofDays(15));
        UUID item = fx.item(firstSeen);
        for (int i = 0; i < 4; i++) fx.submission(fx.user(), item, 30, firstSeen.plusSeconds(i));
        judge.closeDue(judgedAt);
        judge.judge(item, judgedAt);
        clock.set(judgedAt.plus(Duration.ofDays(1)));

        String simResult = studio.simulate(fx.admin(), AdminRole.ADMIN).getSimResult();

        assertThat(simResult).contains("\"total\":");
    }

    @Test
    void previewCountsSubmittersWithoutSeeds() {
        UUID item = fx.item(Instant.parse("2026-09-10T00:00:00Z"));
        fx.seedSubmission(fx.user(), item, Instant.parse("2026-09-10T00:00:00Z"));
        fx.submission(fx.user(), item, 30, Instant.parse("2026-09-10T01:00:00Z"));

        JudgeService.Preview preview = judge.preview(item);

        assertThat(preview.signal().distinctSubmitters()).isEqualTo(1);
        assertThat(preview.signal().validCount()).isEqualTo(2);
    }
}
