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

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 병합 실행(03 §3·§4). 배치(cluster_merge, 0.85↑ 자동)와 ADM-100 관리자 확정이 공유한다.
 * actorId가 null이면 배치 자동 실행 — 감사로그에 그대로 기록된다(다른 자동화 이벤트와 동일 패턴).
 */
@Service
public class MergeService {

    private final TrendItemRepository trendItems;
    private final SubmissionRepository submissions;
    private final AuditLogService auditLogService;

    public MergeService(TrendItemRepository trendItems, SubmissionRepository submissions,
                         AuditLogService auditLogService) {
        this.trendItems = trendItems;
        this.submissions = submissions;
        this.auditLogService = auditLogService;
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

    /** ADM-100 "VOID" — 신규 항목 자체를 무효 처리(허위·규정위반 등). 그 항목의 제보도 함께 VOID. */
    @Transactional
    public void voidTrendItem(UUID trendItemId, UUID actorId, AdminRole actorRole, String reason) {
        TrendItem item = trendItems.findById(trendItemId)
                .orElseThrow(() -> new IllegalStateException("항목이 없습니다: " + trendItemId));
        if (item.getState() == TrendState.MERGED || item.getState() == TrendState.VOID) {
            return;
        }
        for (Submission s : submissions.findByTrendItemIdAndResultNot(trendItemId, SubmissionResult.VOID)) {
            s.voidOut();
        }
        item.transitionTo(TrendState.VOID);

        auditLogService.record(actorId, actorRole, "MERGE_VOID", "TREND_ITEM", trendItemId, Map.of(
                "reason", reason == null ? "" : reason
        ));
    }

    /** 같은 유저가 두 클러스터 모두에 유효 제보를 낸 경우, 늦은 쪽을 VOID(03 §4.4) — 헤지 방지. */
    private void dedupSameUserSubmissions(UUID trendItemA, UUID trendItemB) {
        List<Submission> a = submissions.findByTrendItemIdAndResultNot(trendItemA, SubmissionResult.VOID);
        List<Submission> b = submissions.findByTrendItemIdAndResultNot(trendItemB, SubmissionResult.VOID);
        Map<UUID, List<Submission>> byUser = new HashMap<>();
        for (Submission s : a) byUser.computeIfAbsent(s.getUserId(), k -> new ArrayList<>()).add(s);
        for (Submission s : b) byUser.computeIfAbsent(s.getUserId(), k -> new ArrayList<>()).add(s);

        for (List<Submission> group : byUser.values()) {
            if (group.size() < 2) continue;
            group.sort(Comparator.comparing(Submission::getCreatedAt));
            // 가장 이른 것만 유효로 남기고 나머지(같은 유저의 늦은 중복)는 VOID.
            for (int i = 1; i < group.size(); i++) group.get(i).voidOut();
        }
    }

    private void mergeAliases(TrendItem survivor, TrendItem loser) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(Arrays.asList(survivor.getAliases()));
        merged.add(loser.getNormalizedKey());
        merged.addAll(Arrays.asList(loser.getAliases()));
        survivor.setAliases(merged.toArray(new String[0]));
    }

    private void recomputeCanonicalName(TrendItem survivor) {
        List<Submission> active = submissions.findByTrendItemIdAndResultNot(survivor.getId(), SubmissionResult.VOID);
        if (active.isEmpty()) return;
        Map<String, List<Submission>> byRawInput = active.stream()
                .collect(Collectors.groupingBy(Submission::getRawInput));
        String best = null;
        int bestCount = -1;
        Instant bestEarliest = null;
        for (var e : byRawInput.entrySet()) {
            int count = e.getValue().size();
            Instant earliest = e.getValue().stream().map(Submission::getCreatedAt).min(Instant::compareTo).orElseThrow();
            if (count > bestCount || (count == bestCount && earliest.isBefore(bestEarliest))) {
                best = e.getKey();
                bestCount = count;
                bestEarliest = earliest;
            }
        }
        survivor.setCanonicalName(best);
    }
}
