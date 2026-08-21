package kr.trendstage.merge;

import kr.trendstage.persistence.entity.MergeQueueEntry;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.MergeQueueRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.TrendState;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * cluster_merge 후보 순회·분류·처리. 크론(ClusterMergeJob)과 관리자 수동 트리거
 * (api-admin BatchJobController) 양쪽이 이 서비스를 호출한다 — 둘 다 :merge 모듈에
 * 이미 의존하고 있어(MergeService와 동일 전례) 여기 두면 모듈 의존 방향이 깨지지 않는다.
 */
@Service
public class ClusterMergeCandidateService {

    private static final Logger log = LoggerFactory.getLogger(ClusterMergeCandidateService.class);

    public enum ProcessOutcome { AUTO_MERGED, QUEUED, SEPARATED }

    public record ClusterMergeResult(int candidates, int autoMerged, int queued, int separated, int failed) {}

    private static final double AUTO_MERGE_THRESHOLD = 0.85;
    private static final double QUEUE_THRESHOLD = 0.75;
    /** 관리자 수동 트리거 전용 명시적 락의 최대 보유 시간 — @SchedulerLock(cron)의 lockAtMostFor와 동일 값. */
    private static final Duration MANUAL_LOCK_AT_MOST_FOR = Duration.ofMinutes(60);

    private final TrendItemRepository trendItems;
    private final MergeQueueRepository mergeQueue;
    private final EmbeddingClient embeddingClient;
    private final TrendEmbeddingDao embeddingDao;
    private final MergeService mergeService;
    private final LockProvider lockProvider;
    private final Clock clock;

    public ClusterMergeCandidateService(TrendItemRepository trendItems, MergeQueueRepository mergeQueue,
                                         EmbeddingClient embeddingClient, TrendEmbeddingDao embeddingDao,
                                         MergeService mergeService, LockProvider lockProvider, Clock clock) {
        this.trendItems = trendItems;
        this.mergeQueue = mergeQueue;
        this.embeddingClient = embeddingClient;
        this.embeddingDao = embeddingDao;
        this.mergeService = mergeService;
        this.lockProvider = lockProvider;
        this.clock = clock;
    }

    static ProcessOutcome classify(double similarity) {
        if (similarity >= AUTO_MERGE_THRESHOLD) return ProcessOutcome.AUTO_MERGED;
        if (similarity >= QUEUE_THRESHOLD) return ProcessOutcome.QUEUED;
        return ProcessOutcome.SEPARATED;
    }

    /** 크론(@SchedulerLock으로 이미 보호됨) 전용 진입점. */
    public ClusterMergeResult runNow() {
        List<UUID> candidateIds = trendItems.findByStateInAndMergeCheckedAtIsNull(
                        List.of(TrendState.DRAFT, TrendState.PENDING, TrendState.JUDGING, TrendState.RESOLVED))
                .stream().map(TrendItem::getId).toList();

        int autoMerged = 0, queued = 0, separated = 0, failed = 0;
        for (UUID candidateId : candidateIds) {
            try {
                switch (processOne(candidateId)) {
                    case AUTO_MERGED -> autoMerged++;
                    case QUEUED -> queued++;
                    case SEPARATED -> separated++;
                }
            } catch (Exception e) {
                log.error("cluster_merge 처리 실패 item={} : {}", candidateId, e.getMessage(), e);
                failed++;
            }
        }
        return new ClusterMergeResult(candidateIds.size(), autoMerged, queued, separated, failed);
    }

    /**
     * 관리자 수동 트리거 전용 진입점. 크론과 같은 락 이름("cluster_merge")을 명시적으로 획득 시도한다 —
     * 실패하면(크론이 돌고 있거나 다른 관리자가 동시에 눌렀으면) Optional.empty()를 반환한다.
     * @SchedulerLock 어노테이션과 달리 "락을 못 잡아서 조용히 스킵"이 아니라 호출자가 명확히 알 수 있다.
     */
    public Optional<ClusterMergeResult> tryRunNow() {
        LockConfiguration lockConfig = new LockConfiguration(
                clock.instant(), "cluster_merge", MANUAL_LOCK_AT_MOST_FOR, Duration.ZERO);
        Optional<SimpleLock> lock = lockProvider.lock(lockConfig);
        if (lock.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(runNow());
        } finally {
            lock.get().unlock();
        }
    }

    /**
     * @return 자동 병합이 일어났으면 true.
     *
     * 주의: 같은 클래스 안에서 self-invocation은 Spring AOP 프록시를 거치지 않아 @Transactional이
     * 조용히 무효화된다 — 그래서 여기선 트랜잭션 경계를 기대하지 않고, 체크 완료 표시(markMergeChecked)를
     * save()로 직접 커밋한다. 병합·큐 적재는 각각 별도 빈(MergeService)·리포지토리 호출로 자체 트랜잭션을 갖는다.
     */
    private ProcessOutcome processOne(UUID candidateId) {
        TrendItem candidate = trendItems.findById(candidateId).orElseThrow();
        float[] vec = embeddingClient.embed(candidate.getCanonicalName());
        embeddingDao.updateEmbedding(candidate.getId(), vec);

        var match = embeddingDao.findMostSimilar(candidate.getId(), vec);
        Instant now = clock.instant();

        if (match.isEmpty()) {
            markChecked(candidate, now);
            return ProcessOutcome.SEPARATED;
        }

        double similarity = match.get().similarity();
        TrendItem other = trendItems.findById(match.get().trendItemId()).orElseThrow();
        ProcessOutcome outcome = classify(similarity);

        switch (outcome) {
            case AUTO_MERGED -> {
                TrendItem survivor = candidate.getFirstSeenAt().isBefore(other.getFirstSeenAt()) ? candidate : other;
                TrendItem loser = survivor == candidate ? other : candidate;
                mergeService.merge(survivor.getId(), loser.getId(), null, null,
                        "cluster_merge 자동 병합 (유사도 %.4f)".formatted(similarity));
                markChecked(trendItems.findById(candidateId).orElseThrow(), now);
            }
            case QUEUED -> {
                BigDecimal simScore = BigDecimal.valueOf(similarity).setScale(4, RoundingMode.HALF_UP);
                mergeQueue.save(new MergeQueueEntry(candidate.getId(), other.getId(), simScore));
                markChecked(candidate, now);
            }
            case SEPARATED -> markChecked(candidate, now);
        }
        return outcome;
    }

    private void markChecked(TrendItem item, Instant now) {
        item.markMergeChecked(now);
        trendItems.save(item);
    }
}
