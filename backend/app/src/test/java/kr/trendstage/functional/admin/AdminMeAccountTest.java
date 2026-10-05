package kr.trendstage.functional.admin;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminMeAccountTest extends FunctionalTestBase {

    @Test
    @DisplayName("ADM-ME-01 GET /admin/me는 4개 역할 모두 세션의 {id, loginId, displayName, role}")
    void meReturnsSessionPrincipal() throws Exception {
        for (AdminRole role : AdminRole.values()) {
            UUID id = fx.admin(role.name());
            mvc.perform(get("/admin/me").with(asAdmin(id, role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id.toString()))
                    .andExpect(jsonPath("$.loginId").value("t_" + id))
                    .andExpect(jsonPath("$.displayName").value("테스터"))
                    .andExpect(jsonPath("$.role").value(role.name()));
        }
    }

    @Test
    @DisplayName("ADM-ACC-01 계정 목록: ADMIN·AUDITOR 200, REVIEWER·OPERATOR 403")
    void accountListRoles() throws Exception {
        for (AdminRole role : new AdminRole[]{AdminRole.ADMIN, AdminRole.AUDITOR}) {
            mvc.perform(get("/admin/accounts").with(asAdmin(fx.admin(role.name()), role))).andExpect(status().isOk());
        }
        for (AdminRole role : new AdminRole[]{AdminRole.REVIEWER, AdminRole.OPERATOR}) {
            mvc.perform(get("/admin/accounts").with(asAdmin(fx.admin(role.name()), role))).andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("ADM-ACC-02 ADMIN이 다른 계정 비활성화 → 200 disabledAt, 감사 ACCOUNT_DISABLE, 그 계정 다음 요청 401 session-revoked")
    void disableAccount() throws Exception {
        UUID admin = fx.admin();
        UUID target = fx.admin("OPERATOR");

        mvc.perform(post("/admin/accounts/{id}/disable", target).with(asAdmin(admin, AdminRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disabledAt").value(notNullValue()));

        assertThat(fx.auditCount("ACCOUNT_DISABLE", target)).isEqualTo(1);
        mvc.perform(get("/admin/queues/summary").with(asAdmin(target, AdminRole.OPERATOR)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("session-revoked"));
    }

    @Test
    @DisplayName("ADM-ACC-03 비활성화: 자기 자신 403, 없는 계정 422, OPERATOR 호출 403")
    void disableErrors() throws Exception {
        UUID admin = fx.admin();
        mvc.perform(post("/admin/accounts/{id}/disable", admin).with(asAdmin(admin, AdminRole.ADMIN)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/accounts/{id}/disable", UUID.randomUUID()).with(asAdmin(admin, AdminRole.ADMIN)))
                .andExpect(status().isUnprocessableEntity());
        UUID operator = fx.admin("OPERATOR");
        mvc.perform(post("/admin/accounts/{id}/disable", fx.admin("REVIEWER")).with(asAdmin(operator, AdminRole.OPERATOR)))
                .andExpect(status().isForbidden());
    }
}
