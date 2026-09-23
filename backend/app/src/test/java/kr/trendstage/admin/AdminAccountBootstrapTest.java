package kr.trendstage.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 빈 DB + ADMIN_BOOTSTRAP_* 로 기동하면 최초 관리자 계정과 그 감사 기록이 남아야 한다(docker compose 첫 기동 경로).
 *
 * 공유 컨테이너(AbstractIntegrationTest)는 다른 테스트가 계정을 만들어 두어 "빈 admin_accounts" 조건이
 * 성립하지 않으므로 전용 컨테이너를 쓴다. 부트스트랩이 실패하면 ApplicationRunner 예외로 컨텍스트 로딩부터 실패한다.
 */
@SpringBootTest(properties = {
        "ADMIN_BOOTSTRAP_LOGIN_ID=bootstrap-admin",
        "ADMIN_BOOTSTRAP_PASSWORD=bootstrap-pw-1234",
        "logging.level.org.hibernate.SQL=WARN",
        "logging.level.org.hibernate.orm.jdbc.bind=WARN",
        "jobs.sla-watch.cron=-"
})
class AdminAccountBootstrapTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("trd")
            .withUsername("postgres")
            .withPassword("pw");

    static {
        POSTGRES.start();
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void emptyDbBootstrapCreatesAdminAndAuditEntry() {
        Map<String, Object> account = jdbc.queryForMap(
                "SELECT id, role::text AS role, password_hash FROM admin_accounts WHERE login_id = 'bootstrap-admin'");
        assertThat(account.get("role")).isEqualTo("ADMIN");
        assertThat(passwordEncoder.matches("bootstrap-pw-1234", (String) account.get("password_hash"))).isTrue();

        Integer audits = jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_log WHERE action = 'ADMIN_BOOTSTRAP' AND actor_id = ?",
                Integer.class, account.get("id"));
        assertThat(audits).isEqualTo(1);
    }
}
