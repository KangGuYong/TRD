package kr.trendstage.admin;

import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스펙 §10 #11(승인 대기 경로)·#12·#15 + Review Focus 1. 공유 DB에는 승인 자격자가 늘 있어 부트스트랩 예외가 걸리지 않는다. */
class AccountGovernanceTest extends AbstractIntegrationTest {

    @Autowired PasswordEncoder encoder;

    @Test
    void createNeedsApprovalAndPendingAccountCannotLogIn() throws Exception {
        UUID admin = fx.admin();
        fx.admin();   // 요청자 외 승인 자격자 — 이 클래스가 JVM에서 처음 돌아도 부트스트랩 예외가 걸리지 않게
        String loginId = "new_" + UUID.randomUUID().toString().substring(0, 8);
        String body = mvc.perform(post("/admin/accounts").with(asAdmin(admin, AdminRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"displayName\":\"신규\",\"role\":\"OPERATOR\",\"password\":\"pw-12345678\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.account.pendingApproval").value(true))
                .andReturn().getResponse().getContentAsString();

        login(loginId, "pw-12345678").andExpect(status().isForbidden());

        UUID approvalId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.approvalRequestId"));
        mvc.perform(post("/admin/approvals/{id}/approve", approvalId).with(asAdmin(fx.admin(), AdminRole.ADMIN)))
                .andExpect(status().isOk());
        login(loginId, "pw-12345678").andExpect(status().isOk());
    }

    @Test
    void rejectedCreationDisablesAccount() throws Exception {   // 최종 리뷰 #4 — 반려된 계정이 "승인 대기"로 영영 남지 않는다
        UUID admin = fx.admin();
        fx.admin();
        String loginId = "rej_" + UUID.randomUUID().toString().substring(0, 8);
        String body = mvc.perform(post("/admin/accounts").with(asAdmin(admin, AdminRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"displayName\":\"반려\",\"role\":\"OPERATOR\",\"password\":\"pw-12345678\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID accountId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.account.id"));
        UUID approvalId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.approvalRequestId"));

        mvc.perform(post("/admin/approvals/{id}/reject", approvalId).with(asAdmin(fx.admin(), AdminRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"불필요한 계정\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT disabled_at IS NOT NULL FROM admin_accounts WHERE id = ?", Boolean.class, accountId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT activated_at IS NULL FROM admin_accounts WHERE id = ?", Boolean.class, accountId)).isTrue();
        login(loginId, "pw-12345678").andExpect(status().isForbidden());
        mvc.perform(get("/admin/accounts").with(asAdmin(admin, AdminRole.ADMIN)))
                .andExpect(jsonPath("$[?(@.id == '%s')].pendingApproval", accountId).value(org.hamcrest.Matchers.hasItem(false)));
    }

    @Test
    void enablingPendingAccountIsRejected() throws Exception {
        UUID pending = fx.pendingAdmin("REVIEWER");
        mvc.perform(post("/admin/accounts/{id}/enable", pending).with(asAdmin(fx.admin(), AdminRole.ADMIN)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void roleChangeAppliesAfterApprovalAndRestartsGrace() throws Exception {   // #12
        UUID target = fx.admin("OPERATOR");
        String body = mvc.perform(post("/admin/accounts/{id}/role", target).with(asAdmin(fx.admin(), AdminRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\",\"reason\":\"승격\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(role(target)).isEqualTo("OPERATOR");

        clock.set(Instant.parse("2026-09-23T00:00:00Z"));
        approve(UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.approvalRequestId")));
        assertThat(role(target)).isEqualTo("ADMIN");
        assertThat(jdbc.queryForObject("SELECT approver_since FROM admin_accounts WHERE id = ?", Instant.class, target))
                .isEqualTo(Instant.parse("2026-09-30T00:00:00Z"));
    }

    @Test
    void roleChangeApprovalConflictsWhenRoleAlreadyChanged() throws Exception {   // Review Focus 1
        UUID target = fx.admin("REVIEWER");
        UUID requester = fx.admin();
        String first = requestRole(target, requester, "OPERATOR");
        String second = requestRole(target, requester, "AUDITOR");
        approve(UUID.fromString(com.jayway.jsonpath.JsonPath.read(first, "$.approvalRequestId")));

        UUID secondId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(second, "$.approvalRequestId"));
        mvc.perform(post("/admin/approvals/{id}/approve", secondId).with(asAdmin(fx.admin(), AdminRole.ADMIN)))
                .andExpect(status().isConflict());
        assertThat(fx.approvalStatus(secondId)).isEqualTo("PENDING");
        assertThat(role(target)).isEqualTo("OPERATOR");
    }

    @Test
    void cannotChangeOwnRole() throws Exception {
        UUID admin = fx.admin();
        mvc.perform(post("/admin/accounts/{id}/role", admin).with(asAdmin(admin, AdminRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"AUDITOR\",\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownPasswordChange() throws Exception {   // #15
        UUID admin = fx.admin("REVIEWER");
        jdbc.update("UPDATE admin_accounts SET password_hash = ? WHERE id = ?", encoder.encode("old-pass-1234"), admin);
        String loginId = jdbc.queryForObject("SELECT login_id FROM admin_accounts WHERE id = ?", String.class, admin);

        mvc.perform(post("/admin/me/password").with(asAdmin(admin, AdminRole.REVIEWER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"current\":\"wrong-pass\",\"next\":\"new-pass-1234\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/admin/me/password").with(asAdmin(admin, AdminRole.REVIEWER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"current\":\"old-pass-1234\",\"next\":\"new-pass-1234\"}"))
                .andExpect(status().isOk());

        login(loginId, "old-pass-1234").andExpect(status().isUnauthorized());
        login(loginId, "new-pass-1234").andExpect(status().isOk());
        assertThat(fx.auditCount("ACCOUNT_PASSWORD_CHANGE", admin)).isEqualTo(1);
    }

    private String requestRole(UUID target, UUID requester, String role) throws Exception {
        return mvc.perform(post("/admin/accounts/{id}/role", target).with(asAdmin(requester, AdminRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\",\"reason\":\"변경\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
    }

    private void approve(UUID approvalId) throws Exception {
        mvc.perform(post("/admin/approvals/{id}/approve", approvalId).with(asAdmin(fx.admin(), AdminRole.ADMIN)))
                .andExpect(status().isOk());
    }

    private String role(UUID id) {
        return jdbc.queryForObject("SELECT role::text FROM admin_accounts WHERE id = ?", String.class, id);
    }

    private org.springframework.test.web.servlet.ResultActions login(String loginId, String password) throws Exception {
        jakarta.servlet.http.Cookie csrf = mvc.perform(get("/admin/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return mvc.perform(post("/admin/auth/login").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\"}"));
    }
}
