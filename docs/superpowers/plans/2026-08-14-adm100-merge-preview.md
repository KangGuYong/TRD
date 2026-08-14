# ADM-100 병합 검수 확인 정보 보강 + 병합 후 미리보기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ADM-100 병합 검수 큐 화면에 (1) 제보 원문(raw_input/one_line/evidence_url) 전체 목록과 (2) 병합 후 실제로 무엇이 바뀌는지(대표명·선점 순위·baseline 영향) dry-run 미리보기를 추가한다.

**Architecture:** `MergeService.merge()`가 쓰던 계산 로직(동일 유저 중복 판정, 대표명 재계산)을 의존성 없는 순수 함수 클래스 `MergeComputation`으로 추출해 `merge()`(실행)와 신규 `preview()`(dry-run, DB에 안 씀) 둘 다 재사용한다. 컨트롤러는 `GET /admin/merge-queue/{id}/preview` 신규 엔드포인트와 큐 목록 응답에 전체 제보 배열을 추가한다. 프론트는 "더 보기" 토글(이미 받은 데이터 펼치기)과 "병합 후 미리보기" 모달(온디맨드 호출, 모달에서 바로 확정 가능)을 추가한다.

**Tech Stack:** Spring Boot 3.3.5 / Java 17 / JUnit 5 (백엔드), React + TypeScript + TanStack Query (프론트, `admin/` 디렉터리)

**참고 문서:** [docs/superpowers/specs/2026-08-14-adm100-merge-preview-design.md](../specs/2026-08-14-adm100-merge-preview-design.md)

---

## Task 1: `Submission` 엔티티에 `getEvidenceUrl()` 게터 추가

`evidence_url` 필드는 이미 있지만 게터가 없다. 확인 정보 노출에 필요.

**Files:**
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/Submission.java`

- [ ] **Step 1: 게터 추가**

`public String getOneLine() { return oneLine; }` 줄 바로 아래에 추가:

```java
    public String getEvidenceUrl() { return evidenceUrl; }
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew.bat :persistence:compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add backend/persistence/src/main/java/kr/trendstage/persistence/entity/Submission.java
git commit -m "Add Submission.getEvidenceUrl() getter"
```

---

## Task 2: `MergeComputation` 순수 함수 유틸리티 + 단위 테스트

`MergeService.merge()`가 쓰는 계산 로직(동일 유저 중복 판정, 대표명 재계산, 선점 순위 계산)을 엔티티·DB에 의존하지 않는 정적 함수로 뽑아낸다. `merge()`(실행)와 `preview()`(dry-run)가 이 클래스를 공유하므로 두 결과가 어긋날 수 없다.

**Files:**
- Create: `backend/merge/src/main/java/kr/trendstage/merge/MergeComputation.java`
- Test: `backend/merge/src/test/java/kr/trendstage/merge/MergeComputationTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.trendstage.merge;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MergeComputationTest {

    private static MergeComputation.SubmissionInput input(UUID user, String rawInput, Instant createdAt) {
        return new MergeComputation.SubmissionInput(UUID.randomUUID(), user, rawInput, createdAt);
    }

    @Test void 같은_유저가_양쪽에_제보하면_늦은_쪽만_VOID_대상() {
        UUID user = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-01T00:00:00Z");
        var early = input(user, "탕후루 챌린지", t0);
        var late = input(user, "탕후루 챌린지 2", t0.plus(1, ChronoUnit.DAYS));

        Set<UUID> voided = MergeComputation.computeDedup(List.of(early), List.of(late));

        assertEquals(Set.of(late.submissionId()), voided);
    }

    @Test void 서로_다른_유저는_dedup_대상_아님() {
        Instant t0 = Instant.parse("2026-08-01T00:00:00Z");
        var a = input(UUID.randomUUID(), "탕후루 챌린지", t0);
        var b = input(UUID.randomUUID(), "탕후루 챌린지", t0.plus(1, ChronoUnit.DAYS));

        assertTrue(MergeComputation.computeDedup(List.of(a), List.of(b)).isEmpty());
    }

    @Test void 가장_많이_쓰인_표기가_새_대표명() {
        Instant t0 = Instant.parse("2026-08-01T00:00:00Z");
        var subs = List.of(
                input(UUID.randomUUID(), "두바이 초콜릿", t0),
                input(UUID.randomUUID(), "두바이 초콜릿", t0.plus(1, ChronoUnit.DAYS)),
                input(UUID.randomUUID(), "피스타치오 카다이프 초콜릿", t0.plus(2, ChronoUnit.DAYS)));

        assertEquals(Optional.of("두바이 초콜릿"), MergeComputation.computeCanonicalName(subs));
    }

    @Test void 표기_건수가_동률이면_더_이른_쪽이_대표명() {
        Instant t0 = Instant.parse("2026-08-01T00:00:00Z");
        var subs = List.of(
                input(UUID.randomUUID(), "나중 표기", t0.plus(1, ChronoUnit.DAYS)),
                input(UUID.randomUUID(), "먼저 표기", t0));

        assertEquals(Optional.of("먼저 표기"), MergeComputation.computeCanonicalName(subs));
    }

    @Test void 제보가_없으면_대표명_변경_없음() {
        assertEquals(Optional.empty(), MergeComputation.computeCanonicalName(List.of()));
    }

    @Test void 선점_순위는_제보_시각_오름차순() {
        Instant t0 = Instant.parse("2026-08-01T00:00:00Z");
        var s1 = input(UUID.randomUUID(), "a", t0.plus(2, ChronoUnit.DAYS));
        var s2 = input(UUID.randomUUID(), "b", t0);
        var s3 = input(UUID.randomUUID(), "c", t0.plus(1, ChronoUnit.DAYS));

        var order = MergeComputation.computeCombinedOrder(List.of(s1, s2, s3));

        assertEquals(List.of(s2.submissionId(), s3.submissionId(), s1.submissionId()),
                order.stream().map(MergeComputation.OrderComputed::submissionId).toList());
        assertEquals(1, order.get(0).rank());
        assertEquals(2, order.get(1).rank());
        assertEquals(3, order.get(2).rank());
    }
}
```

- [ ] **Step 2: 테스트 실행 → 컴파일 실패 확인**

Run: `cd backend && ./gradlew.bat :merge:test`
Expected: FAIL — `MergeComputation` 클래스가 없어 컴파일 에러

- [ ] **Step 3: `MergeComputation` 구현**

```java
package kr.trendstage.merge;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 병합 계산 순수 함수 모음. MergeService.merge()(실행)와 preview()(dry-run) 둘 다 재사용한다 —
 * 여기 로직이 바뀌면 실행 결과와 미리보기 결과가 항상 같이 바뀐다(드리프트 불가능).
 * DB·엔티티에 의존하지 않아 Spring 컨텍스트 없이 단위 테스트 가능하다.
 */
