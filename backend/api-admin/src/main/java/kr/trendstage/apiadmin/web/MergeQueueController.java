package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.judge.JudgeService;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
    private final SubmissionOrderRankRepository orderRanks;
    private final UserRepository users;
    private final UserGradeRepository userGrades;
    private final MergeService mergeService;
    private final JudgeService judgeService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    public MergeQueueController(MergeQueueRepository mergeQueue, TrendItemRepository trendItems,
                                 SubmissionRepository submissions, SubmissionOrderRankRepository orderRanks,
                                 UserRepository users, UserGradeRepository userGrades,
                                 MergeService mergeService, JudgeService judgeService,
                                 AuditLogService auditLogService, Clock clock) {
        this.mergeQueue = mergeQueue;
        this.trendItems = trendItems;
        this.submissions = submissions;
        this.orderRanks = orderRanks;
        this.users = users;
        this.userGrades = userGrades;
        this.mergeService = mergeService;
        this.judgeService = judgeService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    public record KV(String k, String v) {}
    public record SubmissionDetail(String handle, String rawInput, String oneLine,
                                    String evidenceUrl, String createdAt) {}
    public record MergeCandidateResponse(String id, double similarity, String newName, String oldName,
                                          String oldClusterId, String ago, List<KV> newRows, List<KV> oldRows,
                                          List<String> orderPreview, List<SubmissionDetail> newSubmissions,
                                          List<SubmissionDetail> oldSubmissions) {}
    public record DecisionRequest(String reason) {}

    public record OrderEntry(String handle, Integer rankBefore, int rankAfter) {}
    public record MergePreviewResponse(String newCanonicalName, List<OrderEntry> orderRank,
                                        String firstSeenAtBefore, String firstSeenAtAfter,
                                        boolean baselineShifted, List<String> dedupVoidedHandles) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN', 'AUDITOR')")
    public List<MergeCandidateResponse> list() {
        return mergeQueue.findByStatusOrderByCreatedAtAsc(MergeQueueStatus.PENDING).stream()
                .map(this::toResponse)
                .toList();
    }

    /** 온디맨드 dry-run — 병합 실행과 같은 계산(MergeComputation)을 공유하므로 실제 결과와 일치한다. */
    @GetMapping("/{id}/preview")
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN', 'AUDITOR')")
    public MergePreviewResponse preview(@PathVariable UUID id) {
        MergeQueueEntry entry = requirePending(id);
        TrendItem newItem = trendItems.findById(entry.getNewTrendItemId()).orElseThrow();
        TrendItem oldItem = trendItems.findById(entry.getOldTrendItemId()).orElseThrow();

        TrendItem survivor = newItem.getFirstSeenAt().isBefore(oldItem.getFirstSeenAt()) ? newItem : oldItem;
        TrendItem loser = survivor == newItem ? oldItem : newItem;

        MergeService.PreviewResult result = mergeService.preview(survivor.getId(), loser.getId());

        Map<UUID, Integer> beforeRank = new HashMap<>();
        for (SubmissionOrderRank r : orderRanks.findByTrendItemId(survivor.getId())) beforeRank.put(r.getSubmissionId(), r.getOrderRank());
        for (SubmissionOrderRank r : orderRanks.findByTrendItemId(loser.getId())) beforeRank.put(r.getSubmissionId(), r.getOrderRank());

        List<OrderEntry> orderRank = result.orderAfter().stream()
                .map(o -> new OrderEntry(handleOf(o.userId()), beforeRank.get(o.submissionId()), o.rank()))
                .toList();

        List<String> dedupVoidedHandles = new ArrayList<>();
        if (!result.dedupVoidedSubmissionIds().isEmpty()) {
            List<Submission> combined = new ArrayList<>();
            combined.addAll(submissions.findByTrendItemIdAndResultNot(survivor.getId(), SubmissionResult.VOID));
            combined.addAll(submissions.findByTrendItemIdAndResultNot(loser.getId(), SubmissionResult.VOID));
            for (Submission s : combined) {
                if (result.dedupVoidedSubmissionIds().contains(s.getId())) {
                    dedupVoidedHandles.add(handleOf(s.getUserId()));
                }
            }
        }

        return new MergePreviewResponse(
                result.newCanonicalName(), orderRank,
                DISPLAY_FORMAT.format(result.firstSeenAtBefore()), DISPLAY_FORMAT.format(result.firstSeenAtAfter()),
                result.baselineShifted(), dedupVoidedHandles);
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
        // 항목 VOID는 판정 사건이다(P5) — 판정된 항목이면 원장 상쇄까지 JudgeService가 한다
        judgeService.voidItem(entry.getNewTrendItemId(), reason, clock.instant());
        auditLogService.record(actor.id(), actor.role(), "MERGE_VOID", "TREND_ITEM", entry.getNewTrendItemId(),
                Map.of("reason", reason == null ? "" : reason));
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
            String submitter = handleOf(founding.getUserId());
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
        List<SubmissionDetail> newSubmissions = submissionDetails(newItem.getId());
        List<SubmissionDetail> oldSubmissions = submissionDetails(oldItem.getId());

        return new MergeCandidateResponse(
                entry.getId().toString(), entry.getSimilarity().doubleValue(),
                newItem.getCanonicalName(), oldItem.getCanonicalName(),
                "#" + oldItem.getId().toString().substring(0, 8),
                formatAgo(entry.getCreatedAt()),
                newRows, oldRows, orderPreview, newSubmissions, oldSubmissions);
    }

    /** 검수자가 원문을 직접 읽고 판단할 수 있도록 — evidence_url/one_line/raw_input은 요약 필드로 대체 불가(03 §2④). */
    private List<SubmissionDetail> submissionDetails(UUID trendItemId) {
        return submissions.findByTrendItemIdAndResultNot(trendItemId, SubmissionResult.VOID).stream()
                .sorted(Comparator.comparing(Submission::getCreatedAt))
                .map(s -> new SubmissionDetail(handleOf(s.getUserId()), s.getRawInput(), s.getOneLine(),
                        s.getEvidenceUrl(), formatAgo(s.getCreatedAt())))
                .toList();
    }

    private String handleOf(UUID userId) {
        return users.findById(userId).map(UserAccount::getHandle).orElse("(탈퇴)");
    }

    private List<String> buildOrderPreview(UUID newTrendItemId, UUID oldTrendItemId) {
        List<Submission> combined = new ArrayList<>();
        combined.addAll(submissions.findByTrendItemIdAndResultNot(newTrendItemId, SubmissionResult.VOID));
        combined.addAll(submissions.findByTrendItemIdAndResultNot(oldTrendItemId, SubmissionResult.VOID));
        combined.sort(Comparator.comparing(Submission::getCreatedAt));

        List<String> preview = new ArrayList<>();
        int rank = 1;
        for (Submission s : combined) {
            preview.add("order%d %s".formatted(rank++, handleOf(s.getUserId())));
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
