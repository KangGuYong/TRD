package kr.trendstage.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트 공통 기반. 실제 PostgreSQL(pgvector, infra/docker-compose와 같은 이미지)을 한 번 띄워
 * 모든 테스트 클래스가 공유한다 — Flyway가 V1부터 전부 적용되므로 신규 마이그레이션 검증도 겸한다.
 *
 * Docker가 없으면 컨테이너 기동에서 실패한다. disabledWithoutDocker로 조용히 건너뛰게 하지 않는다 —
 * 테스트를 돌리지 않고 초록불이 뜨면 검증한 것처럼 보이기 때문이다.
 */
@SpringBootTest(properties = {
        // 로컬 전용 application-local.yml(있다면)이 켜는 SQL·바인딩 로그를 테스트에서는 끈다.
        "logging.level.org.hibernate.SQL=WARN",
        "logging.level.org.hibernate.orm.jdbc.bind=WARN"
})
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("trd")
            .withUsername("postgres")
            .withPassword("pw");

    static {
        POSTGRES.start();
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MutableClock clock;
    protected Fixtures fx;

    @BeforeEach
    void resetClock() {
        clock.reset();
        fx = new Fixtures(jdbc);
    }
}