public final class MergeComputation {

    private MergeComputation() {}

    public record SubmissionInput(UUID submissionId, UUID userId, String rawInput, Instant createdAt) {}
    public record OrderComputed(UUID submissionId, UUID userId, int rank) {}

    /** 같은 유저가 두 클러스터 모두에 유효 제보를 낸 경우, 늦은 쪽 submissionId를 반환(03 §4.4 — 헤지 방지). */
    public static Set<UUID> computeDedup(List<SubmissionInput> a, List<SubmissionInput> b) {
        Map<UUID, List<SubmissionInput>> byUser = new HashMap<>();
        for (SubmissionInput s : a) byUser.computeIfAbsent(s.userId(), k -> new ArrayList<>()).add(s);
        for (SubmissionInput s : b) byUser.computeIfAbsent(s.userId(), k -> new ArrayList<>()).add(s);

        Set<UUID> voided = new HashSet<>();
        for (List<SubmissionInput> group : byUser.values()) {
            if (group.size() < 2) continue;
            group.sort(Comparator.comparing(SubmissionInput::createdAt));
            for (int i = 1; i < group.size(); i++) voided.add(group.get(i).submissionId());
        }
        return voided;
    }

    /** 가장 많이 쓰인 raw_input(동률이면 더 이른 것)을 새 대표명으로. 입력이 비었으면 empty(대표명 변경 없음). */
    public static Optional<String> computeCanonicalName(List<SubmissionInput> active) {
        if (active.isEmpty()) return Optional.empty();
        Map<String, List<SubmissionInput>> byRawInput = active.stream()
                .collect(Collectors.groupingBy(SubmissionInput::rawInput));
        String best = null;
        int bestCount = -1;
        Instant bestEarliest = null;
        for (var e : byRawInput.entrySet()) {
            int count = e.getValue().size();
            Instant earliest = e.getValue().stream().map(SubmissionInput::createdAt).min(Instant::compareTo).orElseThrow();
            if (count > bestCount || (count == bestCount && earliest.isBefore(bestEarliest))) {
                best = e.getKey();
                bestCount = count;
                bestEarliest = earliest;
            }
        }
        return Optional.of(best);
    }

