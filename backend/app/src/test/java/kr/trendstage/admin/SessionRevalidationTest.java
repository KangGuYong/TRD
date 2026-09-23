package kr.trendstage.admin;

import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스펙 §10 #12(세션 쪽)·#13 — K7. */
class SessionRevalidationTest extends AbstractIntegrationTest {

    @Test
    void activeAccountPasses() throws Exception {
        mvc.perform(get("/admin/queues/summary").with(asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER)))
                .andExpect(status().isOk());
    }

    @Test
    void disabledAccountSessionIsRevoked() throws Exception {   // #13
        UUID id = fx.admin("OPERATOR");
        jdbc.update("UPDATE admin_accounts SET disabled_at = now() WHERE id = ?", id);
        mvc.perform(get("/admin/queues/summary").with(asAdmin(id, AdminRole.OPERATOR)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("session-revoked"));
    }

    @Test
    void pendingAccountSessionIsRevoked() throws Exception {
        UUID id = fx.pendingAdmin("REVIEWER");
        mvc.perform(get("/admin/queues/summary").with(asAdmin(id, AdminRole.REVIEWER)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roleChangedSinceLoginIsRevoked() throws Exception {   // #12
        UUID id = fx.admin("OPERATOR");
        mvc.perform(get("/admin/queues/summary").with(asAdmin(id, AdminRole.ADMIN)))   // 세션은 ADMIN, DB는 OPERATOR
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("권한이 바뀌었습니다 — 다시 로그인하세요"));
    }

    @Test
    void unauthenticatedCsrfEndpointUnaffected() throws Exception {
        mvc.perform(get("/admin/auth/csrf")).andExpect(status().isOk());
    }

    @Test
    void disabledAccountCanStillLogout() throws Exception {   // 스펙 §5.3 — 로그아웃은 재검증 제외
        UUID id = fx.admin("OPERATOR");
        jdbc.update("UPDATE admin_accounts SET disabled_at = now() WHERE id = ?", id);

        mvc.perform(post("/admin/auth/logout").with(asAdmin(id, AdminRole.OPERATOR)))
                .andExpect(status().isOk());

        assertThat(fx.auditCount("LOGOUT", id)).isEqualTo(1);
    }

    @Test
    void roleMismatchedSessionReachesLoginController() throws Exception {   // 스펙 §5.3 — 로그인은 재검증 제외
        UUID id = fx.admin("OPERATOR");
        String loginId = jdbc.queryForObject("SELECT login_id FROM admin_accounts WHERE id = ?", String.class, id);

        mvc.perform(post("/admin/auth/login")
                        .with(asAdmin(id, AdminRole.ADMIN))   // 세션은 ADMIN, DB는 OPERATOR
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("about:blank"));   // session-revoked가 아니라 컨트롤러의 자격증명 오류
    }
}
