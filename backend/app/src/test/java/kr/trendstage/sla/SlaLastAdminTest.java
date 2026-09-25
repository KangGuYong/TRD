package kr.trendstage.sla;

import kr.trendstage.scheduler.SlaWatchJob;
import kr.trendstage.scheduler.SlaWatchService;
import kr.trendstage.support.Fixtures;
import kr.trendstage.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스펙 §10 #17 — 마지막 활성 ADMIN은 90일 미접속이어도 비활성화하지 않는다(K11).
 * 공유 DB에는 다른 활성 ADMIN이 늘 있으므로 전용 컨테이너를 쓴다.
 */
@SpringBootTest(properties = {
        "logging.level.org.hibernate.SQL=WARN",
        "logging.level.org.hibernate.orm.jdbc.bind=WARN",
        "jobs.sla-watch.cron=-",
        "signal.hash-secret=test-signal-secret"
})
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class SlaLastAdminTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("trd").withUsername("postgres").withPassword("pw");

    static { POSTGRES.start(); }

    @Autowired SlaWatchJob job;
    @Autowired SlaWatchService sla;
    @Autowired JdbcTemplate jdbc;

    @Test
    void lastActiveAdminIsNeverDisabled() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        UUID only = new Fixtures(jdbc).admin();
        jdbc.update("UPDATE admin_accounts SET created_at = ?, activated_at = ?, last_login_at = NULL WHERE id = ?",
                Timestamp.from(now.minus(java.time.Duration.ofDays(120))), Timestamp.from(now.minus(java.time.Duration.ofDays(120))), only);

        assertThat(sla.inactiveAdminIds(now)).contains(only);   // 미접속 후보이긴 하다
        job.disableInactiveAdmins(now);

        assertThat(jdbc.queryForObject("SELECT disabled_at IS NULL FROM admin_accounts WHERE id = ?", Boolean.class, only)).isTrue();
    }
}
