package kr.trendstage.functional.admin;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminSeedTest extends FunctionalTestBase {

    private static String seedBody(String name, int confidence, String category) {
        return """
                {"name":"%s","category":"%s","platform":"X","evidenceUrl":"https://example.com/s",\
                "confidence":%d,"oneLine":"시딩 설명"}""".formatted(name, category, confidence);
    }

    private ResultActions seed(UUID admin, AdminRole role, String body) throws Exception {
        return postJson("/admin/seed/submissions", asAdmin(admin, role), body);
    }

    @Test
    @DisplayName("ADM-SEED-01 OPERATOR 첫 시딩 → 200, 시딩 유저(seed_<loginId>) 생성·연결, is_seed=true, 감사 SEED_SUBMISSION_CREATE")
    void firstSeedCreatesSeedUser() throws Exception {
        clock.set(T0);
        UUID op = fx.admin("OPERATOR");
        String name = uniq("seed");

        String json = body(seed(op, AdminRole.OPERATOR, seedBody(name, 30, "MEME")).andExpect(status().isOk()));

        UUID submission = uuid(json, "$.submissionId");
        UUID item = uuid(json, "$.trendItemId");
        UUID seedUser = jdbc.queryForObject("SELECT seed_user_id FROM admin_accounts WHERE id = ?", UUID.class, op);
        String loginId = jdbc.queryForObject("SELECT login_id FROM admin_accounts WHERE id = ?", String.class, op);
        assertThat(fx.handle(seedUser)).isEqualTo("seed_" + loginId);
        assertThat(jdbc.queryForObject("SELECT is_seed FROM submissions WHERE id = ?", Boolean.class, submission)).isTrue();
        assertThat(jdbc.queryForObject("SELECT user_id FROM submissions WHERE id = ?", UUID.class, submission)).isEqualTo(seedUser);
        assertThat(fx.auditCount("SEED_SUBMISSION_CREATE", item)).isEqualTo(1);
    }

    @Test
    @DisplayName("ADM-SEED-02 같은 관리자가 같은 항목을 다시 시딩하면 422")
    void reseedIs422() throws Exception {
        clock.set(T0);
        UUID op = fx.admin("OPERATOR");
        String name = uniq("reseed");
        seed(op, AdminRole.OPERATOR, seedBody(name, 30, "MEME")).andExpect(status().isOk());

        seed(op, AdminRole.OPERATOR, seedBody(name, 30, "MEME")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("ADM-SEED-03 확신도 20·잘못된 카테고리 422, 필수 누락 400")
    void seedValidation() throws Exception {
        clock.set(T0);
        UUID op = fx.admin("OPERATOR");
        seed(op, AdminRole.OPERATOR, seedBody(uniq("s"), 20, "MEME")).andExpect(status().isUnprocessableEntity());
        seed(op, AdminRole.OPERATOR, seedBody(uniq("s"), 30, "NOPE")).andExpect(status().isUnprocessableEntity());
        seed(op, AdminRole.OPERATOR, "{\"category\":\"MEME\",\"platform\":\"X\",\"evidenceUrl\":\"https://e\","
                + "\"confidence\":30,\"oneLine\":\"a\"}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("ADM-SEED-04 같은 이름 유저 제보는 시딩 항목에 합류하고, 시딩이 먼저여도 유저가 1위")
    void userJoinsSeededItemAsFirstRank() throws Exception {
        clock.set(T0);
        String name = uniq("anchor");
        UUID item = uuid(body(seed(fx.admin("OPERATOR"), AdminRole.OPERATOR, seedBody(name, 30, "MEME"))
                .andExpect(status().isOk())), "$.trendItemId");

        clock.set(T0.plusSeconds(60));
        UUID u = fx.user();
        String res = submit(u, name, 30);

        assertThat(itemOf(res)).isEqualTo(item);
        // 생성 응답의 orderRank는 BUG-9로 null — 순위는 내 제보 목록에서 확인
        assertThat((Integer) read(getOk("/v1/submissions/me", asUser(u)), "$[0].orderRank")).isEqualTo(1);
    }

    @Test
    @DisplayName("ADM-SEED-05 JUDGING·RESOLVED 항목 시딩도 200 (D9: 현재 동작 고정)")
    void seedOnClosedItemIsAllowed() throws Exception {
        clock.set(T0);
        UUID op = fx.admin("OPERATOR");
        for (String state : new String[]{"JUDGING", "RESOLVED"}) {
            UUID item = fx.item(T0);
            fx.setState(item, state);
            seed(op, AdminRole.OPERATOR, seedBody(fx.key(item), 30, "MEME")).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("ADM-SEED-06 시딩 정확도: HIT 1·MISS 1 → hit 1, miss 1, judged 2, TI (1+2)/(2+5)")
    void seedAccuracy() throws Exception {
        clock.set(T0);
        UUID op = fx.admin("OPERATOR");
        String displayName = uniq("시더");
        jdbc.update("UPDATE admin_accounts SET display_name = ? WHERE id = ?", displayName, op);
        UUID hit = uuid(body(seed(op, AdminRole.OPERATOR, seedBody(uniq("acc"), 30, "MEME"))), "$.submissionId");
        UUID miss = uuid(body(seed(op, AdminRole.OPERATOR, seedBody(uniq("acc"), 30, "MEME"))), "$.submissionId");
        jdbc.update("UPDATE submissions SET result = 'HIT', resolved_at = ? WHERE id = ?", Timestamp.from(T0), hit);
        jdbc.update("UPDATE submissions SET result = 'MISS', resolved_at = ? WHERE id = ?", Timestamp.from(T0), miss);

        String json = getOk("/admin/seed/accuracy", asAdmin(fx.admin("AUDITOR"), AdminRole.AUDITOR));
        List<Map<String, Object>> rows = read(json, "$[?(@.operatorName == '" + displayName + "')]");

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.get("hit")).isEqualTo(1);
            assertThat(r.get("miss")).isEqualTo(1);
            assertThat(r.get("judged")).isEqualTo(2);
            assertThat(((Number) r.get("trustIndex")).doubleValue()).isCloseTo(3.0 / 7.0, within(0.001));
        });
    }

    @Test
    @DisplayName("ADM-SEED-07 역할: REVIEWER·AUDITOR 시딩 403, 정확도 조회는 4개 역할 200")
    void seedRoles() throws Exception {
        clock.set(T0);
        for (AdminRole role : new AdminRole[]{AdminRole.REVIEWER, AdminRole.AUDITOR}) {
            seed(fx.admin(role.name()), role, seedBody(uniq("r"), 30, "MEME")).andExpect(status().isForbidden());
        }
        for (AdminRole role : AdminRole.values()) {
            mvc.perform(get("/admin/seed/accuracy").with(asAdmin(fx.admin(role.name()), role))).andExpect(status().isOk());
        }
    }
}
