package kr.trendstage.merge;

import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.SubmissionResult;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * 병합 실행(03 §3·§4). 배치(cluster_merge, 0.85↑ 자동)와 ADM-100 관리자 확정이 공유한다.
 * actorId가 null이면 배치 자동 실행 — 감사로그에 그대로 기록된다(다른 자동화 이벤트와 동일 패턴).
 */
@Service
public class MergeService {

    private final TrendItemRepository trendItems;
    private final SubmissionRepository submissions;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public MergeService(TrendItemRepository trendItems, SubmissionRepository submissions,
                         AuditLogService auditLogService, Clock clock) {
        this.trendItems = trendItems;
        this.submissions = submissions;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /**
     * survivor(승자)로 loser(패자)를 흡수한다. 승자 결정은 호출 측 책임(first_seen_at 이른 쪽, 03 §4.1).
     */
    @Transactional
    public void merge(UUID survivorId, UUID loserId, UUID actorId, AdminRole actorRole, String reason) {
        // 동시 병합 충돌은 TrendItem.version(낙관적 락, 03 §6)이 커밋 시점에 잡아낸다 —
        // 여기서 실패하면 OptimisticLockingFailureException이 올라가고 배치/컨트롤러가 해당 건만 스킵한다.
        TrendItem survivor = trendItems.findById(survivorId)
                .orElseThrow(() -> new IllegalStateException("승자 항목이 없습니다: " + survivorId));
        TrendItem loser = trendItems.findById(loserId)
                .orElseThrow(() -> new IllegalStateException("패자 항목이 없습니다: " + loserId));

        if (survivor.getState() == TrendState.MERGED || loser.getState() == TrendState.MERGED) {
            // 멱등 가드: 이미 처리된 큐 항목의 재실행(재시도·중복 클릭)은 조용히 무시.
            return;
        }

        dedupSameUserSubmissions(survivorId, loserId);

        for (Submission s : submissions.findByTrendItemId(loserId)) {
            s.reassignTrendItem(survivorId);
        }

        mergeAliases(survivor, loser);
        survivor.pullForwardFirstSeen(loser.getFirstSeenAt());
        recomputeCanonicalName(survivor);

        loser.mergeInto(survivorId);

        auditLogService.record(actorId, actorRole, "MERGE", "TREND_ITEM", survivorId, Map.of(
                "loserId", loserId.toString(),
                "loserName", loser.getCanonicalName(),
                "survivorName", survivor.getCanonicalName(),
                "reason", reason == null ? "" : reason
        ));
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
     * 재사용하므로 실제 병합 결과와 어긋날 수 없다(readOnly 트랜잭션으로 실수 저장도 방지).
     */
    @Transactional(readOnly = true)
    public PreviewResult preview(UUID survivorId, UUID loserId) {
        TrendItem survivor = trendItems.findById(survivorId)
                .orElseThrow(() -> new IllegalStateException("승자 항목이 없습니다: " + survivorId));
        TrendItem loser = trendItems.findById(loserId)
                .orElseThrow(() -> new IllegalStateException("패자 항목이 없습니다: " + loserId));

        List<Submission> survivorSubs = submissions.findByTrendItemIdAndResultNot(survivorId, SubmissionResult.VOID);
        List<Submission> loserSubs = submissions.findByTrendItemIdAndResultNot(loserId, SubmissionResult.VOID);

        List<MergeComputation.SubmissionInput> a = toInputs(survivorSubs);
        List<MergeComputation.SubmissionInput> b = toInputs(loserSubs);

        Set<UUID> voided = MergeComputation.computeDedup(a, b);
        List<MergeComputation.SubmissionInput> activeAfterDedup = new ArrayList<>();
        for (var s : a) if (!voided.contains(s.submissionId())) activeAfterDedup.add(s);
        for (var s : b) if (!voided.contains(s.submissionId())) activeAfterDedup.add(s);

        String newCanonicalName = MergeComputation.computeCanonicalName(activeAfterDedup)
                .orElse(survivor.getCanonicalName());
        List<MergeComputation.OrderComputed> orderAfter = MergeComputation.computeCombinedOrder(activeAfterDedup);

        Instant firstSeenAtBefore = survivor.getFirstSeenAt();
        Instant firstSeenAtAfter = loser.getFirstSeenAt().isBefore(firstSeenAtBefore)
                ? loser.getFirstSeenAt() : firstSeenAtBefore;
        boolean baselineShifted = loser.getFirstSeenAt().isBefore(firstSeenAtBefore);

        return new PreviewResult(newCanonicalName, orderAfter, voided, firstSeenAtBefore, firstSeenAtAfter, baselineShifted);
    }

    public record PreviewResult(
            String newCanonicalName,
            List<MergeComputation.OrderComputed> orderAfter,
            Set<UUID> dedupVoidedSubmissionIds,
            Instant firstSeenAtBefore,
            Instant firstSeenAtAfter,
            boolean baselineShifted
    ) {}

    /** 같은 유저가 두 클러스터 모두에 유효 제보를 낸 경우, 늦은 쪽을 VOID(03 §4.4) — 헤지 방지. */
    private void dedupSameUserSubmissions(UUID trendItemA, UUID trendItemB) {
        List<Submission> a = submissions.findByTrendItemIdAndResultNot(trendItemA, SubmissionResult.VOID);
        List<Submission> b = submissions.findByTrendItemIdAndResultNot(trendItemB, SubmissionResult.VOID);
        Map<UUID, Submission> byId = new HashMap<>();
        for (Submission s : a) byId.put(s.getId(), s);
        for (Submission s : b) byId.put(s.getId(), s);

        Set<UUID> voided = MergeComputation.computeDedup(toInputs(a), toInputs(b));
        for (UUID id : voided) byId.get(id).voidOut(clock.instant());
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
