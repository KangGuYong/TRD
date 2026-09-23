package kr.trendstage.scheduler;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 시간당 SLA 감시(SP3 §7). 단계는 서로 독립이고, 단계 안에서도 행 하나가 한 트랜잭션이다 —
 * 한 행이 실패해도 로그만 남기고 나머지를 계속 처리한다. 처리는 SlaWatchService(다른 빈 — 트랜잭션 프록시를 탄다).
 */
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
        int hidden = autoHideOverdueReports(now);
        int extended = extendStalledMerges(now);
        int disabled = disableInactiveAdmins(now);
        log.info("sla_watch 완료: 자동 숨김 {} · 마감 연장 {} · 관리자 비활성화 {}", hidden, extended, disabled);
    }

    /** 신고 4h → 임시 비공개. @return 처리한 신고 수 */
    public int autoHideOverdueReports(Instant now) {
        return each("신고 자동 숨김", () -> service.overdueReportIds(now), id -> service.autoHideReport(id, now));
    }

    /** 병합 24h → 마감 연장. @return 연장이 일어난 병합 후보 수 */
    public int extendStalledMerges(Instant now) {
        return each("병합 마감 연장", () -> service.stalledMergeIds(now), id -> service.extendStalledMerge(id, now));
    }

    /** 90일 미접속 관리자 비활성화. @return 비활성화한 계정 수 */
    public int disableInactiveAdmins(Instant now) {
        return each("미접속 관리자 비활성화", () -> service.inactiveAdminIds(now), id -> service.disableIfInactive(id, now));
    }

    private static int each(String step, Supplier<List<UUID>> candidates, Predicate<UUID> unit) {
        List<UUID> ids;
        try {
            ids = candidates.get();
        } catch (RuntimeException e) {
            log.error("sla_watch 후보 조회 실패({}): {}", step, e.getMessage(), e);
            return 0;
        }
        int n = 0;
        for (UUID id : ids) {
            try {
                if (unit.test(id)) n++;
            } catch (RuntimeException e) {
                log.error("sla_watch 처리 실패({} · {}): {}", step, id, e.getMessage(), e);
            }
        }
        return n;
    }
}
