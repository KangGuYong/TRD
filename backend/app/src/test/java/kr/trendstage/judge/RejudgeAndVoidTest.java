package kr.trendstage.judge;

import kr.trendstage.domain.verdict.VerdictResult;
import kr.trendstage.persistence.entity.Verdict;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 스펙 §7.2 8~10: 재판정 차액(제보 단위·원 판정 기준), 동결 파라미터(J2), 항목 VOID. */
class RejudgeAndVoidTest extends AbstractIntegrationTest {

    private static final Instant FIRST_SEEN = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant JUDGED_AT = FIRST_SEEN.plus(Duration.ofDays(15));

    @Autowired JudgeService judge;

    @Test
    void rejudgeWritesPerSubmissionDiffAndSecondRejudgeAddsNothing() {   // #8
        UUID item = fx.item(FIRST_SEEN);
        UUID seedUser = fx.user();
        fx.seedSubmission(seedUser, item, FIRST_SEEN);
        List<UUID> users = new ArrayList<>();
        List<UUID> subs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID u = fx.user();
            users.add(u);
            subs.add(fx.submission(u, item, 30, FIRST_SEEN.plusSeconds(10 + i)));
        }
        judgeAfterDeadline(item);   // 실유저 4명 → HIT L1

        // 카르텔로 드러난 2건 VOID → 2명 → MISS
        fx.voidSubmission(subs.get(2), JUDGED_AT.plusSeconds(10));
        fx.voidSubmission(subs.get(3), JUDGED_AT.plusSeconds(10));
        judge.rejudge(item, "카르텔", JUDGED_AT.plus(Duration.ofDays(1)));

        assertThat(fx.ledgerSum(users.get(0), item)).isEqualByComparingTo("-15");   // 36 − 51
        assertThat(fx.ledgerSum(users.get(1), item)).isEqualByComparingTo("-15");   // 21.6 − 36.6
        assertThat(fx.ledgerSum(users.get(2), item)).isEqualByComparingTo("0");
        assertThat(fx.ledgerSum(users.get(3), item)).isEqualByComparingTo("0");
        assertThat(fx.ledgerSum(seedUser, item)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM score_ledger WHERE user_id = ?", Integer.class, seedUser)).isZero();
        // ADJ는 원 판정의 감쇠 기준을 쓴다
        assertThat(jdbc.queryForObject("SELECT count(*) FROM score_ledger l JOIN verdicts v ON v.id = l.verdict_id "
                + "WHERE v.trend_item_id = ? AND l.kind = 'ADJ' AND (l.decay_anchor_at <> ? OR l.halflife_days <> 90)",
                Integer.class, item, Timestamp.from(JUDGED_AT))).isZero();

        int rowsAfterFirst = fx.ledgerRows(item);
        judge.rejudge(item, "재확인", JUDGED_AT.plus(Duration.ofDays(2)));
        assertThat(fx.ledgerRows(item)).isEqualTo(rowsAfterFirst);   // 차액 0 — 두 번째 재판정 이중 반영 회귀
        assertThat(fx.ledgerSum(users.get(0), item)).isEqualByComparingTo("-15");
    }

    @Test
    void rejudgeUsesParametersFrozenAtOriginalJudgment() {   // #9, J2
        UUID item = fx.item(FIRST_SEEN);
        for (int i = 0; i < 3; i++) fx.submission(fx.user(), item, 30, FIRST_SEEN.plusSeconds(i));
        judgeAfterDeadline(item);   // 3명 → T = 0.15 → MISS

        UUID draft = fx.appliedDraft("{\"submitterTarget\":20,\"hitThreshold\":0.10}");   // 지금 적용값이면 HIT
        try {
            Verdict next = judge.rejudge(item, "입력 변화 없음", JUDGED_AT.plus(Duration.ofDays(1)));
            assertThat(next.getResult()).isEqualTo(VerdictResult.MISS);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM score_ledger l JOIN verdicts v ON v.id = l.verdict_id "
                    + "WHERE v.trend_item_id = ? AND l.kind = 'ADJ'", Integer.class, item)).isZero();
        } finally {
            fx.deleteDraft(draft);
        }
    }

    @Test
    void voidingJudgedItemZeroesLedgerAndVoidsSubmissions() {   // #10
        UUID item = fx.item(FIRST_SEEN);
        List<UUID> users = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID u = fx.user();
            users.add(u);
            fx.submission(u, item, 30, FIRST_SEEN.plusSeconds(i));
        }
        judgeAfterDeadline(item);
        Instant voidAt = JUDGED_AT.plus(Duration.ofDays(1));

        assertThat(judge.voidItem(item, "허위 제보", voidAt)).isPresent();

        users.forEach(u -> assertThat(fx.ledgerSum(u, item)).isEqualByComparingTo("0"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM submissions WHERE trend_item_id = ? "
                + "AND (result <> 'VOID' OR voided_at <> ?)", Integer.class, item, Timestamp.from(voidAt))).isZero();
        assertThat(fx.itemState(item)).isEqualTo("VOID");
        assertThatThrownBy(() -> judge.voidItem(item, "다시", voidAt)).isInstanceOf(JudgeConflictException.class);
        assertThatThrownBy(() -> judge.rejudge(item, "다시", voidAt)).isInstanceOf(JudgeConflictException.class);
    }

    @Test
    void voidingPendingItemOnlyVoidsSubmissions() {   // ADM-100 큐 VOID 경로
        UUID item = fx.item(FIRST_SEEN);
        UUID s = fx.submission(fx.user(), item, 30, FIRST_SEEN.plusSeconds(1));

        assertThat(judge.voidItem(item, null, FIRST_SEEN.plus(Duration.ofDays(1)))).isEmpty();

        assertThat(fx.itemState(item)).isEqualTo("VOID");
        assertThat(fx.submissionResult(s)).isEqualTo("VOID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM verdicts WHERE trend_item_id = ?", Integer.class, item)).isZero();
    }

    private void judgeAfterDeadline(UUID item) {
        judge.closeDue(JUDGED_AT);
        judge.judge(item, JUDGED_AT);
    }
}
