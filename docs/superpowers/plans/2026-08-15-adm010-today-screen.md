# ADM-010 오늘의 작업 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /admin/queues/summary`를 구현해 fixture 전용이던 ADM-010 화면을 실 데이터(병합 검수 큐 상태, 판정 임박, 시딩 비중)로 전환한다.

**Architecture:** 판정 기한 계산을 `domain-core`의 순수 함수(`DeadlineWindow`)로 분리해 `VerdictRunner`와 같은 규칙을 공유하면서도 배치 코드는 건드리지 않는다. `api-admin`에 신규 `QueueSummaryService`/`QueueSummaryController`를 추가한다. 프론트엔드는 이미 완성돼 있어 변경 없음.

**Tech Stack:** Spring Boot 3.3.5 / Java 17, JPA, JUnit 5.

**설계 문서:** `docs/superpowers/specs/2026-08-15-adm010-today-screen-design.md`

---

### Task 1: domain-core — `DeadlineWindow` 순수 함수 + 단위 테스트 (TDD)

**Files:**
- Create: `backend/domain-core/src/main/java/kr/trendstage/domain/verdict/DeadlineWindow.java`
- Test: `backend/domain-core/src/test/java/kr/trendstage/domain/DeadlineWindowTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.trendstage.domain;

import kr.trendstage.domain.verdict.DeadlineWindow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadlineWindowTest {

    @Test void override_없으면_first_seen_at_14일_후가_기한() {
        Instant firstSeenAt = Instant.parse("2026-08-01T00:00:00Z");
        Instant expected = Instant.parse("2026-08-15T00:00:00Z");
        assertEquals(expected, DeadlineWindow.effectiveDeadline(firstSeenAt, null));
    }

    @Test void override_있으면_override가_기한() {
        Instant firstSeenAt = Instant.parse("2026-08-01T00:00:00Z");
        Instant override = Instant.parse("2026-08-20T00:00:00Z");
        assertEquals(override, DeadlineWindow.effectiveDeadline(firstSeenAt, override));
    }

    @Test void 윈도우_시작_경계값은_포함() {
        Instant deadline = Instant.parse("2026-08-15T00:00:00Z");
        Instant windowStart = Instant.parse("2026-08-15T00:00:00Z");
        Instant windowEnd = Instant.parse("2026-08-16T00:00:00Z");
        assertTrue(DeadlineWindow.fallsWithin(deadline, windowStart, windowEnd));
    }

    @Test void 윈도우_끝_경계값은_배제() {
        Instant deadline = Instant.parse("2026-08-16T00:00:00Z");
        Instant windowStart = Instant.parse("2026-08-15T00:00:00Z");
        Instant windowEnd = Instant.parse("2026-08-16T00:00:00Z");
        assertFalse(DeadlineWindow.fallsWithin(deadline, windowStart, windowEnd));
    }

    @Test void 윈도우_밖이면_false() {
        Instant deadline = Instant.parse("2026-08-20T00:00:00Z");
        Instant now = Instant.parse("2026-08-15T00:00:00Z");
        assertFalse(DeadlineWindow.fallsWithin(deadline, now, now.plus(Duration.ofHours(24))));
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인 (컴파일 에러 — DeadlineWindow 없음)**

Run: `cd backend && ./gradlew :domain-core:test --tests DeadlineWindowTest`
Expected: FAIL (compilation error)

- [ ] **Step 3: `DeadlineWindow.java` 구현**

```java
package kr.trendstage.domain.verdict;

import java.time.Duration;
import java.time.Instant;

/**
 * 유효 판정 기한이 특정 시간창 안에 드는지 판정하는 순수 규칙.
 * VerdictRunner.isDue()와 같은 "override ?? first_seen_at+14일" 규칙을 쓰지만,
 * 배치 코드(VerdictRunner)는 건드리지 않고 ADM-010 집계 전용으로 별도 둔다.
 */
public final class DeadlineWindow {
    private DeadlineWindow() {}

    private static final int JUDGE_WINDOW_DAYS = 14;

    public static Instant effectiveDeadline(Instant firstSeenAt, Instant override) {
        return override != null ? override : firstSeenAt.plus(Duration.ofDays(JUDGE_WINDOW_DAYS));
    }

