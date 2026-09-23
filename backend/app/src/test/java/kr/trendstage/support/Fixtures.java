package kr.trendstage.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 판정 파이프라인 통합 테스트 데이터. JDBC로 직접 넣어 created_at·first_seen_at을 과거로 정할 수 있다.
 * 모든 테스트 클래스가 DB 하나를 공유하므로 이름·키는 항상 랜덤이다.
 */
public class Fixtures {

    private final JdbcTemplate jdbc;

    public Fixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID user() {
        return jdbc.queryForObject("INSERT INTO users (handle) VALUES (?) RETURNING id", UUID.class, "u_" + rand());
    }

    /** PENDING 항목. 관측 마감 = firstSeenAt + 14일. */
    public UUID item(Instant firstSeenAt) {
        String key = "k_" + rand();
        return jdbc.queryForObject(
                "INSERT INTO trend_items (canonical_name, normalized_key, category, state, first_seen_at) "
                        + "VALUES (?, ?, 'MEME', 'PENDING', ?) RETURNING id",
                UUID.class, key, key, Timestamp.from(firstSeenAt));
    }

    public UUID submission(UUID userId, UUID itemId, int confidence, Instant createdAt) {
        return insertSubmission(userId, itemId, confidence, createdAt, false);
    }

    public UUID seedSubmission(UUID userId, UUID itemId, Instant createdAt) {
        return insertSubmission(userId, itemId, 30, createdAt, true);
    }

    public void voidSubmission(UUID submissionId, Instant at) {
        jdbc.update("UPDATE submissions SET result = 'VOID', voided_at = ? WHERE id = ?", Timestamp.from(at), submissionId);
    }

    public String submissionResult(UUID submissionId) {
        return jdbc.queryForObject("SELECT result::text FROM submissions WHERE id = ?", String.class, submissionId);
    }

    public String itemState(UUID itemId) {
        return jdbc.queryForObject("SELECT state::text FROM trend_items WHERE id = ?", String.class, itemId);
    }

    /** 이 항목의 판정 체인에 귀속된 원장의 유저별 합(ADJ 포함). */
    public BigDecimal ledgerSum(UUID userId, UUID itemId) {
        return jdbc.queryForObject(
                "SELECT coalesce(sum(l.delta), 0) FROM score_ledger l JOIN verdicts v ON v.id = l.verdict_id "
                        + "WHERE l.user_id = ? AND v.trend_item_id = ?",
                BigDecimal.class, userId, itemId);
    }

