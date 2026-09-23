package kr.trendstage.approval;

import kr.trendstage.apiadmin.approval.ActionType;
import kr.trendstage.apiadmin.approval.ApprovalGate;
import kr.trendstage.apiadmin.web.AdminConflictException;
import kr.trendstage.judge.JudgeService;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스펙 §10 #3~#8. 판정 점수(HIT L1, m = 0.2):
 *   확신도 50 — 1~4위 60 / 36 / 24 / 12. 3·4위 VOID 후 재판정 → 2명 MISS(−25): 차액 85+61+24+12 = 182
 *   확신도 10 — 12 / 7.2 / 4.8 / 2.4.   3·4위 VOID 후 재판정 → MISS(−5):   차액 17+12.2+4.8+2.4 = 36.4
 */
class VerdictApprovalTest extends AbstractIntegrationTest {

    private static final Instant FIRST_SEEN = Instant.parse("2026-07-10T00:00:00Z");
    private static final Instant JUDGED_AT = FIRST_SEEN.plus(Duration.ofDays(15));

    @Autowired JudgeService judge;
    @Autowired ApprovalGate gate;
    @Autowired TransactionTemplate tx;

    private UUID operator;
    private UUID approver;

    @BeforeEach
    void setUp() {
        operator = fx.admin("OPERATOR");
        approver = fx.admin();
        clock.set(JUDGED_AT.plus(Duration.ofDays(1)));
    }

