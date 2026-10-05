package kr.trendstage.functional.admin;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SuppressWarnings("unchecked")
class AdminMergeQueueTest extends FunctionalTestBase {

    /** 병합 후보 한 쌍. old = 먼저 본 항목(생존 예정), new = 나중 항목. */
    private record Pair(UUID queue, UUID oldItem, UUID newItem) {}

    @BeforeEach
    void setClock() {
        clock.set(T0.plus(Duration.ofDays(1)));
    }

    private Pair pair() {
        UUID oldItem = fx.item(T0);
        UUID newItem = fx.item(T0.plus(Duration.ofHours(1)));
        fx.submission(fx.user(), oldItem, 30, T0);
        fx.submission(fx.user(), newItem, 30, T0.plus(Duration.ofHours(1)));
        return new Pair(fx.mergeQueueEntry(newItem, oldItem, 0.80), oldItem, newItem);
    }

    private ResultActions decide(UUID queue, String action, String key, UUID admin, AdminRole role) throws Exception {
        MockHttpServletRequestBuilder req = post("/admin/merge-queue/{id}/" + action, queue)
                .with(asAdmin(admin, role)).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"테스트\"}");
        if (key != null) req = req.header("Idempotency-Key", key);
        return mvc.perform(req);
    }

    private static String key() {
        return "k-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("ADM-MQ-01 큐 목록(4개 역할): 내 PENDING 행이 similarity·이름·orderPreview와 함께, 시딩은 '시딩 handle'")
    void listShowsPendingEntry() throws Exception {
        Pair p = pair();
        fx.seedSubmission(fx.user(), p.oldItem(), T0.plusSeconds(30));

        for (AdminRole role : AdminRole.values()) {
            String json = getOk("/admin/merge-queue", asAdmin(fx.admin(role.name()), role));
            List<Map<String, Object>> rows = read(json, "$[?(@.id == '" + p.queue() + "')]");
            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(((Number) row.get("similarity")).doubleValue()).isEqualTo(0.80);
                assertThat(row.get("newName")).isEqualTo(fx.key(p.newItem()));
                assertThat(row.get("oldName")).isEqualTo(fx.key(p.oldItem()));
                assertThat((List<String>) row.get("orderPreview")).anySatisfy(e -> assertThat(e).startsWith("시딩"));
            });
        }
    }

    @Test
    @DisplayName("ADM-MQ-02 미리보기: 생존=먼저 본 항목, 같은 유저 중복은 VOID·제보권 반환 대상, 시딩은 순위 없음")
    void previewShowsMergeEffects() throws Exception {
        UUID oldItem = fx.item(T0);
        UUID newItem = fx.item(T0.plus(Duration.ofHours(1)));
        UUID u = fx.user();
        UUID v = fx.user();
        fx.submission(u, oldItem, 30, T0);
        fx.seedSubmission(fx.user(), oldItem, T0.plusSeconds(60));
        fx.submission(u, newItem, 30, T0.plus(Duration.ofHours(1)));
        fx.submission(v, newItem, 30, T0.plus(Duration.ofHours(2)));
        UUID queue = fx.mergeQueueEntry(newItem, oldItem, 0.80);

        String json = getOk("/admin/merge-queue/{id}/preview", asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER), queue);

        assertThat((List<String>) read(json, "$.dedupVoidedHandles")).containsExactly(fx.handle(u));
        assertThat((List<String>) read(json, "$.quotaRefundHandles")).containsExactly(fx.handle(u));
        List<Map<String, Object>> order = read(json, "$.orderRank");
        assertThat(order).anySatisfy(e -> {
            assertThat(e.get("seed")).isEqualTo(true);
            assertThat(e.get("rankAfter")).isNull();
        });
        assertThat(order).anySatisfy(e -> {
            assertThat(e.get("handle")).isEqualTo(fx.handle(v));
            assertThat(e.get("rankAfter")).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("ADM-MQ-03 미리보기: 없는 id·이미 처리된 항목은 422")
    void previewErrors() throws Exception {
        UUID reviewer = fx.admin("REVIEWER");
        mvc.perform(get("/admin/merge-queue/{id}/preview", UUID.randomUUID()).with(asAdmin(reviewer, AdminRole.REVIEWER)))
                .andExpect(status().isUnprocessableEntity());
        Pair p = pair();
        decide(p.queue(), "merge", key(), reviewer, AdminRole.REVIEWER).andExpect(status().isOk());
        mvc.perform(get("/admin/merge-queue/{id}/preview", p.queue()).with(asAdmin(reviewer, AdminRole.REVIEWER)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("ADM-MQ-04 병합 역할: REVIEWER·OPERATOR 200, AUDITOR 403")
    void mergeRoles() throws Exception {
        decide(pair().queue(), "merge", key(), fx.admin("REVIEWER"), AdminRole.REVIEWER).andExpect(status().isOk());
        decide(pair().queue(), "merge", key(), fx.admin("OPERATOR"), AdminRole.OPERATOR).andExpect(status().isOk());
        decide(pair().queue(), "merge", key(), fx.admin("AUDITOR"), AdminRole.AUDITOR).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADM-MQ-05 같은 Idempotency-Key로 병합 두 번 → 두 번째 replayed=true, 감사 MERGE 1행")
    void sameKeyReplays() throws Exception {
        Pair p = pair();
        UUID reviewer = fx.admin("REVIEWER");
        String k = key();

        decide(p.queue(), "merge", k, reviewer, AdminRole.REVIEWER)
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(false));
        decide(p.queue(), "merge", k, reviewer, AdminRole.REVIEWER)
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));

        assertThat(fx.auditCount("MERGE", p.oldItem())).isEqualTo(1);
    }

    @Test
    @DisplayName("ADM-MQ-06 같은 키로 다른 결정은 422 idempotency-key-mismatch, 처리된 항목에 새 키는 409 merge-queue-decided")
    void keyMismatchAndDecided() throws Exception {
        Pair p = pair();
        UUID reviewer = fx.admin("REVIEWER");
        String k = key();
        decide(p.queue(), "merge", k, reviewer, AdminRole.REVIEWER).andExpect(status().isOk());

        decide(p.queue(), "separate", k, reviewer, AdminRole.REVIEWER)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("idempotency-key-mismatch"));
        decide(p.queue(), "merge", key(), reviewer, AdminRole.REVIEWER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("merge-queue-decided"));
    }

    @Test
    @DisplayName("ADM-MQ-07 판정된(RESOLVED) 항목과의 병합은 409 merge-resolved, 큐는 PENDING 유지")
    void resolvedTargetIsRejected() throws Exception {
        Pair p = pair();
        fx.setState(p.oldItem(), "RESOLVED");

        decide(p.queue(), "merge", key(), fx.admin("REVIEWER"), AdminRole.REVIEWER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("merge-resolved"));
        assertThat(fx.queueStatus(p.queue())).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("ADM-MQ-08 분리 → 200 SKIPPED, 감사 MERGE_SEPARATE, 두 항목·제보 변화 없음")
    void separateChangesNoData() throws Exception {
        UUID oldItem = fx.item(T0);
        UUID newItem = fx.item(T0.plus(Duration.ofHours(1)));
        UUID sub = fx.submission(fx.user(), newItem, 30, T0.plus(Duration.ofHours(1)));
        UUID queue = fx.mergeQueueEntry(newItem, oldItem, 0.80);

        decide(queue, "separate", key(), fx.admin("REVIEWER"), AdminRole.REVIEWER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SKIPPED"));

        assertThat(fx.auditCount("MERGE_SEPARATE", newItem)).isEqualTo(1);
        assertThat(fx.itemState(newItem)).isEqualTo("PENDING");
        assertThat(fx.itemState(oldItem)).isEqualTo("PENDING");
        assertThat(fx.itemOf(sub)).isEqualTo(newItem);
    }

    @Test
    @DisplayName("ADM-MQ-09 분리: 키 없으면 400 idempotency-key-required, 판정된 항목도 200(가드 없음)")
    void separateErrorsAndNoGuard() throws Exception {
        UUID reviewer = fx.admin("REVIEWER");
        decide(pair().queue(), "separate", null, reviewer, AdminRole.REVIEWER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("idempotency-key-required"));

        Pair p = pair();
        fx.setState(p.oldItem(), "RESOLVED");
        decide(p.queue(), "separate", key(), reviewer, AdminRole.REVIEWER).andExpect(status().isOk());
        assertThat(fx.itemState(p.oldItem())).isEqualTo("RESOLVED");
    }
}
