package kr.trendstage.functional.admin;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminTrendItemTest extends FunctionalTestBase {

    @Test
    @DisplayName("ADM-ITM-01 항목 목록: 내 항목(MERGED 포함)이 submitterCount(시딩·VOID 제외)·currentResult와 함께")
    void listIncludesMyItems() throws Exception {
        clock.set(T0);
        UUID item = fx.item(T0);
        fx.submission(fx.user(), item, 30, T0);
        fx.submission(fx.user(), item, 30, T0.plusSeconds(60));
        fx.seedSubmission(fx.user(), item, T0.plusSeconds(90));
        UUID voided = fx.submission(fx.user(), item, 30, T0.plusSeconds(120));
        fx.voidSubmission(voided, T0.plusSeconds(150));
        UUID merged = fx.item(T0);
        fx.merged(merged, item);

        String json = getOk("/admin/trend-items", asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER));

        List<Map<String, Object>> mine = read(json, "$[?(@.id == '" + item + "')]");
        assertThat(mine).singleElement().satisfies(r -> assertThat(r.get("submitterCount")).isEqualTo(2));
        List<Map<String, Object>> tomb = read(json, "$[?(@.id == '" + merged + "')]");
        assertThat(tomb).singleElement().satisfies(r -> assertThat(r.get("state")).isEqualTo("MERGED"));
    }

    @Test
    @DisplayName("ADM-ITM-02 미판정 항목 상세: deadline·daysLeft·제보자 수·플랫폼 수(시딩 제외)·인정 수, preview 채워짐, 1위 순위")
    void pendingDetail() throws Exception {
        clock.set(T0);
        UUID item = fx.item(T0);
        UUID first = fx.user();
        fx.submission(first, item, 30, T0);
        fx.submission(fx.user(), item, 30, T0.plusSeconds(60));
        fx.seedSubmission(fx.user(), item, T0.plusSeconds(90));

        String json = getOk("/admin/trend-items/{id}", asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER), item);

        assertThat((String) read(json, "$.deadline")).isNotNull();
        assertThat(((Number) read(json, "$.daysLeft")).longValue()).isEqualTo(14);
        assertThat((Integer) read(json, "$.distinctSubmitters")).isEqualTo(2);
        assertThat((Integer) read(json, "$.distinctPlatforms")).isEqualTo(1);
        assertThat(((Number) read(json, "$.endorseCount")).longValue()).isZero();
        assertThat((String) read(json, "$.previewResult")).isEqualTo("MISS");
        assertThat((String) read(json, "$.currentResult")).isNull();
        List<Map<String, Object>> rows = read(json, "$.submissions[?(@.userHandle == '" + fx.handle(first) + "')]");
        assertThat(rows).singleElement().satisfies(r -> assertThat(r.get("orderRank")).isEqualTo(1));
    }

    @Test
    @DisplayName("ADM-ITM-03 판정된 항목 상세: current 채워짐, preview 없음")
    void judgedDetail() throws Exception {
        clock.set(T0);
        UUID item = fx.item(T0);
        fx.submission(fx.user(), item, 30, T0);
        fx.verdictRow(item);
        fx.setState(item, "RESOLVED");

        String json = getOk("/admin/trend-items/{id}", asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER), item);

        assertThat((String) read(json, "$.currentResult")).isEqualTo("MISS");
        assertThat((String) read(json, "$.previewResult")).isNull();
    }

    @Test
    @DisplayName("ADM-ITM-04 없는 항목 상세는 422")
    void unknownItemIs422() throws Exception {
        mvc.perform(get("/admin/trend-items/{id}", UUID.randomUUID()).with(asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER)))
                .andExpect(status().isUnprocessableEntity());
    }
}
