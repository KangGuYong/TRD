package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.apiadmin.merge.IdempotencyKeyRequiredException;
import kr.trendstage.apiadmin.merge.MergeDecisionService;
import kr.trendstage.apiadmin.merge.MergeDecisionService.Decision;
import kr.trendstage.apiadmin.merge.MergeDecisionService.Outcome;
import kr.trendstage.merge.MergeComputation;
import kr.trendstage.merge.MergeService;
import kr.trendstage.persistence.entity.*;
import kr.trendstage.persistence.repo.*;
import kr.trendstage.persistence.type.MergeQueueStatus;
import kr.trendstage.persistence.type.SubmissionResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * 결정 요청은 Idempotency-Key 필수(SP2 K5) — 같은 키 재요청은 이전 결과를 돌려준다.
 */
@RestController
@RequestMapping("/admin/merge-queue")
public class MergeQueueController {

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 80;

    private final MergeQueueRepository mergeQueue;
    private final TrendItemRepository trendItems;
    private final SubmissionRepository submissions;
    private final SubmissionOrderRankRepository orderRanks;
    private final UserRepository users;
    private final UserGradeRepository userGrades;
    private final MergeService mergeService;
    private final MergeDecisionService decisions;
    private final Clock clock;

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    public MergeQueueController(MergeQueueRepository mergeQueue, TrendItemRepository trendItems,
                                 SubmissionRepository submissions, SubmissionOrderRankRepository orderRanks,
                                 UserRepository users, UserGradeRepository userGrades,
                                 MergeService mergeService, MergeDecisionService decisions, Clock clock) {
        this.mergeQueue = mergeQueue;
        this.trendItems = trendItems;
        this.submissions = submissions;
        this.orderRanks = orderRanks;
        this.users = users;
        this.userGrades = userGrades;
        this.mergeService = mergeService;
        this.decisions = decisions;
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

    public record OrderEntry(String handle, Integer rankBefore, Integer rankAfter, boolean seed) {}
    public record MergePreviewResponse(String newCanonicalName, List<OrderEntry> orderRank,
                                        String firstSeenAtBefore, String firstSeenAtAfter,
                                        String deadlineBefore, String deadlineAfter, boolean deadlineGuarded,
                                        List<String> dedupVoidedHandles, List<String> quotaRefundHandles) {}

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
                .map(o -> new OrderEntry(handleOf(o.userId()), beforeRank.get(o.submissionId()), o.rank(), o.seed()))
                .toList();

        List<String> dedupVoidedHandles = handlesOf(result.dedupVoidedSubmissionIds(), survivor.getId(), loser.getId());
        List<String> quotaRefundHandles = handlesOf(result.quotaRefundSubmissionIds(), survivor.getId(), loser.getId());

        return new MergePreviewResponse(
                result.newCanonicalName(), orderRank,
                DISPLAY_FORMAT.format(result.firstSeenAtBefore()), DISPLAY_FORMAT.format(result.firstSeenAtAfter()),
                DISPLAY_FORMAT.format(result.deadlineBefore()), DISPLAY_FORMAT.format(result.deadlineAfter()),
                result.deadlineGuarded(), dedupVoidedHandles, quotaRefundHandles);
    }

    @PostMapping("/{id}/merge")
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN')")
    public ResponseEntity<?> merge(@PathVariable UUID id,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                   @RequestBody(required = false) DecisionRequest req,
                                   @AuthenticationPrincipal AdminPrincipal actor) {
        return respond(decisions.decide(id, Decision.MERGE, requireKey(key), actor.id(), actor.role(), reasonOf(req)));
    }

    @PostMapping("/{id}/separate")
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN')")
    public ResponseEntity<?> separate(@PathVariable UUID id,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                      @RequestBody(required = false) DecisionRequest req,
                                      @AuthenticationPrincipal AdminPrincipal actor) {
        return respond(decisions.decide(id, Decision.SEPARATE, requireKey(key), actor.id(), actor.role(), reasonOf(req)));
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ResponseEntity<?> voidCandidate(@PathVariable UUID id,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @RequestBody(required = false) DecisionRequest req,
                                           @AuthenticationPrincipal AdminPrincipal actor) {
        return respond(decisions.decide(id, Decision.VOID, requireKey(key), actor.id(), actor.role(), reasonOf(req)));
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IdempotencyKeyRequiredException("Idempotency-Key 헤더(1~80자)가 필요합니다");
        }
        return key;
    }

    private static String reasonOf(DecisionRequest req) {
        return req == null ? null : req.reason();
    }

    /** 사라진 후보는 서비스가 정리를 커밋한 뒤 409로 알린다 — 예외로 던지면 정리가 롤백된다. */
    private static ResponseEntity<?> respond(Outcome outcome) {
        if (outcome.stale()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Problems.of(409, "merge-target-merged",
                    "대상 항목이 이미 다른 항목으로 병합돼 후보를 정리했습니다"));
        }
        return ResponseEntity.ok(outcome.body());
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

    private List<String> handlesOf(Set<UUID> submissionIds, UUID itemA, UUID itemB) {
        if (submissionIds.isEmpty()) return List.of();
        List<Submission> combined = new ArrayList<>();
        combined.addAll(submissions.findByTrendItemIdAndResultNot(itemA, SubmissionResult.VOID));
        combined.addAll(submissions.findByTrendItemIdAndResultNot(itemB, SubmissionResult.VOID));
        return combined.stream()
                .filter(s -> submissionIds.contains(s.getId()))
                .map(s -> handleOf(s.getUserId()))
                .toList();
    }

    /** 목록 카드의 병합 후 순위 칩 — 미리보기와 같은 규칙(시딩 제외, 동순위). */
    /** 병합 후 순위 요약 — 미리보기(MergeService.preview)와 같이 같은 유저의 늦은 제보(VOID될 것)는 뺀다. */
    private List<String> buildOrderPreview(UUID newTrendItemId, UUID oldTrendItemId) {
        List<MergeComputation.SubmissionInput> newInputs = inputsOf(newTrendItemId);
        List<MergeComputation.SubmissionInput> oldInputs = inputsOf(oldTrendItemId);
        Set<UUID> voided = MergeComputation.computeDedup(newInputs, oldInputs);
        List<MergeComputation.SubmissionInput> inputs = new ArrayList<>(newInputs);
        inputs.addAll(oldInputs);
        inputs.removeIf(i -> voided.contains(i.submissionId()));
        return MergeComputation.computeCombinedOrder(inputs).stream()
                .map(o -> o.seed()
                        ? "시딩 " + handleOf(o.userId())
                        : "order%d %s".formatted(o.rank(), handleOf(o.userId())))
                .toList();
    }

    private List<MergeComputation.SubmissionInput> inputsOf(UUID trendItemId) {
        return submissions.findByTrendItemIdAndResultNot(trendItemId, SubmissionResult.VOID).stream()
                .map(s -> new MergeComputation.SubmissionInput(
                        s.getId(), s.getUserId(), s.getRawInput(), s.getCreatedAt(), s.isSeed()))
                .toList();
    }

    private static String formatAgo(Instant createdAt) {
        Duration d = Duration.between(createdAt, Instant.now());
        if (d.toHours() < 1) return d.toMinutes() + "분 전";
        if (d.toDays() < 1) return d.toHours() + "h 전";
        return d.toDays() + "일 전";
    }
}
