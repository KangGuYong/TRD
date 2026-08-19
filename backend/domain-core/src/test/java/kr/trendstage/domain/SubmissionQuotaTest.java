package kr.trendstage.domain;

import kr.trendstage.domain.grade.Grade;
import kr.trendstage.domain.grade.SubmissionQuota;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubmissionQuotaTest {

    @Test void 등급별_주간_한도는_CLAUDE_md_표를_따른다() {
        assertEquals(2, SubmissionQuota.weeklyLimit(Grade.L0));
        assertEquals(3, SubmissionQuota.weeklyLimit(Grade.L1));
        assertEquals(5, SubmissionQuota.weeklyLimit(Grade.L2));
        assertEquals(8, SubmissionQuota.weeklyLimit(Grade.L3));
        assertEquals(12, SubmissionQuota.weeklyLimit(Grade.L4));
    }
}