    /** created_at 오름차순 = 선점 순위. 1위부터 시작. */
    public static List<OrderComputed> computeCombinedOrder(List<SubmissionInput> active) {
        List<SubmissionInput> sorted = active.stream()
                .sorted(Comparator.comparing(SubmissionInput::createdAt))
                .toList();
        List<OrderComputed> result = new ArrayList<>();
        int rank = 1;
        for (SubmissionInput s : sorted) {
            result.add(new OrderComputed(s.submissionId(), s.userId(), rank++));
        }
        return result;
    }
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `cd backend && ./gradlew.bat :merge:test`
Expected: `BUILD SUCCESSFUL`, 6개 테스트 전부 통과

- [ ] **Step 5: 커밋**

```bash
git add backend/merge/src/main/java/kr/trendstage/merge/MergeComputation.java backend/merge/src/test/java/kr/trendstage/merge/MergeComputationTest.java
git commit -m "Add MergeComputation pure functions (dedup/canonical-name/order) with unit tests"
```

---

## Task 3: `MergeService.merge()`가 `MergeComputation`을 재사용하도록 리팩터링

동작은 그대로 두고(순수 리팩터링), 내부 계산을 Task 2의 함수로 위임한다.

**Files:**
- Modify: `backend/merge/src/main/java/kr/trendstage/merge/MergeService.java`

- [ ] **Step 1: import 블록 수정**

파일 상단의 다음 줄을 찾는다:

```java
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
```

`java.util.stream.Collectors`를 삭제한다(더 이상 이 파일에서 안 씀 — `Collectors`는 `MergeComputation` 안으로 옮겨감):

```java
import java.time.Instant;
import java.util.*;
```

- [ ] **Step 2: `dedupSameUserSubmissions` 메서드 교체**

기존:

```java
    private void dedupSameUserSubmissions(UUID trendItemA, UUID trendItemB) {
        List<Submission> a = submissions.findByTrendItemIdAndResultNot(trendItemA, SubmissionResult.VOID);
        List<Submission> b = submissions.findByTrendItemIdAndResultNot(trendItemB, SubmissionResult.VOID);
        Map<UUID, List<Submission>> byUser = new HashMap<>();
        for (Submission s : a) byUser.computeIfAbsent(s.getUserId(), k -> new ArrayList<>()).add(s);
        for (Submission s : b) byUser.computeIfAbsent(s.getUserId(), k -> new ArrayList<>()).add(s);

        for (List<Submission> group : byUser.values()) {
            if (group.size() < 2) continue;
            group.sort(Comparator.comparing(Submission::getCreatedAt));
            for (int i = 1; i < group.size(); i++) group.get(i).voidOut();
        }
    }
```

다음으로 교체:

```java
    private void dedupSameUserSubmissions(UUID trendItemA, UUID trendItemB) {
        List<Submission> a = submissions.findByTrendItemIdAndResultNot(trendItemA, SubmissionResult.VOID);
        List<Submission> b = submissions.findByTrendItemIdAndResultNot(trendItemB, SubmissionResult.VOID);
        Map<UUID, Submission> byId = new HashMap<>();
        for (Submission s : a) byId.put(s.getId(), s);
        for (Submission s : b) byId.put(s.getId(), s);

        Set<UUID> voided = MergeComputation.computeDedup(toInputs(a), toInputs(b));
        for (UUID id : voided) byId.get(id).voidOut();
    }
```

- [ ] **Step 3: `recomputeCanonicalName` 메서드 교체**

기존:

```java
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
```

다음으로 교체:

```java
    private void recomputeCanonicalName(TrendItem survivor) {
        List<Submission> active = submissions.findByTrendItemIdAndResultNot(survivor.getId(), SubmissionResult.VOID);
        MergeComputation.computeCanonicalName(toInputs(active)).ifPresent(survivor::setCanonicalName);
    }

    private static List<MergeComputation.SubmissionInput> toInputs(List<Submission> subs) {
        return subs.stream()
                .map(s -> new MergeComputation.SubmissionInput(s.getId(), s.getUserId(), s.getRawInput(), s.getCreatedAt()))
                .toList();
    }
```

- [ ] **Step 4: 컴파일 + 기존 테스트 재확인**

Run: `cd backend && ./gradlew.bat :merge:compileJava :merge:test`
Expected: `BUILD SUCCESSFUL` (Task 2에서 만든 테스트가 여전히 통과 — 이 파일은 안 건드렸으므로 당연히 통과해야 함)

- [ ] **Step 5: 커밋**

```bash
git add backend/merge/src/main/java/kr/trendstage/merge/MergeService.java
git commit -m "Refactor MergeService.merge() to reuse MergeComputation (behavior-preserving)"
```

---

## Task 4: `MergeService.preview()` 추가

기존 코드는 손대지 않고 dry-run 메서드만 추가한다.

**Files:**
- Modify: `backend/merge/src/main/java/kr/trendstage/merge/MergeService.java`

- [ ] **Step 1: `preview()` 메서드와 `PreviewResult` 레코드 추가**

`voidTrendItem` 메서드 바로 다음, `dedupSameUserSubmissions` 메서드 앞에 추가:

```java
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
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew.bat :merge:compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add backend/merge/src/main/java/kr/trendstage/merge/MergeService.java
git commit -m "Add MergeService.preview() dry-run for ADM-100 merge diff"
```

---

## Task 5: `MergeQueueController` — `/preview` 엔드포인트 + 확인 정보 확장

`SubmissionOrderRankRepository` 의존성을 추가하고, 신규 `/preview` 엔드포인트와 큐 목록 응답의 제보 전체 목록을 넣는다. 파일 전체를 아래 내용으로 교체한다(기존 `merge`/`separate`/`void`/`requirePending`/`buildOrderPreview`/`formatAgo` 로직은 그대로 유지, 필요한 부분만 확장).

**Files:**
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/MergeQueueController.java`

- [ ] **Step 1: 파일 전체를 다음 내용으로 교체**

```java
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
    private final SubmissionOrderRankRepository orderRanks;
    private final UserRepository users;
    private final UserGradeRepository userGrades;
    private final MergeService mergeService;
    private final Clock clock;

