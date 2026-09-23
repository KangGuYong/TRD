package kr.trendstage.admin;

import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스펙 §10 #19 + Review Focus 4. */
class AuditLogApiTest extends AbstractIntegrationTest {

    @Autowired AuditLogService audit;

    @Test
    void filtersByTargetAndReturnsDetail() throws Exception {
        UUID admin = fx.admin();
        UUID target = UUID.randomUUID();
        for (int i = 0; i < 3; i++) audit.record(admin, AdminRole.ADMIN, "LEDGER_ADJ", "USER", target, Map.of("amount", "1" + i));

        mvc.perform(get("/admin/audit-log").param("targetId", target.toString()).with(asAdmin(fx.admin("AUDITOR"), AdminRole.AUDITOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].detail.amount").value("12"))
                .andExpect(jsonPath("$.nextBeforeId").doesNotExist());
    }

    @Test
    void cursorPagesBackward() throws Exception {
        UUID admin = fx.admin();
        UUID target = UUID.randomUUID();
        for (int i = 0; i < 105; i++) audit.record(admin, AdminRole.ADMIN, "LEDGER_ADJ", "USER", target, Map.of());
        String first = mvc.perform(get("/admin/audit-log").param("targetId", target.toString()).with(asAdmin(admin, AdminRole.ADMIN)))
                .andExpect(jsonPath("$.items", hasSize(100)))
                .andReturn().getResponse().getContentAsString();
        Number next = com.jayway.jsonpath.JsonPath.read(first, "$.nextBeforeId");
        mvc.perform(get("/admin/audit-log").param("targetId", target.toString()).param("beforeId", next.toString())
                        .with(asAdmin(admin, AdminRole.ADMIN)))
                .andExpect(jsonPath("$.items", hasSize(5)));
    }

    @Test
    void operatorSeesOnlyOwnRows() throws Exception {
        UUID operator = fx.admin("OPERATOR");
        UUID other = fx.admin();
        UUID target = UUID.randomUUID();
        audit.record(operator, AdminRole.OPERATOR, "LEDGER_ADJ", "USER", target, Map.of());
        audit.record(other, AdminRole.ADMIN, "LEDGER_ADJ", "USER", target, Map.of());
        mvc.perform(get("/admin/audit-log").param("targetId", target.toString()).with(asAdmin(operator, AdminRole.OPERATOR)))
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void badDateFilterIs422() throws Exception {   // Review Focus 4
        mvc.perform(get("/admin/audit-log").param("from", "어제").with(asAdmin(fx.admin(), AdminRole.ADMIN)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void actionFilter() throws Exception {
        UUID admin = fx.admin();
        UUID target = UUID.randomUUID();
        audit.record(admin, AdminRole.ADMIN, "LEDGER_ADJ", "USER", target, Map.of());
        audit.record(admin, AdminRole.ADMIN, "APPROVAL_REQUEST", "USER", target, Map.of());
        mvc.perform(get("/admin/audit-log").param("targetId", target.toString()).param("action", "LEDGER_ADJ")
                        .with(asAdmin(admin, AdminRole.ADMIN)))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[*].action", everyItem(is("LEDGER_ADJ"))));
    }
}
