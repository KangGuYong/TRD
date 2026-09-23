package kr.trendstage.admin;

import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.Fixtures;
import kr.trendstage.support.MutableClock;
import kr.trendstage.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스펙 §10 #11 — 승인 자격자가 요청자 외 0명이면 ADMIN 단독 생성(K6), 자격자가 생기면 승인 대기.
 * 공유 DB에는 자격자가 늘 있으므로 전용 컨테이너를 쓴다.
 */
@SpringBootTest(properties = {"logging.level.org.hibernate.SQL=WARN", "logging.level.org.hibernate.orm.jdbc.bind=WARN",
        "jobs.sla-watch.cron=-"})
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class AccountBootstrapModeTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("trd").withUsername("postgres").withPassword("pw");

    static { POSTGRES.start(); }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    @Test
    void soloAdminCreatesUntilAnotherApproverIsEligible() throws Exception {
        Instant t0 = Instant.parse("2026-09-23T00:00:00Z");
        clock.set(t0);
        UUID root = new Fixtures(jdbc).admin();

        create(root, "second").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CREATED_BOOTSTRAP"));
        // second는 7일 유예 중 — 여전히 자격자 0명
        create(root, "third").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CREATED_BOOTSTRAP"));

        clock.set(t0.plus(Duration.ofDays(8)));
        create(root, "fourth").andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }

    private org.springframework.test.web.servlet.ResultActions create(UUID actor, String loginId) throws Exception {
        return mvc.perform(post("/admin/accounts").with(asAdmin(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + loginId + "\",\"displayName\":\"" + loginId + "\",\"role\":\"ADMIN\",\"password\":\"pw-12345678\"}"));
    }

    private RequestPostProcessor asAdmin(UUID id) throws Exception {
        jakarta.servlet.http.Cookie csrf = mvc.perform(get("/admin/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        var principal = new kr.trendstage.apiadmin.auth.AdminPrincipal(id, "root", "root", AdminRole.ADMIN);
        RequestPostProcessor auth = authentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        return r -> { auth.postProcessRequest(r); r.setCookies(csrf); r.addHeader("X-XSRF-TOKEN", csrf.getValue()); return r; };
    }
}
