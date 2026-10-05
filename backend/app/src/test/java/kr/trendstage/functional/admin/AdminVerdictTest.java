package kr.trendstage.functional.admin;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SuppressWarnings("unchecked")
class AdminVerdictTest extends FunctionalTestBase {

    private ResultActions extend(UUID item, String body) throws Exception {
        return postJson("/admin/verdicts/{id}/extend-grace", asAdmin(fx.admin("OPERATOR"), AdminRole.OPERATOR), body, item);
    }

    @Test
    @DisplayName("ADM-VRD-01 판정 목록: 판정된 내 항목은 judged, 마감 임박 내 항목은 imminent")
    void listJudgedAndImminent() throws Exception {
        // 시계를 다른 테스트 데이터(2026-07 이후)보다 앞에 둬서 내 항목이 '가장 임박'하게 — imminent는 최대 30개
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        clock.set(now);
        UUID judged = fx.item(now.minus(Duration.ofDays(20)));
        fx.verdictRow(judged);
        fx.setState(judged, "RESOLVED");
        UUID imminent = fx.item(now.minus(Duration.ofDays(14)).plus(Duration.ofHours(1)));

        String json = getOk("/admin/verdicts", asAdmin(fx.admin("OPERATOR"), AdminRole.OPERATOR));

        assertThat((List<String>) read(json, "$.judged[*].trendItemId")).contains(judged.toString());
        assertThat((List<String>) read(json, "$.imminent[*].trendItemId")).contains(imminent.toString());
    }

    @Test
    @DisplayName("ADM-VRD-02 판정 목록 역할: OPERATOR·ADMIN·AUDITOR 200, REVIEWER 403")
    void listRoles() throws Exception {
        for (AdminRole role : new AdminRole[]{AdminRole.OPERATOR, AdminRole.ADMIN, AdminRole.AUDITOR}) {
            mvc.perform(get("/admin/verdicts").with(asAdmin(fx.admin(role.name()), role))).andExpect(status().isOk());
        }
        mvc.perform(get("/admin/verdicts").with(asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADM-VRD-03 PENDING 항목 3일 연장 → 마감 D+17, 감사 VERDICT_GRACE_EXTEND")
    void extendThreeDays() throws Exception {
        clock.set(T0);
        UUID item = fx.item(T0);

        extend(item, "{\"days\":3,\"reason\":\"병합 검수 지연\"}").andExpect(status().isOk());

        assertThat(fx.deadlineOverride(item)).isEqualTo(T0.plus(Duration.ofDays(17)));
        assertThat(fx.auditCount("VERDICT_GRACE_EXTEND", item)).isEqualTo(1);
    }

    @Test
    @DisplayName("ADM-VRD-04 누적 D+21 초과분은 잘려 D+21(200), 이미 D+21이면 422")
    void extendCapsAtD21() throws Exception {
        clock.set(T0);
        UUID item = fx.item(T0);
        fx.setDeadlineOverride(item, T0.plus(Duration.ofDays(20)));

        extend(item, "{\"days\":7,\"reason\":\"r\"}").andExpect(status().isOk());
        assertThat(fx.deadlineOverride(item)).isEqualTo(T0.plus(Duration.ofDays(21)));

        extend(item, "{\"days\":1,\"reason\":\"r\"}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("ADM-VRD-05 days 0·8, 판정된 항목, 없는 항목은 422 (1~7일)")
    void extendValidation() throws Exception {
        clock.set(T0);
        UUID item = fx.item(T0);
        extend(item, "{\"days\":0,\"reason\":\"r\"}").andExpect(status().isUnprocessableEntity());
        extend(item, "{\"days\":8,\"reason\":\"r\"}").andExpect(status().isUnprocessableEntity());

        UUID judged = fx.item(T0);
        fx.verdictRow(judged);
        extend(judged, "{\"days\":1,\"reason\":\"r\"}").andExpect(status().isUnprocessableEntity());
        extend(UUID.randomUUID(), "{\"days\":1,\"reason\":\"r\"}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("ADM-VRD-06 JUDGING 항목을 연장해 새 마감이 미래면 PENDING 복귀")
    void extendReopensJudgingItem() throws Exception {
        UUID item = fx.item(T0);
        clock.set(T0.plus(Duration.ofDays(14)).plus(Duration.ofHours(1)));
        fx.setState(item, "JUDGING");

        extend(item, "{\"days\":3,\"reason\":\"r\"}").andExpect(status().isOk());

        assertThat(fx.itemState(item)).isEqualTo("PENDING");
        assertThat(fx.deadlineOverride(item)).isEqualTo(T0.plus(Duration.ofDays(17)));
    }

    @Test
    @DisplayName("ADM-VRD-07 사유 없이 연장해도 200(서버가 사유를 요구하지 않음)")
    void extendWithoutReason() throws Exception {
        clock.set(T0);
        extend(fx.item(T0), "{\"days\":2}").andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADM-VRD-08 MERGED·VOID 항목 연장은 422, override 변화 없음 (D8)")
    void extendRejectsMergedAndVoid() throws Exception {
        clock.set(T0);
        UUID merged = fx.item(T0);
        fx.merged(merged, fx.item(T0));
        UUID voided = fx.item(T0);
        fx.setState(voided, "VOID");

        for (UUID item : List.of(merged, voided)) {
            extend(item, "{\"days\":1,\"reason\":\"r\"}").andExpect(status().isUnprocessableEntity());
            assertThat(fx.deadlineOverride(item)).isNull();
        }
    }

    @Test
    @DisplayName("ADM-VRD-09 연장 역할: REVIEWER·AUDITOR 403")
    void extendRoles() throws Exception {
        clock.set(T0);
        UUID item = fx.item(T0);
        for (AdminRole role : new AdminRole[]{AdminRole.REVIEWER, AdminRole.AUDITOR}) {
            postJson("/admin/verdicts/{id}/extend-grace", asAdmin(fx.admin(role.name()), role),
                    "{\"days\":1,\"reason\":\"r\"}", item).andExpect(status().isForbidden());
        }
    }
}
