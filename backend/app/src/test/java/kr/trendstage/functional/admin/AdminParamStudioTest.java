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

/**
 * 파라미터 드래프트는 전역 1개다 — 앞뒤로 DRAFT·REVIEW 드래프트를 지우고 딸린 승인 요청을 닫는다.
 * SP4부터 편집 값은 9개(필수)이고, 승인 요청에는 시뮬레이션과 백테스트가 모두 필요하다.
 */
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

    /** 편집 값 9개(나머지는 기본값과 같은 예시). */
    private static String values(int targetFloor, String hitThreshold) {
        return """
                {"targetFloor":%d,"targetRatio":0.1,"activeWindowDays":28,"hitThreshold":%s,
                 "persistenceFloor":0.5,"persistenceFullDays":4,"diversityFloor":0.5,"diversityFullPlatforms":3,
                 "independenceMode":"OFF"}""".formatted(targetFloor, hitThreshold);
    }

    private UUID draftId() throws Exception {
        return uuid(getOk(DRAFT, op), "$.draftId");
    }

    private void simulate() throws Exception {
        mvc.perform(post(DRAFT + "/simulate").with(op)).andExpect(status().isOk());
    }

    /** 백테스트 실행 결과만 채운다(데이터셋 실행은 BacktestApiTest가 다룬다). */
    private void backtestDone(UUID draftId) {
        jdbc.update("UPDATE parameter_drafts SET backtest_result = '{\"caseCount\":1}'::jsonb WHERE id = ?", draftId);
    }

    @Test
    @DisplayName("ADM-PRM-01 OPERATOR 조회 → 드래프트 생성(DRAFT), 드래프트 값 = 현재 값")
    void getCreatesDraft() throws Exception {
        String json = getOk(DRAFT, op);

        assertThat((String) read(json, "$.draftId")).isNotNull();
        assertThat((String) read(json, "$.status")).isEqualTo("DRAFT");
        assertThat((Integer) read(json, "$.values.targetFloor")).isEqualTo((Integer) read(json, "$.current.targetFloor"));
    }

    @Test
    @DisplayName("ADM-PRM-02 PUT(목표 하한 25, 임계값 0.25) → 값 반영, simResult 비워짐, 감사 PARAM_DRAFT_UPDATE")
    void putUpdatesAndClearsSimulation() throws Exception {
        UUID id = draftId();
        simulate();

        putJson(DRAFT, op, values(25, "0.25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.targetFloor").value(25))
                .andExpect(jsonPath("$.values.hitThreshold").value(closeTo(0.25, 1e-9)))
                .andExpect(jsonPath("$.simResult").value(nullValue()));
        assertThat(fx.auditCount("PARAM_DRAFT_UPDATE", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("ADM-PRM-03 PUT targetFloor=0은 422")
    void putZeroTargetIs422() throws Exception {
        putJson(DRAFT, op, values(0, "0.2")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("ADM-PRM-04 PUT hitThreshold -0.1·1.5·0은 422, 1.0은 200 (D5: 0 < hitThreshold ≤ 1)")
    void putThresholdOutOfRangeIs422() throws Exception {
        for (String t : new String[]{"-0.1", "1.5", "0"}) {
            putJson(DRAFT, op, values(20, t)).andExpect(status().isUnprocessableEntity());
        }
        putJson(DRAFT, op, values(20, "1.0")).andExpect(status().isOk());   // 상한 1은 포함
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
        UUID id = draftId();
        backtestDone(id);
        postJson(DRAFT + "/request-approval", op, "{\"reason\":\"보정\"}").andExpect(status().isUnprocessableEntity());

        simulate();
        putJson(DRAFT, op, values(22, "0.2")).andExpect(status().isOk());
        backtestDone(id);   // PUT은 백테스트도 지우므로 다시 채워 시뮬레이션만 빠진 상태로
        postJson(DRAFT + "/request-approval", op, "{\"reason\":\"보정\"}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("ADM-PRM-07 시뮬레이션·백테스트 후 승인 요청 → 200 REVIEW, approval_requests PARAM_APPLY 1행")
    void requestApproval() throws Exception {
        UUID id = draftId();
        simulate();
        backtestDone(id);

        postJson(DRAFT + "/request-approval", op, "{\"reason\":\"보정\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_requests WHERE action_type = 'PARAM_APPLY' "
                + "AND target_ref = ? AND status = 'PENDING'", Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("ADM-PRM-08 승인 요청: 사유 빈값 422, REVIEW 중 재요청 409, REVIEW 중 PUT 409")
    void approvalErrors() throws Exception {
        UUID id = draftId();
        simulate();
        backtestDone(id);
        postJson(DRAFT + "/request-approval", op, "{\"reason\":\" \"}").andExpect(status().isUnprocessableEntity());
        postJson(DRAFT + "/request-approval", op, "{\"reason\":\"보정\"}").andExpect(status().isOk());

        postJson(DRAFT + "/request-approval", op, "{\"reason\":\"다시\"}").andExpect(status().isConflict());
        putJson(DRAFT, op, values(30, "0.2")).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("ADM-PRM-09 REVIEW 중 simulate는 409, sim_result 그대로 (D4)")
    void simulateDuringReviewIs409() throws Exception {
        UUID id = draftId();
        simulate();
        backtestDone(id);
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
            putJson(DRAFT, who, values(20, "0.2")).andExpect(status().isForbidden());
            mvc.perform(post(DRAFT + "/simulate").with(who)).andExpect(status().isForbidden());
            postJson(DRAFT + "/request-approval", who, "{\"reason\":\"x\"}").andExpect(status().isForbidden());
        }
    }
}
