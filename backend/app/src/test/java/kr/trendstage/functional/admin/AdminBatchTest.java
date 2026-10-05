package kr.trendstage.functional.admin;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminBatchTest extends FunctionalTestBase {

    private static final String RUN = "/admin/batch-jobs/cluster-merge/run";

    private int triggerAudits() {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_log WHERE action = 'CLUSTER_MERGE_MANUAL_TRIGGER'",
                Integer.class);
    }

    @Test
    @DisplayName("ADM-BAT-01 임베딩 서비스 없이 수동 실행 → 200, 병합·큐·분리 0, 내 후보는 merge_checked_at NULL 유지, 감사 1행")
    void runWithoutEmbeddingServiceFailsSoftly() throws Exception {
        UUID candidate = fx.item(clock.instant());
        int before = triggerAudits();

        String json = body(mvc.perform(post(RUN).with(asAdmin(fx.admin("OPERATOR"), AdminRole.OPERATOR)))
                .andExpect(status().isOk()));

        assertThat((Integer) read(json, "$.autoMerged")).isZero();
        assertThat((Integer) read(json, "$.queued")).isZero();
        assertThat((Integer) read(json, "$.separated")).isZero();
        assertThat((Integer) read(json, "$.failed")).isPositive();
        assertThat(fx.mergeCheckedAt(candidate)).isNull();
        assertThat(triggerAudits()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("ADM-BAT-02 잠금이 잡혀 있으면(실행 중) 409, 감사 행 증가 없음")
    void runWhileLockedIs409() throws Exception {
        jdbc.update("INSERT INTO shedlock (name, lock_until, locked_at, locked_by) "
                + "VALUES ('cluster_merge', now() + interval '10 minutes', now(), 'test') "
                + "ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until");
        try {
            int before = triggerAudits();
            mvc.perform(post(RUN).with(asAdmin(fx.admin("OPERATOR"), AdminRole.OPERATOR))).andExpect(status().isConflict());
            assertThat(triggerAudits()).isEqualTo(before);
        } finally {
            releaseBatchLock("cluster_merge");
        }
    }

    @Test
    @DisplayName("ADM-BAT-03 REVIEWER·AUDITOR 실행은 403")
    void runRoles() throws Exception {
        for (AdminRole role : new AdminRole[]{AdminRole.REVIEWER, AdminRole.AUDITOR}) {
            mvc.perform(post(RUN).with(asAdmin(fx.admin(role.name()), role))).andExpect(status().isForbidden());
        }
    }
}
