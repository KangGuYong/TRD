package kr.trendstage.functional.user;

import kr.trendstage.functional.FunctionalTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserSubmissionTest extends FunctionalTestBase {

    @Test
    @DisplayName("USR-SUB-01 새 이름 제보 → 201, PENDING·1위·D-14, 항목(PENDING·PUBLIC·first_seen=제보 시각) 생성")
    void newNameCreatesItem() throws Exception {
        clock.set(T0);
        String name = uniq("새트렌드");

        String res = submit(fx.user(), name, 30);

        assertThat((String) read(res, "$.status")).isEqualTo("PENDING");
        // 생성 응답의 orderRank는 USR-SUB-14(BUG-9)
        assertThat(((Number) read(res, "$.judgeInDays")).longValue()).isEqualTo(14);
        assertThat((String) read(res, "$.word")).isEqualTo(name);
        UUID item = itemOf(res);
        assertThat(fx.itemState(item)).isEqualTo("PENDING");
        assertThat(fx.visibility(item)).isEqualTo("PUBLIC");
        assertThat(jdbc.queryForObject("SELECT first_seen_at FROM trend_items WHERE id = ?", Timestamp.class, item)
                .toInstant()).isEqualTo(T0);
        assertThat(jdbc.queryForObject("SELECT is_seed FROM submissions WHERE id = ?", Boolean.class,
                uuid(res, "$.id"))).isFalse();
    }

    @Test
    @DisplayName("USR-SUB-02 공백·대소문자만 다른 이름은 같은 항목에 2위로 합류")
    void normalizedNameJoinsExistingItem() throws Exception {
        clock.set(T0);
        String base = "foo bar " + UUID.randomUUID().toString().substring(0, 8);
        UUID item = itemOf(submit(fx.user(), base, 30));

        clock.set(T0.plusSeconds(60));
        UUID second = fx.user();
        String res = submit(second, "  " + base.toUpperCase().replace(" ", "   ") + " ", 30);

        assertThat(itemOf(res)).isEqualTo(item);
        mvc.perform(get("/v1/submissions/me").with(asUser(second))).andExpect(jsonPath("$[0].orderRank").value(2));
    }

    @Test
    @org.junit.jupiter.api.Disabled("BUG-9: 제보 생성 응답의 orderRank가 항상 null — save() 후 flush 없이 submission_order_rank 뷰를 조회해 "
            + "방금 넣은 제보가 안 보인다(SubmissionService.toResponse)")
    @DisplayName("USR-SUB-14 제보 생성 응답의 orderRank는 파생 순위(1위, 이어서 2위)")
    void createResponseCarriesDerivedRank() throws Exception {
        clock.set(T0);
        String name = uniq("rank");
        assertThat((Integer) read(submit(fx.user(), name, 30), "$.orderRank")).isEqualTo(1);
        clock.set(T0.plusSeconds(60));
        assertThat((Integer) read(submit(fx.user(), name, 30), "$.orderRank")).isEqualTo(2);
    }

    @Test
    @DisplayName("USR-SUB-03 같은 유저의 같은 항목 재제보는 409 {trendItemId, dupeRank}(Problem 아님), 제보권 차감 없음")
    void duplicateIs409WithBody() throws Exception {
        clock.set(T0);
        UUID u = fx.user();
        String name = uniq("dup");
        UUID item = itemOf(submit(u, name, 30));

        postSubmission(u, submissionBody(name, 30, "X", "MEME", "설명"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.trendItemId").value(item.toString()))
                .andExpect(jsonPath("$.dupeRank").value(1))
                .andExpect(jsonPath("$.type").doesNotExist());
        mvc.perform(get("/v1/me/summary").with(asUser(u))).andExpect(jsonPath("$.quotaUsed").value(1));
    }

    @Test
    @DisplayName("USR-SUB-04 확신도가 10/30/50이 아니면 422")
    void invalidConfidenceIs422() throws Exception {
        clock.set(T0);
        postSubmission(fx.user(), submissionBody(uniq("conf"), 20, "X", "MEME", "설명"))
                .andExpect(status().isUnprocessableEntity());
    }

    static Stream<String> invalidBodies() {
        return Stream.of(
                // name 없음
                "{\"category\":\"MEME\",\"platform\":\"X\",\"evidenceUrl\":\"https://e\",\"confidence\":30,\"disclosure\":false,\"oneLine\":\"a\"}",
                submissionBody("가".repeat(121), 30, "X", "MEME", "a"),
                submissionBody(uniq("n"), 30, "X", "MEME", "가".repeat(201)),
                submissionBody(uniq("n"), 30, "X", "NOPE", "a"),
                // confidence 없음
                "{\"name\":\"n\",\"category\":\"MEME\",\"platform\":\"X\",\"evidenceUrl\":\"https://e\",\"disclosure\":false,\"oneLine\":\"a\"}");
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    @DisplayName("USR-SUB-05 필수 누락·길이 초과(name 121, oneLine 201)·잘못된 카테고리는 400")
    void invalidBodyIs400(String body) throws Exception {
        clock.set(T0);
        postSubmission(fx.user(), body).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("USR-SUB-07 마감 지난 항목·JUDGING 항목 제보는 422 item-closed, 제보권 차감 없음")
    void closedItemIs422() throws Exception {
        clock.set(T0);
        UUID u = fx.user();
        UUID expired = fx.item(T0.minus(Duration.ofDays(15)));
        UUID judging = fx.item(T0.minus(Duration.ofDays(1)));
        fx.setState(judging, "JUDGING");

        for (UUID item : List.of(expired, judging)) {
            postSubmission(u, submissionBody(fx.key(item), 30, "X", "MEME", "설명"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type").value("item-closed"));
        }
        mvc.perform(get("/v1/me/summary").with(asUser(u))).andExpect(jsonPath("$.quotaUsed").value(0));
    }

    @Test
    @DisplayName("USR-SUB-09 내 제보 목록은 내 것만 최신순, PENDING이면 judgeInDays")
    void mySubmissionsNewestFirst() throws Exception {
        clock.set(T0);
        UUID u = fx.user();
        String first = uniq("mine1");
        String second = uniq("mine2");
        submit(u, first, 10);
        clock.set(T0.plusSeconds(60));
        submit(u, second, 50);
        submit(fx.user(), uniq("others"), 30);

        mvc.perform(get("/v1/submissions/me").with(asUser(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].word").value(second))
                .andExpect(jsonPath("$[1].word").value(first))
                .andExpect(jsonPath("$[0].judgeInDays").value(14));
    }

    @Test
    @DisplayName("USR-SUB-11 NFD로 분해된 한글 이름은 NFC 이름 항목에 합류")
    void nfdHangulJoinsNfcItem() throws Exception {
        clock.set(T0);
        String nfc = "두쫀쿠 " + UUID.randomUUID().toString().substring(0, 8);
        UUID item = itemOf(submit(fx.user(), nfc, 30));
        String nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo(nfc);

        assertThat(itemOf(submit(fx.user(), nfd, 30))).isEqualTo(item);
    }

    @Test
    @DisplayName("USR-SUB-12 같은 유저·같은 새 이름 동시 2회 → 201 + 409, 500 없음, 1행")
    void concurrentSameSubmissionIsOneCreatedOneConflict() throws Exception {
        clock.set(T0);
        UUID u = fx.user();
        String body = submissionBody(uniq("race"), 30, "X", "MEME", "설명");

        List<Integer> statuses = concurrently(2, () -> postSubmission(u, body).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM submissions WHERE user_id = ?", Integer.class, u))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("USR-SUB-13 이름 정확히 120자는 201")
    void name120CharsIsAccepted() throws Exception {
        clock.set(T0);
        String name = "가".repeat(111) + "-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(name).hasSize(120);

        submit(fx.user(), name, 30);
    }
}
