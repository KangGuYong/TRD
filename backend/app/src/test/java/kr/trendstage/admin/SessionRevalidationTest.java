package kr.trendstage.admin;

import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
