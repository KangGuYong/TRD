package kr.trendstage.apipublic.service;

import kr.trendstage.domain.grade.Grade;
import kr.trendstage.domain.grade.SubmissionQuota;
import kr.trendstage.persistence.entity.UserGrade;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.UserGradeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 제보권(01 §3.3, J4). 저장 카운터 없이 제보 행에서 파생한다 — 이번 주 사용 = 이번 주 제출 − 이번 주 VOID 반환.
 * 리필은 주 경계(월 00:00 KST) 자체, 한도의 등급은 주간 스냅샷(J5).
 * 집행(SubmissionService)과 표시(MeService.summary)가 이 한 곳을 쓴다.
 */
@Service
public class QuotaService {

    private final SubmissionRepository submissions;
    private final UserGradeRepository grades;

    public QuotaService(SubmissionRepository submissions, UserGradeRepository grades) {
        this.submissions = submissions;
        this.grades = grades;
    }

    public record Quota(Grade grade, int used, int max, int remaining) {}

    @Transactional(readOnly = true)
    public Quota of(UUID userId, Instant now) {
        Instant weekStart = SubmissionQuota.weekStart(now);
        int submitted = (int) submissions.countByUserIdAndSeedFalseAndCreatedAtGreaterThanEqual(userId, weekStart);
        int refunded = (int) submissions.countByUserIdAndSeedFalseAndVoidedAtGreaterThanEqual(userId, weekStart);
        Grade grade = grades.findTopByUserIdOrderByComputedAtDesc(userId).map(UserGrade::getGrade).orElse(Grade.L0);
        int used = SubmissionQuota.used(submitted, refunded);
        return new Quota(grade, used, SubmissionQuota.weeklyLimit(grade), SubmissionQuota.remaining(grade, used));
    }
}
