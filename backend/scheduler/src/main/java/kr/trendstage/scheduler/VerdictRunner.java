package kr.trendstage.scheduler;

import kr.trendstage.judge.JudgeService;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.TrendState;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * D+14 판정 배치 — 목록 순회만 한다. 판정은 JudgeService(별도 빈, 항목마다 트랜잭션)가 한다.
 * 이 클래스에 @Transactional 메서드를 두고 직접 호출하면 프록시를 타지 않아 트랜잭션이 걸리지 않는다(04 §3).
 * 1) 마감 지난 PENDING → JUDGING  2) JUDGING 항목마다 판정. 실패한 항목은 JUDGING으로 남아 다음 실행에서 재시도된다.
 */
@Component
public class VerdictRunner {

    private static final Logger log = LoggerFactory.getLogger(VerdictRunner.class);

    private final JudgeService judgeService;
    private final TrendItemRepository trendItems;
    private final Clock clock;

    public VerdictRunner(JudgeService judgeService, TrendItemRepository trendItems, Clock clock) {
        this.judgeService = judgeService;
        this.trendItems = trendItems;
        this.clock = clock;
    }

    /** 일 1회 03:00 KST(= 18:00 UTC — 컨테이너 JVM은 UTC). */
    @Scheduled(cron = "${jobs.verdict-runner.cron:0 0 18 * * *}")
    @SchedulerLock(name = "verdict_runner", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        Instant now = clock.instant();
        int closed = judgeService.closeDue(now);
        List<UUID> judging = trendItems.findByStateIn(List.of(TrendState.JUDGING)).stream()
                .map(TrendItem::getId).toList();
        int judged = 0;
        int failed = 0;
        for (UUID id : judging) {
            try {
                if (judgeService.judge(id, now)) judged++;
            } catch (RuntimeException e) {
                failed++;
                log.error("판정 실패 item={} — JUDGING으로 남겨 다음 실행에서 재시도: {}", id, e.getMessage(), e);
            }
        }
        log.info("verdict_runner 완료: 마감 {} · 판정 대상 {} · 판정 {} · 실패 {}", closed, judging.size(), judged, failed);
    }
}
