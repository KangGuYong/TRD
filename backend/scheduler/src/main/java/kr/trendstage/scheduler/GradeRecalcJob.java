package kr.trendstage.scheduler;

import kr.trendstage.domain.grade.GradePolicy;
import kr.trendstage.domain.grade.GradeStatus;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.ActiveScore;
import kr.trendstage.domain.score.TrustIndex;
import kr.trendstage.persistence.entity.ScoreLedgerEntry;
import kr.trendstage.persistence.entity.UserAccount;
import kr.trendstage.persistence.entity.UserGrade;
import kr.trendstage.persistence.repo.ScoreLedgerRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.UserGradeRepository;
import kr.trendstage.persistence.repo.UserRepository;
import kr.trendstage.persistence.type.SubmissionResult;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 주간 등급 재계산. 원장을 읽어 AS·TI를 산출하고 GradePolicy로 등급을 매긴다(파생값, 01 §1·§6).
 *
 * 등급은 활동량이 아니라 적중률 주축(AS AND TI, R3). 강등은 여기서 확정하지 않는다 —
 * 4주 연속 미달 판정은 별도(오탐/급락 방지). 이 잡은 스냅샷을 append할 뿐(user_grades 불변).
 * 제보권 리필(이월 없음)은 QuotaService 도입 후 연결(TODO).
 */
@Component
public class GradeRecalcJob {

    private static final Logger log = LoggerFactory.getLogger(GradeRecalcJob.class);

    private final UserRepository users;
    private final SubmissionRepository submissions;
    private final ScoreLedgerRepository ledger;
    private final UserGradeRepository grades;
    private final ParameterSetProvider params;
    private final Clock clock;

    public GradeRecalcJob(UserRepository users, SubmissionRepository submissions, ScoreLedgerRepository ledger,
                          UserGradeRepository grades, ParameterSetProvider params, Clock clock) {
        this.users = users; this.submissions = submissions; this.ledger = ledger;
        this.grades = grades; this.params = params; this.clock = clock;
    }

    /** 주 1회 월요일 00:00 (배포 타임존 기준으로 cron 조정). */
    @Scheduled(cron = "${jobs.grade-recalc.cron:0 0 0 * * MON}")
    @SchedulerLock(name = "grade_recalc", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void run() {
        ParameterSet p = params.current();
        Instant now = clock.instant();
        int n = 0;
        for (UserAccount u : users.findAll()) {
            recalcOne(u, p, now);
            n++;
        }
        log.info("grade_recalc 완료: 유저 {}", n);
    }

    private void recalcOne(UserAccount u, ParameterSet p, Instant now) {
        // TODO: 최근 180일 창으로 제한 (현재는 전체). SubmissionRepository에 기간 카운트 추가 예정.
        int hit = (int) submissions.countByUserIdAndResult(u.getId(), SubmissionResult.HIT);
        int miss = (int) submissions.countByUserIdAndResult(u.getId(), SubmissionResult.MISS);
        int judged = hit + miss;

        double ti = TrustIndex.compute(hit, miss, p);

        List<ActiveScore.Aged> aged = new ArrayList<>();
        for (ScoreLedgerEntry e : ledger.findByUserIdOrderByCreatedAtDesc(u.getId())) {
            long ageDays = Duration.between(e.getCreatedAt(), now).toDays();
            aged.add(new ActiveScore.Aged(e.getDelta().doubleValue(), Math.max(0, ageDays)));
        }
        double as = ActiveScore.compute(aged, p);

        GradeStatus status = GradePolicy.evaluate(judged, ti, as);

        grades.save(new UserGrade(
                u.getId(),
                status.current(),
                BigDecimal.valueOf(ti).setScale(3, RoundingMode.HALF_UP),
                BigDecimal.valueOf(as).setScale(4, RoundingMode.HALF_UP),
                judged));
        // TODO: 제보권 리필(등급별 주간 quota, 이월 없음).
    }
}
