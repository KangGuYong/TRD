package kr.trendstage.merge;

import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.TrendState;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
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

    public enum ProcessOutcome { AUTO_MERGED, QUEUED, SEPARATED, SKIPPED_JUDGED, DEFERRED }

    /** skippedJudged = 판정된 항목과 닮아 기록만 함(K4), deferred = 경합으로 거부돼 다음 실행에서 다시 봄. */
    public record ClusterMergeResult(int candidates, int autoMerged, int queued, int separated,
                                     int skippedJudged, int deferred, int failed) {}

    private static final double AUTO_MERGE_THRESHOLD = 0.85;
    private static final double QUEUE_THRESHOLD = 0.75;
    /** 관리자 수동 트리거 전용 명시적 락의 최대 보유 시간 — @SchedulerLock(cron)의 lockAtMostFor와 동일 값. */
    private static final Duration MANUAL_LOCK_AT_MOST_FOR = Duration.ofMinutes(60);

    private final TrendItemRepository trendItems;
    private final EmbeddingClient embeddingClient;
    private final TrendEmbeddingDao embeddingDao;
    private final ClusterMergeDecider decider;
    private final LockProvider lockProvider;
    private final Clock clock;

    public ClusterMergeCandidateService(TrendItemRepository trendItems,
                                         EmbeddingClient embeddingClient, TrendEmbeddingDao embeddingDao,
                                         ClusterMergeDecider decider, LockProvider lockProvider, Clock clock) {
        this.trendItems = trendItems;
        this.embeddingClient = embeddingClient;
        this.embeddingDao = embeddingDao;
        this.decider = decider;
        this.lockProvider = lockProvider;
        this.clock = clock;
    }

    static ProcessOutcome classify(double similarity) {
        if (similarity >= AUTO_MERGE_THRESHOLD) return ProcessOutcome.AUTO_MERGED;
        if (similarity >= QUEUE_THRESHOLD) return ProcessOutcome.QUEUED;
        return ProcessOutcome.SEPARATED;
    }

    /** 크론(@SchedulerLock으로 이미 보호됨) 전용 진입점. 판정 전 상태만 후보로 본다(판정된 항목은 병합 대상이 아니다). */
    public ClusterMergeResult runNow() {
        List<UUID> candidateIds = trendItems.findByStateInAndMergeCheckedAtIsNull(
                        List.of(TrendState.DRAFT, TrendState.PENDING))
                .stream().map(TrendItem::getId).toList();

        int autoMerged = 0, queued = 0, separated = 0, skippedJudged = 0, deferred = 0, failed = 0;
        for (UUID candidateId : candidateIds) {
            try {
                switch (processOne(candidateId)) {
                    case AUTO_MERGED -> autoMerged++;
                    case QUEUED -> queued++;
                    case SEPARATED -> separated++;
                    case SKIPPED_JUDGED -> skippedJudged++;
                    case DEFERRED -> deferred++;
                }
            } catch (Exception e) {
                log.error("cluster_merge 처리 실패 item={} : {}", candidateId, e.getMessage(), e);
                failed++;
            }
        }
        return new ClusterMergeResult(candidateIds.size(), autoMerged, queued, separated, skippedJudged, deferred, failed);
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
     * 임베딩 계산·저장은 트랜잭션 밖(외부 HTTP), 결정 + 확인 표시는 ClusterMergeDecider의 트랜잭션 하나.
     * 같은 실행에서 앞 후보에 흡수돼 이미 판정 전 상태가 아니면 건너뛴다.
     */
    private ProcessOutcome processOne(UUID candidateId) {
        TrendItem candidate = trendItems.findById(candidateId).orElseThrow();
        if (candidate.getState() != TrendState.DRAFT && candidate.getState() != TrendState.PENDING) {
            return ProcessOutcome.DEFERRED;
        }
        float[] vec = embeddingClient.embed(candidate.getCanonicalName());
        embeddingDao.updateEmbedding(candidate.getId(), vec);

        var match = embeddingDao.findMostSimilar(candidate.getId(), vec);
        try {
            return decider.decide(candidateId,
                    match.map(TrendEmbeddingDao.SimilarMatch::trendItemId).orElse(null),
                    match.map(TrendEmbeddingDao.SimilarMatch::similarity).orElse(0.0),
                    clock.instant());
        } catch (MergeConflictException e) {
            log.info("cluster_merge 병합 보류 item={} : {}", candidateId, e.getMessage());
            return ProcessOutcome.DEFERRED;
        }
    }
}
