package kr.trendstage.functional.user;

import kr.trendstage.functional.FunctionalTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserTrendTest extends FunctionalTestBase {

    private List<String> listIds(String json) {
        return read(json, "$.items[*].id");
    }

    /** 다른 테스트 항목과 겹치지 않는 검색 토큰(소문자). */
    private static String token() {
        return "zq" + UUID.randomUUID().toString().substring(0, 8);
    }

    private List<String> search(String q) throws Exception {
        return listIds(body(mvc.perform(get("/v1/trends").param("q", q)).andExpect(status().isOk())));
    }

    @Test
    @DisplayName("USR-TRD-21 홈 카드 경로 문구는 플랫폼을 처음 본 순서대로(APP-7)")
    void pathTextIsChronological() throws Exception {
        clock.set(T0);
        String name = uniq("path");
        UUID item = itemOf(submit(fx.user(), name, 30, "인스타"));
        clock.set(T0.plusSeconds(60));
        submit(fx.user(), name, 30, "디시");
        clock.set(T0.plusSeconds(120));
        submit(fx.user(), name, 30, "X");   // 그 외 링크 → 기타

        List<String> path = read(getOk("/v1/trends", ANON), "$.items[?(@.id == '" + item + "')].pathText");

        assertThat(path).containsExactly("인스타 → 디시 → 기타");
    }

    @Test
    @DisplayName("USR-TRD-16 검색: 이름에 검색어가 들어간 항목만(APP-2)")
    void searchMatchesContainedName() throws Exception {
        clock.set(T0);
        String t = token();
        UUID a = itemOf(submit(fx.user(), t + " cookie", 30));
        UUID b = itemOf(submit(fx.user(), "milk " + t, 30));
        UUID other = itemOf(submit(fx.user(), uniq("unrelated"), 30));

        assertThat(search(t)).contains(a.toString(), b.toString()).doesNotContain(other.toString());
    }

    @Test
    @DisplayName("USR-TRD-17 검색: 검색어도 정규화(대소문자·공백)하고 완전일치 → 앞부분 일치 → 포함 순")
    void searchRanksExactThenPrefixThenContains() throws Exception {
        clock.set(T0);
        String t = token();
        UUID contains = itemOf(submit(fx.user(), "pre " + t, 30));
        UUID prefix = itemOf(submit(fx.user(), t + " plus", 30));
        UUID exact = itemOf(submit(fx.user(), t, 30));

        assertThat(search("  " + t.toUpperCase() + " ")).containsExactly(exact.toString(), prefix.toString(), contains.toString());
    }

    @Test
    @DisplayName("USR-TRD-18 검색: 판정된(RESOLVED) 항목은 포함, MERGED·VOID·비공개는 제외")
    void searchIncludesResolvedButNotMergedVoidOrHidden() throws Exception {
        clock.set(T0);
        String t = token();
        UUID resolved = itemOf(submit(fx.user(), t + " resolved", 30));
        fx.setState(resolved, "RESOLVED");
        UUID merged = itemOf(submit(fx.user(), t + " merged", 30));
        fx.merged(merged, resolved);
        UUID voided = itemOf(submit(fx.user(), t + " voided", 30));
        fx.setState(voided, "VOID");
        UUID hidden = itemOf(submit(fx.user(), t + " hidden", 30));
        fx.setVisibility(hidden, "TEMP_HIDDEN");

        assertThat(search(t)).containsExactly(resolved.toString());
    }

    @Test
    @DisplayName("USR-TRD-19 검색: 병합돼 사라진 이름(별칭)으로도 생존 항목이 나온다")
    void searchMatchesAliases() throws Exception {
        clock.set(T0);
        String t = token();
        UUID survivor = itemOf(submit(fx.user(), uniq("survivor"), 30));
        jdbc.update("UPDATE trend_items SET aliases = ARRAY[?] WHERE id = ?", t + " old name", survivor);

        assertThat(search(t + " old")).containsExactly(survivor.toString());
    }

    @Test
    @DisplayName("USR-TRD-20 검색: 결과 없음은 빈 목록, %·_는 와일드카드가 아니다")
    void searchWithoutMatchAndLiteralWildcards() throws Exception {
        clock.set(T0);
        String t = token();
        UUID mine = itemOf(submit(fx.user(), t + " wildcard", 30));

        assertThat(search(token())).isEmpty();
        assertThat(search("%")).doesNotContain(mine.toString());
        assertThat(search("_")).doesNotContain(mine.toString());
    }

    @Test
    @DisplayName("USR-TRD-01 목록은 PENDING·JUDGING이고 PUBLIC인 항목만(MERGED·RESOLVED·TEMP_HIDDEN 제외)")
    void listShowsOnlyOpenPublicItems() throws Exception {
        clock.set(T0);
        UUID pending = itemOf(submit(fx.user(), uniq("pending"), 30));
        UUID judging = fx.item(T0);
        fx.setState(judging, "JUDGING");
        UUID merged = fx.item(T0);
        fx.merged(merged, pending);
        UUID resolved = fx.item(T0);
        fx.setState(resolved, "RESOLVED");
        UUID hidden = fx.item(T0);
        fx.setVisibility(hidden, "TEMP_HIDDEN");

        List<String> ids = listIds(getOk("/v1/trends", ANON));

        assertThat(ids).contains(pending.toString(), judging.toString())
                .doesNotContain(merged.toString(), resolved.toString(), hidden.toString());
    }

    @Test
    @DisplayName("USR-TRD-02 단계: 플랫폼 1종 SEED, 2종 RISING")
    void stageFollowsDistinctPlatforms() throws Exception {
        clock.set(T0);
        UUID seed = itemOf(submit(fx.user(), uniq("seedstage"), 30, "디시"));
        String rising = uniq("risingstage");
        UUID risingItem = itemOf(submit(fx.user(), rising, 30, "디시"));
        submit(fx.user(), rising, 30, "인스타");

        mvc.perform(get("/v1/trends/{id}", seed)).andExpect(jsonPath("$.stage").value("SEED"));
        mvc.perform(get("/v1/trends/{id}", risingItem)).andExpect(jsonPath("$.stage").value("RISING"));
    }

    @Test
    @DisplayName("USR-TRD-03 로그인 + daily=true는 최대 5개, 같은 날 두 번 호출하면 같은 목록, daily_selections 기록")
    void dailySelectionIsStableWithinDay() throws Exception {
        clock.set(T0);
        UUID u = fx.user();
        submit(fx.user(), uniq("daily"), 30);

        List<String> first = listIds(getOk("/v1/trends?daily=true", asUser(u)));
        List<String> second = listIds(getOk("/v1/trends?daily=true", asUser(u)));

        assertThat(first).isNotEmpty().hasSizeLessThanOrEqualTo(5).isEqualTo(second);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM daily_selections WHERE user_id = ?", Integer.class, u))
                .isEqualTo(first.size());
    }

    @Test
    @DisplayName("USR-TRD-04 오늘의 5개 선정 후 비공개·병합된 항목은 다시 조회하면 빠진다")
    void dailySelectionDropsHiddenOrMergedItems() throws Exception {
        clock.set(T0);
        UUID u = fx.user();
        fx.preferences(u, "CHALLENGE");   // 다른 테스트 항목은 MEME — 내 CHALLENGE 항목이 선정에 반드시 들어간다
        UUID toHide = itemOf(body(postSubmission(fx.user(),
                submissionBody(uniq("hideme"), 30, "X", "CHALLENGE", "설명")).andExpect(status().isCreated())));
        UUID toMerge = itemOf(body(postSubmission(fx.user(),
                submissionBody(uniq("mergeme"), 30, "X", "CHALLENGE", "설명")).andExpect(status().isCreated())));
        assertThat(listIds(getOk("/v1/trends?daily=true", asUser(u)))).contains(toHide.toString(), toMerge.toString());

        fx.setVisibility(toHide, "TEMP_HIDDEN");
        fx.merged(toMerge, fx.item(T0));

        assertThat(listIds(getOk("/v1/trends?daily=true", asUser(u))))
                .doesNotContain(toHide.toString(), toMerge.toString());
    }

    @Test
    @DisplayName("USR-TRD-05 상세: meaning=가장 이른 제보의 oneLine, pathText에 플랫폼, 투표 0이면 voteCount null")
    void detailBasics() throws Exception {
        clock.set(T0);
        String name = uniq("detail");
        UUID item = itemOf(body(postSubmission(fx.user(), submissionBody(name, 30, "디시", "MEME", "첫 설명"))
                .andExpect(status().isCreated())));
        clock.set(T0.plusSeconds(60));
        postSubmission(fx.user(), submissionBody(name, 30, "인스타", "MEME", "둘째 설명")).andExpect(status().isCreated());

        mvc.perform(get("/v1/trends/{id}", item))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meaning").value("첫 설명"))
                .andExpect(jsonPath("$.pathText").value(containsString("디시")))
                .andExpect(jsonPath("$.pathText").value(containsString("인스타")))
                .andExpect(jsonPath("$.voteCount").value(nullValue()))
                .andExpect(jsonPath("$.verdict").value(nullValue()));
    }

    @Test
    @DisplayName("USR-TRD-06 상세: 없는 id·MERGED·TEMP_HIDDEN·PERMANENT_HIDDEN은 404")
    void detailNotFoundCases() throws Exception {
        clock.set(T0);
        UUID merged = fx.item(T0);
        fx.merged(merged, fx.item(T0));
        UUID temp = fx.item(T0);
        fx.setVisibility(temp, "TEMP_HIDDEN");
        UUID perm = fx.item(T0);
        fx.setVisibility(perm, "PERMANENT_HIDDEN");

        for (UUID id : List.of(UUID.randomUUID(), merged, temp, perm)) {
            mvc.perform(get("/v1/trends/{id}", id)).andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("USR-TRD-08 상세: 내가 워치한 항목은 watched=true, 다른 유저에게는 false")
    void detailWatchedFlag() throws Exception {
        clock.set(T0);
        String name = uniq("watched");
        UUID item = itemOf(submit(fx.user(), name, 30));
        UUID watcher = fx.user();
        postJson("/v1/me/watch", asUser(watcher), "{\"keyword\":\"" + name + "\"}").andExpect(status().isCreated());

        mvc.perform(get("/v1/trends/{id}", item).with(asUser(watcher))).andExpect(jsonPath("$.watched").value(true));
        mvc.perform(get("/v1/trends/{id}", item).with(asUser(fx.user()))).andExpect(jsonPath("$.watched").value(false));
    }

    @Test
    @DisplayName("USR-TRD-09 투표 true → false 토글: 둘 다 200, votes 1행, voteCount 문구 생김")
    void voteToggles() throws Exception {
        clock.set(T0);
        UUID item = itemOf(submit(fx.user(), uniq("vote"), 30));
        UUID u = fx.user();

        postJson("/v1/trends/{id}/vote", asUser(u), "{\"willTrend\":true}", item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.willTrend").value(true))
                .andExpect(jsonPath("$.voteCount").value(notNullValue()));
        postJson("/v1/trends/{id}/vote", asUser(u), "{\"willTrend\":false}", item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.willTrend").value(false));

        assertThat(jdbc.queryForList("SELECT will_trend FROM votes WHERE user_id = ? AND trend_item_id = ?",
                Boolean.class, u, item)).containsExactly(false);
    }

    @Test
    @DisplayName("USR-TRD-10 투표: willTrend 누락 400, 없는 항목·MERGED 404")
    void voteErrors() throws Exception {
        clock.set(T0);
        UUID item = itemOf(submit(fx.user(), uniq("voteerr"), 30));
        UUID merged = fx.item(T0);
        fx.merged(merged, item);
        UUID u = fx.user();

        postJson("/v1/trends/{id}/vote", asUser(u), "{}", item).andExpect(status().isBadRequest());
        postJson("/v1/trends/{id}/vote", asUser(u), "{\"willTrend\":true}", UUID.randomUUID()).andExpect(status().isNotFound());
        postJson("/v1/trends/{id}/vote", asUser(u), "{\"willTrend\":true}", merged).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("USR-TRD-11 비공개(TEMP_HIDDEN·PERMANENT_HIDDEN) 항목 투표·인정은 404 (D1)")
    void hiddenItemInteractionsAre404() throws Exception {
        clock.set(T0);
        UUID u = fx.user();
        for (String visibility : List.of("TEMP_HIDDEN", "PERMANENT_HIDDEN")) {
            UUID item = fx.item(T0);
            fx.setVisibility(item, visibility);
            postJson("/v1/trends/{id}/vote", asUser(u), "{\"willTrend\":true}", item).andExpect(status().isNotFound());
            mvc.perform(post("/v1/trends/{id}/endorse", item).with(asUser(u))).andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("USR-TRD-12 인정: 첫 요청 201(바디 없음), 두 번째 409, endorsements 1행")
    void endorseOnce() throws Exception {
        clock.set(T0);
        UUID item = itemOf(submit(fx.user(), uniq("endorse"), 30));
        UUID u = fx.user();

        mvc.perform(post("/v1/trends/{id}/endorse", item).with(asUser(u)))
                .andExpect(status().isCreated())
                .andExpect(content().string(""));
        mvc.perform(post("/v1/trends/{id}/endorse", item).with(asUser(u))).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM endorsements WHERE user_id = ? AND trend_item_id = ?",
                Integer.class, u, item)).isEqualTo(1);
    }

    @Test
    @DisplayName("USR-TRD-13 인정: 없는 항목·MERGED는 404")
    void endorseNotFound() throws Exception {
        clock.set(T0);
        UUID merged = fx.item(T0);
        fx.merged(merged, fx.item(T0));
        UUID u = fx.user();

        mvc.perform(post("/v1/trends/{id}/endorse", UUID.randomUUID()).with(asUser(u))).andExpect(status().isNotFound());
        mvc.perform(post("/v1/trends/{id}/endorse", merged).with(asUser(u))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("USR-TRD-14 그 항목에 제보한 유저의 인정은 409 (D2)")
    void submitterCannotEndorseOwnItem() throws Exception {
        clock.set(T0);
        UUID submitter = fx.user();
        UUID item = itemOf(submit(submitter, uniq("selfendorse"), 30));

        mvc.perform(post("/v1/trends/{id}/endorse", item).with(asUser(submitter))).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("USR-TRD-15 같은 유저 투표·인정 동시 첫 요청: 500 없음, 투표 1행, 인정 201+409")
    void concurrentFirstVoteAndEndorse() throws Exception {
        clock.set(T0);
        UUID item = itemOf(submit(fx.user(), uniq("racevote"), 30));
        UUID voter = fx.user();
        UUID endorser = fx.user();

        List<Integer> votes = concurrently(2, () -> postJson("/v1/trends/{id}/vote", asUser(voter),
                "{\"willTrend\":true}", item).andReturn().getResponse().getStatus());
        List<Integer> endorses = concurrently(2, () -> mvc.perform(post("/v1/trends/{id}/endorse", item)
                .with(asUser(endorser))).andReturn().getResponse().getStatus());

        assertThat(votes).containsOnly(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM votes WHERE user_id = ? AND trend_item_id = ?",
                Integer.class, voter, item)).isEqualTo(1);
        assertThat(endorses).containsExactlyInAnyOrder(201, 409);
    }
}
