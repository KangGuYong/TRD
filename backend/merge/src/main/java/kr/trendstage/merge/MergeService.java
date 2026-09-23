package kr.trendstage.merge;

import kr.trendstage.audit.AuditLogService;
import kr.trendstage.domain.verdict.DeadlineWindow;
import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.SubmissionResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * 병합 실행(03 §3·§4). 배치(cluster_merge, 0.85↑ 자동)와 ADM-100 관리자 확정이 공유한다.
 * actorId가 null이면 배치 자동 실행 — 감사로그에 그대로 기록된다.
 *
 * 두 항목을 id 오름차순으로 SELECT … FOR UPDATE 잠근다. JudgeService가 같은 행 잠금을 잡으므로
 * 병합과 판정·VOID가 직렬화된다(SP2 K3). 가드는 MergeGuard(K1).
 */
@Service
public class MergeService {

    private final TrendItemRepository trendItems;
    private final SubmissionRepository submissions;
    private final MergeGuard guard;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public MergeService(TrendItemRepository trendItems, SubmissionRepository submissions, MergeGuard guard,
                         AuditLogService auditLogService, Clock clock) {
        this.trendItems = trendItems;
        this.submissions = submissions;
        this.guard = guard;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /**
     * 두 항목을 id 오름차순으로 잠근다. 모든 쌍 잠금이 같은 순서를 쓰므로 교착이 없다
     * (순서는 java.util.UUID 비교 — 쌍을 잠그는 코드가 전부 이 메서드를 거치면 일관된다).
     * 호출 측 트랜잭션 안에서만 의미가 있다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, TrendItem> lockPair(UUID a, UUID b) {
        Map<UUID, TrendItem> locked = new HashMap<>();
        for (UUID id : Stream.of(a, b).sorted().toList()) {
            locked.put(id, trendItems.findByIdForUpdate(id)
                    .orElseThrow(() -> new IllegalStateException("항목이 없습니다: " + id)));
        }
        return locked;
    }

    /** survivor(승자)로 loser(패자)를 흡수한다. 승자 결정은 호출 측 책임(first_seen_at 이른 쪽, 03 §4.1). */
    @Transactional
    public void merge(UUID survivorId, UUID loserId, UUID actorId, AdminRole actorRole, String reason) {
        Map<UUID, TrendItem> locked = lockPair(survivorId, loserId);
        TrendItem survivor = locked.get(survivorId);
        TrendItem loser = locked.get(loserId);
        guard.require(survivor);
        guard.require(loser);

        Instant now = clock.instant();
        Instant deadlineBefore = DeadlineWindow.effectiveDeadline(
                survivor.getFirstSeenAt(), survivor.getJudgmentDeadlineOverride());

        dedupSameUserSubmissions(survivorId, loserId, now);
        for (Submission s : submissions.findByTrendItemId(loserId)) {
            s.reassignTrendItem(survivorId);
        }
        // 선점 순위는 submission_order_rank 뷰가 파생하므로 재배정만으로 같은 트랜잭션 안에서 다시 매겨진다
        mergeAliases(survivor, loser);
        survivor.pullForwardFirstSeen(loser.getFirstSeenAt());
        recomputeCanonicalName(survivor);

        // 최소 관측 보장(K7): 당겨진 마감이 병합 시점 + 3일보다 이르면 연장(상한 D+21). 마감을 당기지는 않는다.
        Optional<DeadlineWindow.MergeDeadline> guarded = DeadlineWindow.afterMerge(
                survivor.getFirstSeenAt(), survivor.getJudgmentDeadlineOverride(), now);
        guarded.ifPresent(d -> survivor.extendJudgmentDeadline(d.override()));
        Instant deadlineAfter = DeadlineWindow.effectiveDeadline(
                survivor.getFirstSeenAt(), survivor.getJudgmentDeadlineOverride());

        loser.mergeInto(survivorId);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("loserId", loserId.toString());
        detail.put("loserName", loser.getCanonicalName());
        detail.put("survivorName", survivor.getCanonicalName());
        detail.put("reason", reason == null ? "" : reason);
        if (!deadlineAfter.equals(deadlineBefore)) {
            detail.put("deadlineBefore", deadlineBefore.toString());
            detail.put("deadlineAfter", deadlineAfter.toString());
        }
        if (guarded.map(DeadlineWindow.MergeDeadline::capped).orElse(false)) {
            detail.put("minObservationCapped", true);
        }
        auditLogService.record(actorId, actorRole, "MERGE", "TREND_ITEM", survivorId, detail);
    }

    /** ADM-100 "분리" — 유사도 제안을 기각. 데이터는 건드리지 않고 두 항목을 계속 독립적으로 둔다. */
    public void recordSeparateDecision(UUID actorId, AdminRole actorRole, UUID newTrendItemId, UUID oldTrendItemId, String reason) {
        auditLogService.record(actorId, actorRole, "MERGE_SEPARATE", "TREND_ITEM", newTrendItemId, Map.of(
                "comparedTo", oldTrendItemId.toString(),
                "reason", reason == null ? "" : reason
        ));
    }

    /**
     * ADM-100 병합 후 미리보기(dry-run). DB에 아무것도 쓰지 않는다 — merge()와 같은 순수 함수를
     * 재사용하므로 실제 병합 결과와 어긋날 수 없다.
     */
    @Transactional(readOnly = true)
    public PreviewResult preview(UUID survivorId, UUID loserId) {
        TrendItem survivor = trendItems.findById(survivorId)
                .orElseThrow(() -> new IllegalStateException("승자 항목이 없습니다: " + survivorId));
        TrendItem loser = trendItems.findById(loserId)
                .orElseThrow(() -> new IllegalStateException("패자 항목이 없습니다: " + loserId));

        List<MergeComputation.SubmissionInput> a = toInputs(
                submissions.findByTrendItemIdAndResultNot(survivorId, SubmissionResult.VOID));
        List<MergeComputation.SubmissionInput> b = toInputs(
                submissions.findByTrendItemIdAndResultNot(loserId, SubmissionResult.VOID));

        Set<UUID> voided = MergeComputation.computeDedup(a, b);
        Set<UUID> seedIds = new HashSet<>();
        List<MergeComputation.SubmissionInput> activeAfterDedup = new ArrayList<>();
        for (var s : a) { if (s.seed()) seedIds.add(s.submissionId()); if (!voided.contains(s.submissionId())) activeAfterDedup.add(s); }
        for (var s : b) { if (s.seed()) seedIds.add(s.submissionId()); if (!voided.contains(s.submissionId())) activeAfterDedup.add(s); }
        Set<UUID> quotaRefund = new HashSet<>(voided);
        quotaRefund.removeAll(seedIds);   // 시딩은 제보권 대상이 아니다

        String newCanonicalName = MergeComputation.computeCanonicalName(activeAfterDedup)
                .orElse(survivor.getCanonicalName());
        List<MergeComputation.OrderComputed> orderAfter = MergeComputation.computeCombinedOrder(activeAfterDedup);

        Instant firstSeenAtBefore = survivor.getFirstSeenAt();
        Instant firstSeenAtAfter = loser.getFirstSeenAt().isBefore(firstSeenAtBefore)
                ? loser.getFirstSeenAt() : firstSeenAtBefore;

        Instant deadlineBefore = DeadlineWindow.effectiveDeadline(firstSeenAtBefore, survivor.getJudgmentDeadlineOverride());
        Optional<DeadlineWindow.MergeDeadline> guarded = DeadlineWindow.afterMerge(
                firstSeenAtAfter, survivor.getJudgmentDeadlineOverride(), clock.instant());
        Instant deadlineAfter = guarded.map(DeadlineWindow.MergeDeadline::override)
                .orElse(DeadlineWindow.effectiveDeadline(firstSeenAtAfter, survivor.getJudgmentDeadlineOverride()));

        return new PreviewResult(newCanonicalName, orderAfter, voided, quotaRefund,
                firstSeenAtBefore, firstSeenAtAfter, deadlineBefore, deadlineAfter, guarded.isPresent());
    }

    public record PreviewResult(
            String newCanonicalName,
            List<MergeComputation.OrderComputed> orderAfter,
            Set<UUID> dedupVoidedSubmissionIds,
            Set<UUID> quotaRefundSubmissionIds,
            Instant firstSeenAtBefore,
            Instant firstSeenAtAfter,
            Instant deadlineBefore,
            Instant deadlineAfter,
            boolean deadlineGuarded
    ) {}

    /** 같은 유저가 두 클러스터 모두에 유효 제보를 낸 경우, 늦은 쪽을 VOID(03 §4.4) — 헤지 방지. voided_at이 제보권을 돌려준다. */
    private void dedupSameUserSubmissions(UUID trendItemA, UUID trendItemB, Instant now) {
        List<Submission> a = submissions.findByTrendItemIdAndResultNot(trendItemA, SubmissionResult.VOID);
        List<Submission> b = submissions.findByTrendItemIdAndResultNot(trendItemB, SubmissionResult.VOID);
        Map<UUID, Submission> byId = new HashMap<>();
        for (Submission s : a) byId.put(s.getId(), s);
        for (Submission s : b) byId.put(s.getId(), s);

        Set<UUID> voided = MergeComputation.computeDedup(toInputs(a), toInputs(b));
        for (UUID id : voided) byId.get(id).voidOut(now);
    }

    private void mergeAliases(TrendItem survivor, TrendItem loser) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(Arrays.asList(survivor.getAliases()));
        merged.add(loser.getNormalizedKey());
        merged.addAll(Arrays.asList(loser.getAliases()));
        survivor.setAliases(merged.toArray(new String[0]));
    }

    private void recomputeCanonicalName(TrendItem survivor) {
        List<Submission> active = submissions.findByTrendItemIdAndResultNot(survivor.getId(), SubmissionResult.VOID);
        MergeComputation.computeCanonicalName(toInputs(active)).ifPresent(survivor::setCanonicalName);
    }

    private static List<MergeComputation.SubmissionInput> toInputs(List<Submission> subs) {
        return subs.stream()
                .map(s -> new MergeComputation.SubmissionInput(
                        s.getId(), s.getUserId(), s.getRawInput(), s.getCreatedAt(), s.isSeed()))
                .toList();
    }
}
