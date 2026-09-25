package kr.trendstage.params;

import com.jayway.jsonpath.JsonPath;
import kr.trendstage.apiadmin.approval.ParamApplyExecutor;
import kr.trendstage.apiadmin.params.BacktestDatasetParser;
import kr.trendstage.persistence.repo.ApprovalRequestRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.StringJoiner;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 백테스트 데이터셋 업로드·실행·승인 요약(SP4 §5.2~§5.4, 테스트 13·14). */
class BacktestApiTest extends AbstractIntegrationTest {

    @Autowired BacktestDatasetParser parser;
    @Autowired ParamApplyExecutor paramApplyExecutor;
    @Autowired ApprovalRequestRepository approvals;

    @BeforeEach
    @AfterEach
    void clean() {
        fx.clearActiveDrafts();
    }

    static String rand() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    static String dataset(String name, String... cases) {
        return "{\"formatVersion\":1,\"name\":\"" + name + "\",\"cases\":[" + String.join(",", cases) + "]}";
    }

    static String oneCase(String caseId) {
        return "{\"caseId\":\"" + caseId + "\",\"title\":\"t\",\"label\":\"HIT\",\"deadline\":\"2026-06-15T00:00:00Z\","
                + "\"activeSubmitters\":60,\"submissions\":[{\"submitter\":\"u1\",\"at\":\"2026-06-01T03:00:00Z\","
                + "\"evidenceUrl\":\"https://x.com/a\"}]}";
    }

