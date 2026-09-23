package kr.trendstage.approval;

import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스펙 §10 #9·#10: 요청자 + 승인자 1명(K1), 승인자 자격(K2). */
class ApprovalFlowTest extends AbstractIntegrationTest {

    @Test
    void paramApplyExecutesOnSingleApproval() throws Exception {
        UUID requester = fx.admin("OPERATOR");
        UUID approver = fx.admin();
        UUID[] ids = fx.paramDraftInReview(requester);
        try {
            mvc.perform(post("/admin/approvals/{id}/approve", ids[1]).with(asAdmin(approver, AdminRole.ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("EXECUTED"))
                    .andExpect(jsonPath("$.approvals").value(1))
                    .andExpect(jsonPath("$.requiredApprovals").value(1));
            assertThat(jdbc.queryForObject("SELECT status::text FROM parameter_drafts WHERE id = ?", String.class, ids[0]))
                    .isEqualTo("APPLIED");
            assertThat(fx.auditCount("APPROVAL_EXECUTE", ids[0])).isEqualTo(1);
        } finally {
            fx.deleteDraft(ids[0]);
        }
    }

    @Test
    void requesterCannotApproveOwnRequest() throws Exception {
        UUID requester = fx.admin();
        UUID[] ids = fx.paramDraftInReview(requester);
        try {
            mvc.perform(post("/admin/approvals/{id}/approve", ids[1]).with(asAdmin(requester, AdminRole.ADMIN)))
                    .andExpect(status().isConflict());
            assertThat(fx.approvalStatus(ids[1])).isEqualTo("PENDING");
        } finally {
            fx.deleteDraft(ids[0]);
        }
    }

    @Test
    void adminInGraceCannotApprove() throws Exception {
        UUID requester = fx.admin("OPERATOR");
        UUID grace = fx.adminInGrace(clock.instant().plus(Duration.ofDays(3)));
        UUID[] ids = fx.paramDraftInReview(requester);
        try {
            mvc.perform(post("/admin/approvals/{id}/approve", ids[1]).with(asAdmin(grace, AdminRole.ADMIN)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value(containsString("부터")));
            assertThat(fx.approvalStatus(ids[1])).isEqualTo("PENDING");
        } finally {
            fx.deleteDraft(ids[0]);
        }
    }

    @Test
    void operatorCannotApprove() throws Exception {
        UUID requester = fx.admin("OPERATOR");
        UUID operator = fx.admin("OPERATOR");
        UUID[] ids = fx.paramDraftInReview(requester);
        try {
            mvc.perform(post("/admin/approvals/{id}/approve", ids[1]).with(asAdmin(operator, AdminRole.OPERATOR)))
                    .andExpect(status().isForbidden());
        } finally {
            fx.deleteDraft(ids[0]);
        }
    }

    @Test
    void pendingListShowsSummary() throws Exception {
        UUID requester = fx.admin("OPERATOR");
        UUID[] ids = fx.paramDraftInReview(requester);
        try {
            mvc.perform(get("/admin/approvals").with(asAdmin(fx.admin(), AdminRole.ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.id == '" + ids[1] + "')].summary").value(org.hamcrest.Matchers.hasItem(startsWith("파라미터 적용"))));
        } finally {
            fx.deleteDraft(ids[0]);
        }
    }
}
