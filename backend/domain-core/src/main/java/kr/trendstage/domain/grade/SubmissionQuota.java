package kr.trendstage.domain.grade;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

/**
 * 제보권(01 §3.3). 등급별 주간 한도, 이월 없음. 저장 카운터 없이 제보 행에서 파생한다(J4):
 * 이번 주 사용 = 이번 주 제출 − 이번 주 VOID 반환. 리필은 주 경계(월 00:00 KST) 자체라 배치가 없다.
 */
public final class SubmissionQuota {
    private SubmissionQuota() {}

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final Map<Grade, Integer> WEEKLY_LIMIT = Map.of(
            Grade.L0, 2, Grade.L1, 3, Grade.L2, 5, Grade.L3, 8, Grade.L4, 12
    );

    public static int weeklyLimit(Grade grade) {
        return WEEKLY_LIMIT.get(grade);
    }

    /** 제보권 주의 시작 — 그 주 월요일 00:00 KST. */
    public static Instant weekStart(Instant now) {
        return now.atZone(KST).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(KST).toInstant();
    }

    /** 이번 주 사용량 = 이번 주 제출 − 이번 주 VOID 반환(지난주에 낸 것 포함). 0 미만이면 0. */
    public static int used(int submittedThisWeek, int refundedThisWeek) {
        return Math.max(0, submittedThisWeek - refundedThisWeek);
    }

    public static int remaining(Grade grade, int used) {
        return Math.max(0, weeklyLimit(grade) - used);
    }
}
