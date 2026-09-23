package kr.trendstage.sla;

import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.scheduler.SlaWatchJob;
import kr.trendstage.scheduler.SlaWatchService;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스펙 §10 #1·#2·#16·#17 + Review Focus 5 + 최종 리뷰 R9~R11. 시계는 실제 현재 근처만 쓴다(Global Constraints). */
class SlaWatchTest extends AbstractIntegrationTest {

    @Autowired SlaWatchService sla;
    @Autowired SlaWatchJob job;

    @Test
    void overdueOpenReportIsAutoHiddenOnce() {   // #1
        Instant now = now();
        UUID item = fx.item(now.minus(Duration.ofDays(2)));
        UUID overdue = fx.report(item, "OPEN", now.minus(Duration.ofHours(4)).minusSeconds(1));
        UUID fresh = fx.report(fx.item(now.minus(Duration.ofDays(2))), "OPEN", now.minus(Duration.ofMinutes(239)));

        job.autoHideOverdueReports(now);
        job.autoHideOverdueReports(now);

        assertThat(fx.visibility(item)).isEqualTo("TEMP_HIDDEN");
        assertThat(reportStatus(overdue)).isEqualTo("OPEN");
        assertThat(autoHiddenAt(overdue)).isNotNull();
        assertThat(fx.auditCount("SLA_AUTO_HIDE", overdue)).isEqualTo(1);
        assertThat(autoHiddenAt(fresh)).isNull();
    }

    @Test
    void autoHideLeavesNonPublicItemsAlone() {   // Review Focus 5
        Instant now = now();
        UUID item = fx.item(now.minus(Duration.ofDays(2)));
        fx.setVisibility(item, "PERMANENT_HIDDEN");
        UUID report = fx.report(item, "OPEN", now.minus(Duration.ofHours(5)));

        job.autoHideOverdueReports(now);

        assertThat(fx.visibility(item)).isEqualTo("PERMANENT_HIDDEN");
        assertThat(autoHiddenAt(report)).isNotNull();
    }

    @Test
    void jobRunsAutoHide() {
        Instant now = now();
        clock.set(now);
        UUID item = fx.item(now.minus(Duration.ofDays(2)));
        fx.report(item, "OPEN", now.minus(Duration.ofHours(6)));
        releaseBatchLock("sla_watch");
        job.run();
        assertThat(fx.visibility(item)).isEqualTo("TEMP_HIDDEN");
    }

