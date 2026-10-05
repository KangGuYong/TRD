package kr.trendstage.params;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import kr.trendstage.judge.JudgeService;
import kr.trendstage.judge.VerdictEvidence;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 완료 기준(스펙 §12-2): 업로드 → 백테스트 → 시뮬레이션 → 승인 요청 → 다른 ADMIN 승인 → 다음 판정이 새 파라미터로.
 * 적용된 드래프트는 전역 운영값을 바꾸므로 finally에서 반드시 지운다. 판정 항목은 2036년 전용.
 */
class ParamApplyEndToEndTest extends AbstractIntegrationTest {

    @Autowired JudgeService judge;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    @AfterEach
    void clean() {
        fx.clearActiveDrafts();
    }

    @Test
    void approvedDraftDrivesNextVerdict() throws Exception {
        UUID op = fx.admin("OPERATOR");
        UUID approver = fx.admin();
        UUID draftId = null;
        try {
            String draft = mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                            .contentType(MediaType.APPLICATION_JSON).content(ParamStudioDraftTest.EXAMPLE))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            draftId = UUID.fromString(JsonPath.read(draft, "$.draftId"));
            String example = mvc.perform(get("/admin/params/backtest-datasets/example").with(asAdmin(op, AdminRole.OPERATOR)))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            String dataset = mvc.perform(post("/admin/params/backtest-datasets").with(asAdmin(op, AdminRole.OPERATOR))
                    .contentType(MediaType.APPLICATION_JSON).content(example)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            mvc.perform(post("/admin/params/draft/backtest").with(asAdmin(op, AdminRole.OPERATOR))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"datasetId\":\"" + JsonPath.read(dataset, "$.id") + "\"}")).andExpect(status().isOk());
            mvc.perform(post("/admin/params/draft/simulate").with(asAdmin(op, AdminRole.OPERATOR))).andExpect(status().isOk());
            mvc.perform(post("/admin/params/draft/request-approval").with(asAdmin(op, AdminRole.OPERATOR))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"백테스트 보정\"}")).andExpect(status().isOk());
            UUID approvalId = jdbc.queryForObject("SELECT approval_id FROM parameter_drafts WHERE id = ?", UUID.class, draftId);
            mvc.perform(post("/admin/approvals/{id}/approve", approvalId).with(asAdmin(approver, AdminRole.ADMIN)))
                    .andExpect(status().isOk());
            assertThat(jdbc.queryForObject("SELECT status::text FROM parameter_drafts WHERE id = ?", String.class, draftId))
                    .isEqualTo("APPLIED");

            // 새 파라미터로 판정: 4명·4일·3곳, 활성 4명 → 목표치 max(8, ⌈0.4⌉) = 8 → T 0.5 → HIT L2 (기본값이면 0.2 → L1)
            Instant first = Instant.parse("2036-03-01T00:00:00Z");
            UUID item = fx.item(first);
            fx.submissionFrom(fx.user(), item, Instant.parse("2036-03-01T03:00:00Z"), "https://gall.dcinside.com/board/1", null, null);
            fx.submissionFrom(fx.user(), item, Instant.parse("2036-03-03T03:00:00Z"), "https://x.com/a/status/1", null, null);
            fx.submissionFrom(fx.user(), item, Instant.parse("2036-03-05T03:00:00Z"), "https://www.instagram.com/p/a/", null, null);
            fx.submissionFrom(fx.user(), item, Instant.parse("2036-03-08T03:00:00Z"), "https://gall.dcinside.com/board/2", null, null);
            Instant judgedAt = first.plus(Duration.ofDays(15));
            judge.closeDue(judgedAt);
            judge.judge(item, judgedAt);

            VerdictEvidence ev = objectMapper.readValue(fx.evidenceJson(item), VerdictEvidence.class);
            assertThat(ev.result()).isEqualTo("HIT");
            assertThat(ev.reach()).isEqualTo("L2");
            assertThat(ev.params().axes().persistenceFloor()).isEqualTo(0.5);
            assertThat(ev.tBreakdown().target()).isEqualTo(8);
            assertThat(ev.tBreakdown().t()).isEqualTo(0.5);
        } finally {
            if (draftId != null) fx.deleteDraft(draftId);
        }
    }
}
