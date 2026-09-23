package kr.trendstage.persistence.grade;

import kr.trendstage.domain.score.ActiveScore;
import kr.trendstage.persistence.repo.ScoreLedgerRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.type.SubmissionResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 등급 계산 입력. grade_recalc(스냅샷)와 /v1/me(진행 상황)가 같은 값을 보도록 한 곳에서 읽는다(J5) —
 * 두 모듈이 공통으로 의존하는 persistence에 둔다(CurrentParameterSetResolver와 같은 이유).
 * TI는 최근 180일 판정분(01 §5.2), 판정완료 건수는 전 기간, AS는 행별 감쇠(J1). 시딩 제보는 제외.
 */
@Component
public class GradeInputsReader {

    public static final int TI_WINDOW_DAYS = 180;

    private final SubmissionRepository submissions;
    private final ScoreLedgerRepository ledger;

    public GradeInputsReader(SubmissionRepository submissions, ScoreLedgerRepository ledger) {
        this.submissions = submissions;
        this.ledger = ledger;
    }

    public record GradeInputs(int judgedCount, int hitInWindow, int missInWindow, double activeScore) {}

    @Transactional(readOnly = true)
    public GradeInputs read(UUID userId, Instant now) {
        Instant since = now.minus(Duration.ofDays(TI_WINDOW_DAYS));
        int hit = (int) submissions.countByUserIdAndResultAndSeedFalseAndResolvedAtGreaterThanEqual(
                userId, SubmissionResult.HIT, since);
        int miss = (int) submissions.countByUserIdAndResultAndSeedFalseAndResolvedAtGreaterThanEqual(
                userId, SubmissionResult.MISS, since);
        int judged = (int) (submissions.countByUserIdAndResultAndSeedFalse(userId, SubmissionResult.HIT)
                + submissions.countByUserIdAndResultAndSeedFalse(userId, SubmissionResult.MISS));
        List<ActiveScore.Aged> aged = ledger.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(e -> new ActiveScore.Aged(e.getDelta().doubleValue(),
                        Math.max(0, Duration.between(e.getDecayAnchorAt(), now).toDays()), e.getHalflifeDays()))
                .toList();
        return new GradeInputs(judged, hit, miss, ActiveScore.compute(aged));
    }
}
