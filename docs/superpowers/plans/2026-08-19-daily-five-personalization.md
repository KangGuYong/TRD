# "오늘의 5개" 하루 고정 + 개인화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /v1/trends?daily=true`가 로그인 유저에게는 그날(KST) 안에서는 항상 같은 5개(관심 카테고리 우선순위로 개인화)를 반환하게 만든다 — 지금은 요청마다 실시간 재계산돼 완독 추적이 무의미해지는 문제를 고친다.

**Architecture:** `daily_selections` 신규 테이블에 유저×날짜×5개 `trend_item_id`를 고정 저장. 그날 첫 요청 때 지연 생성(lazy) — 배치 잡 없음. 선정 순서 계산은 `domain-core`에 순수 함수(`DailySelectionPicker`)로 분리해 단위 테스트. 동시 요청 경쟁은 `UNIQUE` 제약 + 별도 트랜잭션(REQUIRES_NEW) insert-or-ignore로 처리. 비로그인 요청은 기존 실시간 전체 top-5 동작을 그대로 유지.

**Tech Stack:** Spring Boot 3.3.5 / Java 17, Spring Data JPA, Flyway, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-08-19-daily-five-personalization-design.md`

---

## Task 1: `daily_selections` 마이그레이션 + 엔티티 + 리포지토리

**Files:**
- Create: `backend/persistence/src/main/resources/db/migration/V22__daily_selections.sql`
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/DailySelection.java`
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/DailySelectionRepository.java`

- [ ] **Step 1: 마이그레이션 작성**

`backend/persistence/src/main/resources/db/migration/V22__daily_selections.sql`:

```sql
-- V22 · 유저별 "오늘의 5개" 하루 고정 배정. selection_date는 KST 달력 날짜.
CREATE TABLE daily_selections (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID          NOT NULL REFERENCES users(id),
    selection_date DATE          NOT NULL,
    trend_item_id  UUID          NOT NULL REFERENCES trend_items(id),
    rank           SMALLINT      NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (user_id, selection_date, rank),
    UNIQUE (user_id, selection_date, trend_item_id)
);
```

- [ ] **Step 2: 엔티티 작성**

`backend/persistence/src/main/java/kr/trendstage/persistence/entity/DailySelection.java`:

```java
package kr.trendstage.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** "오늘의 5개" 하루 고정 배정 한 행 = 유저×날짜의 순위 하나. [[trend_reads]]와 함께 완독 추적의 기준이 되는 셋. */
@Entity
@Table(name = "daily_selections", uniqueConstraints = {
        @UniqueConstraint(name = "uq_daily_selections_rank", columnNames = {"user_id", "selection_date", "rank"}),
        @UniqueConstraint(name = "uq_daily_selections_item", columnNames = {"user_id", "selection_date", "trend_item_id"})
})
public class DailySelection {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "selection_date", nullable = false)
    private LocalDate selectionDate;

    @Column(name = "trend_item_id", nullable = false)
    private UUID trendItemId;

    @Column(name = "rank", nullable = false)
    private short rank;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected DailySelection() {}

    public DailySelection(UUID userId, LocalDate selectionDate, UUID trendItemId, short rank) {
        this.userId = userId;
        this.selectionDate = selectionDate;
        this.trendItemId = trendItemId;
        this.rank = rank;
    }

    public UUID getTrendItemId() { return trendItemId; }
    public short getRank() { return rank; }
}
```

- [ ] **Step 3: 리포지토리 작성**

`backend/persistence/src/main/java/kr/trendstage/persistence/repo/DailySelectionRepository.java`:

```java
package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.DailySelection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DailySelectionRepository extends JpaRepository<DailySelection, UUID> {
    List<DailySelection> findByUserIdAndSelectionDateOrderByRankAsc(UUID userId, LocalDate selectionDate);
}
```

- [ ] **Step 4: persistence 모듈 컴파일 확인**

Run: `cd backend && ./gradlew :persistence:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add backend/persistence/src/main/resources/db/migration/V22__daily_selections.sql backend/persistence/src/main/java/kr/trendstage/persistence/entity/DailySelection.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/DailySelectionRepository.java
git commit -m "feat(persistence): daily_selections table + entity + repo"
```

---

## Task 2: `DailySelectionPicker` 순수 함수 + 단위 테스트 (TDD)

카테고리 일치 우선 → `actionPriority` 순으로 정렬해 상위 N개 ID를 고르는 로직. DB/Spring 의존 없는 순수 함수로 분리해 `domain-core`에 둔다(기존 `StageEvaluator`와 같은 패키지). `TrendCategory`는 `persistence` 모듈에 있고 `domain-core`는 `persistence`에 의존하지 않으므로, 카테고리는 `String`으로 다룬다(`TrendCategory.name()` / `UserPreference.categories`가 이미 `String[]`이라 자연스럽게 맞음).

**Files:**
- Create: `backend/domain-core/src/main/java/kr/trendstage/domain/trend/DailySelectionPicker.java`
- Test: `backend/domain-core/src/test/java/kr/trendstage/domain/trend/DailySelectionPickerTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`backend/domain-core/src/test/java/kr/trendstage/domain/trend/DailySelectionPickerTest.java`:

