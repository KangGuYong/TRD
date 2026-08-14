package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.merge.MergeService;
import kr.trendstage.persistence.entity.*;
import kr.trendstage.persistence.repo.*;
import kr.trendstage.persistence.type.MergeQueueStatus;
import kr.trendstage.persistence.type.SubmissionResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * ADM-100 병합 검수 큐. 권한(02 §1.1): 병합/분리는 REVIEWER 이상, VOID는 OPERATOR 이상.
 * Type1(신규 제보 ↔ 기존 클러스터)만 다룬다 — 관리자가 임의로 두 클러스터를 고르는 수동 병합(Type2)은 아직 없음.
 */
@RestController
@RequestMapping("/admin/merge-queue")
public class MergeQueueController {

    private final MergeQueueRepository mergeQueue;
    private final TrendItemRepository trendItems;
    private final SubmissionRepository submissions;
    private final UserRepository users;
    private final UserGradeRepository userGrades;
    private final MergeService mergeService;
    private final Clock clock;

    public MergeQueueController(MergeQueueRepository mergeQueue, TrendItemRepository trendItems,
                                 SubmissionRepository submissions, UserRepository users,
                                 UserGradeRepository userGrades, MergeService mergeService, Clock clock) {
        this.mergeQueue = mergeQueue;
        this.trendItems = trendItems;
        this.submissions = submissions;
        this.users = users;
        this.userGrades = userGrades;
        this.mergeService = mergeService;
        this.clock = clock;
    }

    public record KV(String k, String v) {}
    public record MergeCandidateResponse(String id, double similarity, String newName, String oldName,
                                          String oldClusterId, String ago, List<KV> newRows, List<KV> oldRows,
                                          List<String> orderPreview) {}
    public record DecisionRequest(String reason) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN', 'AUDITOR')")
    public List<MergeCandidateResponse> list() {
        return mergeQueue.findByStatusOrderByCreatedAtAsc(MergeQueueStatus.PENDING).stream()
                .map(this::toResponse)
                .toList();
    }

    @PostMapping("/{id}/merge")
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN')")
    @Transactional
    public void merge(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest req,
                       @AuthenticationPrincipal AdminPrincipal actor) {
        MergeQueueEntry entry = requirePending(id);
        TrendItem newItem = trendItems.findById(entry.getNewTrendItemId()).orElseThrow();
        TrendItem oldItem = trendItems.findById(entry.getOldTrendItemId()).orElseThrow();

        TrendItem survivor = newItem.getFirstSeenAt().isBefore(oldItem.getFirstSeenAt()) ? newItem : oldItem;
        TrendItem loser = survivor == newItem ? oldItem : newItem;
        String reason = req == null ? null : req.reason();

        mergeService.merge(survivor.getId(), loser.getId(), actor.id(), actor.role(), reason);
        entry.resolve(MergeQueueStatus.MERGED, actor.id(), clock.instant());
    }

    @PostMapping("/{id}/separate")
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN')")
    @Transactional
    public void separate(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest req,
                          @AuthenticationPrincipal AdminPrincipal actor) {
        MergeQueueEntry entry = requirePending(id);
        String reason = req == null ? null : req.reason();
        mergeService.recordSeparateDecision(actor.id(), actor.role(), entry.getNewTrendItemId(), entry.getOldTrendItemId(), reason);
        entry.resolve(MergeQueueStatus.SKIPPED, actor.id(), clock.instant());
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Transactional
    public void voidCandidate(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest req,
                               @AuthenticationPrincipal AdminPrincipal actor) {
        MergeQueueEntry entry = requirePending(id);
        String reason = req == null ? null : req.reason();
        mergeService.voidTrendItem(entry.getNewTrendItemId(), actor.id(), actor.role(), reason);
        entry.resolve(MergeQueueStatus.VOIDED, actor.id(), clock.instant());
    }

    private MergeQueueEntry requirePending(UUID id) {
        MergeQueueEntry entry = mergeQueue.findById(id)
                .orElseThrow(() -> new AdminValidationException("존재하지 않는 큐 항목입니다"));
        if (entry.getStatus() != MergeQueueStatus.PENDING) {
            throw new AdminValidationException("이미 처리된 큐 항목입니다");
        }
        return entry;
    }

    private MergeCandidateResponse toResponse(MergeQueueEntry entry) {
        TrendItem newItem = trendItems.findById(entry.getNewTrendItemId()).orElseThrow();
        TrendItem oldItem = trendItems.findById(entry.getOldTrendItemId()).orElseThrow();

        List<KV> newRows = new ArrayList<>();
        newRows.add(new KV("카테고리", newItem.getCategory().name()));
        submissions.findFirstByTrendItemIdOrderByCreatedAtAsc(newItem.getId()).ifPresent(founding -> {
            newRows.add(new KV("플랫폼", founding.getSourcePlatform()));
            String submitter = users.findById(founding.getUserId()).map(UserAccount::getHandle).orElse("(탈퇴)");
            String gradeInfo = userGrades.findTopByUserIdOrderByComputedAtDesc(founding.getUserId())
                    .map(g -> "TI %.2f / %s".formatted(g.getTrustIndex().doubleValue(), g.getGrade()))
                    .orElse("미평가");
            newRows.add(new KV("제보자", submitter + " · " + gradeInfo));
            newRows.add(new KV("URL", "1건"));
        });

        long oldCount = submissions.countByTrendItemIdAndResultNot(oldItem.getId(), SubmissionResult.VOID);
        long daysAgo = Duration.between(oldItem.getFirstSeenAt(), clock.instant()).toDays();
        List<KV> oldRows = List.of(
                new KV("제보", "%d건 (order 1~%d)".formatted(oldCount, oldCount)),
                new KV("최초", daysAgo <= 0 ? "오늘" : daysAgo + "일 전"),
                new KV("상태", oldItem.getState().name())
        );

        List<String> orderPreview = buildOrderPreview(newItem.getId(), oldItem.getId());

        return new MergeCandidateResponse(
                entry.getId().toString(), entry.getSimilarity().doubleValue(),
                newItem.getCanonicalName(), oldItem.getCanonicalName(),
                "#" + oldItem.getId().toString().substring(0, 8),
                formatAgo(entry.getCreatedAt()),
                newRows, oldRows, orderPreview);
    }

    private List<String> buildOrderPreview(UUID newTrendItemId, UUID oldTrendItemId) {
        List<Submission> combined = new ArrayList<>();
        combined.addAll(submissions.findByTrendItemIdAndResultNot(newTrendItemId, SubmissionResult.VOID));
        combined.addAll(submissions.findByTrendItemIdAndResultNot(oldTrendItemId, SubmissionResult.VOID));
        combined.sort(Comparator.comparing(Submission::getCreatedAt));

        List<String> preview = new ArrayList<>();
        int rank = 1;
        for (Submission s : combined) {
            String handle = users.findById(s.getUserId()).map(UserAccount::getHandle).orElse("탈퇴유저");
            preview.add("order%d %s".formatted(rank++, handle));
        }
        return preview;
    }

    private static String formatAgo(Instant createdAt) {
        Duration d = Duration.between(createdAt, Instant.now());
        if (d.toHours() < 1) return d.toMinutes() + "분 전";
        if (d.toDays() < 1) return d.toHours() + "h 전";
        return d.toDays() + "일 전";
    }
}
