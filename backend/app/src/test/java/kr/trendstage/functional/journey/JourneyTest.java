package kr.trendstage.functional.journey;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
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
    @DisplayName("USR-SUB-10 판정 끝난 제보: 내 제보 목록에 delta(+60)·reachLevel(L1)·note(T + 원장 산정식), 판정 전은 셋 다 null")
    void judgedSubmissionShowsScoreAndBasis() throws Exception {
        clock.set(T0);
        UUID pendingUser = fx.user();
        submit(pendingUser, uniq("pending note"), 30);
        String pending = getOk("/v1/submissions/me", asUser(pendingUser));
        for (String field : new String[]{"delta", "reachLevel", "note"}) {
            assertThat((Object) read(pending, "$[0]." + field)).as(field).isNull();
        }

        Hit h = hitJourney();
        String json = getOk("/v1/submissions/me", asUser(h.users().get(0)));

        assertThat(doubles(json, "$[*].delta")).containsExactly(60.0);
        assertThat((List<String>) read(json, "$[*].reachLevel")).containsExactly("L1");
        assertThat((String) read(json, "$[0].note"))
                .startsWith("T=0.2")
                .contains("HIT L1 · 확신도 50 × 선점 1위(1.0) × 확산 ×1.2 = +60.0")
                .doesNotContain("조정 후 합계");
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

    @Test
    @DisplayName("JRN-05 재판정: D의 제보 VOID 후 재판정 → 차액 133.8 > 100이라 202(아무것도 안 씀) → 승인 → MISS, A 원장 = HIT 60 그대로 + ADJ −85")
    void rejudgeGoesThroughApprovalAndAppendsOnly() throws Exception {
        Hit h = hitJourney();
        UUID a = h.users().get(0);
        UUID aSub = h.submissions().get(0);
        fx.voidSubmission(h.submissions().get(3), AFTER_DEADLINE.plusSeconds(10));
        int rowsBefore = fx.ledgerRows(h.item());

        String res = body(postJson("/admin/verdicts/{id}/rejudge", asAdmin(fx.admin("OPERATOR"), AdminRole.OPERATOR),
                "{\"reason\":\"중복 계정\"}", h.item()).andExpect(status().isAccepted()));
        assertThat(fx.ledgerRows(h.item())).isEqualTo(rowsBefore);

        mvc.perform(post("/admin/approvals/{id}/approve", uuid(res, "$.approvalRequestId"))
                        .with(asAdmin(fx.admin(), AdminRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        assertThat(doubles(ledger(a), "$.items[*].delta")).containsExactlyInAnyOrder(60.0, -85.0);
        assertThat(fx.ledgerSum(a, h.item()).doubleValue()).isCloseTo(-25.0, within(0.0001));
        assertThat(jdbc.queryForObject("SELECT delta FROM score_ledger WHERE submission_id = ? AND kind = 'HIT'",
                java.math.BigDecimal.class, aSub).doubleValue()).isCloseTo(60.0, within(0.0001));
        assertThat(fx.ledgerRows(h.item())).isGreaterThan(rowsBefore);
        mvc.perform(get("/v1/trends/{id}", h.item())).andExpect(jsonPath("$.verdict").value("빗나갔어요"));
        // 내 제보 목록(USR-SUB-10): 재판정 반영 합계, MISS라 reachLevel 없음, 원 산정식 + 조정 후 합계
        String mine = getOk("/v1/submissions/me", asUser(a));
        assertThat(doubles(mine, "$[*].delta")).containsExactly(-25.0);
        assertThat((Object) read(mine, "$[0].reachLevel")).isNull();
        assertThat((String) read(mine, "$[0].note")).contains("HIT L1").endsWith(" · 조정 후 합계 -25.0");
    }

    @Test
    @DisplayName("JRN-06 판정된 항목 VOID: 차액 88.8 ≤ 100이라 즉시 → 유저별 원장 합 0, 원래 행 불변, 제보 VOID, 등급 판정 0·AS 0")
    void voidingJudgedItemZeroesScore() throws Exception {
        Hit h = hitJourney();
        UUID a = h.users().get(0);

        postJson("/admin/verdicts/{id}/void", asAdmin(fx.admin("OPERATOR"), AdminRole.OPERATOR),
                "{\"reason\":\"어뷰징\"}", h.item())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));

        for (UUID u : h.users()) {
            assertThat(fx.ledgerSum(u, h.item()).doubleValue()).isCloseTo(0.0, within(0.0001));
        }
        assertThat(jdbc.queryForObject("SELECT delta FROM score_ledger WHERE submission_id = ? AND kind = 'HIT'",
                java.math.BigDecimal.class, h.submissions().get(0)).doubleValue()).isCloseTo(60.0, within(0.0001));
        mvc.perform(get("/v1/submissions/me").with(asUser(a))).andExpect(jsonPath("$[0].status").value("VOID"));
        String mine = getOk("/v1/submissions/me", asUser(a));
        assertThat(doubles(mine, "$[*].delta")).containsExactly(0.0);
        assertThat((String) read(mine, "$[0].note")).endsWith(" · 조정 후 합계 +0.0");
        String g = grade(a);
        assertThat((Integer) read(g, "$.judgedCount")).isZero();
        assertThat(((Number) read(g, "$.activeScore")).doubleValue()).isCloseTo(0.0, within(0.001));
        mvc.perform(get("/v1/trends/{id}", h.item())).andExpect(jsonPath("$.verdict").value(nullValue()));
    }

    @Test
    @DisplayName("JRN-07 투표·인정은 판정에 안 들어간다(R1): 제보자 3명 + 30명 '뜬다' 투표·인정 → MISS, T 0.15, 투표자 적중 0/1")
    void votesAndEndorsementsDoNotAffectVerdict() throws Exception {
        String name = uniq("hype");
        UUID item = null;
        for (int i = 0; i < 3; i++) {
            clock.set(T0.plusSeconds(60L * i));
            item = itemOf(submit(fx.user(), name, 30));
        }
        clock.set(T0.plus(Duration.ofHours(1)));
        UUID voter = null;
        for (int i = 0; i < 30; i++) {
            voter = fx.user();
            postJson("/v1/trends/{id}/vote", asUser(voter), "{\"willTrend\":true}", item).andExpect(status().isOk());
            mvc.perform(post("/v1/trends/{id}/endorse", item).with(asUser(voter))).andExpect(status().isCreated());
        }
        clock.set(AFTER_DEADLINE);
        runVerdicts();

        Map<String, Object> v = jdbc.queryForMap("SELECT result::text AS result, score_t, "
                + "evidence_json->>'distinctSubmitters' AS n FROM verdicts WHERE trend_item_id = ?", item);
        assertThat(v.get("result")).isEqualTo("MISS");
        assertThat(((Number) v.get("score_t")).doubleValue()).isCloseTo(0.15, within(0.0001));
        assertThat(v.get("n")).isEqualTo("3");
        mvc.perform(get("/v1/me/summary").with(asUser(voter)))
                .andExpect(jsonPath("$.votesTotal").value(1))
                .andExpect(jsonPath("$.votesCorrect").value(0));
    }

    @Test
    @DisplayName("JRN-08 주간 등급 재계산(R3 AND): A(판정 5·TI 0.7·AS≥30) → L1·제보권 3, B(판정 5·AS 충분·TI 0.2) → L0")
    void weeklyGradeRecalcRequiresAllConditions() throws Exception {
        Hit h = hitJourney();   // A의 HIT 1건(+60) — 시계는 AFTER_DEADLINE(2026-10-05 월 10:00 KST)
        UUID a = h.users().get(0);
        UUID b = fx.user();
        for (int i = 0; i < 4; i++) fx.judgedSubmission(a, "HIT", T0.minus(Duration.ofDays(30)));
        fx.ledgerRow(a, 10, 90, T0);
        for (int i = 0; i < 5; i++) fx.judgedSubmission(b, "MISS", T0.minus(Duration.ofDays(30)));
        fx.ledgerRow(b, 200, 90, T0);

        clock.set(ZonedDateTime.of(2026, 10, 12, 0, 0, 0, 0, KST).toInstant());   // 다음 월요일 00:00 KST
        runGradeRecalc();

        mvc.perform(get("/v1/me/grade").with(asUser(a))).andExpect(jsonPath("$.grade").value("L1"));
        mvc.perform(get("/v1/me/summary").with(asUser(a))).andExpect(jsonPath("$.quotaMax").value(3));
        mvc.perform(get("/v1/me/grade").with(asUser(b))).andExpect(jsonPath("$.grade").value("L0"));
        mvc.perform(get("/v1/me/summary").with(asUser(b))).andExpect(jsonPath("$.quotaMax").value(2));
    }

    @Test
    @DisplayName("JRN-09 신고 → 소명 요청 → 소명 → 영구 비공개: 신고자에게 결정 표시, 상세 404, 목록에서 빠짐")
    void reportExplanationDecision() throws Exception {
        clock.set(T0);
        UUID s = fx.user();
        String sub = submit(s, uniq("defame"), 30);
        UUID item = itemOf(sub);
        UUID reporter = fx.user();
        UUID report = uuid(body(postJson("/v1/reports", asUser(reporter),
                "{\"trendItemId\":\"" + item + "\",\"reason\":\"DEFAMATION\",\"detail\":\"허위 사실\"}")
                .andExpect(status().isCreated())), "$.id");

        postJson("/admin/reports/{id}/request-explanation", asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER),
                "{\"submissionId\":\"" + uuid(sub, "$.id") + "\",\"note\":\"소명 요청\"}", report).andExpect(status().isOk());
        assertThat((List<String>) read(getOk("/v1/reports/received", asUser(s)), "$[*].id")).contains(report.toString());
        clock.set(T0.plus(Duration.ofHours(1)));
        postJson("/v1/reports/{id}/explanation", asUser(s), "{\"text\":\"근거 있습니다\"}", report).andExpect(status().isOk());
        postJson("/admin/reports/{id}/decide", asAdmin(fx.admin("OPERATOR"), AdminRole.OPERATOR),
                "{\"decision\":\"HIDE_PERMANENT\",\"note\":\"명예훼손 확인\"}", report).andExpect(status().isOk());

        List<Map<String, Object>> mine = read(getOk("/v1/reports/me", asUser(reporter)), "$[?(@.id == '" + report + "')]");
        assertThat(mine).singleElement().satisfies(r -> {
            assertThat(r.get("status")).isEqualTo("DECIDED");
            assertThat(r.get("decision")).isEqualTo("HIDE_PERMANENT");
        });
        mvc.perform(get("/v1/trends/{id}", item)).andExpect(status().isNotFound());
        mvc.perform(get("/v1/trends")).andExpect(jsonPath("$.items[*].id").value(not(hasItem(item.toString()))));
    }
}
