package kr.trendstage.params;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.signal.SignalAxes;
import kr.trendstage.persistence.params.CurrentParameterSetResolver;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADM-600 편집 항목 9개·승인 조건(SP4 §5.1·§5.3). 드래프트는 전역 하나라 시작·끝에 비운다. */
class ParamStudioDraftTest extends AbstractIntegrationTest {

    /** 예시 보정값(합성 시나리오가 전부 맞는 값, Task 8). */
    static final String EXAMPLE = """
            {"targetFloor":8,"targetRatio":0.1,"activeWindowDays":28,"hitThreshold":0.3,
             "persistenceFloor":0.5,"persistenceFullDays":4,"diversityFloor":0.5,"diversityFullPlatforms":3,
             "independenceMode":"DEVICE"}""";

    @Autowired CurrentParameterSetResolver resolver;

    @BeforeEach
    @AfterEach
    void clean() {
        fx.clearActiveDrafts();
    }

    private String with(String key, String rawValue) {
        return EXAMPLE.replaceFirst("\"" + key + "\":[^,}]+", "\"" + key + "\":" + rawValue);
    }

    @Test
    void savesNineValues() throws Exception {
        UUID op = fx.admin("OPERATOR");
        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(EXAMPLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.targetFloor").value(8))
                .andExpect(jsonPath("$.values.persistenceFullDays").value(4))
                .andExpect(jsonPath("$.values.independenceMode").value("DEVICE"))
                .andExpect(jsonPath("$.current.targetFloor").value(20))
                .andExpect(jsonPath("$.current.independenceMode").value("OFF"));
        mvc.perform(get("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR)))
                .andExpect(jsonPath("$.values.diversityFloor").value(0.5))
                .andExpect(jsonPath("$.values.targetRatio").value(0.1));
    }

    @Test
    void rangeViolationIs422WithFieldName() throws Exception {
        UUID op = fx.admin("OPERATOR");
        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(with("persistenceFloor", "1.5")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(startsWith("persistenceFloor")));
        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(with("targetFloor", "0")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(startsWith("targetFloor")));
    }

    @Test
    void missingOrUnknownModeIs422() throws Exception {
        UUID op = fx.admin("OPERATOR");
        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(EXAMPLE.replaceFirst(",\\s*\"independenceMode\":\"DEVICE\"", "")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("independenceMode: 값이 필요합니다"));
        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(with("independenceMode", "\"IP\"")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(startsWith("independenceMode")));
    }

    @Test
    void editClearsSimulationAndBacktest() throws Exception {
        UUID op = fx.admin("OPERATOR");
        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                .contentType(MediaType.APPLICATION_JSON).content(EXAMPLE)).andExpect(status().isOk());
        jdbc.update("UPDATE parameter_drafts SET sim_result = '{\"changed\":0,\"total\":0,\"missToHit\":0,\"hitToMiss\":0,\"reachChanged\":0}'::jsonb, "
                + "backtest_result = '{\"caseCount\":1}'::jsonb WHERE status = 'DRAFT'");
        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(with("targetFloor", "9")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simResult").value(nullValue()))
                .andExpect(jsonPath("$.backtestResult").value(nullValue()));
    }

    @Test
    void approvalRequiresBacktest() throws Exception {
        UUID op = fx.admin("OPERATOR");
        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                .contentType(MediaType.APPLICATION_JSON).content(EXAMPLE)).andExpect(status().isOk());
        mvc.perform(post("/admin/params/draft/simulate").with(asAdmin(op, AdminRole.OPERATOR))).andExpect(status().isOk());
        mvc.perform(post("/admin/params/draft/request-approval").with(asAdmin(op, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"보정\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("백테스트")));
    }

    @Test
    void legacyPayloadReadsNeutral() {   // Review Focus 5
        UUID draft = fx.appliedDraft("{\"submitterTarget\":17,\"hitThreshold\":0.25}");
        try {
            ParameterSet p = resolver.resolve();
            assertThat(p.targetFloor).isEqualTo(17);
            assertThat(p.hitThreshold).isEqualTo(0.25);
            assertThat(p.axes).isEqualTo(SignalAxes.NEUTRAL);
        } finally {
            fx.deleteDraft(draft);
        }
    }

    @Test
    void auditorCannotWrite() throws Exception {
        UUID auditor = fx.admin("AUDITOR");
        mvc.perform(put("/admin/params/draft").with(asAdmin(auditor, AdminRole.AUDITOR))
                .contentType(MediaType.APPLICATION_JSON).content(EXAMPLE)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/params/draft").with(asAdmin(auditor, AdminRole.AUDITOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NONE"));
    }
}
