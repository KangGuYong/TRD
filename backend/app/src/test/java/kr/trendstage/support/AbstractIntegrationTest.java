package kr.trendstage.support;

import jakarta.servlet.http.Cookie;
import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
        "logging.level.org.hibernate.orm.jdbc.bind=WARN",
        // 배치 크론은 전부 끈다 — 실제 시각이 크론에 걸리면 공유 MutableClock으로 DB 전체를 판정·병합·등급 재계산해
        // 다른 테스트를 흔든다(Spring '-'는 크론 비활성). 테스트는 배치를 직접 호출한다.
        "jobs.sla-watch.cron=-",
        "jobs.verdict-runner.cron=-",
        "jobs.cluster-merge.cron=-",
        "jobs.grade-recalc.cron=-",
        // 로컬에 임베딩 서비스(TEI, 기본 :6000)가 떠 있어도 붙지 않게 — cluster_merge 수동 실행 결과를 결정적으로.
        "embedding.service.url=http://127.0.0.1:1"
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

    /**
     * 배치의 ShedLock(lockAtLeastFor = PT1M)을 만료시켜 곧바로 다시 실행할 수 있게 한다. 배치를 부르기 전에 항상 호출한다.
     * 행을 DELETE하면 안 된다 — JdbcTemplateLockProvider는 한 번 만든 행을 기억해 이후 UPDATE만 시도하므로,
     * 행이 없으면 락을 못 잡고 배치가 조용히 건너뛰어진다.
     */
    protected void releaseBatchLock(String name) {
        jdbc.update("UPDATE shedlock SET lock_until = TIMESTAMP '1970-01-01' WHERE name = ?", name);
    }

    /**
     * 앱 유저. Firebase 필터가 SecurityContext에 심는 것과 같은 principal(내부 userId UUID).
     * /v1 체인은 STATELESS·CSRF 없음이라 이것으로 충분하다 — 토큰 파싱만 건너뛰고 나머지 보안 체인은 그대로 탄다.
     */
    protected RequestPostProcessor asUser(UUID userId) {
        return authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    /**
     * 관리자 세션 + CSRF(쿠키-헤더 이중 제출). role은 계정의 DB 역할과 같아야 한다 —
     * 세션 재검증(SP3 K7)이 다르면 401을 낸다.
     */
    protected RequestPostProcessor asAdmin(UUID accountId, AdminRole role) throws Exception {
        Cookie csrf = mvc.perform(get("/admin/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        AdminPrincipal principal = new AdminPrincipal(accountId, "t_" + accountId, "테스터", role);
        RequestPostProcessor auth = authentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
        return request -> {
            auth.postProcessRequest(request);
            request.setCookies(csrf);
            request.addHeader("X-XSRF-TOKEN", csrf.getValue());
            return request;
        };
    }
}
