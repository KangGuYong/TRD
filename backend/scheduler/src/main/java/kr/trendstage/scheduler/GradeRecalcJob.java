package kr.trendstage.scheduler;

import kr.trendstage.domain.grade.Grade;
import kr.trendstage.domain.grade.GradePolicy;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.TrustIndex;
import kr.trendstage.persistence.entity.UserAccount;
import kr.trendstage.persistence.entity.UserGrade;
import kr.trendstage.persistence.grade.GradeInputsReader;
import kr.trendstage.persistence.params.CurrentParameterSetResolver;
import kr.trendstage.persistence.repo.UserGradeRepository;
import kr.trendstage.persistence.repo.UserRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * 주간 등급 재계산 — 공식 등급은 이 스냅샷이다(J5). 등급은 활동량이 아니라 적중률 주축(AS AND TI, R3).
 * 스냅샷을 append할 뿐(user_grades 불변). 강등 규칙(01 §6.1, Phase 2) 전까지 등급을 내리지 않는다(J8).
 * 제보권 리필은 주 경계 자체라 여기서 할 일이 없다(J4).
 */
@Component
public class GradeRecalcJob {

    private static final Logger log = LoggerFactory.getLogger(GradeRecalcJob.class);

    private final UserRepository users;
    private final UserGradeRepository grades;
    private final GradeInputsReader inputs;
    private final CurrentParameterSetResolver params;
    private final Clock clock;

    public GradeRecalcJob(UserRepository users, UserGradeRepository grades, GradeInputsReader inputs,
                          CurrentParameterSetResolver params, Clock clock) {
        this.users = users;
        this.grades = grades;
        this.inputs = inputs;
        this.params = params;
        this.clock = clock;
    }

    /** 주 1회 월요일 00:00 KST — 제보권 주 경계와 같은 순간. 타임존을 코드에 명시해 서버 타임존과 무관하게 한다. */
    @Scheduled(cron = "${jobs.grade-recalc.cron:0 0 0 * * MON}", zone = "${jobs.grade-recalc.zone:Asia/Seoul}")
    @SchedulerLock(name = "grade_recalc", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void run() {
        ParameterSet p = params.resolve();
        Instant now = clock.instant();
        int n = 0;
        for (UserAccount u : users.findAll()) {
            recalcOne(u.getId(), p, now);
            n++;
        }
        log.info("grade_recalc 완료: 유저 {}", n);
    }

    private void recalcOne(UUID userId, ParameterSet p, Instant now) {
        GradeInputsReader.GradeInputs in = inputs.read(userId, now);
        double ti = TrustIndex.compute(in.hitInWindow(), in.missInWindow(), p);
        Grade evaluated = GradePolicy.evaluate(in.judgedCount(), ti, in.activeScore()).current();
        Grade previous = grades.findTopByUserIdOrderByComputedAtDesc(userId).map(UserGrade::getGrade).orElse(Grade.L0);
        Grade grade = evaluated.compareTo(previous) >= 0 ? evaluated : previous;   // J8 — 강등 규칙 전까지 유지

        grades.save(new UserGrade(userId, grade,
                BigDecimal.valueOf(ti).setScale(3, RoundingMode.HALF_UP),
                BigDecimal.valueOf(in.activeScore()).setScale(4, RoundingMode.HALF_UP),
                in.judgedCount()));
    }
}
