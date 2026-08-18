package kr.trendstage.apiadmin.queues;

import kr.trendstage.domain.verdict.DeadlineWindow;
import kr.trendstage.persistence.entity.MergeQueueEntry;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.MergeQueueRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.MergeQueueStatus;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

/**
 * ADM-010. 병합 검수 큐만 실제 백엔드가 있고(어뷰징/이의제기/신고는 서브시스템 자체가 미구현),
 * 그 셋은 0건 고정으로 응답한다 — 화면 구조는 유지하되 미구현임을 숨기지 않는다.
 */
@Service
public class QueueSummaryService {

    private static final int MERGE_SLA_HOURS = 24;
    private static final int SEED_RATIO_WINDOW_DAYS = 7;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final MergeQueueRepository mergeQueue;
    private final TrendItemRepository trendItems;
    private final SubmissionRepository submissions;
    private final Clock clock;

    public QueueSummaryService(MergeQueueRepository mergeQueue, TrendItemRepository trendItems,
                                SubmissionRepository submissions, Clock clock) {
        this.mergeQueue = mergeQueue;
        this.trendItems = trendItems;
        this.submissions = submissions;
        this.clock = clock;
    }

    public record QueueTile(String id, String name, int count, String oldest, boolean slaExceeded) {}
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

        List<QueueTile> queues = List.of(
                new QueueTile("ADM-100", "병합 검수", pending.size(), oldest, mergeOverdue > 0),
                new QueueTile("ADM-300", "어뷰징", 0, "-", false),
                new QueueTile("ADM-400", "이의 제기", 0, "-", false),
                new QueueTile("ADM-410", "신고 콘텐츠", 0, "-", false)
        );
        List<Alert> alerts = mergeOverdue == 0 ? List.of() : List.of(
                new Alert("병합 검수 큐 SLA 초과 " + mergeOverdue + "건", "24시간 기준 초과, 미처리 시 판정 유예 자동 연장")
        );

        return new QueueSummaryResponse((int) mergeOverdue, queues, alerts,
                seedRatio, (int) judgedToday, (int) imminent24h);
    }

    private static String formatAgo(Instant t, Instant now) {
        Duration d = Duration.between(t, now);
        if (d.toHours() < 1) return d.toMinutes() + "분";
        return d.toHours() + "h";
    }
}
