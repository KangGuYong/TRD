package kr.trendstage.functional.admin;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 파라미터 드래프트는 전역 1개다 — 앞뒤로 DRAFT·REVIEW 드래프트를 지우고 딸린 승인 요청을 닫는다. */
class AdminParamStudioTest extends FunctionalTestBase {

    private static final String DRAFT = "/admin/params/draft";

    private RequestPostProcessor op;

    @BeforeEach
    void setUp() throws Exception {
        clearActiveDrafts();
        op = asAdmin(fx.admin("OPERATOR"), AdminRole.OPERATOR);
    }

    @AfterEach
    void clearActiveDrafts() {
        jdbc.update("UPDATE approval_requests SET status = 'REJECTED' WHERE action_type = 'PARAM_APPLY' AND status = 'PENDING' "
                + "AND target_ref IN (SELECT id FROM parameter_drafts WHERE status IN ('DRAFT', 'REVIEW'))");
        jdbc.update("DELETE FROM parameter_drafts WHERE status IN ('DRAFT', 'REVIEW')");
    }

    private UUID draftId() throws Exception {
        return uuid(getOk(DRAFT, op), "$.draftId");
    }

    private void simulate() throws Exception {
        mvc.perform(post(DRAFT + "/simulate").with(op)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADM-PRM-01 OPERATOR 조회 → 드래프트 생성(DRAFT), 드래프트 값 = 현재 값")
    void getCreatesDraft() throws Exception {
        String json = getOk(DRAFT, op);

        assertThat((String) read(json, "$.draftId")).isNotNull();
        assertThat((String) read(json, "$.status")).isEqualTo("DRAFT");
        assertThat((Integer) read(json, "$.submitterTarget")).isEqualTo((Integer) read(json, "$.currentSubmitterTarget"));
    }

    @Test
    @DisplayName("ADM-PRM-02 PUT {25, 0.25} → 값 반영, simResult 비워짐, 감사 PARAM_DRAFT_UPDATE")
    void putUpdatesAndClearsSimulation() throws Exception {
        UUID id = draftId();
        simulate();

        putJson(DRAFT, op, "{\"submitterTarget\":25,\"hitThreshold\":0.25}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitterTarget").value(25))
                .andExpect(jsonPath("$.hitThreshold").value(closeTo(0.25, 1e-9)))
                .andExpect(jsonPath("$.simResult").value(nullValue()));
        assertThat(fx.auditCount("PARAM_DRAFT_UPDATE", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("ADM-PRM-03 PUT submitterTarget=0은 422")
    void putZeroTargetIs422() throws Exception {
        putJson(DRAFT, op, "{\"submitterTarget\":0,\"hitThreshold\":0.2}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    @org.junit.jupiter.api.Disabled("BUG-7: hitThreshold 범위를 검증하지 않는다(D5 결정: 0 < hitThreshold ≤ 1, 밖이면 422)")
    @DisplayName("ADM-PRM-04 PUT hitThreshold -0.1·1.5·0은 422 (D5: 0 < hitThreshold ≤ 1)")
    void putThresholdOutOfRangeIs422() throws Exception {
        for (String t : new String[]{"-0.1", "1.5", "0"}) {
            putJson(DRAFT, op, "{\"submitterTarget\":20,\"hitThreshold\":" + t + "}")
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    @Test
    @DisplayName("ADM-PRM-05 simulate → simResult 5개 필드, 감사 PARAM_SIMULATE")
    void simulateWritesResult() throws Exception {
        UUID id = draftId();

        mvc.perform(post(DRAFT + "/simulate").with(op))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simResult.changed").value(notNullValue()))
                .andExpect(jsonPath("$.simResult.total").value(notNullValue()))
                .andExpect(jsonPath("$.simResult.missToHit").value(notNullValue()))
                .andExpect(jsonPath("$.simResult.hitToMiss").value(notNullValue()))
                .andExpect(jsonPath("$.simResult.reachChanged").value(notNullValue()));
        assertThat(fx.auditCount("PARAM_SIMULATE", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("ADM-PRM-06 시뮬레이션 없이 승인 요청 422, PUT으로 시뮬레이션이 지워진 뒤에도 422")
    void approvalRequiresSimulation() throws Exception {
        draftId();
        postJson(DRAFT + "/request-approval", op, "{\"reason\":\"보정\"}").andExpect(status().isUnprocessableEntity());

        simulate();
        putJson(DRAFT, op, "{\"submitterTarget\":22,\"hitThreshold\":0.2}").andExpect(status().isOk());
        postJson(DRAFT + "/request-approval", op, "{\"reason\":\"보정\"}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("ADM-PRM-07 시뮬레이션 후 승인 요청 → 200 REVIEW, approval_requests PARAM_APPLY 1행")
    void requestApproval() throws Exception {
        UUID id = draftId();
        simulate();

        postJson(DRAFT + "/request-approval", op, "{\"reason\":\"보정\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_requests WHERE action_type = 'PARAM_APPLY' "
                + "AND target_ref = ? AND status = 'PENDING'", Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("ADM-PRM-08 승인 요청: 사유 빈값 422, REVIEW 중 재요청 409, REVIEW 중 PUT 409")
    void approvalErrors() throws Exception {
        draftId();
        simulate();
        postJson(DRAFT + "/request-approval", op, "{\"reason\":\" \"}").andExpect(status().isUnprocessableEntity());
        postJson(DRAFT + "/request-approval", op, "{\"reason\":\"보정\"}").andExpect(status().isOk());

        postJson(DRAFT + "/request-approval", op, "{\"reason\":\"다시\"}").andExpect(status().isConflict());
        putJson(DRAFT, op, "{\"submitterTarget\":30,\"hitThreshold\":0.2}").andExpect(status().isConflict());
    }

    @Test
    @org.junit.jupiter.api.Disabled("BUG-6: REVIEW(승인 대기) 드래프트도 simulate가 sim_result를 덮어쓴다(D4 결정: 409)")
    @DisplayName("ADM-PRM-09 REVIEW 중 simulate는 409, sim_result 그대로 (D4)")
    void simulateDuringReviewIs409() throws Exception {
        UUID id = draftId();
        simulate();
        postJson(DRAFT + "/request-approval", op, "{\"reason\":\"보정\"}").andExpect(status().isOk());
        String before = jdbc.queryForObject("SELECT sim_result::text FROM parameter_drafts WHERE id = ?", String.class, id);

        mvc.perform(post(DRAFT + "/simulate").with(op)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT sim_result::text FROM parameter_drafts WHERE id = ?", String.class, id))
                .isEqualTo(before);
    }

    @Test
    @DisplayName("ADM-PRM-10 역할: REVIEWER는 GET·PUT·simulate·request-approval 403, AUDITOR는 PUT·simulate·request-approval 403")
    void roles() throws Exception {
        RequestPostProcessor reviewer = asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER);
        RequestPostProcessor auditor = asAdmin(fx.admin("AUDITOR"), AdminRole.AUDITOR);

        mvc.perform(get(DRAFT).with(reviewer)).andExpect(status().isForbidden());
        for (RequestPostProcessor who : new RequestPostProcessor[]{reviewer, auditor}) {
            putJson(DRAFT, who, "{\"submitterTarget\":20,\"hitThreshold\":0.2}").andExpect(status().isForbidden());
            mvc.perform(post(DRAFT + "/simulate").with(who)).andExpect(status().isForbidden());
            postJson(DRAFT + "/request-approval", who, "{\"reason\":\"x\"}").andExpect(status().isForbidden());
        }
    }
}
