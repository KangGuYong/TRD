package kr.trendstage.functional.user;

import kr.trendstage.functional.FunctionalTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserMeTest extends FunctionalTestBase {

    @Test
    @DisplayName("USR-ME-01 신규 유저 등급: L0, TI 0.4, 판정 0, 다음 등급 L1(표시명 제보자), 요건 3개(판정 수·TI·AS) 각각 basis")
    void newUserGrade() throws Exception {
        mvc.perform(get("/v1/me/grade").with(asUser(fx.user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grade").value("L0"))
                .andExpect(jsonPath("$.trustIndex").value(closeTo(0.4, 0.0001)))
                .andExpect(jsonPath("$.judgedCount").value(0))
                .andExpect(jsonPath("$.nextGrade").value("제보자"))   // 다음 등급(L1)의 표시명 — OpenAPI는 string만 정함
                .andExpect(jsonPath("$.requirements[*].kind")
                        .value(containsInAnyOrder("JUDGED_COUNT", "TRUST_INDEX", "ACTIVE_SCORE")))
                .andExpect(jsonPath("$.requirements[*].basis").value(everyItem(notNullValue())));
    }

    @Test
    @DisplayName("USR-ME-02 신규 유저 원장은 빈 목록")
    void newUserLedgerIsEmpty() throws Exception {
        mvc.perform(get("/v1/me/ledger").with(asUser(fx.user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @DisplayName("USR-ME-03 제보 없는 수동 ADJ 원장 행은 word='계정 조정', kind=ADJ")
    void manualAdjustmentShowsAsAccountAdjustment() throws Exception {
        UUID u = fx.user();
        fx.ledgerRow(u, 5, 90, T0);

        mvc.perform(get("/v1/me/ledger").with(asUser(u)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].word").value("계정 조정"))
                .andExpect(jsonPath("$.items[0].kind").value("ADJ"))
                .andExpect(jsonPath("$.items[0].delta").value(closeTo(5.0, 0.0001)));
    }

    @Test
    @DisplayName("USR-ME-04 요약: 제보 1건 후 quotaUsed 1 / quotaMax 2(L0)")
    void summaryShowsQuota() throws Exception {
        clock.set(T0);
        UUID u = fx.user();
        submit(u, uniq("quota"), 30);

        mvc.perform(get("/v1/me/summary").with(asUser(u)))
                .andExpect(jsonPath("$.quotaUsed").value(1))
                .andExpect(jsonPath("$.quotaMax").value(2));
    }

    @Test
    @DisplayName("USR-ME-05 투표 정확도: 판정된 항목 투표만 집계(HIT에 true 적중, MISS에 true 빗나감, 미판정 제외)")
    void voteAccuracyCountsOnlyJudgedItems() throws Exception {
        clock.set(T0);
        UUID hit = fx.item(T0);
        fx.verdict(hit, "HIT");
        UUID miss = fx.item(T0);
        fx.verdict(miss, "MISS");
        UUID pending = fx.item(T0);
        UUID u = fx.user();
        for (UUID item : new UUID[]{hit, miss, pending}) {
            postJson("/v1/trends/{id}/vote", asUser(u), "{\"willTrend\":true}", item).andExpect(status().isOk());
        }

        mvc.perform(get("/v1/me/summary").with(asUser(u)))
                .andExpect(jsonPath("$.votesTotal").value(2))
                .andExpect(jsonPath("$.votesCorrect").value(1))
                .andExpect(jsonPath("$.voteHitRate").value(closeTo(0.5, 0.0001)));
    }

    @Test
    @DisplayName("USR-ME-06 온보딩 전 선호 조회는 404")
    void preferencesBeforeOnboardingIs404() throws Exception {
        mvc.perform(get("/v1/me/preferences").with(asUser(fx.user()))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("USR-ME-07 선호 저장 → 200 요청 그대로, 조회도 같다")
    void savePreferences() throws Exception {
        UUID u = fx.user();
        String body = "{\"categories\":[\"MEME\",\"SLANG\"],\"notifyHour\":9}";

        putJson("/v1/me/preferences", asUser(u), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").value(containsInAnyOrder("MEME", "SLANG")))
                .andExpect(jsonPath("$.notifyHour").value(9));
        mvc.perform(get("/v1/me/preferences").with(asUser(u)))
                .andExpect(jsonPath("$.categories").value(containsInAnyOrder("MEME", "SLANG")))
                .andExpect(jsonPath("$.notifyHour").value(9));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"categories\":[],\"notifyHour\":9}",
            "{\"categories\":[\"MEME\"],\"notifyHour\":24}",
            "{\"categories\":[\"MEME\"],\"notifyHour\":-1}",
            "{\"categories\":[\"NOPE\"],\"notifyHour\":9}"})
    @DisplayName("USR-ME-08 선호 저장: 빈 카테고리·notifyHour 범위 밖·잘못된 카테고리는 400")
    void invalidPreferencesAre400(String body) throws Exception {
        putJson("/v1/me/preferences", asUser(fx.user()), body).andExpect(status().isBadRequest());
    }
}
