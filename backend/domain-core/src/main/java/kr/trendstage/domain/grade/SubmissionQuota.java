package kr.trendstage.domain.grade;

import java.util.Map;

/** 등급별 주간 제보권 한도. 01 §? 표 그대로 — 이월 없음, 매주 월요일 00:00 KST 리필(배치는 별도). */
public final class SubmissionQuota {
    private SubmissionQuota() {}

    private static final Map<Grade, Integer> WEEKLY_LIMIT = Map.of(
            Grade.L0, 2, Grade.L1, 3, Grade.L2, 5, Grade.L3, 8, Grade.L4, 12
    );

    public static int weeklyLimit(Grade grade) {
        return WEEKLY_LIMIT.get(grade);
    }
}