```java
package kr.trendstage.domain.trend;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DailySelectionPickerTest {

    @Test void 선호_카테고리_일치_항목이_우선한다() {
        UUID meme = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        var candidates = List.of(
                new DailySelectionPicker.Candidate(product, "PRODUCT", 0), // actionPriority 더 좋음(0)
                new DailySelectionPicker.Candidate(meme, "MEME", 1));      // 선호 카테고리지만 priority는 나쁨(1)

        List<UUID> picked = DailySelectionPicker.pick(candidates, Set.of("MEME"), 5);

        assertEquals(List.of(meme, product), picked);
    }

    @Test void 선호_카테고리_내에서는_actionPriority_순으로_정렬한다() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var candidates = List.of(
                new DailySelectionPicker.Candidate(a, "MEME", 2),
                new DailySelectionPicker.Candidate(b, "MEME", 0));

        List<UUID> picked = DailySelectionPicker.pick(candidates, Set.of("MEME"), 5);

        assertEquals(List.of(b, a), picked);
    }

    @Test void 상위_N개로_제한한다() {
        var candidates = List.of(
                new DailySelectionPicker.Candidate(UUID.randomUUID(), "MEME", 0),
                new DailySelectionPicker.Candidate(UUID.randomUUID(), "MEME", 1),
                new DailySelectionPicker.Candidate(UUID.randomUUID(), "MEME", 2));

        assertEquals(2, DailySelectionPicker.pick(candidates, Set.of("MEME"), 2).size());
    }

    @Test void 선호_카테고리가_비어있으면_actionPriority_순으로만_정렬한다() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var candidates = List.of(
                new DailySelectionPicker.Candidate(a, "MEME", 1),
                new DailySelectionPicker.Candidate(b, "PRODUCT", 0));

        assertEquals(List.of(b, a), DailySelectionPicker.pick(candidates, Set.of(), 5));
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd backend && ./gradlew :domain-core:test --tests "kr.trendstage.domain.trend.DailySelectionPickerTest"`
Expected: FAIL — `DailySelectionPicker` 클래스가 없어 컴파일 에러

- [ ] **Step 3: 최소 구현 작성**

`backend/domain-core/src/main/java/kr/trendstage/domain/trend/DailySelectionPicker.java`:

```java
package kr.trendstage.domain.trend;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * "오늘의 5개" 선정 순서 계산. 순수 함수 — DB/시간 의존 없음.
 * 선호 카테고리 일치 항목 우선, 그 안에서는 actionPriority(낮을수록 먼저) 순.
 */
public final class DailySelectionPicker {
    private DailySelectionPicker() {}

    public record Candidate(UUID trendItemId, String category, int actionPriority) {}

    public static List<UUID> pick(List<Candidate> candidates, Set<String> preferredCategories, int limit) {
        Comparator<Candidate> byMatchThenPriority = Comparator
                .comparingInt((Candidate c) -> preferredCategories.contains(c.category()) ? 0 : 1)
                .thenComparingInt(Candidate::actionPriority);

        return candidates.stream()
                .sorted(byMatchThenPriority)
                .map(Candidate::trendItemId)
                .limit(limit)
                .toList();
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew :domain-core:test --tests "kr.trendstage.domain.trend.DailySelectionPickerTest"`
Expected: PASS (4/4)

- [ ] **Step 5: 커밋**

```bash
git add backend/domain-core/src/main/java/kr/trendstage/domain/trend/DailySelectionPicker.java backend/domain-core/src/test/java/kr/trendstage/domain/trend/DailySelectionPickerTest.java
git commit -m "feat(domain-core): DailySelectionPicker pure function + tests"
```

---

## Task 3: `DailySelectionWriter` — 동시성 안전 insert-or-ignore

