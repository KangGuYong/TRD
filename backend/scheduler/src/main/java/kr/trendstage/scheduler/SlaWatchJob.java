package kr.trendstage.scheduler;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

/** 시간당 SLA 감시(SP3 §7). 단계는 서로 독립 — 한 단계가 실패해도 나머지는 돈다. 처리는 SlaWatchService. */
@Component
public class SlaWatchJob {

    private static final Logger log = LoggerFactory.getLogger(SlaWatchJob.class);

    private final SlaWatchService service;
    private final Clock clock;

    public SlaWatchJob(SlaWatchService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(cron = "${jobs.sla-watch.cron:0 0 * * * *}")
    @SchedulerLock(name = "sla_watch", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void run() {
        Instant now = clock.instant();
        int hidden = step("신고 자동 숨김", () -> service.autoHideOverdueReports(now));
        int extended = step("병합 마감 연장", () -> service.extendStalledMerges(now));
        int disabled = step("미접속 관리자 비활성화", () -> service.disableInactiveAdmins(now));
        log.info("sla_watch 완료: 자동 숨김 {} · 마감 연장 {} · 관리자 비활성화 {}", hidden, extended, disabled);
    }

    private static int step(String name, Supplier<Integer> body) {
        try {
            return body.get();
        } catch (RuntimeException e) {
            log.error("sla_watch 단계 실패({}): {}", name, e.getMessage(), e);
            return 0;
        }
    }
}