    @Test
    void rejudgeOver100WaitsForApprovalThenApplies() throws Exception {   // #3
        List<UUID> subs = new ArrayList<>();
        UUID item = judgedHit(50, subs);
        fx.voidSubmission(subs.get(2), JUDGED_AT.plusSeconds(10));
        fx.voidSubmission(subs.get(3), JUDGED_AT.plusSeconds(10));
        int rowsBefore = fx.ledgerRows(item);

        String body = mvc.perform(post("/admin/verdicts/{id}/rejudge", item).with(asAdmin(operator, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"카르텔\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn().getResponse().getContentAsString();
        UUID approvalId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.approvalRequestId"));
        assertThat(fx.ledgerRows(item)).isEqualTo(rowsBefore);
        assertThat(verdictCount(item)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload->>'expectedAdjTotal' FROM approval_requests WHERE id = ?",
                String.class, approvalId)).isEqualTo("182.0000");

        approve(approvalId);

        assertThat(verdictCount(item)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM score_ledger l JOIN verdicts v ON v.id = l.verdict_id "
                + "WHERE v.trend_item_id = ? AND l.kind = 'ADJ' AND l.approval_id = ? AND l.approved_by_admin_id = ?",
                Integer.class, item, approvalId, approver)).isEqualTo(4);
        assertThat(lastExecuteDetail(item, "adjTotal")).isEqualTo("182.0000");
    }

    @Test
    void rejudgeUnder100AppliesImmediately() throws Exception {   // #4
        List<UUID> subs = new ArrayList<>();
        UUID item = judgedHit(10, subs);
        fx.voidSubmission(subs.get(2), JUDGED_AT.plusSeconds(10));
        fx.voidSubmission(subs.get(3), JUDGED_AT.plusSeconds(10));

        mvc.perform(post("/admin/verdicts/{id}/rejudge", item).with(asAdmin(operator, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"카르텔\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.adjTotal").value(36.4));
        assertThat(verdictCount(item)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM score_ledger l JOIN verdicts v ON v.id = l.verdict_id "
                + "WHERE v.trend_item_id = ? AND l.kind = 'ADJ' AND l.approval_id IS NULL", Integer.class, item)).isEqualTo(4);
    }

    @Test
    void pendingRequestBlocksAnyVerdictChangeOnItem() throws Exception {   // #5
        List<UUID> subs = new ArrayList<>();
        UUID item = judgedHit(50, subs);
        mvc.perform(post("/admin/verdicts/{id}/void", item).with(asAdmin(operator, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"허위\"}"))
                .andExpect(status().isAccepted());

        mvc.perform(post("/admin/verdicts/{id}/rejudge", item).with(asAdmin(operator, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"다시\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("approval-pending"));

        // 같은 항목에 요청 두 개는 DB가 막는다(동시 요청 경합의 마지막 방어선)
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> gate.request(ActionType.VERDICT_REJUDGE, item,
                Map.of("reason", "경합"), operator, AdminRole.OPERATOR)))
                .isInstanceOf(AdminConflictException.class);
    }

    @Test
    void voidOver100WaitsForApprovalThenZeroesLedger() throws Exception {   // #6
        List<UUID> subs = new ArrayList<>();
        List<UUID> users = new ArrayList<>();
        UUID item = judgedHit(50, subs, users);
        String body = mvc.perform(post("/admin/verdicts/{id}/void", item).with(asAdmin(operator, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"허위 제보\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(fx.itemState(item)).isEqualTo("RESOLVED");

        approve(UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.approvalRequestId")));

        assertThat(fx.itemState(item)).isEqualTo("VOID");
        users.forEach(u -> assertThat(fx.ledgerSum(u, item)).isEqualByComparingTo("0"));
        subs.forEach(s -> assertThat(fx.submissionResult(s)).isEqualTo("VOID"));
    }

    @Test
    void mergeQueueVoidOverLimitIsRejected() throws Exception {   // #7
        List<UUID> subs = new ArrayList<>();
        UUID item = judgedHit(50, subs);
        UUID other = fx.item(FIRST_SEEN.plus(Duration.ofDays(20)));
        UUID queueId = fx.mergeQueueEntry(item, other, 0.80);

        mvc.perform(post("/admin/merge-queue/{id}/void", queueId).with(asAdmin(operator, AdminRole.OPERATOR))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"허위\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("void-needs-approval"));
        assertThat(fx.queueStatus(queueId)).isEqualTo("PENDING");
        assertThat(fx.itemState(item)).isEqualTo("RESOLVED");
    }

    @Test
    void approvalRecomputesWithInputsAtApprovalTime() throws Exception {   // #8
        List<UUID> subs = new ArrayList<>();
        UUID item = judgedHit(50, subs);
        fx.voidSubmission(subs.get(2), JUDGED_AT.plusSeconds(10));
        fx.voidSubmission(subs.get(3), JUDGED_AT.plusSeconds(10));
        String body = mvc.perform(post("/admin/verdicts/{id}/rejudge", item).with(asAdmin(operator, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"카르텔\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        fx.voidSubmission(subs.get(1), JUDGED_AT.plusSeconds(20));   // 승인 대기 중 한 건 더 VOID

        approve(UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.approvalRequestId")));

        assertThat(lastExecuteDetail(item, "expectedAdjTotal")).isEqualTo("182.0000");
        assertThat(lastExecuteDetail(item, "adjTotal")).isEqualTo("157.0000");   // 85 + 36 + 24 + 12
    }

    private UUID judgedHit(int confidence, List<UUID> subsOut) {
        return judgedHit(confidence, subsOut, new ArrayList<>());
    }

    /** 실유저 4명(선점 1~4위)이 같은 확신도로 제보한 항목을 HIT L1로 판정한다. */
    private UUID judgedHit(int confidence, List<UUID> subsOut, List<UUID> usersOut) {
        UUID item = fx.item(FIRST_SEEN);
        for (int i = 0; i < 4; i++) {
            UUID u = fx.user();
            usersOut.add(u);
            subsOut.add(fx.submission(u, item, confidence, FIRST_SEEN.plusSeconds(1 + i)));
        }
        judge.closeDue(JUDGED_AT);
        judge.judge(item, JUDGED_AT);
        assertThat(jdbc.queryForObject("SELECT result::text FROM verdicts WHERE trend_item_id = ?", String.class, item))
                .isEqualTo("HIT");
        return item;
    }

    private void approve(UUID approvalId) throws Exception {
        mvc.perform(post("/admin/approvals/{id}/approve", approvalId).with(asAdmin(approver, AdminRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));
    }

    private int verdictCount(UUID item) {
        return jdbc.queryForObject("SELECT count(*) FROM verdicts WHERE trend_item_id = ?", Integer.class, item);
    }

    private String lastExecuteDetail(UUID item, String key) {
        return jdbc.queryForObject("SELECT detail->>? FROM admin_audit_log WHERE action = 'APPROVAL_EXECUTE' "
                + "AND target_id = ? ORDER BY id DESC LIMIT 1", String.class, key, item);
    }
}
