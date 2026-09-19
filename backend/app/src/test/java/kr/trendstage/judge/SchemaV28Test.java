package kr.trendstage.judge;

import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.type.SubmissionResult;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V28 제약·뷰, 제보 엔티티의 VOID·판정 시각 매핑. */
class SchemaV28Test extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired SubmissionRepository submissions;
    @Autowired TransactionTemplate tx;

    @Test
    void voidSubmissionRequiresVoidedAt() {
        UUID s = fx.submission(fx.user(), fx.item(T0), 30, T0);
        assertThatThrownBy(() -> jdbc.update("UPDATE submissions SET result = 'VOID' WHERE id = ?", s))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void judgedSubmissionRequiresResolvedAt() {
        UUID s = fx.submission(fx.user(), fx.item(T0), 30, T0);
        assertThatThrownBy(() -> jdbc.update("UPDATE submissions SET result = 'HIT' WHERE id = ?", s))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void orderRankViewExcludesSeeds() {
        UUID item = fx.item(T0);
        UUID seed = fx.seedSubmission(fx.user(), item, T0);
        UUID first = fx.submission(fx.user(), item, 30, T0.plusSeconds(60));
        UUID second = fx.submission(fx.user(), item, 30, T0.plusSeconds(120));

        List<UUID> ids = jdbc.queryForList(
                "SELECT id FROM submission_order_rank WHERE trend_item_id = ? ORDER BY order_rank", UUID.class, item);
        List<Integer> ranks = jdbc.queryForList(
                "SELECT order_rank FROM submission_order_rank WHERE trend_item_id = ? ORDER BY order_rank", Integer.class, item);

        assertThat(ids).containsExactly(first, second).doesNotContain(seed);
        assertThat(ranks).containsExactly(1, 2);
    }

    @Test
    void ledgerAllowsOneRowPerVerdictAndSubmission() {
        UUID user = fx.user();
        UUID item = fx.item(T0);
        UUID s = fx.submission(user, item, 30, T0);
        UUID v = insertVerdict(item, null);
        insertLedger(user, s, v);
        assertThatThrownBy(() -> insertLedger(user, s, v)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void verdictChainCannotFork() {
        UUID item = fx.item(T0);
        UUID original = insertVerdict(item, null);
        insertVerdict(item, original);
        assertThatThrownBy(() -> insertVerdict(item, original)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ledgerRowRequiresDecayBasis() {
        UUID user = fx.user();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO score_ledger (user_id, kind, delta, reason) VALUES (?, 'ADJ', 1, '사유')", user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void submissionEntityRecordsVoidAndFirstResolveTime() {
        UUID a = fx.submission(fx.user(), fx.item(T0), 30, T0);
        UUID b = fx.submission(fx.user(), fx.item(T0), 30, T0);
        Instant at = Instant.parse("2026-09-20T00:00:00Z");

        tx.executeWithoutResult(status -> {
            submissions.findById(a).orElseThrow().voidOut(at);
            Submission sb = submissions.findById(b).orElseThrow();
            sb.markResult(SubmissionResult.HIT, at);
            sb.markResult(SubmissionResult.MISS, at.plusSeconds(3600)); // 재판정 — 처음 판정 시각 유지
        });

        assertThat(jdbc.queryForObject("SELECT voided_at FROM submissions WHERE id = ?", Timestamp.class, a).toInstant())
                .isEqualTo(at);
        assertThat(fx.submissionResult(b)).isEqualTo("MISS");
        assertThat(jdbc.queryForObject("SELECT resolved_at FROM submissions WHERE id = ?", Timestamp.class, b).toInstant())
                .isEqualTo(at);
    }

    /** VOID 판정으로 넣는다 — 근거가 비어 있어도 ADM-600 시뮬레이션(공유 DB 전체를 읽는다)이 VOID는 건너뛴다. */
    private UUID insertVerdict(UUID item, UUID supersedes) {
        return jdbc.queryForObject(
                "INSERT INTO verdicts (trend_item_id, result, score_t, judged_at, evidence_json, supersedes) "
                        + "VALUES (?, 'VOID', NULL, now(), '{}'::jsonb, ?) RETURNING id",
                UUID.class, item, supersedes);
    }

    private void insertLedger(UUID user, UUID submission, UUID verdict) {
        jdbc.update("INSERT INTO score_ledger (user_id, submission_id, verdict_id, kind, delta, reason, "
                + "halflife_days, decay_anchor_at) VALUES (?, ?, ?, 'MISS', -15, '근거', 90, now())",
                user, submission, verdict);
    }
}
