package kr.trendstage.scheduler;

import kr.trendstage.merge.EmbeddingClient;
import kr.trendstage.merge.MergeService;
import kr.trendstage.merge.TrendEmbeddingDao;
import kr.trendstage.persistence.entity.MergeQueueEntry;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.MergeQueueRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.TrendState;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 임베딩 유사도 병합(03 §2③④). 완전일치(§2②)로 안 걸러진 항목을 대상으로:
 *   - 유사도 ≥ 0.85 → 자동 병합
 *   - 0.75 ≤ 유사도 < 0.85 → ADM-100 관리자 큐
 *   - 유사도 < 0.75 → 별개 항목으로 확정(재검토 없음)
 * 임계값은 O7(미결) — 초기값은 CLAUDE.md/03 §8 M1 기본값. Phase 0 백테스트로 보정 예정.
 * 멱등: trend_items.merge_checked_at으로 이미 처리한 항목은 매일 다시 비교하지 않는다.
 */
@Component
public class ClusterMergeJob {

    private static final Logger log = LoggerFactory.getLogger(ClusterMergeJob.class);
    private static final double AUTO_MERGE_THRESHOLD = 0.85;
    private static final double QUEUE_THRESHOLD = 0.75;

    private final TrendItemRepository trendItems;
    private final MergeQueueRepository mergeQueue;
    private final EmbeddingClient embeddingClient;
    private final TrendEmbeddingDao embeddingDao;
    private final MergeService mergeService;
    private final Clock clock;

    public ClusterMergeJob(TrendItemRepository trendItems, MergeQueueRepository mergeQueue,
                            EmbeddingClient embeddingClient, TrendEmbeddingDao embeddingDao,
                            MergeService mergeService, Clock clock) {
        this.trendItems = trendItems;
        this.mergeQueue = mergeQueue;
        this.embeddingClient = embeddingClient;
        this.embeddingDao = embeddingDao;
        this.mergeService = mergeService;
        this.clock = clock;
    }

    /** 일 1회(CLAUDE.md 배치 표). */
    @Scheduled(cron = "${jobs.cluster-merge.cron:0 0 17 * * *}")
    @SchedulerLock(name = "cluster_merge", lockAtMostFor = "PT60M", lockAtLeastFor = "PT5M")
    public void run() {
        List<UUID> candidateIds = trendItems.findByStateInAndMergeCheckedAtIsNull(
                        List.of(TrendState.DRAFT, TrendState.PENDING, TrendState.JUDGING, TrendState.RESOLVED))
                .stream().map(TrendItem::getId).toList();

        int autoMerged = 0, other = 0, failed = 0;
        for (UUID candidateId : candidateIds) {
            try {
                if (processOne(candidateId)) autoMerged++;
                else other++; // 큐 적재 또는 별개 확정 — 세부 구분은 merge_queue 테이블로 조회 가능
            } catch (Exception e) {
                log.error("cluster_merge 처리 실패 item={} : {}", candidateId, e.getMessage(), e);
                failed++;
            }
        }
        log.info("cluster_merge 완료: 후보 {} · 자동병합 {} · 큐/별개 {} · 실패 {}",
                candidateIds.size(), autoMerged, other, failed);
    }

    /**
     * @return 자동 병합이 일어났으면 true.
     *
     * 주의: 같은 클래스 안에서 {@code this.processOne(...)}로 호출되는 self-invocation은 Spring AOP
     * 프록시를 거치지 않아 {@code @Transactional}이 조용히 무효화된다 — 그래서 여기선 트랜잭션 경계를
     * 기대하지 않고, 체크 완료 표시(markMergeChecked)를 {@code save()}로 직접 커밋한다. 병합·큐 적재는
     * 각각 별도 빈(MergeService)·리포지토리 호출로 자체 트랜잭션을 갖는다.
     */
    protected boolean processOne(UUID candidateId) {
        TrendItem candidate = trendItems.findById(candidateId).orElseThrow();
        float[] vec = embeddingClient.embed(candidate.getCanonicalName());
        embeddingDao.updateEmbedding(candidate.getId(), vec);

        var match = embeddingDao.findMostSimilar(candidate.getId(), vec);
        Instant now = clock.instant();

        if (match.isEmpty()) {
            markChecked(candidate, now);
            return false;
        }

        double similarity = match.get().similarity();
        TrendItem other = trendItems.findById(match.get().trendItemId()).orElseThrow();

        if (similarity >= AUTO_MERGE_THRESHOLD) {
            TrendItem survivor = candidate.getFirstSeenAt().isBefore(other.getFirstSeenAt()) ? candidate : other;
            TrendItem loser = survivor == candidate ? other : candidate;
            mergeService.merge(survivor.getId(), loser.getId(), null, null,
                    "cluster_merge 자동 병합 (유사도 %.4f)".formatted(similarity));
            // 승자·패자는 MergeService가 이미 저장했다 — candidate가 그중 하나라도 다시 findById로
            // 최신 버전을 읽어와야 낙관적 락(version) 충돌 없이 markMergeChecked를 저장할 수 있다.
            markChecked(trendItems.findById(candidateId).orElseThrow(), now);
            return true;
        }

        if (similarity >= QUEUE_THRESHOLD) {
            BigDecimal simScore = BigDecimal.valueOf(similarity).setScale(4, RoundingMode.HALF_UP);
            mergeQueue.save(new MergeQueueEntry(candidate.getId(), other.getId(), simScore));
            markChecked(candidate, now);
            return false;
        }

        markChecked(candidate, now);
        return false;
    }

    private void markChecked(TrendItem item, Instant now) {
        item.markMergeChecked(now);
        trendItems.save(item);
    }
}