같은 유저의 하루 첫 요청이 동시에 두 번 들어와도 5행이 중복 저장되지 않도록, 별도 트랜잭션(REQUIRES_NEW)에서 저장을 시도하고 유니크 제약 충돌 시 무시한다. 별도 빈으로 분리하는 이유: 같은 클래스 안의 `this.method()` 호출로는 `@Transactional(propagation = REQUIRES_NEW)`가 걸리지 않기 때문(Spring AOP 프록시는 외부 호출만 가로챔).

**Files:**
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/service/DailySelectionWriter.java`

- [ ] **Step 1: 구현**

`backend/api-public/src/main/java/kr/trendstage/apipublic/service/DailySelectionWriter.java`:

```java
package kr.trendstage.apipublic.service;

import kr.trendstage.persistence.entity.DailySelection;
import kr.trendstage.persistence.repo.DailySelectionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * "오늘의 5개" 저장 시도. 별도 트랜잭션(REQUIRES_NEW)에서 실행 — 동시 요청 경쟁으로
 * UNIQUE 제약을 위반해도 호출자의 트랜잭션(재조회)을 오염시키지 않는다.
 */
@Service
public class DailySelectionWriter {

    private final DailySelectionRepository repo;

    public DailySelectionWriter(DailySelectionRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void trySave(UUID userId, LocalDate selectionDate, List<UUID> trendItemIds) {
        try {
            List<DailySelection> rows = new ArrayList<>();
            short rank = 1;
            for (UUID trendItemId : trendItemIds) {
                rows.add(new DailySelection(userId, selectionDate, trendItemId, rank++));
            }
            repo.saveAll(rows);
            repo.flush();
        } catch (DataIntegrityViolationException e) {
            // 동시 요청이 먼저 저장 완료 — 무시. 호출자가 다시 조회해서 그 결과를 쓴다.
        }
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :api-public:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/api-public/src/main/java/kr/trendstage/apipublic/service/DailySelectionWriter.java
git commit -m "feat(api-public): DailySelectionWriter insert-or-ignore"
```

---

## Task 4: `TrendQueryService.home()` — 로그인 유저 개인화 고정 배정 연결

**Files:**
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/service/TrendQueryService.java`

- [ ] **Step 1: 생성자에 의존성 추가, `stageOf` 추출**

`toSummary`가 하던 단계 계산을 별도 메서드로 빼서 `generateSelection`에서도 재사용한다. 아래는 파일 전체를 교체하는 형태로 제시한다(기존 메서드 `detail`/`buildPropagationPath`/`toSummary`/`lifeText`는 그대로 유지, `home`과 새 private 메서드들만 추가/변경).

`backend/api-public/src/main/java/kr/trendstage/apipublic/service/TrendQueryService.java` 상단 import에 추가:

```java
import kr.trendstage.domain.trend.DailySelectionPicker;
import kr.trendstage.persistence.entity.DailySelection;
import kr.trendstage.persistence.entity.UserPreference;
import kr.trendstage.persistence.repo.DailySelectionRepository;
import kr.trendstage.persistence.repo.UserPreferenceRepository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.Arrays;
```

(`ZoneId`, `LinkedHashMap`은 이미 import돼 있으므로 중복 추가하지 않는다 — 실제 편집 시 기존 import 목록과 diff해서 빠진 것만 추가할 것.)

필드/생성자 교체:

```java
private static final int DAILY_LIMIT = 5;
private static final ZoneId KST = ZoneId.of("Asia/Seoul");
private static final DateTimeFormatter PATH_DATE = DateTimeFormatter.ofPattern("MM-dd").withZone(KST);

private final TrendItemRepository trends;
private final SubmissionRepository submissions;
private final VerdictRepository verdicts;
private final VoteRepository votes;
private final WatchRepository watches;
private final DailySelectionRepository dailySelections;
private final UserPreferenceRepository preferences;
private final DailySelectionWriter selectionWriter;

public TrendQueryService(TrendItemRepository trends, SubmissionRepository submissions,
                         VerdictRepository verdicts, VoteRepository votes, WatchRepository watches,
                         DailySelectionRepository dailySelections, UserPreferenceRepository preferences,
                         DailySelectionWriter selectionWriter) {
    this.trends = trends; this.submissions = submissions;
    this.verdicts = verdicts; this.votes = votes; this.watches = watches;
    this.dailySelections = dailySelections; this.preferences = preferences;
    this.selectionWriter = selectionWriter;
}
```

- [ ] **Step 2: `home()`을 유저 인지형으로 교체**

기존 `home(boolean daily)`를 아래로 교체:

```java
@Transactional(readOnly = true)
public List<TrendSummaryResponse> home(boolean daily, UUID userId) {
    if (daily && userId != null) {
        return dailyForUser(userId);
    }
    List<TrendSummaryResponse> all = liveRanked();
    return daily ? all.stream().limit(DAILY_LIMIT).toList() : all;
}

private List<TrendSummaryResponse> liveRanked() {
    return trends.findByStateIn(List.of(TrendState.PENDING, TrendState.JUDGING))
            .stream()
            .map(this::toSummary)
            .sorted(Comparator.comparingInt(r -> StageEvaluator.actionPriority(DisplayStage.valueOf(r.stage()))))
            .collect(Collectors.toList());
}

private List<TrendSummaryResponse> dailyForUser(UUID userId) {
    LocalDate today = LocalDate.now(KST);
    List<UUID> ids = dailySelections.findByUserIdAndSelectionDateOrderByRankAsc(userId, today)
            .stream().map(DailySelection::getTrendItemId).toList();
    if (ids.isEmpty()) {
        ids = generateSelection(userId, today);
    }
    Map<UUID, TrendItem> byId = trends.findAllById(ids).stream()
            .collect(Collectors.toMap(TrendItem::getId, it -> it));
    return ids.stream()
            .map(byId::get)
            .filter(Objects::nonNull)
            .map(this::toSummary)
            .toList();
}

/** 오늘자 배정이 없을 때만 호출. 후보군을 계산해 선정하고 저장을 시도한 뒤, 실제 저장된(경쟁 시 상대방 것일 수도 있는) 결과를 다시 읽는다. */
private List<UUID> generateSelection(UUID userId, LocalDate today) {
    List<TrendItem> candidates = trends.findByStateIn(List.of(TrendState.PENDING, TrendState.JUDGING));
    List<DailySelectionPicker.Candidate> picked = candidates.stream()
            .map(item -> new DailySelectionPicker.Candidate(
                    item.getId(),
                    item.getCategory() != null ? item.getCategory().name() : "",
                    StageEvaluator.actionPriority(stageOf(item))))
            .toList();

    Set<String> preferredCategories = preferences.findByUserId(userId)
            .map(UserPreference::getCategories)
            .map(Set::of)
            .orElseGet(Set::of);

    List<UUID> selected = DailySelectionPicker.pick(picked, preferredCategories, DAILY_LIMIT);
    if (selected.isEmpty()) return selected;

    selectionWriter.trySave(userId, today, selected);

    return dailySelections.findByUserIdAndSelectionDateOrderByRankAsc(userId, today)
            .stream().map(DailySelection::getTrendItemId).toList();
}

private DisplayStage stageOf(TrendItem item) {
    int reachedCount = submissions.findDistinctPlatforms(item.getId(), SubmissionResult.VOID).size();
    boolean resolvedFading = item.getState() == TrendState.RESOLVED;
    return StageEvaluator.fromReach(reachedCount, resolvedFading);
}
```

- [ ] **Step 3: `toSummary`가 `stageOf`를 재사용하도록 정리**

기존 `toSummary` 안의 아래 두 줄:

```java
boolean resolvedFading = item.getState() == TrendState.RESOLVED;   // 잠정 근사
DisplayStage stage = StageEvaluator.fromReach(reachedCount, resolvedFading);
```

을 다음으로 교체(단, `reachedCount`는 플랫폼 목록 크기로 여전히 필요하므로 `platforms` 조회는 유지):

```java
DisplayStage stage = stageOf(item);
```

`toSummary` 전체가 아래 형태가 되어야 한다:

```java
private TrendSummaryResponse toSummary(TrendItem item) {
    List<String> platforms = submissions.findDistinctPlatforms(item.getId(), SubmissionResult.VOID);
    int reachedCount = platforms.size();
    DisplayStage stage = stageOf(item);

    String meaning = submissions.findFirstByTrendItemIdOrderByCreatedAtAsc(item.getId())
            .map(Submission::getOneLine).orElse("");

    String pathText = String.join(" → ", platforms);

    return new TrendSummaryResponse(
            item.getId(),
            item.getCanonicalName(),
            meaning,
            stage.name(),
            stage.label(),
            lifeText(stage),
            pathText,
            reachedCount,
            null);
}
```

(참고: `stageOf`가 내부적으로 `submissions.findDistinctPlatforms`를 다시 호출해 `reachedCount`와 별개로 조회하지만, `toSummary`가 이미 `platforms`를 가지고 있으므로 중복 쿼리 1회가 늘어난다. 트렌드 항목 수가 적어 무시 가능한 수준이라 지금은 단순함을 우선한다 — 최적화가 필요해지면 `stageOf(int reachedCount, TrendState state)`로 시그니처를 바꿔 중복 조회를 없앨 것.)

- [ ] **Step 4: api-public 모듈 컴파일 확인**

Run: `cd backend && ./gradlew :api-public:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add backend/api-public/src/main/java/kr/trendstage/apipublic/service/TrendQueryService.java
git commit -m "feat(api-public): personalize + day-fix daily-5 selection for logged-in users"
```

---

## Task 5: `TrendController` — 인증 정보 전달

**Files:**
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/TrendController.java:36-40`

- [ ] **Step 1: `list()`가 `Authentication`을 받아 `userId`를 넘기도록 수정**

기존:

```java
/** 홈 "오늘의 5개" / 목록. daily=true면 5개로 끝(더 보기 없음). */
@GetMapping
public TrendListResponse list(@RequestParam(name = "daily", defaultValue = "false") boolean daily) {
    return new TrendListResponse(service.home(daily), null);
}
```

교체:

```java
/** 홈 "오늘의 5개" / 목록. daily=true면 5개로 끝(더 보기 없음).
 * 로그인 유저는 그날(KST) 안에서 고정된 개인화 5개, 비로그인은 실시간 전체 top-5. */
@GetMapping
public TrendListResponse list(@RequestParam(name = "daily", defaultValue = "false") boolean daily, Authentication auth) {
    UUID viewerId = auth != null ? (UUID) auth.getPrincipal() : null;
    return new TrendListResponse(service.home(daily, viewerId), null);
}
```

- [ ] **Step 2: api-public 모듈 컴파일 확인**

Run: `cd backend && ./gradlew :api-public:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/api-public/src/main/java/kr/trendstage/apipublic/web/TrendController.java
git commit -m "feat(api-public): pass viewer identity into daily-5 endpoint"
```

---

## Task 6: 전체 빌드 + 실동작 검증

**Files:** 없음(검증 전용)

- [ ] **Step 1: 전체 빌드 + 테스트**

Run: `cd backend && ./gradlew build`
Expected: BUILD SUCCESSFUL, `DailySelectionPickerTest` 4개 포함 전체 테스트 통과

- [ ] **Step 2: 로컬 dev DB로 기동**

Run: `cd backend && ./gradlew :app:bootRun`
Expected: Flyway가 V22 정상 적용(로그에 `Migrating schema ... to version "22 - daily selections"` 확인), 정상 기동

- [ ] **Step 3: 로그인 유저로 daily=true 2회 호출해 고정 여부 확인**

기존 세션에서 쓰던 방식대로(Firebase custom token → ID token 발급 후 curl, 또는 이미 로그인된 Expo 앱)으로:

```bash
curl -s "http://localhost:8080/v1/trends?daily=true" -H "Authorization: Bearer $TOKEN" | jq '.items[].id'
curl -s "http://localhost:8080/v1/trends?daily=true" -H "Authorization: Bearer $TOKEN" | jq '.items[].id'
```

Expected: 두 응답의 `id` 목록과 순서가 동일. `psql`로 `SELECT * FROM daily_selections WHERE user_id = '<uid>'`가 정확히 5행(rank 1~5)인지 확인.

- [ ] **Step 4: 비로그인 요청은 기존과 동일하게 동작하는지 확인**

```bash
curl -s "http://localhost:8080/v1/trends?daily=true" | jq '.items | length'
```

Expected: 5개 반환(토큰 없이도 정상 동작, `daily_selections`에 아무 것도 저장 안 됨 — `psql`로 재확인).

- [ ] **Step 5: 앱(Expo)에서 홈 화면 확인**

로그인 상태로 홈 화면을 열어 "오늘의 5개"가 뜨는지, 하나를 읽음 처리한 뒤 앱을 재시작해도 그 항목이 여전히 오늘의 5개 안에 있고 "읽음" 표시가 유지되는지 확인.

---

## Verification Checklist (자체 점검용, 실행 불필요)

- [x] 스펙의 "선정 알고리즘"(카테고리 우선 → actionPriority) → Task 2, 4
- [x] 스펙의 "생성 시점: 지연 생성" → Task 4 `generateSelection`
- [x] 스펙의 "하루 안 재조회 시 데이터 신선도"(상태 필터 없이 ID로 직접 조회) → Task 4 `dailyForUser`가 `trends.findAllById` 사용(상태 필터 없음)
- [x] 스펙의 "익명 사용자" → Task 4 `home()`의 `userId == null` 분기
- [x] 스펙의 "동시성" → Task 3
- [x] 스펙의 "daily=false는 변경 없음" → Task 4 `liveRanked()`가 기존 로직 그대로 유지