    public MergeQueueController(MergeQueueRepository mergeQueue, TrendItemRepository trendItems,
                                 SubmissionRepository submissions, SubmissionOrderRankRepository orderRanks,
                                 UserRepository users, UserGradeRepository userGrades,
                                 MergeService mergeService, Clock clock) {
        this.mergeQueue = mergeQueue;
        this.trendItems = trendItems;
        this.submissions = submissions;
        this.orderRanks = orderRanks;
        this.users = users;
        this.userGrades = userGrades;
        this.mergeService = mergeService;
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
                result.firstSeenAtBefore().toString(), result.firstSeenAtAfter().toString(),
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
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew.bat :api-admin:compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/MergeQueueController.java
git commit -m "Add GET /admin/merge-queue/{id}/preview and full submission lists in queue response"
```

---

## Task 6: 백엔드 전체 컴파일 확인

**Files:** 없음 (검증만)

- [ ] **Step 1: 전체 빌드**

Run: `cd backend && ./gradlew.bat :app:compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: 전체 테스트**

Run: `cd backend && ./gradlew.bat test`
Expected: `BUILD SUCCESSFUL` (기존 domain-core 테스트 + Task 2의 MergeComputationTest 전부 통과)

---

## Task 7: 프론트 타입 확장

**Files:**
- Modify: `admin/src/api/types.ts`

- [ ] **Step 1: `MergeCandidate` 인터페이스 앞에 `SubmissionDetail` 추가, `MergeCandidate`에 필드 추가**

기존:

```ts
export interface MergeCandidate {
  id: string;
  similarity: number;
  newName: string;
  oldName: string;
  oldClusterId: string;
  ago: string;
  newRows: { k: string; v: string }[];
  oldRows: { k: string; v: string }[];
  orderPreview: string[];
}
```

다음으로 교체:

```ts
export interface SubmissionDetail {
  handle: string;
  rawInput: string;
  oneLine: string;
  evidenceUrl: string;
  createdAt: string;
}
export interface MergeCandidate {
  id: string;
  similarity: number;
  newName: string;
  oldName: string;
  oldClusterId: string;
  ago: string;
  newRows: { k: string; v: string }[];
  oldRows: { k: string; v: string }[];
  orderPreview: string[];
  newSubmissions: SubmissionDetail[];
  oldSubmissions: SubmissionDetail[];
}

export interface OrderEntry {
  handle: string;
  rankBefore: number | null;
  rankAfter: number;
}
export interface MergePreview {
  newCanonicalName: string;
  orderRank: OrderEntry[];
  firstSeenAtBefore: string;
  firstSeenAtAfter: string;
  baselineShifted: boolean;
  dedupVoidedHandles: string[];
}
```

- [ ] **Step 2: 커밋**

```bash
git add admin/src/api/types.ts
git commit -m "Add SubmissionDetail/MergePreview types for ADM-100 preview"
```

---

## Task 8: 프론트 훅 추가

**Files:**
- Modify: `admin/src/api/hooks.ts`

- [ ] **Step 1: import에 `MergePreview` 추가**

기존:

```ts
import type { AdminAccountSummary, AdminUserDetail, AuditEntry, MergeCandidate, QueueSummary, VerdictListResponse } from "./types";
```

다음으로 교체:

```ts
import type { AdminAccountSummary, AdminUserDetail, AuditEntry, MergeCandidate, MergePreview, QueueSummary, VerdictListResponse } from "./types";
```

- [ ] **Step 2: `decideMergeCandidate` 함수 바로 아래에 함수 추가**

```ts
export const fetchMergePreview = (id: string) =>
  api.get<MergePreview>(`/admin/merge-queue/${id}/preview`);
```

- [ ] **Step 3: 커밋**

```bash
git add admin/src/api/hooks.ts
git commit -m "Add fetchMergePreview hook function"
```

---

## Task 9: 픽스처 확장

**Files:**
- Modify: `admin/src/fixtures.ts`

- [ ] **Step 1: import에 새 타입 추가**

기존:

```ts
import type { AdminAccountSummary, AdminUserDetail, AuditEntry, MergeCandidate, ParameterPayload, QueueSummary, SimulationResult } from "./api/types";
```

다음으로 교체:

```ts
import type { AdminAccountSummary, AdminUserDetail, AuditEntry, MergeCandidate, MergePreview, ParameterPayload, QueueSummary, SimulationResult } from "./api/types";
```

- [ ] **Step 2: `fxMergeQueue`의 후보 객체에 `newSubmissions`/`oldSubmissions` 추가**

기존:

```ts
export const fxMergeQueue: MergeCandidate[] = [
  {
    id: "mq-1",
    similarity: 0.82,
    newName: "새싹챌린지",
    oldName: "새싹 챌린지",
    oldClusterId: "#1204",
    ago: "2h 전",
    newRows: [
      { k: "카테고리", v: "챌린지" },
      { k: "플랫폼", v: "인스타" },
      { k: "제보자", v: "user_8821 · TI 0.51 / L2" },
      { k: "URL", v: "2건" },
    ],
    oldRows: [
      { k: "제보", v: "4건 (order 1~4)" },
      { k: "최초", v: "3일 전" },
      { k: "상태", v: "PENDING · 현재 T 추정 0.31" },
    ],
    orderPreview: ["order1 user_4410", "order2 user_8821", "order3 user_0092", "order4 신규", "order5 신규"],
  },
];
```

다음으로 교체:

```ts
export const fxMergeQueue: MergeCandidate[] = [
  {
    id: "mq-1",
    similarity: 0.82,
    newName: "새싹챌린지",
    oldName: "새싹 챌린지",
    oldClusterId: "#1204",
    ago: "2h 전",
    newRows: [
      { k: "카테고리", v: "챌린지" },
      { k: "플랫폼", v: "인스타" },
      { k: "제보자", v: "user_8821 · TI 0.51 / L2" },
      { k: "URL", v: "2건" },
    ],
    oldRows: [
      { k: "제보", v: "4건 (order 1~4)" },
      { k: "최초", v: "3일 전" },
      { k: "상태", v: "PENDING · 현재 T 추정 0.31" },
    ],
    orderPreview: ["order1 user_4410", "order2 user_8821", "order3 user_0092", "order4 신규", "order5 신규"],
    newSubmissions: [
      { handle: "user_8821", rawInput: "새싹챌린지", oneLine: "요즘 카페에서 새싹 올린 음료 챌린지 유행 중", evidenceUrl: "https://instagram.com/p/example1", createdAt: "2h 전" },
      { handle: "user_0092", rawInput: "새싹 올리기 챌린지", oneLine: "인스타에서 새싹 사진 릴레이", evidenceUrl: "https://instagram.com/p/example2", createdAt: "1h 전" },
    ],
    oldSubmissions: [
      { handle: "user_4410", rawInput: "새싹 챌린지", oneLine: "새싹 인테리어 유행", evidenceUrl: "https://instagram.com/p/example3", createdAt: "3일 전" },
      { handle: "user_1122", rawInput: "새싹 챌린지", oneLine: "새싹 키우기 챌린지 확산 중", evidenceUrl: "", createdAt: "2일 전" },
    ],
  },
];

export const fxMergePreview: MergePreview = {
  newCanonicalName: "새싹챌린지",
  orderRank: [
    { handle: "user_4410", rankBefore: 1, rankAfter: 1 },
    { handle: "user_1122", rankBefore: 2, rankAfter: 2 },
    { handle: "user_8821", rankBefore: 1, rankAfter: 3 },
    { handle: "user_0092", rankBefore: 2, rankAfter: 4 },
  ],
  firstSeenAtBefore: "2026-08-11 09:00",
  firstSeenAtAfter: "2026-08-11 09:00",
  baselineShifted: false,
  dedupVoidedHandles: [],
};
```

- [ ] **Step 3: 커밋**

```bash
git add admin/src/fixtures.ts
git commit -m "Extend merge queue fixtures with submission details and preview"
```

---

## Task 10: `MergeQueueScreen.tsx` — 더 보기 토글 + 병합 후 미리보기 모달

**Files:**
- Modify: `admin/src/screens/MergeQueueScreen.tsx`

- [ ] **Step 1: 파일 전체를 다음 내용으로 교체**

```tsx
import React, { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useMergeQueue, decideMergeCandidate, fetchMergePreview } from "../api/hooks";
import { ApiError, USE_FIXTURES } from "../api/client";
import { Card, StateView, Btn, Label } from "../components/ui";
import { C } from "../theme";
import { useRole, CAN } from "../state/role";
import * as fx from "../fixtures";
import type { MergePreview } from "../api/types";

export default function MergeQueueScreen() {
  const q = useMergeQueue();
  const { role } = useRole();
  const qc = useQueryClient();
  const [reason, setReason] = useState("");
  const [toast, setToast] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});

  const [previewFor, setPreviewFor] = useState<string | null>(null);
  const [previewLabel, setPreviewLabel] = useState("");
  const [previewData, setPreviewData] = useState<MergePreview | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);

  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2600); };
  const toggle = (key: string) => setExpanded((e) => ({ ...e, [key]: !e[key] }));

  const act = async (id: string, label: string, action: "merge" | "separate" | "void") => {
    if (USE_FIXTURES) {
      flash(`(데모) ${label} 처리됨. 근거: ${reason || "—"}`);
      setReason("");
      return;
    }
    setBusyId(id);
    try {
      await decideMergeCandidate(id, action, reason);
      await qc.invalidateQueries({ queryKey: ["admin", "merge-queue"] });
      flash(`${label} 처리됨 (감사 로그 기록)`);
      setReason("");
    } catch (e) {
      flash(e instanceof ApiError ? e.message : `${label} 처리에 실패했습니다`);
    } finally {
      setBusyId(null);
    }
  };

  const openPreview = async (id: string, label: string) => {
    setPreviewFor(id);
    setPreviewLabel(label);
    setPreviewData(null);
    setPreviewError(null);
    setPreviewLoading(true);
    try {
      const data = USE_FIXTURES ? fx.fxMergePreview : await fetchMergePreview(id);
      setPreviewData(data);
    } catch (e) {
      setPreviewError(e instanceof ApiError ? e.message : "미리보기를 불러오지 못했습니다");
    } finally {
      setPreviewLoading(false);
    }
  };

  const confirmMergeFromModal = async () => {
    if (!previewFor) return;
    await act(previewFor, "병합", "merge");
    setPreviewFor(null);
  };

  return (
    <div style={{ maxWidth: 1000 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
        <span style={{ font: "500 12px Pretendard", color: C.sub }}>자동 병합 금지 구간 0.75–0.85</span>
        <span style={{ display: "flex", gap: 5 }}>
          {[["J/K", "이동"], ["M", "병합"], ["S", "분리"], ["V", "VOID"]].map(([k, d]) => (
            <span key={k} style={{ font: "600 10px ui-monospace, monospace", padding: "5px 6px", borderRadius: 5, background: "rgba(20,19,15,0.06)", color: C.sub }}>{k} {d}</span>
          ))}
        </span>
      </div>

      <StateView query={q}>
        {(list) => list.length === 0 ? (
          <Card><div style={{ textAlign: "center", padding: 40 }}><b>큐를 비웠습니다</b></div></Card>
        ) : (
          <Card style={{ padding: 0, overflow: "hidden" }}>
            {list.map((mi) => (
              <div key={mi.id}>
                <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "16px 20px", borderBottom: `1px solid ${C.line}` }}>
                  <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
                    <b style={{ fontSize: 13 }}>유사도 {mi.similarity}</b>
                    <span style={{ height: 5, width: 120, borderRadius: 9, background: "rgba(20,19,15,0.09)", overflow: "hidden", display: "block" }}>
                      <span style={{ display: "block", height: "100%", borderRadius: 9, background: C.peak, width: `${mi.similarity * 100}%` }} />
                    </span>
                  </div>
                  <span style={{ font: "500 11.5px Pretendard", color: C.faint }}>제보 {mi.ago}</span>
                </div>

                <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr" }}>
                  <div style={{ padding: 20, borderRight: `1px solid ${C.line}` }}>
                    <div style={{ font: "600 10.5px Pretendard", letterSpacing: ".09em", color: C.faint, marginBottom: 12 }}>신규 제보</div>
                    <div style={{ font: "700 22px Pretendard", letterSpacing: "-0.02em" }}>{mi.newName}</div>
                    <div style={{ display: "flex", flexDirection: "column", gap: 9, marginTop: 16 }}>
                      {mi.newRows.map((r, i) => <Row key={i} k={r.k} v={r.v} />)}
                    </div>
                    <SubmissionList label={`더 보기 (제보 ${mi.newSubmissions.length}건)`}
                      open={!!expanded[`${mi.id}-new`]} onToggle={() => toggle(`${mi.id}-new`)}
                      items={mi.newSubmissions} />
                    <div style={{ marginTop: 14, padding: "11px 13px", borderRadius: 10, background: "rgba(20,19,15,0.035)", font: "400 11px Pretendard", color: C.sub, lineHeight: 1.5 }}>
                      제보자 등급은 참고 정보입니다. 고등급자 제보를 우대하면 공정성이 깨집니다 — 병합 판단에 반영하지 마세요.
                    </div>
                  </div>
                  <div style={{ padding: 20, background: "rgba(20,19,15,0.02)" }}>
                    <div style={{ font: "600 10.5px Pretendard", letterSpacing: ".09em", color: C.faint, marginBottom: 12 }}>기존 클러스터</div>
                    <div style={{ display: "flex", alignItems: "baseline", gap: 9 }}>
                      <span style={{ font: "700 22px Pretendard", letterSpacing: "-0.02em" }}>{mi.oldName}</span>
                      <span style={{ font: "600 12px ui-monospace, monospace", color: C.faint }}>{mi.oldClusterId}</span>
                    </div>
                    <div style={{ display: "flex", flexDirection: "column", gap: 9, marginTop: 16 }}>
                      {mi.oldRows.map((r, i) => <Row key={i} k={r.k} v={r.v} />)}
                    </div>
                    <SubmissionList label={`더 보기 (제보 ${mi.oldSubmissions.length}건)`}
                      open={!!expanded[`${mi.id}-old`]} onToggle={() => toggle(`${mi.id}-old`)}
                      items={mi.oldSubmissions} />
                    <div style={{ marginTop: 14, padding: "12px 13px", borderRadius: 10, background: "#fff", border: "1px dashed rgba(20,19,15,0.16)" }}>
                      <div style={{ font: "600 11px Pretendard", color: C.sub, marginBottom: 8 }}>병합 시 선점 순위 미리보기</div>
                      <div style={{ display: "flex", flexWrap: "wrap", gap: 5 }}>
                        {mi.orderPreview.map((o, i) => (
                          <span key={i} style={{ font: "600 10.5px ui-monospace, monospace", padding: "5px 7px", borderRadius: 6, background: "rgba(20,19,15,0.06)", color: C.ink }}>{o}</span>
                        ))}
                      </div>
                    </div>
                  </div>
                </div>

                <div style={{ padding: "18px 20px", borderTop: `1px solid ${C.line}` }}>
                  <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="판단 근거 (선택 · 감사 로그에 기록됩니다)"
                    style={{ width: "100%", boxSizing: "border-box", padding: "12px 14px", borderRadius: 10, border: "1px solid rgba(20,19,15,0.1)", background: "#FBFAF7", outline: "none", font: "500 12.5px Pretendard" }} />
                  <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
                    <Btn onClick={() => openPreview(mi.id, mi.newName)}>병합 후 미리보기</Btn>
                    <Btn tone="primary" disabled={!CAN.merge(role) || busyId === mi.id} onClick={() => act(mi.id, "병합", "merge")}>병합</Btn>
                    <Btn disabled={!CAN.merge(role) || busyId === mi.id} onClick={() => act(mi.id, "분리", "separate")}>별도 항목으로 분리</Btn>
                    <Btn tone="danger" disabled={!CAN.void(role) || busyId === mi.id} onClick={() => act(mi.id, "VOID", "void")} title={!CAN.void(role) ? "OPERATOR 이상 필요" : undefined}>VOID (허위/규정위반)</Btn>
                    <Btn onClick={() => flash("다음 항목으로 보류")}>보류 → 다음</Btn>
                  </div>
                  {!CAN.void(role) && <div style={{ marginTop: 11, font: "500 11.5px Pretendard", color: C.fading }}>현재 역할({role})에는 VOID 권한이 없습니다. OPERATOR 이상 필요.</div>}
                </div>
              </div>
            ))}
          </Card>
        )}
      </StateView>

      {previewFor && (
        <div style={{ position: "fixed", inset: 0, background: "rgba(20,19,15,0.35)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 10 }}>
          <Card style={{ width: 460, maxHeight: "80vh", overflow: "auto" }}>
            <b style={{ fontSize: 14 }}>{previewLabel} — 병합 후 미리보기</b>
            {previewLoading && <div style={{ marginTop: 14, color: C.faint, font: "500 12.5px Pretendard" }}>계산 중…</div>}
            {previewError && (
              <div style={{ marginTop: 14, padding: "9px 12px", borderRadius: 8, background: "rgba(216,72,60,0.08)", color: C.fading, font: "600 12px Pretendard" }}>{previewError}</div>
            )}
            {previewData && (
              <>
                <div style={{ marginTop: 14 }}>
                  <Label>대표명</Label>
                  <div style={{ font: "600 13px Pretendard" }}>{previewData.newCanonicalName}</div>
                </div>
                <div style={{ marginTop: 14 }}>
                  <Label>선점 순위 (전 → 후)</Label>
                  <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
                    {previewData.orderRank.map((o, i) => (
                      <div key={i} style={{ display: "flex", justifyContent: "space-between", font: "500 12px ui-monospace, monospace" }}>
                        <span>{o.handle}</span>
                        <span>{o.rankBefore ?? "-"} → {o.rankAfter}</span>
                      </div>
                    ))}
                  </div>
                </div>
                <div style={{ marginTop: 14 }}>
                  <Label>최초 목격 시각</Label>
                  <div style={{ font: "500 12.5px Pretendard" }}>{previewData.firstSeenAtBefore} → {previewData.firstSeenAtAfter}</div>
                  {previewData.baselineShifted && (
                    <div style={{ marginTop: 8, padding: "9px 12px", borderRadius: 8, background: "rgba(216,150,60,0.1)", color: C.sub, font: "600 11.5px Pretendard" }}>
                      최초 목격 시각이 앞당겨집니다 — 기존 판정 기준선(baseline)이 재계산 대상이 됩니다.
                    </div>
                  )}
                </div>
                {previewData.dedupVoidedHandles.length > 0 && (
                  <div style={{ marginTop: 14, padding: "9px 12px", borderRadius: 8, background: "rgba(20,19,15,0.05)", font: "500 11.5px Pretendard", color: C.sub }}>
                    동일 유저 중복 제보로 VOID 처리될 제보자: {previewData.dedupVoidedHandles.join(", ")}
                  </div>
                )}
                <div style={{ marginTop: 16 }}>
                  <Label>사유</Label>
                  <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="판단 근거 (선택 · 감사 로그에 기록됩니다)"
                    style={{ width: "100%", boxSizing: "border-box", padding: "11px 13px", borderRadius: 9, border: "1px solid rgba(20,19,15,0.12)", background: "#FBFAF7", outline: "none", font: "500 12.5px Pretendard" }} />
                </div>
                <div style={{ display: "flex", gap: 8, marginTop: 16 }}>
                  <Btn tone="primary" disabled={!CAN.merge(role) || busyId === previewFor} onClick={confirmMergeFromModal}>이 내용으로 병합</Btn>
                  <Btn onClick={() => setPreviewFor(null)}>닫기</Btn>
                </div>
              </>
            )}
          </Card>
        </div>
      )}

      {toast && (
        <div style={{ position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)", background: C.ink, color: "#fff", padding: "12px 18px", borderRadius: 10, font: "500 12.5px Pretendard", boxShadow: "0 8px 24px rgba(0,0,0,0.2)" }}>{toast}</div>
      )}
    </div>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div style={{ display: "flex", gap: 14 }}>
      <span style={{ width: 78, flex: "none", font: "500 11.5px Pretendard", color: C.faint }}>{k}</span>
      <span style={{ font: "500 12.5px Pretendard" }}>{v}</span>
    </div>
  );
}

function SubmissionList({ label, open, onToggle, items }: {
  label: string; open: boolean; onToggle: () => void;
  items: { handle: string; rawInput: string; oneLine: string; evidenceUrl: string; createdAt: string }[];
}) {
  return (
    <div style={{ marginTop: 12 }}>
      <button onClick={onToggle} style={{ background: "none", border: "none", padding: 0, cursor: "pointer", font: "600 11.5px Pretendard", color: C.sub, textDecoration: "underline" }}>
        {open ? "접기" : label}
      </button>
      {open && (
        <div style={{ display: "flex", flexDirection: "column", gap: 10, marginTop: 10 }}>
          {items.map((s, i) => (
            <div key={i} style={{ padding: "10px 12px", borderRadius: 9, background: "rgba(20,19,15,0.03)" }}>
              <div style={{ display: "flex", justifyContent: "space-between" }}>
                <span style={{ font: "600 11.5px Pretendard" }}>{s.handle}</span>
                <span style={{ font: "500 10.5px ui-monospace, monospace", color: C.faint }}>{s.createdAt}</span>
              </div>
              <div style={{ font: "600 12.5px Pretendard", marginTop: 4 }}>{s.rawInput}</div>
              <div style={{ font: "400 11.5px Pretendard", color: C.sub, marginTop: 3 }}>{s.oneLine}</div>
              {s.evidenceUrl && (
                <a href={s.evidenceUrl} target="_blank" rel="noreferrer"
                  style={{ font: "500 11px Pretendard", color: C.peak, marginTop: 4, display: "inline-block" }}>
                  근거 링크 열기 ↗
                </a>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: 커밋**

```bash
git add admin/src/screens/MergeQueueScreen.tsx
git commit -m "Add submission detail expand + merge preview modal to MergeQueueScreen"
```

---

## Task 11: 프론트 타입체크

**Files:** 없음 (검증만)

- [ ] **Step 1: 타입체크 실행**

Run: `cd admin && npx tsc --noEmit`
Expected: 에러 없이 종료

---

## Task 12: End-to-end 검증

**Files:** 없음 (검증만)

- [ ] **Step 1: 백엔드 재기동**

기존에 떠 있는 백엔드 프로세스를 찾아 종료 후 재기동한다(이 프로젝트에서 반복적으로 쓰는 패턴):

```bash
netstat -ano | grep ":8080" | grep LISTENING
```

찾은 PID로:

```bash
taskkill //PID <PID> //F
cd backend && nohup ./gradlew.bat :app:bootRun > /tmp/backend-preview.log 2>&1 &
```

기동 대기(최대 30회, 3초 간격):

```bash
for i in $(seq 1 30); do
  code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/admin/merge-queue --max-time 2)
  if [ "$code" != "000" ]; then echo "up, code=$code"; break; fi
  sleep 3
done
```

Expected: `up, code=401` (미인증 401 — 서버가 뜬 것)

- [ ] **Step 2: 관리자 로그인 + 테스트 데이터 확인**

```bash
curl -s -c /tmp/admin_cookies.txt -X POST http://localhost:8080/admin/auth/login \
  -H "Content-Type: application/json" -d '{"loginId":"admin","password":"TestPass123!"}'
```

Expected: `{"loginId":"admin","displayName":"admin","role":"ADMIN"}`

`merge_queue`에 PENDING 항목이 있는지 확인(없으면 ClusterMergeJob이 0.75~0.85 유사도 후보를 만들어야 함 — 이 프로젝트 이전 세션에서 KURE-v1 임베딩으로 이미 검증된 경로이므로 새 시딩 스크립트는 이 태스크 범위 밖):

```bash
docker exec -e PGPASSWORD=pw trd-db psql -U postgres -d trd -c \
  "select id, new_trend_item_id, old_trend_item_id, similarity from merge_queue where status='PENDING' limit 1;"
```

PENDING 항목이 있으면 그 `id`를 다음 단계에서 사용한다. 없으면 이 단계는 스킵하고 Step 5(프론트 픽스처 모드 검증)로 건너뛴다.

- [ ] **Step 3: `/preview` 엔드포인트 curl 검증**

```bash
curl -s -b /tmp/admin_cookies.txt http://localhost:8080/admin/merge-queue/<위에서-찾은-id>/preview -w "\nHTTP:%{http_code}\n"
```

Expected: HTTP 200, `newCanonicalName`/`orderRank`/`firstSeenAtBefore`/`firstSeenAtAfter`/`baselineShifted`/`dedupVoidedHandles` 필드가 채워진 JSON.

- [ ] **Step 4: 큐 목록에 제보 상세 포함 확인**

```bash
curl -s -b /tmp/admin_cookies.txt http://localhost:8080/admin/merge-queue -w "\n" | head -c 2000
```

Expected: 응답에 `newSubmissions`/`oldSubmissions` 배열이 각 원소별 `rawInput`/`oneLine`/`evidenceUrl` 포함해서 들어있음.

- [ ] **Step 5: 브라우저 검증 (픽스처 모드)**

`admin/.env.local`이 없으면(기본값 = 픽스처 모드) 그대로 둔 채:

```bash
# preview_start (name: "admin")로 dev 서버 기동 후 브라우저에서:
# 1. ADM-100 화면 진입
# 2. "더 보기" 클릭 → raw_input/one_line/근거 링크가 펼쳐지는지 확인
# 3. "병합 후 미리보기" 클릭 → 모달에 대표명/선점순위/최초목격시각 표시 확인
# 4. "이 내용으로 병합" 클릭 → (픽스처 모드이므로) 데모 토스트 표시 + 모달 닫힘 확인
```

- [ ] **Step 6: 브라우저 검증 (실 API 모드, PENDING 항목이 있었던 경우)**

`admin/.env.local`에 `VITE_USE_FIXTURES=false` 설정 후 dev 서버 재기동, 로그인 후 ADM-100에서 Step 2~3과 동일한 항목으로 "병합 후 미리보기" 클릭 → 모달 내용이 curl 결과와 일치하는지 확인. 검증 후 `.env.local` 삭제(다른 화면들과 동일하게 기본은 픽스처 모드 유지).

- [ ] **Step 7: 커밋 여부는 사용자에게 확인**

이 플랜의 각 태스크는 이미 개별 커밋되어 있다. 이 단계는 검증 전용이며 추가 커밋이 필요 없다.

---

## Self-Review 체크리스트 (작성자용, 실행 시 참고만)

- **스펙 커버리지**: 확인 정보 보강(Task 5/7/9/10) ✅, 병합 후 미리보기(Task 2/4/5/7/8/9/10) ✅, 계산 로직 공유로 드리프트 버그 수정(Task 2/3) ✅, baseline 경고(Task 4/5/10) ✅, 모달에서 바로 확정(Task 10) ✅, 온디맨드 호출(preview는 버튼 클릭 시에만, Task 5/10) ✅.
- **비범위 확인**: Type 2 수동 병합, 제보자 등급 기반 판단 반영 — 둘 다 이번 플랜에 포함 안 함 (스펙과 일치).
