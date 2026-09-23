package kr.trendstage.apiadmin.queues;

import kr.trendstage.domain.verdict.DeadlineWindow;
import kr.trendstage.persistence.entity.MergeQueueEntry;
import kr.trendstage.persistence.entity.Report;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.MergeQueueRepository;
import kr.trendstage.persistence.repo.ReportRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.MergeQueueStatus;
import kr.trendstage.persistence.type.ReportStatus;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * ADM-010 오늘의 작업. 병합 검수(ADM-100)와 신고 콘텐츠(ADM-410)는 실데이터.
 * 어뷰징(ADM-300)·이의 제기(ADM-400)는 큐 자체가 없어(Phase 2) available=false로 응답한다 —
 * 0건으로 표시하면 "처리할 게 없다"로 읽히므로 미구현임을 숨기지 않는다.
 */
@Service
public class QueueSummaryService {

    private static final int MERGE_SLA_HOURS = 24;
    private static final int REPORT_SLA_HOURS = 4;
    private static final int SEED_RATIO_WINDOW_DAYS = 7;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final MergeQueueRepository mergeQueue;
    private final TrendItemRepository trendItems;
    private final SubmissionRepository submissions;
    private final ReportRepository reports;
    private final Clock clock;

    public QueueSummaryService(MergeQueueRepository mergeQueue, TrendItemRepository trendItems,
                                SubmissionRepository submissions, ReportRepository reports, Clock clock) {
        this.mergeQueue = mergeQueue;
        this.trendItems = trendItems;
        this.submissions = submissions;
        this.reports = reports;
        this.clock = clock;
    }

    public record QueueTile(String id, String name, int count, String oldest, boolean slaExceeded, boolean available) {}
    public record Alert(String title, String detail) {}
    public record QueueSummaryResponse(int slaBreaches, List<QueueTile> queues, List<Alert> alerts,
                                        double seedRatio, int judgedToday, int imminent24h) {}

    public QueueSummaryResponse summarize() {
        Instant now = clock.instant();

        List<MergeQueueEntry> pending = mergeQueue.findByStatusOrderByCreatedAtAsc(MergeQueueStatus.PENDING);
        long mergeOverdue = pending.stream()
                .filter(e -> Duration.between(e.getCreatedAt(), now).toHours() >= MERGE_SLA_HOURS)
                .count();
        String oldest = pending.stream().map(MergeQueueEntry::getCreatedAt)
                .min(Comparator.naturalOrder())
                .map(t -> formatAgo(t, now)).orElse("-");

        List<TrendItem> active = trendItems.findByStateIn(List.of(TrendState.PENDING, TrendState.JUDGING));
        Instant todayStartKst = now.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
        Instant todayEndKst = todayStartKst.plus(Duration.ofDays(1));
        long judgedToday = active.stream()
                .filter(i -> DeadlineWindow.fallsWithin(
                        DeadlineWindow.effectiveDeadline(i.getFirstSeenAt(), i.getJudgmentDeadlineOverride()),
                        todayStartKst, todayEndKst))
                .count();
        long imminent24h = active.stream()
                .filter(i -> DeadlineWindow.fallsWithin(
                        DeadlineWindow.effectiveDeadline(i.getFirstSeenAt(), i.getJudgmentDeadlineOverride()),
                        now, now.plus(Duration.ofHours(24))))
                .count();

        Instant seedWindowStart = now.minus(Duration.ofDays(SEED_RATIO_WINDOW_DAYS));
        long seedCount = submissions.countByCreatedAtAfterAndSeedTrue(seedWindowStart);
        long totalCount = submissions.countByCreatedAtAfter(seedWindowStart);
        double seedRatio = totalCount == 0 ? 0.0 : (double) seedCount / totalCount;

        long reportPending = reports.countByStatusIn(List.of(ReportStatus.OPEN, ReportStatus.EXPLAINING));
        List<Report> openReports = reports.findByStatusOrderByCreatedAtAsc(ReportStatus.OPEN);
        long reportOverdue = openReports.stream()
                .filter(r -> Duration.between(r.getCreatedAt(), now).toHours() >= REPORT_SLA_HOURS)
                .count();
        String reportOldest = openReports.isEmpty() ? "-" : formatAgo(openReports.get(0).getCreatedAt(), now);

        List<QueueTile> queues = List.of(
                new QueueTile("ADM-100", "병합 검수", pending.size(), oldest, mergeOverdue > 0, true),
                new QueueTile("ADM-300", "어뷰징", 0, "-", false, false),
                new QueueTile("ADM-400", "이의 제기", 0, "-", false, false),
                new QueueTile("ADM-410", "신고 콘텐츠", (int) reportPending, reportOldest, reportOverdue > 0, true)
        );

        List<Alert> alerts = new ArrayList<>();
        if (mergeOverdue > 0) {
            alerts.add(new Alert("병합 검수 큐 SLA 초과 " + mergeOverdue + "건",
                    "24시간 기준 초과 — 판정 마감은 sla_watch가 자동 연장 중(상한 최초 제보 + 21일)"));
        }
        long atCeiling = pending.stream()
                .filter(e -> Duration.between(e.getCreatedAt(), now).toHours() >= MERGE_SLA_HOURS)
                .flatMap(e -> java.util.stream.Stream.of(e.getNewTrendItemId(), e.getOldTrendItemId()))
                .distinct()
                .map(trendItems::findById).flatMap(Optional::stream)
                .filter(i -> i.getState() == TrendState.PENDING || i.getState() == TrendState.JUDGING)
                .filter(i -> DeadlineWindow.ceilingReached(i.getFirstSeenAt(), i.getJudgmentDeadlineOverride()))
                .count();
        if (atCeiling > 0) {
            alerts.add(new Alert("병합 대기로 판정 마감 상한 도달 " + atCeiling + "건",
                    "최초 제보 + 21일 — 더 연장할 수 없습니다. 병합 결정을 서두르세요"));
        }
        if (reportOverdue > 0) {
            alerts.add(new Alert("신고 콘텐츠 SLA 초과 " + reportOverdue + "건",
                    "4시간 기준 초과 — sla_watch가 임시 비공개함, 1차 처리 필요"));
        }

        return new QueueSummaryResponse((int) (mergeOverdue + reportOverdue), queues, alerts,
                seedRatio, (int) judgedToday, (int) imminent24h);
    }

    private static String formatAgo(Instant t, Instant now) {
        Duration d = Duration.between(t, now);
        if (d.toHours() < 1) return d.toMinutes() + "분";
        return d.toHours() + "h";
    }
}
