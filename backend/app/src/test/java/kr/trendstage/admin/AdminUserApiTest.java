package kr.trendstage.admin;

import kr.trendstage.apipublic.service.MeService;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스펙 §10 #14·#20 + Review Focus 2. */
class AdminUserApiTest extends AbstractIntegrationTest {

    @Autowired MeService me;

    @Test
    void detailMatchesAppGradeInputs() throws Exception {   // #20
        Instant now = Instant.parse("2026-09-21T00:00:00Z");
        clock.set(now);
        UUID u = fx.user();
        for (int i = 0; i < 3; i++) fx.judgedSubmission(u, "HIT", now.minus(Duration.ofDays(5)));
        fx.judgedSubmission(u, "MISS", now.minus(Duration.ofDays(5)));
        fx.ledgerRow(u, 40, 90, now);
        var app = me.grade(u);

        mvc.perform(get("/admin/users/{id}", u).with(asAdmin(fx.admin("AUDITOR"), AdminRole.AUDITOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trustIndex").value(closeTo(app.trustIndex(), 1e-9)))
                .andExpect(jsonPath("$.activeScore").value(closeTo(app.activeScore(), 1e-9)))
                .andExpect(jsonPath("$.judgedCount").value(4))
                .andExpect(jsonPath("$.hitInWindow").value(3))
                .andExpect(jsonPath("$.ledger", hasSize(1)))
                .andExpect(jsonPath("$.basis[0]").value(org.hamcrest.Matchers.startsWith("TI ")));
    }

    @Test
    void searchByHandlePrefix() throws Exception {
        UUID u = fx.user();
        String handle = jdbc.queryForObject("SELECT handle FROM users WHERE id = ?", String.class, u);
        // 전체 핸들을 앞부분으로 — 공유 DB에서 짧은 접두어는 상위 20건 밖으로 밀릴 수 있다
        mvc.perform(get("/admin/users").param("handle", handle.toUpperCase()).with(asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + u + "')]", hasSize(1)));
    }

    @Test
    void unknownUserIs404() throws Exception {
        mvc.perform(get("/admin/users/{id}", UUID.randomUUID()).with(asAdmin(fx.admin(), AdminRole.ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void manualAdjustmentByRoleAndAmount() throws Exception {   // #14
        UUID u = fx.user();
        UUID admin = fx.admin();
        adjust(u, admin, AdminRole.ADMIN, "50").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("APPLIED"));
        adjust(u, admin, AdminRole.ADMIN, "150").andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        UUID operator = fx.admin("OPERATOR");
        String body = adjust(u, operator, AdminRole.OPERATOR, "10").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM score_ledger WHERE user_id = ?", Integer.class, u)).isEqualTo(1);

        UUID approvalId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.approvalRequestId"));
        UUID approver = fx.admin();
        mvc.perform(post("/admin/approvals/{id}/approve", approvalId).with(asAdmin(approver, AdminRole.ADMIN)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM score_ledger WHERE user_id = ? AND kind = 'ADJ' "
                + "AND verdict_id IS NULL AND approval_id = ? AND approved_by_admin_id = ? AND delta = 10",
                Integer.class, u, approvalId, approver)).isEqualTo(1);

        mvc.perform(get("/admin/users/{id}", u).with(asAdmin(admin, AdminRole.ADMIN)))
                .andExpect(jsonPath("$.ledger[?(@.approvedBy)]", hasSize(1)));
    }

    @Test
    void manualAdjustmentValidatesAmount() throws Exception {   // Review Focus 2
        UUID u = fx.user();
        UUID admin = fx.admin();
        adjust(u, admin, AdminRole.ADMIN, "0").andExpect(status().isUnprocessableEntity());
        adjust(u, admin, AdminRole.ADMIN, "10000").andExpect(status().isUnprocessableEntity());
        adjust(u, admin, AdminRole.ADMIN, "1.23456").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT delta FROM score_ledger WHERE user_id = ?", java.math.BigDecimal.class, u))
                .isEqualByComparingTo("1.2346");
        mvc.perform(post("/admin/users/{id}/ledger-adjustments", u).with(asAdmin(admin, AdminRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5,\"reason\":\" \"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    private org.springframework.test.web.servlet.ResultActions adjust(UUID user, UUID actor, AdminRole role, String amount) throws Exception {
        return mvc.perform(post("/admin/users/{id}/ledger-adjustments", user).with(asAdmin(actor, role))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + ",\"reason\":\"수집 장애 보정\"}"));
    }
}
