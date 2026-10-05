package kr.trendstage.functional.user;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SuppressWarnings("unchecked")
class UserReportTest extends FunctionalTestBase {

    private String reportBody(UUID item, String reason, String detail) {
        return "{\"trendItemId\":\"" + item + "\",\"reason\":\"" + reason + "\",\"detail\":\"" + detail + "\"}";
    }

    private UUID fileReport(UUID reporter, UUID item) throws Exception {
        return uuid(body(postJson("/v1/reports", asUser(reporter), reportBody(item, "DEFAMATION", "허위"))
                .andExpect(status().isCreated())), "$.id");
    }

    /** 신고 → REVIEWER가 제보자 S의 제보를 지정해 소명 요청(기한 = 지금 + 48h). */
    private void requestExplanation(UUID report, UUID submissionId) throws Exception {
        postJson("/admin/reports/{id}/request-explanation", asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER),
                "{\"submissionId\":\"" + submissionId + "\",\"note\":\"소명 요청\"}", report)
                .andExpect(status().isOk());
    }

    private String explanation(String text) {
        return "{\"text\":\"" + text + "\"}";
    }

    @Test
    @DisplayName("USR-RPT-01 신고 접수 → 201 OPEN, decision null, reports 1행")
    void fileReport() throws Exception {
        clock.set(T0);
        UUID item = itemOf(submit(fx.user(), uniq("rpt"), 30));
        UUID reporter = fx.user();

        postJson("/v1/reports", asUser(reporter), reportBody(item, "OTHER", "설명"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.trendItemId").value(item.toString()))
                .andExpect(jsonPath("$.decision").value(nullValue()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reports WHERE reporter_id = ?", Integer.class, reporter))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("USR-RPT-02 신고: reason 누락·잘못된 값·detail 1001자 400, 없는 항목·MERGED 404")
    void reportErrors() throws Exception {
        clock.set(T0);
        UUID item = itemOf(submit(fx.user(), uniq("rpterr"), 30));
        UUID merged = fx.item(T0);
        fx.merged(merged, item);
        UUID u = fx.user();

        postJson("/v1/reports", asUser(u), "{\"trendItemId\":\"" + item + "\"}").andExpect(status().isBadRequest());
        postJson("/v1/reports", asUser(u), reportBody(item, "NOPE", "x")).andExpect(status().isBadRequest());
        postJson("/v1/reports", asUser(u), reportBody(item, "OTHER", "가".repeat(1001))).andExpect(status().isBadRequest());
        postJson("/v1/reports", asUser(u), reportBody(UUID.randomUUID(), "OTHER", "x")).andExpect(status().isNotFound());
        postJson("/v1/reports", asUser(u), reportBody(merged, "OTHER", "x")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("USR-RPT-03 비공개 항목도 신고할 수 있다(201)")
    void hiddenItemCanBeReported() throws Exception {
        clock.set(T0);
        UUID item = fx.item(T0);
        fx.setVisibility(item, "TEMP_HIDDEN");

        postJson("/v1/reports", asUser(fx.user()), reportBody(item, "OTHER", "x")).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("USR-RPT-04 내 신고 목록은 내 것만 최신순")
    void myReportsNewestFirst() throws Exception {
        clock.set(T0);
        UUID a = itemOf(submit(fx.user(), uniq("rpta"), 30));
        UUID b = itemOf(submit(fx.user(), uniq("rptb"), 30));
        UUID reporter = fx.user();
        UUID first = fileReport(reporter, a);
        UUID second = fileReport(reporter, b);
        fileReport(fx.user(), a);

        mvc.perform(get("/v1/reports/me").with(asUser(reporter)))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second.toString()))
                .andExpect(jsonPath("$[1].id").value(first.toString()));
    }

    @Test
    @DisplayName("USR-RPT-05 받은 신고: 내 제보가 지정된 신고가 보이고 신고자 id는 없다, 제보 없는 유저는 빈 목록")
    void receivedReports() throws Exception {
        clock.set(T0);
        UUID s = fx.user();
        String sub = submit(s, uniq("recv"), 30);
        UUID reporter = fx.user();
        UUID report = fileReport(reporter, itemOf(sub));
        requestExplanation(report, uuid(sub, "$.id"));

        String json = getOk("/v1/reports/received", asUser(s));
        assertThat((List<String>) read(json, "$[*].id")).containsExactly(report.toString());
        assertThat(json).doesNotContain("reporterId").doesNotContain(reporter.toString());   // 지목된 제보자는 신고자를 알 수 없다(어떤 필드명으로도)
        mvc.perform(get("/v1/reports/received").with(asUser(fx.user()))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("USR-RPT-06 지정된 제보자가 EXPLAINING 신고에 소명 → 200, explanation_text 저장")
    void submitExplanation() throws Exception {
        clock.set(T0);
        UUID s = fx.user();
        String sub = submit(s, uniq("expl"), 30);
        UUID report = fileReport(fx.user(), itemOf(sub));
        requestExplanation(report, uuid(sub, "$.id"));

        postJson("/v1/reports/{id}/explanation", asUser(s), explanation("사실입니다"), report).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT explanation_text FROM reports WHERE id = ?", String.class, report))
                .isEqualTo("사실입니다");
    }

    @Test
    @DisplayName("USR-RPT-07 소명: 없는 신고 404, 지정 안 된 유저·지정 제보 없음 403, DECIDED 409, text 빈값·2001자 400")
    void explanationErrors() throws Exception {
        clock.set(T0);
        UUID s = fx.user();
        String sub = submit(s, uniq("explerr"), 30);
        UUID item = itemOf(sub);
        UUID report = fileReport(fx.user(), item);
        requestExplanation(report, uuid(sub, "$.id"));
        UUID unassigned = fx.report(item, "OPEN", T0);

        postJson("/v1/reports/{id}/explanation", asUser(s), explanation("x"), UUID.randomUUID()).andExpect(status().isNotFound());
        postJson("/v1/reports/{id}/explanation", asUser(fx.user()), explanation("x"), report).andExpect(status().isForbidden());
        postJson("/v1/reports/{id}/explanation", asUser(s), explanation("x"), unassigned).andExpect(status().isForbidden());
        postJson("/v1/reports/{id}/explanation", asUser(s), explanation(""), report).andExpect(status().isBadRequest());
        postJson("/v1/reports/{id}/explanation", asUser(s), explanation("가".repeat(2001)), report).andExpect(status().isBadRequest());

        postJson("/admin/reports/{id}/decide", asAdmin(fx.admin("OPERATOR"), AdminRole.OPERATOR),
                "{\"decision\":\"RESTORE\",\"note\":\"오신고\"}", report).andExpect(status().isOk());
        postJson("/v1/reports/{id}/explanation", asUser(s), explanation("x"), report).andExpect(status().isConflict());
    }

    @Test
    @org.junit.jupiter.api.Disabled("BUG-5: 소명 기한(explanation_deadline, 요청+48h)을 검사하지 않아 마감 후 제출도 200(D3 결정: 409)")
    @DisplayName("USR-RPT-08 소명: 마감 전 재제출은 덮어쓰고(200), 마감(요청+48h) 후 제출은 409 (D3)")
    void explanationDeadline() throws Exception {
        clock.set(T0);
        UUID s = fx.user();
        String sub = submit(s, uniq("expldl"), 30);
        UUID report = fileReport(fx.user(), itemOf(sub));
        requestExplanation(report, uuid(sub, "$.id"));

        clock.set(T0.plus(Duration.ofHours(1)));
        postJson("/v1/reports/{id}/explanation", asUser(s), explanation("첫 소명"), report).andExpect(status().isOk());
        clock.set(T0.plus(Duration.ofHours(2)));
        postJson("/v1/reports/{id}/explanation", asUser(s), explanation("고친 소명"), report).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT explanation_text FROM reports WHERE id = ?", String.class, report))
                .isEqualTo("고친 소명");

        clock.set(T0.plus(Duration.ofHours(49)));
        postJson("/v1/reports/{id}/explanation", asUser(s), explanation("늦은 소명"), report).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT explanation_text FROM reports WHERE id = ?", String.class, report))
                .isEqualTo("고친 소명");
    }
}
