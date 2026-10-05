package kr.trendstage.functional.journey;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 카탈로그 JRN — 제보·조회는 HTTP, 판정·등급은 배치 직접 호출. 점수 기대값은 CLAUDE.md 공식. */
@SuppressWarnings("unchecked")
class JourneyTest extends FunctionalTestBase {

    private record Hit(UUID item, List<UUID> users, List<UUID> submissions) {}

    /** A c50 · B c30 · C c10 · D c10 이 1분 간격 제보(B는 공백·대소문자만 다른 이름) → 마감 직후 판정. */
    private Hit hitJourney() throws Exception {
        String name = "hit journey " + UUID.randomUUID().toString().substring(0, 8);
        String[] names = {name, "  " + name.toUpperCase() + " ", name, name};
        int[] confidences = {50, 30, 10, 10};
        List<UUID> users = new ArrayList<>();
        List<UUID> subs = new ArrayList<>();
        UUID item = null;
        for (int i = 0; i < 4; i++) {
            clock.set(T0.plusSeconds(60L * i));
            UUID u = fx.user();
            String res = submit(u, names[i], confidences[i]);
            users.add(u);
            subs.add(uuid(res, "$.id"));
            item = itemOf(res);
        }
        clock.set(AFTER_DEADLINE);
        runVerdicts();
        return new Hit(item, users, subs);
    }

    private String ledger(UUID user) throws Exception {
        return getOk("/v1/me/ledger", asUser(user));
    }

    private String grade(UUID user) throws Exception {
        return getOk("/v1/me/grade", asUser(user));
    }

    @Test
    @DisplayName("JRN-01 HIT: 4명 → HIT L1, 원장 60/21.6/4.8/2.4, 내 제보·상세·등급에 반영")
    void hitJourneyScoresByOrder() throws Exception {
        Hit h = hitJourney();
        UUID a = h.users().get(0);

        assertThat(fx.itemState(h.item())).isEqualTo("RESOLVED");
        double[] expected = {60, 21.6, 4.8, 2.4};
        for (int i = 0; i < 4; i++) {
            String json = ledger(h.users().get(i));
            assertThat((List<String>) read(json, "$.items[*].kind")).containsExactly("HIT");
            assertThat(doubles(json, "$.items[*].delta")).containsExactly(expected[i]);
        }
        mvc.perform(get("/v1/submissions/me").with(asUser(a)))
                .andExpect(jsonPath("$[0].status").value("HIT"))
                .andExpect(jsonPath("$[0].orderRank").value(1))
                .andExpect(jsonPath("$[0].judgeInDays").doesNotExist());
        mvc.perform(get("/v1/trends/{id}", h.item()))
                .andExpect(jsonPath("$.verdict").value("적중했어요"))
                .andExpect(jsonPath("$.reachLevel").value("L1"));
        String g = grade(a);
        assertThat((Integer) read(g, "$.judgedCount")).isEqualTo(1);
        assertThat(((Number) read(g, "$.trustIndex")).doubleValue()).isCloseTo(0.5, within(0.001));
        assertThat(((Number) read(g, "$.activeScore")).doubleValue()).isCloseTo(60.0, within(0.001));
    }

    @Test
    @org.junit.jupiter.api.Disabled("BUG-10: 판정 끝난 제보의 내 제보 목록에 delta·reachLevel·note(산정 근거)가 항상 null — "
            + "SubmissionService.toResponse가 세 필드를 null로 고정(OpenAPI SubmissionMine은 판정 후 값을 정의)")
    @DisplayName("USR-SUB-10 판정 끝난 제보: 내 제보 목록에 delta(+60)·reachLevel(L1)·note(산정 근거) 채워짐")
    void judgedSubmissionShowsScoreAndBasis() throws Exception {
        Hit h = hitJourney();

        String json = getOk("/v1/submissions/me", asUser(h.users().get(0)));

        assertThat(doubles(json, "$[*].delta")).containsExactly(60.0);
        assertThat((List<String>) read(json, "$[*].reachLevel")).containsExactly("L1");
        assertThat((List<String>) read(json, "$[*].note")).doesNotContainNull();
    }

    @Test
    @DisplayName("JRN-02 MISS: 3명 → MISS, 원장 −25/−15/−5, A의 TI 0.333, 상세 '빗나갔어요'")
    void missJourneyPenalizesHalf() throws Exception {
        String name = uniq("miss journey");
        int[] confidences = {50, 30, 10};
        List<UUID> users = new ArrayList<>();
        UUID item = null;
        for (int i = 0; i < 3; i++) {
            clock.set(T0.plusSeconds(60L * i));
            UUID u = fx.user();
            item = itemOf(submit(u, name, confidences[i]));
            users.add(u);
        }
        clock.set(AFTER_DEADLINE);
        runVerdicts();

        double[] expected = {-25, -15, -5};
        for (int i = 0; i < 3; i++) {
            assertThat(doubles(ledger(users.get(i)), "$.items[*].delta")).containsExactly(expected[i]);
        }
        assertThat(((Number) read(grade(users.get(0)), "$.trustIndex")).doubleValue()).isCloseTo(2.0 / 6.0, within(0.001));
        mvc.perform(get("/v1/trends/{id}", item)).andExpect(jsonPath("$.verdict").value("빗나갔어요"));
    }