    private String upload(UUID actor, AdminRole role, String body, int expectedStatus) throws Exception {
        return mvc.perform(post("/admin/params/backtest-datasets").with(asAdmin(actor, role))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void uploadReportsErrorPosition() throws Exception {
        UUID op = fx.admin("OPERATOR");
        String bad = dataset("bad-" + rand(), "{\"caseId\":\"c1\",\"title\":\"t\",\"label\":\"HIT\",\"deadline\":\"2026-06-15T00:00:00Z\","
                + "\"submissions\":[{\"submitter\":\"u1\",\"at\":\"2026-06-01T00:00:00Z\",\"evidenceUrl\":\"https://x.com/a\"},"
                + "{\"submitter\":\"u2\",\"at\":\"2026-06-16T00:00:00Z\",\"evidenceUrl\":\"https://x.com/b\"}]}");
        String res = upload(op, AdminRole.OPERATOR, bad, 422);
        assertThat((String) JsonPath.read(res, "$.detail")).isEqualTo("cases[0].submissions[1].at: deadline보다 앞서야 합니다");
    }

    @Test
    void sameFileReturnsSameDataset() throws Exception {
        UUID op = fx.admin("OPERATOR");
        String body = dataset("dup-" + rand(), oneCase("c1"));
        String first = upload(op, AdminRole.OPERATOR, body, 201);
        String again = upload(op, AdminRole.OPERATOR, body, 200);
        assertThat((String) JsonPath.read(again, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertThat((Integer) JsonPath.read(first, "$.caseCount")).isEqualTo(1);
    }

    @Test
    void rejectsTooManyCasesAndOversizedFiles() throws Exception {
        UUID op = fx.admin("OPERATOR");
        StringJoiner many = new StringJoiner(",");
        for (int i = 0; i < 501; i++) many.add(oneCase("c" + i));
        String res = upload(op, AdminRole.OPERATOR, "{\"formatVersion\":1,\"name\":\"many\",\"cases\":[" + many + "]}", 422);
        assertThat((String) JsonPath.read(res, "$.detail")).startsWith("cases: 500건 이하");

        String huge = "{\"formatVersion\":1,\"name\":\"huge\",\"padding\":\"" + "x".repeat(2 * 1024 * 1024) + "\",\"cases\":[" + oneCase("c1") + "]}";
        res = upload(op, AdminRole.OPERATOR, huge, 422);
        assertThat((String) JsonPath.read(res, "$.detail")).isEqualTo("사례 파일은 2MB 이하여야 합니다");
    }

    @Test
    void sameSubmitterTwiceCountsOnce() {   // Review Focus 4
        String body = dataset("twice", "{\"caseId\":\"c1\",\"title\":\"t\",\"label\":\"HIT\",\"deadline\":\"2026-06-15T00:00:00Z\","
                + "\"submissions\":[{\"submitter\":\"u1\",\"at\":\"2026-06-01T03:00:00Z\",\"evidenceUrl\":\"https://x.com/a\"},"
                + "{\"submitter\":\"u1\",\"at\":\"2026-06-02T03:00:00Z\",\"evidenceUrl\":\"https://x.com/b\"},"
                + "{\"submitter\":\"u2\",\"at\":\"2026-06-03T03:00:00Z\",\"evidenceUrl\":\"https://x.com/c\"}]}");
        var parsed = parser.parse(body);
        assertThat(parsed.cases().get(0).signal().distinctSubmitters()).isEqualTo(2);
        assertThat(parsed.cases().get(0).signal().validCount()).isEqualTo(3);
    }

    @Test
    void runStoresResultAndEditClearsIt() throws Exception {
        UUID op = fx.admin("OPERATOR");
        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                .contentType(MediaType.APPLICATION_JSON).content(ParamStudioDraftTest.EXAMPLE)).andExpect(status().isOk());
        String id = JsonPath.read(upload(op, AdminRole.OPERATOR, dataset("run-" + rand(), oneCase("c1")), 201), "$.id");

        mvc.perform(post("/admin/params/draft/backtest").with(asAdmin(op, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"datasetId\":\"" + id + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backtestResult.caseCount").value(1))
                .andExpect(jsonPath("$.backtestResult.datasetId").value(id))
                .andExpect(jsonPath("$.backtestResult.report.rows[0].draft.explain").value(startsWith("T ")));

        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(ParamStudioDraftTest.EXAMPLE.replace("\"targetFloor\":8", "\"targetFloor\":9")))
                .andExpect(jsonPath("$.backtestResult").value(nullValue()));
    }

    @Test
    void auditorIsReadOnly() throws Exception {
        UUID auditor = fx.admin("AUDITOR");
        mvc.perform(get("/admin/params/backtest-datasets").with(asAdmin(auditor, AdminRole.AUDITOR))).andExpect(status().isOk());
        mvc.perform(get("/admin/params/backtest-datasets/example").with(asAdmin(auditor, AdminRole.AUDITOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formatVersion").value(1));
        upload(auditor, AdminRole.AUDITOR, dataset("a-" + rand(), oneCase("c1")), 403);
        mvc.perform(post("/admin/params/draft/backtest").with(asAdmin(auditor, AdminRole.AUDITOR))
                .contentType(MediaType.APPLICATION_JSON).content("{\"datasetId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void approvalSummaryShowsBacktest() throws Exception {
        UUID op = fx.admin("OPERATOR");
        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                .contentType(MediaType.APPLICATION_JSON).content(ParamStudioDraftTest.EXAMPLE)).andExpect(status().isOk());
        String example = mvc.perform(get("/admin/params/backtest-datasets/example").with(asAdmin(op, AdminRole.OPERATOR)))
                .andReturn().getResponse().getContentAsString();
        String res = mvc.perform(post("/admin/params/backtest-datasets").with(asAdmin(op, AdminRole.OPERATOR))
                .contentType(MediaType.APPLICATION_JSON).content(example)).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(res, "$.id");   // 이미 올라가 있으면 200 + 같은 id
        mvc.perform(post("/admin/params/draft/backtest").with(asAdmin(op, AdminRole.OPERATOR))
                .contentType(MediaType.APPLICATION_JSON).content("{\"datasetId\":\"" + id + "\"}")).andExpect(status().isOk());
        mvc.perform(post("/admin/params/draft/simulate").with(asAdmin(op, AdminRole.OPERATOR))).andExpect(status().isOk());
        mvc.perform(post("/admin/params/draft/request-approval").with(asAdmin(op, AdminRole.OPERATOR))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"보정\"}")).andExpect(status().isOk());

        UUID approvalId = jdbc.queryForObject("SELECT approval_id FROM parameter_drafts WHERE status = 'REVIEW'", UUID.class);
        String summary = paramApplyExecutor.describe(approvals.findById(approvalId).orElseThrow());
        assertThat(summary)
                .contains("데이터셋 '합성 시나리오 v1'(12건)")
                .contains("정밀도 0.56→1.00")
                .contains("재현율 0.83→1.00")
                .contains("판정 변경 10건");
    }
}
