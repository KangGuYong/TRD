package kr.trendstage.merge;

import kr.trendstage.audit.AuditLogService;
import kr.trendstage.merge.ClusterMergeCandidateService.ProcessOutcome;
import kr.trendstage.persistence.entity.MergeQueueEntry;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.MergeQueueRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * cluster_merge 후보 하나의 결정 + 확인 표시를 한 트랜잭션으로 묶는다(SP2 §2.3). 임베딩 계산(외부 HTTP)은
 * 호출 측(ClusterMergeCandidateService)이 트랜잭션 밖에서 끝낸다. 같은 클래스 안 호출로는 @Transactional이
 * 걸리지 않아 별도 빈으로 뺐다.
 *
 * 후보 항목은 잠그지 않는다 — 병합이 필요하면 MergeService가 두 항목을 id 오름차순으로 잠근다.
 * 여기서 후보를 먼저 잠그면 반대 순서로 잠그는 관리자 결정과 교착할 수 있다.
 */
@Service
public class ClusterMergeDecider {

    private final TrendItemRepository trendItems;
    private final MergeQueueRepository mergeQueue;
    private final MergeService mergeService;
    private final MergeGuard guard;
    private final AuditLogService auditLogService;

    public ClusterMergeDecider(TrendItemRepository trendItems, MergeQueueRepository mergeQueue,
                               MergeService mergeService, MergeGuard guard, AuditLogService auditLogService) {
        this.trendItems = trendItems;
        this.mergeQueue = mergeQueue;
        this.mergeService = mergeService;
        this.guard = guard;
        this.auditLogService = auditLogService;
    }

    /**
     * @param otherId 가장 닮은 항목(없으면 null)
     * @throws MergeConflictException 병합이 판정·다른 병합과 경합해 거부됨 — 확인 표시가 롤백되어 다음 실행에서 다시 본다
     */
    @Transactional
    public ProcessOutcome decide(UUID candidateId, UUID otherId, double similarity, Instant now) {
        ProcessOutcome outcome = otherId == null ? ProcessOutcome.SEPARATED : ClusterMergeCandidateService.classify(similarity);
        if (outcome == ProcessOutcome.SEPARATED) {
            markChecked(candidateId, now);
            return outcome;
        }

        BigDecimal simScore = BigDecimal.valueOf(similarity).setScale(4, RoundingMode.HALF_UP);
        TrendItem other = trendItems.findById(otherId).orElseThrow();
        if (!guard.isMergeable(other)) {
            // 판정 후 병합은 Phase 2(O9). 병합·큐 없이 기록만 남겨 빈도 근거로 쓴다(K4).
            auditLogService.record(null, null, "MERGE_SKIPPED_JUDGED", "TREND_ITEM", candidateId, Map.of(
                    "comparedTo", otherId.toString(),
                    "comparedState", other.getState().name(),
                    "similarity", simScore.toPlainString()));
            markChecked(candidateId, now);
            return ProcessOutcome.SKIPPED_JUDGED;
        }

        TrendItem candidate = trendItems.findById(candidateId).orElseThrow();
        if (outcome == ProcessOutcome.AUTO_MERGED) {
            TrendItem survivor = candidate.getFirstSeenAt().isBefore(other.getFirstSeenAt()) ? candidate : other;
            TrendItem loser = survivor == candidate ? other : candidate;
            mergeService.merge(survivor.getId(), loser.getId(), null, null,
                    "cluster_merge 자동 병합 (유사도 %s)".formatted(simScore.toPlainString()));
        } else {
            mergeQueue.save(new MergeQueueEntry(candidateId, otherId, simScore));
        }
        markChecked(candidateId, now);
        return outcome;
    }

    private void markChecked(UUID itemId, Instant now) {
        trendItems.findById(itemId).orElseThrow().markMergeChecked(now);   // 관리 엔티티 — 커밋 시 flush
    }
}