    @Test
    @DisplayName("JRN-03a 시딩이 가장 먼저여도 T·순위·원장에서 빠진다: 시딩 1 + 실유저 4 → HIT, 첫 실유저 +60(1위), 시딩 원장 없음")
    void seedIsExcludedFromRankAndLedger() throws Exception {
        clock.set(T0);
        String name = uniq("seed journey");
        UUID op = fx.admin("OPERATOR");
        String seedRes = body(postJson("/admin/seed/submissions", asAdmin(op, AdminRole.OPERATOR), """
                {"name":"%s","category":"MEME","platform":"X","evidenceUrl":"https://example.com/s",\
                "confidence":50,"oneLine":"앵커"}""".formatted(name)).andExpect(status().isOk()));
        UUID seedSub = uuid(seedRes, "$.submissionId");
        int[] confidences = {50, 30, 10, 10};
        UUID first = null;
        UUID item = null;
        for (int i = 0; i < 4; i++) {
            clock.set(T0.plusSeconds(60L * (i + 1)));
            UUID u = fx.user();
            if (i == 0) first = u;
            item = itemOf(submit(u, name, confidences[i]));
        }
        clock.set(AFTER_DEADLINE);
        runVerdicts();

        assertThat(jdbc.queryForObject("SELECT result::text FROM verdicts WHERE trend_item_id = ? AND supersedes IS NULL",
                String.class, item)).isEqualTo("HIT");
        assertThat(doubles(ledger(first), "$.items[*].delta")).containsExactly(60.0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM score_ledger WHERE submission_id = ?", Integer.class, seedSub))
                .isZero();
        String ranks = jdbc.queryForObject("SELECT evidence_json->'orderRanks' FROM verdicts WHERE trend_item_id = ?",
                String.class, item);
        assertThat(ranks).doesNotContain(seedSub.toString());
    }

    @Test
    @DisplayName("JRN-03b 관리자 3명 시딩 + 실유저 3명 → MISS (시딩만으로 HIT를 만들 수 없다)")
    void seedsCannotManufactureHit() throws Exception {
        clock.set(T0);
        String name = uniq("seed cartel");
        for (int i = 0; i < 3; i++) {
            postJson("/admin/seed/submissions", asAdmin(fx.admin("OPERATOR"), AdminRole.OPERATOR), """
                    {"name":"%s","category":"MEME","platform":"X","evidenceUrl":"https://example.com/s",\
                    "confidence":30,"oneLine":"앵커"}""".formatted(name)).andExpect(status().isOk());
        }
        UUID item = null;
        for (int i = 0; i < 3; i++) {
            clock.set(T0.plusSeconds(60L * (i + 1)));
            item = itemOf(submit(fx.user(), name, 30));
        }
        clock.set(AFTER_DEADLINE);
        runVerdicts();

        assertThat(jdbc.queryForObject("SELECT result::text FROM verdicts WHERE trend_item_id = ? AND supersedes IS NULL",
                String.class, item)).isEqualTo("MISS");
        assertThat(jdbc.queryForObject("SELECT evidence_json->>'distinctSubmitters' FROM verdicts WHERE trend_item_id = ?",
                String.class, item)).isEqualTo("3");
    }

    @Test
    @DisplayName("JRN-04 병합 후 같은 유저 중복 → 늦은 제보 VOID → 제보권 1장 반환, 새 제보 가능")
    void mergeDedupRefundsQuota() throws Exception {
        clock.set(T0);
        UUID u = fx.user();
        String base = uniq("alpha");
        UUID x = itemOf(submit(u, base, 30));
        clock.set(T0.plus(Duration.ofHours(1)));
        String late = submit(u, base + " 2", 30);
        UUID y = itemOf(late);
        mvc.perform(get("/v1/me/summary").with(asUser(u))).andExpect(jsonPath("$.quotaUsed").value(2));
        UUID queue = fx.mergeQueueEntry(y, x, 0.80);

        clock.set(T0.plus(Duration.ofHours(2)));
        mvc.perform(post("/admin/merge-queue/{id}/merge", queue)
                        .with(asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"같은 밈\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.survivorId").value(x.toString()));

        List<Map<String, Object>> lateRow = read(getOk("/v1/submissions/me", asUser(u)),
                "$[?(@.id == '" + uuid(late, "$.id") + "')]");
        assertThat(lateRow).singleElement().satisfies(r -> assertThat(r.get("status")).isEqualTo("VOID"));
        mvc.perform(get("/v1/me/summary").with(asUser(u))).andExpect(jsonPath("$.quotaUsed").value(1));
        submit(u, uniq("after refund"), 30);
    }
}
