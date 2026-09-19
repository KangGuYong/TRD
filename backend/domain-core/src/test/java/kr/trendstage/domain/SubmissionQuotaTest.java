package kr.trendstage.domain;

import kr.trendstage.domain.grade.Grade;
import kr.trendstage.domain.grade.SubmissionQuota;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubmissionQuotaTest {

    @Test void 등급별_주간_한도는_CLAUDE_md_표를_따른다() {
        assertEquals(2, SubmissionQuota.weeklyLimit(Grade.L0));
        assertEquals(3, SubmissionQuota.weeklyLimit(Grade.L1));
        assertEquals(5, SubmissionQuota.weeklyLimit(Grade.L2));
        assertEquals(8, SubmissionQuota.weeklyLimit(Grade.L3));
        assertEquals(12, SubmissionQuota.weeklyLimit(Grade.L4));
    }

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Test void 주_시작은_월요일_0시_KST다() {
        Instant sundayLate = ZonedDateTime.of(2026, 9, 20, 23, 59, 59, 0, KST).toInstant();   // 일요일
        Instant mondayStart = ZonedDateTime.of(2026, 9, 21, 0, 0, 0, 0, KST).toInstant();
        assertEquals(ZonedDateTime.of(2026, 9, 14, 0, 0, 0, 0, KST).toInstant(), SubmissionQuota.weekStart(sundayLate));
        assertEquals(mondayStart, SubmissionQuota.weekStart(mondayStart));
    }

    @Test void 사용량은_이번주_제출에서_이번주_반환을_뺀다() {
        assertEquals(2, SubmissionQuota.used(2, 0));
        assertEquals(1, SubmissionQuota.used(2, 1));   // 이번 주에 낸 것 하나가 VOID
        assertEquals(0, SubmissionQuota.used(0, 1));   // 지난주 제보가 이번 주 VOID — 음수가 되지 않는다
    }

    @Test void 남은_제보권은_한도를_넘지_않는다() {
        assertEquals(2, SubmissionQuota.remaining(Grade.L0, 0));
        assertEquals(0, SubmissionQuota.remaining(Grade.L0, 2));
        assertEquals(0, SubmissionQuota.remaining(Grade.L0, 5));
        assertEquals(1, SubmissionQuota.remaining(Grade.L1, 2));
    }
}
