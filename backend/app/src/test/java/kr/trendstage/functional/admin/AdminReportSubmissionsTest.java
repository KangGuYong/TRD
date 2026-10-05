package kr.trendstage.functional.admin;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SuppressWarnings("unchecked")
class AdminReportSubmissionsTest extends FunctionalTestBase {

    @Test
    @DisplayName("ADM-RPT-01 신고 대상 제보 목록: 비VOID 제보(시딩 포함) 생성순, 신고자 id 없음")
    void listsNonVoidSubmissionsInOrder() throws Exception {
        clock.set(T0);
        UUID item = fx.item(T0);
        UUID first = fx.submission(fx.user(), item, 30, T0);
        UUID seed = fx.seedSubmission(fx.user(), item, T0.plusSeconds(60));
        UUID voided = fx.submission(fx.user(), item, 30, T0.plusSeconds(120));
        fx.voidSubmission(voided, T0.plusSeconds(180));
        UUID report = fx.report(item, "OPEN", T0.plusSeconds(200));

        String json = getOk("/admin/reports/{id}/submissions", asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER), report);

        assertThat((List<String>) read(json, "$[*].submissionId")).containsExactly(first.toString(), seed.toString());
        UUID reporter = jdbc.queryForObject("SELECT reporter_id FROM reports WHERE id = ?", UUID.class, report);
        assertThat(json).doesNotContain("reporterId").doesNotContain(reporter.toString());
    }

    @Test
    @DisplayName("ADM-RPT-02 없는 신고는 422")
    void unknownReportIs422() throws Exception {
        mvc.perform(get("/admin/reports/{id}/submissions", UUID.randomUUID())
                        .with(asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER)))
                .andExpect(status().isUnprocessableEntity());
    }
}
