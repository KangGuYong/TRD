package kr.trendstage.admin;

import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스펙 §10 #18 — 02 §1.1 매트릭스. */
class RoleMatrixTest extends AbstractIntegrationTest {

    @Test
    void auditorReadsEverything() throws Exception {
        UUID auditor = fx.admin("AUDITOR");
        mvc.perform(get("/admin/reports").with(asAdmin(auditor, AdminRole.AUDITOR))).andExpect(status().isOk());
        mvc.perform(get("/admin/params/draft").with(asAdmin(auditor, AdminRole.AUDITOR))).andExpect(status().isOk());
        mvc.perform(get("/admin/users/{id}", fx.user()).with(asAdmin(auditor, AdminRole.AUDITOR))).andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parameter_drafts WHERE author_id = ?", Integer.class, auditor)).isZero();
    }

    @Test
    void reviewerCannotHideButCanRequestExplanation() throws Exception {
        UUID reviewer = fx.admin("REVIEWER");
        UUID item = fx.item(Instant.parse("2026-09-01T00:00:00Z"));
        UUID sub = fx.submission(fx.user(), item, 30, Instant.parse("2026-09-01T00:00:01Z"));
        UUID report = fx.report(item, "OPEN", Instant.parse("2026-09-01T01:00:00Z"));
        String body = "{\"submissionId\":\"" + sub + "\",\"note\":\"확인\"}";

        mvc.perform(post("/admin/reports/{id}/hide", report).with(asAdmin(reviewer, AdminRole.REVIEWER))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/reports/{id}/request-explanation", report).with(asAdmin(reviewer, AdminRole.REVIEWER))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }
}