    public int ledgerRows(UUID itemId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM score_ledger l JOIN verdicts v ON v.id = l.verdict_id WHERE v.trend_item_id = ?",
                Integer.class, itemId);
    }

    /** 승인권이 있는(유예가 끝난) 활성 ADMIN. */
    public UUID admin() {
        return admin("ADMIN");
    }

    /** 승인권 유예가 끝난 활성 계정. role = REVIEWER|OPERATOR|ADMIN|AUDITOR. */
    public UUID admin(String role) {
        return jdbc.queryForObject("INSERT INTO admin_accounts (login_id, display_name, role, password_hash, "
                + "approver_since, activated_at) VALUES (?, '테스트 관리자', ?::admin_role, 'x', "
                + "TIMESTAMPTZ '2000-01-01 00:00:00+00', now()) RETURNING id", UUID.class, "a_" + rand(), role);
    }

    /** 승인권 유예 중인 활성 ADMIN — until까지 승인할 수 없다. */
    public UUID adminInGrace(Instant until) {
        return jdbc.queryForObject("INSERT INTO admin_accounts (login_id, display_name, role, password_hash, "
                + "approver_since, activated_at) VALUES (?, '유예 관리자', 'ADMIN', 'x', ?, now()) RETURNING id",
                UUID.class, "g_" + rand(), Timestamp.from(until));
    }

    /** 승인 대기(activated_at NULL) 계정. */
    public UUID pendingAdmin(String role) {
        return jdbc.queryForObject("INSERT INTO admin_accounts (login_id, display_name, role, password_hash, "
                + "approver_since) VALUES (?, '대기 관리자', ?::admin_role, 'x', now()) RETURNING id",
                UUID.class, "p_" + rand(), role);
    }

    /** 신고 한 건(신고자는 새 유저). status = OPEN|EXPLAINING|DECIDED. */
    public UUID report(UUID itemId, String status, Instant createdAt) {
        return jdbc.queryForObject("INSERT INTO reports (trend_item_id, reporter_id, reason, status, created_at) "
                + "VALUES (?, ?, 'OTHER', ?::report_status, ?) RETURNING id",
                UUID.class, itemId, user(), status, Timestamp.from(createdAt));
    }

    public String visibility(UUID itemId) {
        return jdbc.queryForObject("SELECT visibility::text FROM trend_items WHERE id = ?", String.class, itemId);
    }

    public void setVisibility(UUID itemId, String visibility) {
        jdbc.update("UPDATE trend_items SET visibility = ?::trend_visibility WHERE id = ?", visibility, itemId);
    }

    /** 적용된 파라미터 드래프트(가장 최근 APPLIED가 현재값). 테스트 끝에 반드시 {@link #deleteDraft}로 지운다 — 공유 DB. */
    public UUID appliedDraft(String payloadJson) {
        return jdbc.queryForObject("INSERT INTO parameter_drafts (author_id, status, payload, applied_at) "
                + "VALUES (?, 'APPLIED', ?::jsonb, now()) RETURNING id", UUID.class, admin(), payloadJson);
    }

    public void deleteDraft(UUID draftId) {
        jdbc.update("DELETE FROM parameter_drafts WHERE id = ?", draftId);
    }

    /** 이미 판정된 제보(항목 포함, 항목은 RESOLVED — 다른 테스트의 배치가 다시 판정하지 않게). TI·판정완료 건수 테스트용. */
    public UUID judgedSubmission(UUID userId, String result, Instant resolvedAt) {
        UUID item = item(resolvedAt.minus(java.time.Duration.ofDays(15)));
        UUID s = submission(userId, item, 30, resolvedAt.minus(java.time.Duration.ofDays(14)));
        jdbc.update("UPDATE submissions SET result = ?::submission_result, resolved_at = ? WHERE id = ?",
                result, Timestamp.from(resolvedAt), s);
        jdbc.update("UPDATE trend_items SET state = 'RESOLVED' WHERE id = ?", item);
        return s;
    }

    /** 판정과 무관한 원장 한 행(AS 테스트용). */
    public void ledgerRow(UUID userId, double delta, int halflifeDays, Instant anchor) {
        jdbc.update("INSERT INTO score_ledger (user_id, kind, delta, reason, halflife_days, decay_anchor_at) "
                + "VALUES (?, 'ADJ', ?, '테스트', ?, ?)", userId, delta, halflifeDays, Timestamp.from(anchor));
    }

    public void gradeSnapshot(UUID userId, String grade, Instant computedAt) {
        jdbc.update("INSERT INTO user_grades (user_id, grade, trust_index, active_score, judged_count, computed_at) "
                + "VALUES (?, ?::grade_level, 0.5, 40, 6, ?)", userId, grade, Timestamp.from(computedAt));
    }

    // ── 병합(SP2) ─────────────────────────────────────────────

    /** 처리 대기 병합 큐 행. */
    public UUID mergeQueueEntry(UUID newItem, UUID oldItem, double similarity) {
        return jdbc.queryForObject("INSERT INTO merge_queue (new_trend_item_id, old_trend_item_id, similarity) "
                + "VALUES (?, ?, ?) RETURNING id", UUID.class, newItem, oldItem, similarity);
    }

    public String queueStatus(UUID queueId) {
        return jdbc.queryForObject("SELECT status::text FROM merge_queue WHERE id = ?", String.class, queueId);
    }

    /** loser를 survivor로 병합된 tombstone으로 만든다(제보는 옮기지 않는다 — 상태만). */
    public void merged(UUID loser, UUID survivor) {
        jdbc.update("UPDATE trend_items SET state = 'MERGED', merged_into = ? WHERE id = ?", survivor, loser);
    }

    public void setState(UUID itemId, String state) {
        jdbc.update("UPDATE trend_items SET state = ?::trend_state WHERE id = ?", state, itemId);
    }

    /** 상태는 그대로 두고 현행 판정 행만 만든다(상태·판정 행 불일치 가드 테스트용). */
    public void verdictRow(UUID itemId) {
        jdbc.update("INSERT INTO verdicts (trend_item_id, result, score_t, judged_at, evidence_json) "
                + "VALUES (?, 'MISS', 0.1, now(), '{}'::jsonb)", itemId);
    }

    public String key(UUID itemId) {
        return jdbc.queryForObject("SELECT normalized_key FROM trend_items WHERE id = ?", String.class, itemId);
    }

    public UUID itemOf(UUID submissionId) {
        return jdbc.queryForObject("SELECT trend_item_id FROM submissions WHERE id = ?", UUID.class, submissionId);
    }

    /** 선점 순위(뷰). 순위가 없으면(시딩·VOID) null. */
    public Integer orderRank(UUID submissionId) {
        List<Integer> r = jdbc.queryForList("SELECT order_rank FROM submission_order_rank WHERE id = ?",
                Integer.class, submissionId);
        return r.isEmpty() ? null : r.get(0);
    }

    public Instant deadlineOverride(UUID itemId) {
        Timestamp t = jdbc.queryForObject("SELECT judgment_deadline_override FROM trend_items WHERE id = ?",
                Timestamp.class, itemId);
        return t == null ? null : t.toInstant();
    }

    public void setDeadlineOverride(UUID itemId, Instant at) {
        jdbc.update("UPDATE trend_items SET judgment_deadline_override = ? WHERE id = ?", Timestamp.from(at), itemId);
    }

    public Instant voidedAt(UUID submissionId) {
        Timestamp t = jdbc.queryForObject("SELECT voided_at FROM submissions WHERE id = ?", Timestamp.class, submissionId);
        return t == null ? null : t.toInstant();
    }

    public Instant mergeCheckedAt(UUID itemId) {
        Timestamp t = jdbc.queryForObject("SELECT merge_checked_at FROM trend_items WHERE id = ?", Timestamp.class, itemId);
        return t == null ? null : t.toInstant();
    }

    public int auditCount(String action, UUID targetId) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_log WHERE action = ? AND target_id = ?",
                Integer.class, action, targetId);
    }

    private UUID insertSubmission(UUID userId, UUID itemId, int confidence, Instant createdAt, boolean seed) {
        String key = jdbc.queryForObject("SELECT normalized_key FROM trend_items WHERE id = ?", String.class, itemId);
        return jdbc.queryForObject(
                "INSERT INTO submissions (user_id, trend_item_id, raw_input, normalized_key, confidence, "
                        + "source_platform, evidence_url, one_line, created_at, is_seed) "
                        + "VALUES (?, ?, ?, ?, ?, 'X', 'https://example.com', '설명', ?, ?) RETURNING id",
                UUID.class, userId, itemId, key, key, confidence, Timestamp.from(createdAt), seed);
    }

    static String rand() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