    public static boolean fallsWithin(Instant deadline, Instant windowStart, Instant windowEndExclusive) {
        return !deadline.isBefore(windowStart) && deadline.isBefore(windowEndExclusive);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew :domain-core:test --tests DeadlineWindowTest`
Expected: PASS (5 tests)

- [ ] **Step 5: 커밋**

```bash
git add backend/domain-core/src/main/java/kr/trendstage/domain/verdict/DeadlineWindow.java backend/domain-core/src/test/java/kr/trendstage/domain/DeadlineWindowTest.java
git commit -m "feat(domain-core): add DeadlineWindow pure function for ADM-010"
```

---

### Task 2: persistence — `SubmissionRepository`에 시딩 비율용 카운트 메서드 추가

**Files:**
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/SubmissionRepository.java`

- [ ] **Step 1: 현재 파일 확인 후 메서드 2개 추가**

`backend/persistence/src/main/java/kr/trendstage/persistence/repo/SubmissionRepository.java`를 먼저 읽고, 기존 메서드 목록(`countByUserIdAndResult` 등) 바로 아래에 추가:

```java

    long countByCreatedAtAfter(Instant since);

    long countByCreatedAtAfterAndSeedTrue(Instant since);
```

`import java.time.Instant;`가 파일에 이미 없다면 추가한다(현재 이 리포지토리는 `Instant`를 직접 쓰지 않으므로 없을 가능성이 큼 — 실제 파일을 확인해서 판단).

Spring Data가 `seed` 불리언 필드를 `SeedTrue` 파생 쿼리로 인식하려면 `Submission` 엔티티의 필드명이 `seed`(getter `isSeed()`)여야 한다 — `backend/persistence/src/main/java/kr/trendstage/persistence/entity/Submission.java`를 확인해 실제 필드명이 `seed`인지 먼저 확인한다(getter가 `isSeed()`이므로 필드명은 `seed`일 가능성이 높음).

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :persistence:compileJava`
Expected: BUILD SUCCESSFUL. 만약 `countByCreatedAtAfterAndSeedTrue`가 필드를 못 찾는다는 에러가 나면, 파생 쿼리 이름을 실제 필드명에 맞게 수정한다(예: 필드명이 다르면 `@Query`로 명시).

- [ ] **Step 3: 커밋**

```bash
git add backend/persistence/src/main/java/kr/trendstage/persistence/repo/SubmissionRepository.java
git commit -m "feat(persistence): add submission count-by-date queries for ADM-010"
```

---

### Task 3: api-admin — `QueueSummaryService`

**Files:**
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/queues/QueueSummaryService.java`

**Files to read first for exact signatures (all pre-existing):**
- `backend/persistence/src/main/java/kr/trendstage/persistence/repo/MergeQueueRepository.java` — `findByStatusOrderByCreatedAtAsc(MergeQueueStatus)`
- `backend/persistence/src/main/java/kr/trendstage/persistence/repo/TrendItemRepository.java` — `findByStateIn(List<TrendState>)`
- `backend/persistence/src/main/java/kr/trendstage/persistence/entity/TrendItem.java` — `getFirstSeenAt()`, `getJudgmentDeadlineOverride()`
- `backend/persistence/src/main/java/kr/trendstage/persistence/entity/MergeQueueEntry.java` — `getCreatedAt()`

- [ ] **Step 1: 서비스 작성**

```java
package kr.trendstage.apiadmin.queues;

import kr.trendstage.domain.verdict.DeadlineWindow;
import kr.trendstage.persistence.entity.MergeQueueEntry;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.MergeQueueRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.MergeQueueStatus;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

/**
 * ADM-010. 병합 검수 큐만 실제 백엔드가 있고(어뷰징/이의제기/신고는 서브시스템 자체가 미구현),
 * 그 셋은 0건 고정으로 응답한다 — 화면 구조는 유지하되 미구현임을 숨기지 않는다.
 */
@Service
public class QueueSummaryService {

    private static final int MERGE_SLA_HOURS = 24;
    private static final int SEED_RATIO_WINDOW_DAYS = 7;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final MergeQueueRepository mergeQueue;
    private final TrendItemRepository trendItems;
    private final SubmissionRepository submissions;
    private final Clock clock;

    public QueueSummaryService(MergeQueueRepository mergeQueue, TrendItemRepository trendItems,
                                SubmissionRepository submissions, Clock clock) {
        this.mergeQueue = mergeQueue;
        this.trendItems = trendItems;
        this.submissions = submissions;
        this.clock = clock;
    }

    public record QueueTile(String id, String name, int count, String oldest, boolean slaExceeded) {}
    public record Alert(String title, String detail) {}
    public record QueueSummaryResponse(int slaBreaches, List<QueueTile> queues, List<Alert> alerts,
                                        double seedRatio, int judgedToday, int imminent24h) {}

    public QueueSummaryResponse summarize() {
        Instant now = clock.instant();

        List<MergeQueueEntry> pending = mergeQueue.findByStatusOrderByCreatedAtAsc(MergeQueueStatus.PENDING);
        long mergeOverdue = pending.stream()
                .filter(e -> Duration.between(e.getCreatedAt(), now).toHours() >= MERGE_SLA_HOURS)
                .count();
        String oldest = pending.stream().map(MergeQueueEntry::getCreatedAt)
                .min(Comparator.naturalOrder())
                .map(t -> formatAgo(t, now)).orElse("-");

        List<TrendItem> active = trendItems.findByStateIn(List.of(TrendState.PENDING, TrendState.JUDGING));
        Instant todayStartKst = now.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
        Instant todayEndKst = todayStartKst.plus(Duration.ofDays(1));
        long judgedToday = active.stream()
                .filter(i -> DeadlineWindow.fallsWithin(
                        DeadlineWindow.effectiveDeadline(i.getFirstSeenAt(), i.getJudgmentDeadlineOverride()),
                        todayStartKst, todayEndKst))
                .count();
        long imminent24h = active.stream()
                .filter(i -> DeadlineWindow.fallsWithin(
                        DeadlineWindow.effectiveDeadline(i.getFirstSeenAt(), i.getJudgmentDeadlineOverride()),
                        now, now.plus(Duration.ofHours(24))))
                .count();

        Instant seedWindowStart = now.minus(Duration.ofDays(SEED_RATIO_WINDOW_DAYS));
        long seedCount = submissions.countByCreatedAtAfterAndSeedTrue(seedWindowStart);
        long totalCount = submissions.countByCreatedAtAfter(seedWindowStart);
        double seedRatio = totalCount == 0 ? 0.0 : (double) seedCount / totalCount;

        List<QueueTile> queues = List.of(
                new QueueTile("ADM-100", "병합 검수", pending.size(), oldest, mergeOverdue > 0),
                new QueueTile("ADM-300", "어뷰징", 0, "-", false),
                new QueueTile("ADM-400", "이의 제기", 0, "-", false),
                new QueueTile("ADM-410", "신고 콘텐츠", 0, "-", false)
        );
        List<Alert> alerts = mergeOverdue == 0 ? List.of() : List.of(
                new Alert("병합 검수 큐 SLA 초과 " + mergeOverdue + "건", "24시간 기준 초과, 미처리 시 판정 유예 자동 연장")
        );

        return new QueueSummaryResponse((int) mergeOverdue, queues, alerts,
                seedRatio, (int) judgedToday, (int) imminent24h);
    }

    private static String formatAgo(Instant t, Instant now) {
        Duration d = Duration.between(t, now);
        if (d.toHours() < 1) return d.toMinutes() + "분";
        return d.toHours() + "h";
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :api-admin:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/queues/QueueSummaryService.java
git commit -m "feat(api-admin): add QueueSummaryService for ADM-010"
```

---

### Task 4: api-admin — `QueueSummaryController`

**Files:**
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/QueueSummaryController.java`

- [ ] **Step 1: 컨트롤러 작성**

```java
package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.queues.QueueSummaryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADM-010 진입 화면 집계. 조회 전용이라 REVIEWER 이상 전부 허용(MergeQueueController.list()와 동일 권한). */
@RestController
@RequestMapping("/admin/queues")
public class QueueSummaryController {

    private final QueueSummaryService service;

    public QueueSummaryController(QueueSummaryService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN', 'AUDITOR')")
    public QueueSummaryService.QueueSummaryResponse summary() {
        return service.summarize();
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :api-admin:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 응답 필드명이 프론트 타입과 일치하는지 확인**

`admin/src/api/types.ts`의 `QueueSummary` 인터페이스를 읽고, `QueueSummaryResponse`/`QueueTile`/`Alert` record의 필드명이 전부 일치하는지 대조한다(대소문자·이름까지 정확히). Jackson은 record 컴포넌트명을 그대로 camelCase JSON 키로 직렬화하므로, 다르면 여기서 프론트가 깨진다.

- [ ] **Step 4: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/QueueSummaryController.java
git commit -m "feat(api-admin): add QueueSummaryController (GET /admin/queues/summary)"
```

---

### Task 5: 백엔드 전체 빌드 검증

**Files:** 없음(검증 전용)

- [ ] **Step 1: 전체 빌드**

Run: `cd backend && ./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 전체 테스트**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL, `DeadlineWindowTest` 5건 포함 전부 PASS

- [ ] **Step 3 (문제 발견 시): 수정 후 재검증**

---

### Task 6: end-to-end 브라우저 검증

**Files:** 없음(검증 전용, 프론트엔드 코드 변경 없음)

- [ ] **Step 1: 백엔드 기동**

로컬 dev DB(도커) 기동 상태에서 `./gradlew :app:bootRun`.

- [ ] **Step 2: 프론트 dev 서버 기동 (real API 모드)**

```bash
cd admin
VITE_USE_FIXTURES=false npm run dev
```

- [ ] **Step 3: 브라우저로 ADM-010 확인**

1. 로그인 후 진입 화면(ADM-010)에서 `GET /admin/queues/summary`가 200으로 응답하는지 네트워크 탭 확인
2. 병합 검수 카드 건수가 `merge_queue`의 실제 PENDING 건수와 일치하는지 확인(필요하면 DB에서 직접 카운트 비교)
3. 어뷰징/이의제기/신고 카드가 0건으로 고정 표시되는지 확인
4. `merge_queue`에 24시간 넘은 PENDING 항목을 하나 만들어(또는 `created_at`을 과거로 UPDATE) SLA 배너·시스템 알림이 뜨는지 확인
5. 판정 예정/D+14 임박 숫자가 `trend_items`의 실제 상태와 대략 맞는지 확인
6. 시딩 비중이 0~100% 범위의 합리적인 값으로 표시되는지 확인(제보가 아예 없는 경우 0%로 표시되고 에러가 안 나는지)

- [ ] **Step 4 (문제 발견 시): 원인 파악 후 관련 태스크로 돌아가 수정, 재검증**
