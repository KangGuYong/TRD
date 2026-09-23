package kr.trendstage.apiadmin.merge;

import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.judge.JudgeService;
import kr.trendstage.merge.MergeConflictException;
import kr.trendstage.merge.MergeService;
import kr.trendstage.persistence.entity.MergeQueueEntry;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.MergeQueueRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.MergeQueueStatus;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * ADM-100 큐 결정(병합·분리·VOID). 큐 행 FOR UPDATE → 멱등키 확인 → 항목 쌍 잠금(id 오름차순) → 실행을
 * 한 트랜잭션으로 한다(SP2 §2.4·§3). 멱등은 merge_queue.decision_key UNIQUE가 DB로 보장한다(K5).
 */
@Service
public class MergeDecisionService {

    public enum Decision { MERGE, SEPARATE, VOID }

    public record MergeDecisionResponse(String queueId, Decision decision, String status,
                                        String survivorId, String loserId,
                                        String decidedBy, String decidedAt, boolean replayed) {}

    /** stale = 대상 항목이 이미 다른 항목으로 병합돼 후보를 정리함(컨트롤러가 409 merge-target-merged로 응답). */
    public record Outcome(MergeDecisionResponse body, boolean stale) {}

    private final MergeQueueRepository mergeQueue;
    private final TrendItemRepository trendItems;
    private final MergeService mergeService;
    private final JudgeService judgeService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public MergeDecisionService(MergeQueueRepository mergeQueue, TrendItemRepository trendItems,
                                MergeService mergeService, JudgeService judgeService,
                                AuditLogService auditLogService, Clock clock) {
        this.mergeQueue = mergeQueue;
        this.trendItems = trendItems;
        this.mergeService = mergeService;
        this.judgeService = judgeService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Transactional
    public Outcome decide(UUID queueId, Decision decision, String key, UUID actorId, AdminRole actorRole, String reason) {
        MergeQueueEntry entry = mergeQueue.findByIdForUpdate(queueId)
                .orElseThrow(() -> new AdminValidationException("존재하지 않는 큐 항목입니다"));

        if (key.equals(entry.getDecisionKey())) {
            if (decisionOf(entry.getStatus()) != decision) {
                throw new IdempotencyKeyMismatchException("같은 멱등키로 다른 결정을 보냈습니다");
            }
            return new Outcome(responseOf(entry, true), false);
        }
        if (entry.getStatus() != MergeQueueStatus.PENDING) {
            throw new MergeConflictException(MergeConflictException.Reason.QUEUE_DECIDED,
                    "이미 처리된 후보입니다 (%s)".formatted(entry.getStatus().name()));
        }
        if (mergeQueue.findByDecisionKey(key).isPresent()) {
            throw new IdempotencyKeyMismatchException("이 멱등키는 다른 후보에 이미 쓰였습니다");
        }

        UUID newId = entry.getNewTrendItemId();
        UUID oldId = entry.getOldTrendItemId();
        Map<UUID, TrendItem> items = mergeService.lockPair(newId, oldId);
        TrendItem newItem = items.get(newId);
        TrendItem oldItem = items.get(oldId);
        Instant now = clock.instant();

        boolean stale = switch (decision) {
            case MERGE, SEPARATE -> newItem.getState() == TrendState.MERGED || oldItem.getState() == TrendState.MERGED;
            case VOID -> newItem.getState() == TrendState.MERGED;
        };
        if (stale) {
            // 상태 이름(SEPARATED 등)은 큐 워크플로 묶음(SP2b)에서 정리한다. 키는 기록하지 않는다 — 결정이 아니라 정리다.
            entry.resolve(MergeQueueStatus.SKIPPED, actorId, now, null);
            auditLogService.record(actorId, actorRole, "MERGE_QUEUE_STALE", "TREND_ITEM", newId, Map.of(
                    "queueId", queueId.toString(), "decision", decision.name()));
            return new Outcome(responseOf(entry, false), true);
        }

        switch (decision) {
            case MERGE -> {
                TrendItem survivor = newItem.getFirstSeenAt().isBefore(oldItem.getFirstSeenAt()) ? newItem : oldItem;
                TrendItem loser = survivor == newItem ? oldItem : newItem;
                mergeService.merge(survivor.getId(), loser.getId(), actorId, actorRole, reason);
                entry.resolve(MergeQueueStatus.MERGED, actorId, now, key);
            }
            case SEPARATE -> {
                mergeService.recordSeparateDecision(actorId, actorRole, newId, oldId, reason);
                entry.resolve(MergeQueueStatus.SKIPPED, actorId, now, key);
            }
            case VOID -> {
                // 항목 VOID는 판정 사건이다(P5) — 판정된 항목이면 원장 상쇄까지 JudgeService가 한다
                judgeService.voidItem(newId, reason, now);
                auditLogService.record(actorId, actorRole, "MERGE_VOID", "TREND_ITEM", newId,
                        Map.of("reason", reason == null ? "" : reason));
                entry.resolve(MergeQueueStatus.VOIDED, actorId, now, key);
            }
        }
        // 위의 findByDecisionKey 확인과 이 기록 사이에 다른 후보가 같은 키를 먼저 커밋하면 UNIQUE가 막는다.
        // 여기서 flush해 그 위반을 커밋 시점의 500 대신 422로 돌려준다(트랜잭션은 예외와 함께 롤백된다).
        try {
            mergeQueue.flush();
        } catch (DataIntegrityViolationException e) {
            throw new IdempotencyKeyMismatchException("이 멱등키는 다른 후보에 이미 쓰였습니다");
        }
        return new Outcome(responseOf(entry, false), false);
    }

    private MergeDecisionResponse responseOf(MergeQueueEntry e, boolean replayed) {
        String survivorId = null, loserId = null;
        if (e.getStatus() == MergeQueueStatus.MERGED) {
            TrendItem n = trendItems.findById(e.getNewTrendItemId()).orElseThrow();
            TrendItem o = trendItems.findById(e.getOldTrendItemId()).orElseThrow();
            TrendItem loser = n.getState() == TrendState.MERGED ? n : o;
            loserId = loser.getId().toString();
            survivorId = loser.getMergedInto().toString();
        }
        return new MergeDecisionResponse(e.getId().toString(), decisionOf(e.getStatus()), e.getStatus().name(),
                survivorId, loserId,
                e.getResolvedBy() == null ? null : e.getResolvedBy().toString(),
                e.getResolvedAt() == null ? null : e.getResolvedAt().toString(),
                replayed);
    }

    private static Decision decisionOf(MergeQueueStatus status) {
        return switch (status) {
            case MERGED -> Decision.MERGE;
            case SKIPPED -> Decision.SEPARATE;
            case VOIDED -> Decision.VOID;
            case PENDING -> null;
        };
    }
}