    @Test
    void openReportCanBeRestoredButNotPermanentlyHidden() throws Exception {   // #2
        Instant now = now();
        clock.set(now);
        UUID item = fx.item(now.minus(Duration.ofDays(2)));
        UUID report = fx.report(item, "OPEN", now.minus(Duration.ofHours(5)));
        job.autoHideOverdueReports(now);
        UUID operator = fx.admin("OPERATOR");

        mvc.perform(post("/admin/reports/{id}/decide", report).with(asAdmin(operator, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"HIDE_PERMANENT\",\"note\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/admin/reports/{id}/decide", report).with(asAdmin(operator, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"RESTORE\",\"note\":\"오신고\"}"))
                .andExpect(status().isOk());
        assertThat(fx.visibility(item)).isEqualTo("PUBLIC");
        assertThat(reportStatus(report)).isEqualTo("DECIDED");
    }

    @Test
    void stalledMergeExtendsDeadlineWithinCeiling() {   // #16
        Instant now = now();
        Instant firstSeen = now.minus(Duration.ofDays(13)).minus(Duration.ofHours(12));   // 기본 마감 = now + 12h
        UUID newItem = fx.item(firstSeen);
        UUID oldItem = fx.item(firstSeen.minus(Duration.ofHours(1)));
        UUID queueId = fx.mergeQueueEntry(newItem, oldItem, 0.80);
        jdbc.update("UPDATE merge_queue SET created_at = ? WHERE id = ?", Timestamp.from(now.minus(Duration.ofHours(25))), queueId);

        job.extendStalledMerges(now);
        assertThat(fx.deadlineOverride(newItem)).isEqualTo(now.plus(Duration.ofHours(24)));

        // 매시간 반복해도 상한(최초 제보 + 21일)을 넘지 않는다
        Instant later = now;
        for (int i = 0; i < 24 * 9; i++) {
            later = later.plus(Duration.ofHours(1));
            job.extendStalledMerges(later);
        }
        assertThat(fx.deadlineOverride(newItem)).isEqualTo(firstSeen.plus(Duration.ofDays(21)));
        assertThat(fx.auditCount("SLA_GRACE_EXTEND", newItem)).isGreaterThan(1);
    }

    @Test
    void closedItemReturnsToPendingAndJudgedItemIsUntouched() {   // #16
        Instant now = now();
        UUID closed = fx.item(now.minus(Duration.ofDays(15)));   // 마감 지남
        fx.setState(closed, "JUDGING");
        UUID judged = fx.item(now.minus(Duration.ofDays(15)));
        fx.setState(judged, "RESOLVED");
        fx.verdictRow(judged);
        UUID queueId = fx.mergeQueueEntry(closed, judged, 0.80);
        jdbc.update("UPDATE merge_queue SET created_at = ? WHERE id = ?", Timestamp.from(now.minus(Duration.ofHours(30))), queueId);

        job.extendStalledMerges(now);

        assertThat(fx.itemState(closed)).isEqualTo("PENDING");
        assertThat(fx.deadlineOverride(closed)).isEqualTo(now.plus(Duration.ofHours(24)));
        assertThat(fx.deadlineOverride(judged)).isNull();
    }

    @Test
    void inactiveAdminIsDisabled() {   // #17
        Instant now = now();
        UUID stale = fx.admin("OPERATOR");
        jdbc.update("UPDATE admin_accounts SET created_at = ?, activated_at = ?, last_login_at = ? WHERE id = ?",
                Timestamp.from(now.minus(Duration.ofDays(200))), Timestamp.from(now.minus(Duration.ofDays(200))),
                Timestamp.from(now.minus(Duration.ofDays(91))), stale);
        UUID recent = fx.admin("OPERATOR");

        job.disableInactiveAdmins(now);

        assertThat(disabled(stale)).isTrue();
        assertThat(disabled(recent)).isFalse();
        assertThat(fx.auditCount("SLA_ADMIN_DISABLE", stale)).isEqualTo(1);
    }

    @Test
    void reenabledAdminIsNotDisabledAgain() throws Exception {   // R10
        Instant now = now();
        UUID account = fx.admin("OPERATOR");
        jdbc.update("UPDATE admin_accounts SET created_at = ?, activated_at = ?, last_login_at = ?, disabled_at = ? WHERE id = ?",
                Timestamp.from(now.minus(Duration.ofDays(200))), Timestamp.from(now.minus(Duration.ofDays(200))),
                Timestamp.from(now.minus(Duration.ofDays(100))), Timestamp.from(now.minus(Duration.ofDays(5))), account);
        clock.set(now.minus(Duration.ofDays(1)));
        mvc.perform(post("/admin/accounts/{id}/enable", account).with(asAdmin(fx.admin(), AdminRole.ADMIN)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT last_enabled_at FROM admin_accounts WHERE id = ?", Instant.class, account))
                .isEqualTo(now.minus(Duration.ofDays(1)));

        job.disableInactiveAdmins(now);

        assertThat(disabled(account)).isFalse();
        assertThat(fx.auditCount("SLA_ADMIN_DISABLE", account)).isZero();
    }

    @Test
    void lateActivatedAccountIsNotDisabled() {   // R10 — 생성은 오래전, 승인(활성화)은 어제
        Instant now = now();
        UUID account = fx.admin("OPERATOR");
        jdbc.update("UPDATE admin_accounts SET created_at = ?, activated_at = ?, last_login_at = NULL WHERE id = ?",
                Timestamp.from(now.minus(Duration.ofDays(200))), Timestamp.from(now.minus(Duration.ofDays(1))), account);

        job.disableInactiveAdmins(now);

        assertThat(disabled(account)).isFalse();
    }

    @Test
    void openRestoreIsBlockedOnPermanentlyHiddenItem() throws Exception {   // R9
        Instant now = now();
        clock.set(now);
        UUID item = fx.item(now.minus(Duration.ofDays(2)));
        fx.setVisibility(item, "PERMANENT_HIDDEN");
        UUID report = fx.report(item, "OPEN", now.minus(Duration.ofHours(1)));

        mvc.perform(post("/admin/reports/{id}/decide", report).with(asAdmin(fx.admin("OPERATOR"), AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"RESTORE\",\"note\":\"오신고\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("report-restore-blocked"));
        assertThat(fx.visibility(item)).isEqualTo("PERMANENT_HIDDEN");
        assertThat(reportStatus(report)).isEqualTo("OPEN");
    }

    @Test
    void openRestoreIsBlockedWhileAnotherReportIsExplaining() throws Exception {   // R9
        Instant now = now();
        clock.set(now);
        UUID item = fx.item(now.minus(Duration.ofDays(2)));
        fx.setVisibility(item, "TEMP_HIDDEN");
        UUID explaining = fx.report(item, "EXPLAINING", now.minus(Duration.ofHours(10)));
        UUID open = fx.report(item, "OPEN", now.minus(Duration.ofHours(1)));
        UUID operator = fx.admin("OPERATOR");

        mvc.perform(post("/admin/reports/{id}/decide", open).with(asAdmin(operator, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"RESTORE\",\"note\":\"오신고\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("report-restore-blocked"));
        assertThat(fx.visibility(item)).isEqualTo("TEMP_HIDDEN");
        assertThat(reportStatus(open)).isEqualTo("OPEN");

        // 소명 중인 신고 자체의 RESTORE는 그대로 허용
        mvc.perform(post("/admin/reports/{id}/decide", explaining).with(asAdmin(operator, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"RESTORE\",\"note\":\"소명 인정\"}"))
                .andExpect(status().isOk());
        assertThat(fx.visibility(item)).isEqualTo("PUBLIC");
    }

    @Test
    void autoHideRechecksReportUnderLock() {   // R11 — 후보 조회 뒤 사람이 먼저 결정했으면 건드리지 않는다
        Instant now = now();
        UUID item = fx.item(now.minus(Duration.ofDays(2)));
        UUID report = fx.report(item, "OPEN", now.minus(Duration.ofHours(5)));
        assertThat(sla.overdueReportIds(now)).contains(report);
        jdbc.update("UPDATE reports SET status = 'DECIDED' WHERE id = ?", report);

        assertThat(sla.autoHideReport(report, now)).isFalse();
        assertThat(fx.visibility(item)).isEqualTo("PUBLIC");
        assertThat(autoHiddenAt(report)).isNull();
        assertThat(fx.auditCount("SLA_AUTO_HIDE", report)).isZero();
    }

    @Test
    void mergeExtensionRechecksQueueEntryUnderLock() {   // R11
        Instant now = now();
        Instant firstSeen = now.minus(Duration.ofDays(13));
        UUID newItem = fx.item(firstSeen);
        UUID oldItem = fx.item(firstSeen.minus(Duration.ofHours(1)));
        UUID queueId = fx.mergeQueueEntry(newItem, oldItem, 0.80);
        jdbc.update("UPDATE merge_queue SET created_at = ? WHERE id = ?", Timestamp.from(now.minus(Duration.ofHours(25))), queueId);
        assertThat(sla.stalledMergeIds(now)).contains(queueId);
        jdbc.update("UPDATE merge_queue SET status = 'SKIPPED' WHERE id = ?", queueId);

        assertThat(sla.extendStalledMerge(queueId, now)).isFalse();
        assertThat(fx.deadlineOverride(newItem)).isNull();
    }

    private Boolean disabled(UUID account) {
        return jdbc.queryForObject("SELECT disabled_at IS NOT NULL FROM admin_accounts WHERE id = ?", Boolean.class, account);
    }

    /** PostgreSQL timestamptz는 마이크로초까지 — 비교가 어긋나지 않게 잘라 둔다. */
    private static Instant now() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private String reportStatus(UUID report) {
        return jdbc.queryForObject("SELECT status::text FROM reports WHERE id = ?", String.class, report);
    }

    private Instant autoHiddenAt(UUID report) {
        Timestamp t = jdbc.queryForObject("SELECT auto_hidden_at FROM reports WHERE id = ?", Timestamp.class, report);
        return t == null ? null : t.toInstant();
    }
}
