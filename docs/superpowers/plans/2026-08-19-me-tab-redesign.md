# 나 탭 재구성 + 온보딩 게이팅 수정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 나 탭을 등급 카드(쉬운 문구) + 제보권/적중률 요약 + 워치·제보 현황 요약 + 설정 진입점으로 재구성하고, 온보딩이 로그인 여부·저장된 preferences 유무로 정확히 게이팅되도록 고친다.

**Architecture:** 백엔드는 `domain-core`에 순수 함수(`GradeRequirementKind`, `SubmissionQuota`, `VoteAccuracy`) 2개를 추가하고, `api-public`에 `GET /v1/me/summary`와 `GET`/`PUT /v1/me/preferences`(기존엔 컨트롤러 자체가 없었음)를 새로 붙인다. 프론트는 `MeScreen`을 재구성하고, `OnboardingScreen`의 입력 UI를 `PreferencesForm` 컴포넌트로 추출해 신규 `SettingsScreen`과 공유하며, `App.tsx`의 온보딩 게이트를 서버 조회 기반으로 바꾼다.

**Tech Stack:** Spring Boot 3.3.5 / Java 17, JUnit 5, React Native/Expo, TanStack Query, TypeScript.

**설계 문서:** [docs/superpowers/specs/2026-08-19-me-tab-redesign-design.md](../specs/2026-08-19-me-tab-redesign-design.md)

---

## Task 1: domain-core — GradeRequirementKind + GradePolicy에 kind 배선

**Files:**
- Create: `backend/domain-core/src/main/java/kr/trendstage/domain/grade/GradeRequirementKind.java`
- Modify: `backend/domain-core/src/main/java/kr/trendstage/domain/grade/GradeRequirement.java`
- Modify: `backend/domain-core/src/main/java/kr/trendstage/domain/grade/GradePolicy.java`
- Modify: `backend/domain-core/src/test/java/kr/trendstage/domain/EngineGoldenTest.java`

- [ ] **Step 1: 실패하는 테스트 작성 — 요구 항목이 올바른 kind로 채워지는지**

`EngineGoldenTest.java`의 `grade_는_AS와_TI를_모두_요구` 테스트 바로 아래에 추가:

```java
    @Test void grade_요구항목은_kind로_구분된다() {
        GradeStatus gs = GradePolicy.evaluate(17, 0.48, 186); // L2 → L3 요구
        assertEquals(3, gs.requirements().size());
        assertEquals(GradeRequirementKind.JUDGED_COUNT, gs.requirements().get(0).kind());
        assertEquals(GradeRequirementKind.TRUST_INDEX, gs.requirements().get(1).kind());
        assertEquals(GradeRequirementKind.ACTIVE_SCORE, gs.requirements().get(2).kind());
    }
```

파일 상단 import에 `import kr.trendstage.domain.grade.GradeRequirementKind;` 추가(같은 패키지라 사실 불필요 — `EngineGoldenTest`가 `kr.trendstage.domain` 패키지이므로 `kr.trendstage.domain.grade.*`를 이미 import하고 있는지 파일 상단을 확인하고, 없으면 `import kr.trendstage.domain.grade.*;` 추가).

- [ ] **Step 2: 테스트 실행 → 컴파일 실패 확인**

Run: `cd backend && ./gradlew.bat :domain-core:test --tests EngineGoldenTest`
Expected: FAIL — `GradeRequirementKind`가 존재하지 않아 컴파일 에러.

- [ ] **Step 3: GradeRequirementKind enum 생성**

```java
package kr.trendstage.domain.grade;

/** 승급 요구 항목의 종류. 앱이 label/basis 원문 대신 이걸로 분기해 쉬운 문구를 만든다. */
public enum GradeRequirementKind { JUDGED_COUNT, TRUST_INDEX, ACTIVE_SCORE }
```

- [ ] **Step 4: GradeRequirement에 kind 필드 추가**

`GradeRequirement.java` 전체를 교체:

```java
package kr.trendstage.domain.grade;

/**
 * 승급 요구 항목 하나와 그 충족 여부.
 * {@code basis}는 산정 근거 문자열(예 "TI 0.48 / 요구치 0.55 / 부족분 0.07") — 관리자 콘솔용, 문구를 바꾸지 않는다.
 * {@code kind}는 앱이 쉬운 말로 재렌더링할 때 쓰는 판별 키.
 * 앱 GET /v1/me/grade 응답에 그대로 노출된다(04 §7.3).
 */
public record GradeRequirement(GradeRequirementKind kind, String label, double current, double required, boolean met, String basis) {}
```

- [ ] **Step 5: GradePolicy가 kind를 넘기도록 수정**

`GradePolicy.java`에서 `reqs.add(...)` 세 줄과 `req()` 메서드를 교체:

```java
        List<GradeRequirement> reqs = new ArrayList<>();
        if (nextReq != null) {
            reqs.add(req(GradeRequirementKind.JUDGED_COUNT, "판정 완료", judgedCount, nextReq.minJudged, 0));
            reqs.add(req(GradeRequirementKind.TRUST_INDEX, "신뢰도 지수 TI", trustIndex, nextReq.minTi, 2));
            reqs.add(req(GradeRequirementKind.ACTIVE_SCORE, "활동 점수 AS", activeScore, nextReq.minAs, 0));
        }
        return new GradeStatus(current, next, reqs);
    }

    private static GradeRequirement req(GradeRequirementKind kind, String label, double cur, double required, int decimals) {
        boolean met = cur >= required;
        double gap = Math.max(0, required - cur);
        String fmt = "%." + decimals + "f";
        String basis = met
                ? String.format(Locale.US, label + " " + fmt + " / 요구치 " + fmt + " · 충족", cur, required)
                : String.format(Locale.US, label + " " + fmt + " / 요구치 " + fmt + " / 부족분 " + fmt, cur, required, gap);
        return new GradeRequirement(kind, label, cur, required, met, basis);
    }
```

- [ ] **Step 6: 테스트 실행 → 통과 확인**

Run: `cd backend && ./gradlew.bat :domain-core:test --tests EngineGoldenTest`
Expected: PASS (전체 테스트 클래스 통과, 신규 테스트 포함)

- [ ] **Step 7: 커밋**

```bash
git add backend/domain-core/src/main/java/kr/trendstage/domain/grade/GradeRequirementKind.java backend/domain-core/src/main/java/kr/trendstage/domain/grade/GradeRequirement.java backend/domain-core/src/main/java/kr/trendstage/domain/grade/GradePolicy.java backend/domain-core/src/test/java/kr/trendstage/domain/EngineGoldenTest.java
git commit -m "feat(grade): add GradeRequirementKind so the app can render friendly wording without touching admin-facing basis text"
```

---

## Task 2: domain-core — SubmissionQuota 순수 함수

**Files:**
- Create: `backend/domain-core/src/main/java/kr/trendstage/domain/grade/SubmissionQuota.java`
- Create: `backend/domain-core/src/test/java/kr/trendstage/domain/SubmissionQuotaTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.trendstage.domain;

import kr.trendstage.domain.grade.Grade;
import kr.trendstage.domain.grade.SubmissionQuota;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubmissionQuotaTest {

    @Test void 등급별_주간_한도는_CLAUDE_md_표를_따른다() {
        assertEquals(2, SubmissionQuota.weeklyLimit(Grade.L0));
        assertEquals(3, SubmissionQuota.weeklyLimit(Grade.L1));
        assertEquals(5, SubmissionQuota.weeklyLimit(Grade.L2));
        assertEquals(8, SubmissionQuota.weeklyLimit(Grade.L3));
        assertEquals(12, SubmissionQuota.weeklyLimit(Grade.L4));
    }
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd backend && ./gradlew.bat :domain-core:test --tests SubmissionQuotaTest`
Expected: FAIL — `SubmissionQuota` 클래스가 없어 컴파일 에러.

- [ ] **Step 3: SubmissionQuota 구현**

```java
package kr.trendstage.domain.grade;

import java.util.Map;

/** 등급별 주간 제보권 한도. 01 §? 표 그대로 — 이월 없음, 매주 월요일 00:00 KST 리필(배치는 별도). */
public final class SubmissionQuota {
    private SubmissionQuota() {}

    private static final Map<Grade, Integer> WEEKLY_LIMIT = Map.of(
            Grade.L0, 2, Grade.L1, 3, Grade.L2, 5, Grade.L3, 8, Grade.L4, 12
    );

    public static int weeklyLimit(Grade grade) {
        return WEEKLY_LIMIT.get(grade);
    }
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `cd backend && ./gradlew.bat :domain-core:test --tests SubmissionQuotaTest`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add backend/domain-core/src/main/java/kr/trendstage/domain/grade/SubmissionQuota.java backend/domain-core/src/test/java/kr/trendstage/domain/SubmissionQuotaTest.java
git commit -m "feat(grade): add SubmissionQuota pure function for weekly submission limits"
```

---

## Task 3: domain-core — VoteAccuracy 순수 함수

**Files:**
- Create: `backend/domain-core/src/main/java/kr/trendstage/domain/vote/VoteAccuracy.java`
- Create: `backend/domain-core/src/test/java/kr/trendstage/domain/VoteAccuracyTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.trendstage.domain;

import kr.trendstage.domain.vote.VoteAccuracy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VoteAccuracyTest {

    @Test void 투표가_없으면_적중률_0() {
        var r = VoteAccuracy.compute(List.of());
        assertEquals(0, r.total());
        assertEquals(0, r.correct());
        assertEquals(0.0, r.hitRate(), 1e-9);
    }

    @Test void 예측이_맞은_경우만_correct에_들어간다() {
        var r = VoteAccuracy.compute(List.of(
                new VoteAccuracy.VoteOutcome(true, true),   // 뜬다에 투표, 실제 HIT → 맞음
                new VoteAccuracy.VoteOutcome(true, false),  // 뜬다에 투표, 실제 MISS → 틀림
                new VoteAccuracy.VoteOutcome(false, false)  // 안 뜬다에 투표, 실제 MISS → 맞음
        ));
        assertEquals(3, r.total());
        assertEquals(2, r.correct());
        assertEquals(2.0 / 3.0, r.hitRate(), 1e-9);
    }
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd backend && ./gradlew.bat :domain-core:test --tests VoteAccuracyTest`
Expected: FAIL — `kr.trendstage.domain.vote` 패키지/클래스가 없어 컴파일 에러.

- [ ] **Step 3: VoteAccuracy 구현**

```java
package kr.trendstage.domain.vote;

import java.util.List;

/**
 * 유저의 "뜬다/안 뜬다" 투표가 실제 판정(HIT/MISS)과 맞았는지 집계.
 * VOID·미판정 항목은 호출자가 미리 걸러서 넘긴다(이 함수는 판정 난 것만 안다는 전제).
 */
public final class VoteAccuracy {
    private VoteAccuracy() {}

    public record VoteOutcome(boolean willTrend, boolean actualHit) {}
    public record Result(int total, int correct, double hitRate) {}

    public static Result compute(List<VoteOutcome> outcomes) {
        int total = outcomes.size();
        int correct = 0;
        for (VoteOutcome o : outcomes) {
            if (o.willTrend() == o.actualHit()) correct++;
        }
        double hitRate = total == 0 ? 0.0 : (double) correct / total;
        return new Result(total, correct, hitRate);
    }
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `cd backend && ./gradlew.bat :domain-core:test --tests VoteAccuracyTest`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add backend/domain-core/src/main/java/kr/trendstage/domain/vote/VoteAccuracy.java backend/domain-core/src/test/java/kr/trendstage/domain/VoteAccuracyTest.java
git commit -m "feat(vote): add VoteAccuracy pure function for user prediction hit-rate"
```

---

## Task 4: persistence — Vote/Submission repo 추가 메서드

**Files:**
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/Vote.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/VoteRepository.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/SubmissionRepository.java`

이 계층은 프레임워크 배선(getter/파생 쿼리)이라 이 프로젝트 관례상 단위 테스트를 따로 만들지 않는다 — Task 6의 통합 curl 검증에서 실제로 맞물리는지 확인한다.

- [ ] **Step 1: Vote에 getTrendItemId() getter 추가**

`Vote.java`의 `public UUID getId() { return id; }` 바로 아래에 추가:

```java
    public UUID getTrendItemId() { return trendItemId; }
```

- [ ] **Step 2: VoteRepository에 findByUserId 추가**

`VoteRepository.java` 전체를 교체:

```java
package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.Vote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VoteRepository extends JpaRepository<Vote, UUID> {
    Optional<Vote> findByUserIdAndTrendItemId(UUID userId, UUID trendItemId);
    long countByTrendItemIdAndWillTrend(UUID trendItemId, boolean willTrend);
    List<Vote> findByUserId(UUID userId);
}
```

- [ ] **Step 3: SubmissionRepository에 주간 카운트 쿼리 추가**

`SubmissionRepository.java`의 마지막 메서드(`countByCreatedAtAfterAndSeedTrue`) 다음 줄에 추가:

```java

    /** 나 탭 제보권 표시용 — 이번 주(월요일 0시 KST 이후) 유효 제보 수. */
    long countByUserIdAndCreatedAtAfterAndResultNot(UUID userId, Instant since, SubmissionResult excluded);
```

- [ ] **Step 4: 컴파일 확인**

Run: `cd backend && ./gradlew.bat :persistence:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add backend/persistence/src/main/java/kr/trendstage/persistence/entity/Vote.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/VoteRepository.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/SubmissionRepository.java
git commit -m "feat(persistence): add repo queries for vote history and weekly submission count"
```

---

## Task 5: persistence — user_preferences 테이블 + 엔티티 + 리포지토리

**Files:**
- Create: `backend/persistence/src/main/resources/db/migration/V20__user_preferences.sql`
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/UserPreference.java`
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/UserPreferenceRepository.java`

- [ ] **Step 1: 마이그레이션 작성**

```sql
-- V20 · 관심 카테고리 · 알림 시간. 1인 1행(append-only 대상 아님 — 원장/판정과 달리 "현재값"만 의미 있음).
CREATE TABLE user_preferences (
    user_id      UUID          PRIMARY KEY REFERENCES users(id),
    categories   TEXT[]        NOT NULL,
    notify_hour  SMALLINT      NOT NULL CHECK (notify_hour BETWEEN 0 AND 23),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
```

- [ ] **Step 2: UserPreference 엔티티 작성**

`TrendItem.java`의 `aliases`(String[] + `@JdbcTypeCode(SqlTypes.ARRAY)`) 패턴을 그대로 따른다.

```java
package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** 관심 카테고리 · 알림 시간. 유저 1명당 1행 — 온보딩에서 생성, 설정 화면에서 갱신. */
@Entity
@Table(name = "user_preferences")
public class UserPreference {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "categories", columnDefinition = "text[]", nullable = false)
    private String[] categories;

    @Column(name = "notify_hour", nullable = false)
    private short notifyHour;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected UserPreference() {}

    public UserPreference(UUID userId, String[] categories, short notifyHour) {
        this.userId = userId;
        this.categories = categories;
        this.notifyHour = notifyHour;
    }

    public UUID getUserId() { return userId; }
    public String[] getCategories() { return categories; }
    public short getNotifyHour() { return notifyHour; }

    public void update(String[] categories, short notifyHour) {
        this.categories = categories;
        this.notifyHour = notifyHour;
        this.updatedAt = Instant.now();
    }
}
```

- [ ] **Step 3: UserPreferenceRepository 작성**

```java
package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, UUID> {
    Optional<UserPreference> findByUserId(UUID userId);
}
```

(PK가 `userId` 자체이므로 `findById(userId)`로도 충분하지만, `MeService`에서 의미가 분명히 드러나도록 `findByUserId`를 명시적으로 둔다.)

- [ ] **Step 4: 컴파일 확인**

Run: `cd backend && ./gradlew.bat :persistence:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add backend/persistence/src/main/resources/db/migration/V20__user_preferences.sql backend/persistence/src/main/java/kr/trendstage/persistence/entity/UserPreference.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/UserPreferenceRepository.java
git commit -m "feat(persistence): add user_preferences table + entity + repo"
```

---

## Task 6: api-public — MeService에 summary()/preferences() 추가 + MeController 라우트

**Files:**
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/MeSummaryResponse.java`
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/PreferencesResponse.java`
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/PreferencesRequest.java`
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/PreferencesNotFoundException.java`
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/GradeRequirementResponse.java`
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/ApiExceptionHandler.java`
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/service/MeService.java`
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/MeController.java`

이 서비스 계층은 이 프로젝트에서 지금까지 전부 Spring 통합(curl) 검증으로 확인해왔다(`MeService.grade()`/`ledger()`도 단위 테스트 없음) — 이 태스크도 같은 관례를 따르고, Step 마지막에 curl로 실제 확인한다.

- [ ] **Step 1: GradeRequirementResponse에 kind 필드 추가**

```java
package kr.trendstage.apipublic.web;

import kr.trendstage.domain.grade.GradeRequirementKind;

public record GradeRequirementResponse(GradeRequirementKind kind, String label, double current, double required, boolean met, String basis) {}
```

- [ ] **Step 2: 신규 DTO 3개 작성**

```java
// MeSummaryResponse.java
package kr.trendstage.apipublic.web;

public record MeSummaryResponse(
        int streakDays, int quotaUsed, int quotaMax,
        double voteHitRate, int votesTotal, int votesCorrect, int totalRead
) {}
```

```java
// PreferencesResponse.java
package kr.trendstage.apipublic.web;

import kr.trendstage.persistence.type.TrendCategory;

import java.util.List;

public record PreferencesResponse(List<TrendCategory> categories, int notifyHour) {}
```

```java
// PreferencesRequest.java
package kr.trendstage.apipublic.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import kr.trendstage.persistence.type.TrendCategory;

import java.util.List;

public record PreferencesRequest(
        @NotEmpty List<TrendCategory> categories,
        @Min(0) @Max(23) int notifyHour
) {}
```

- [ ] **Step 3: PreferencesNotFoundException + 핸들러 매핑**

```java
// PreferencesNotFoundException.java
package kr.trendstage.apipublic.web;

/** 아직 온보딩을 완료하지 않은 유저 — GET /v1/me/preferences가 404로 응답할 때 쓴다. */
public class PreferencesNotFoundException extends RuntimeException {
    public PreferencesNotFoundException() { super("저장된 설정이 없습니다"); }
}
```

`ApiExceptionHandler.java`에 `TrendNotFoundException` 핸들러 바로 아래 추가:

```java
    @ExceptionHandler(PreferencesNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handle(PreferencesNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem(404, e.getMessage()));
    }
```

- [ ] **Step 4: MeService 수정 — grade() 리팩터 + summary()/preferences() 추가**

`MeService.java` 전체를 교체:

```java
package kr.trendstage.apipublic.service;

import kr.trendstage.apipublic.web.GradeRequirementResponse;
import kr.trendstage.apipublic.web.GradeStatusResponse;
import kr.trendstage.apipublic.web.LedgerEntryResponse;
import kr.trendstage.apipublic.web.LedgerListResponse;
import kr.trendstage.apipublic.web.MeSummaryResponse;
import kr.trendstage.apipublic.web.PreferencesNotFoundException;
import kr.trendstage.apipublic.web.PreferencesRequest;
import kr.trendstage.apipublic.web.PreferencesResponse;
import kr.trendstage.domain.grade.Grade;
import kr.trendstage.domain.grade.GradePolicy;
import kr.trendstage.domain.grade.GradeStatus;
import kr.trendstage.domain.grade.SubmissionQuota;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.ActiveScore;
import kr.trendstage.domain.score.TrustIndex;
import kr.trendstage.domain.vote.VoteAccuracy;
import kr.trendstage.persistence.entity.ScoreLedgerEntry;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.entity.UserPreference;
import kr.trendstage.persistence.entity.Verdict;
import kr.trendstage.persistence.entity.Vote;
import kr.trendstage.persistence.repo.ScoreLedgerRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.UserPreferenceRepository;
import kr.trendstage.persistence.repo.VerdictRepository;
import kr.trendstage.persistence.repo.VoteRepository;
import kr.trendstage.persistence.type.SubmissionResult;
import kr.trendstage.persistence.type.TrendCategory;
import kr.trendstage.domain.verdict.VerdictResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 등급·원장·요약·설정 조회. GradeRecalcJob과 동일한 순수 함수 조합을 매 요청 실시간으로 호출한다 —
 * user_grades 스냅샷은 주 1회만 갱신되므로 그걸 읽으면 최대 일주일 묵은 값을 보여줄 수 있다.
 */
@Service
public class MeService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Map<Grade, String> GRADE_NAMES = Map.of(
            Grade.L0, "관찰자", Grade.L1, "제보자", Grade.L2, "탐지자", Grade.L3, "분석가", Grade.L4, "선구자"
    );

    private final SubmissionRepository submissions;
    private final ScoreLedgerRepository ledger;
    private final TrendItemRepository trends;
    private final VoteRepository votes;
    private final VerdictRepository verdicts;
    private final UserPreferenceRepository preferences;
    private final Clock clock;

    public MeService(SubmissionRepository submissions, ScoreLedgerRepository ledger, TrendItemRepository trends,
                     VoteRepository votes, VerdictRepository verdicts, UserPreferenceRepository preferences, Clock clock) {
        this.submissions = submissions; this.ledger = ledger; this.trends = trends;
        this.votes = votes; this.verdicts = verdicts; this.preferences = preferences; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public GradeStatusResponse grade(UUID userId) {
        GradeStatus status = computeGradeStatus(userId);
        int judged = judgedCount(userId);
        double ti = TrustIndex.compute(hitCount(userId), missCount(userId), ParameterSet.defaults());
        double as = activeScore(userId);

        List<GradeRequirementResponse> reqs = status.requirements().stream()
                .map(r -> new GradeRequirementResponse(r.kind(), r.label(), r.current(), r.required(), r.met(), r.basis()))
                .toList();

        String note = status.current() == status.next() ? "최고 등급입니다" : null;

        return new GradeStatusResponse(
                status.current().name(), GRADE_NAMES.get(status.current()),
                ti, as, judged, GRADE_NAMES.get(status.next()), reqs, note);
    }

    @Transactional(readOnly = true)
    public MeSummaryResponse summary(UUID userId) {
        GradeStatus status = computeGradeStatus(userId);
        int quotaMax = SubmissionQuota.weeklyLimit(status.current());

        var now = clock.instant();
        var weekStart = now.atZone(KST).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(KST).toInstant();
        int quotaUsed = (int) submissions.countByUserIdAndCreatedAtAfterAndResultNot(userId, weekStart, SubmissionResult.VOID);

        List<VoteAccuracy.VoteOutcome> outcomes = new ArrayList<>();
        for (Vote v : votes.findByUserId(userId)) {
            Verdict current = verdicts.findCurrentByTrendItemId(v.getTrendItemId()).orElse(null);
            if (current != null && current.getResult() != VerdictResult.VOID) {
                outcomes.add(new VoteAccuracy.VoteOutcome(v.isWillTrend(), current.getResult() == VerdictResult.HIT));
            }
        }
        VoteAccuracy.Result acc = VoteAccuracy.compute(outcomes);

        // streakDays/totalRead: reads 테이블이 없어 계산 불가 — 항상 0. 프론트는 이 두 값을 표시에 쓰지 않는다(설계 문서 §2).
        return new MeSummaryResponse(0, quotaUsed, quotaMax, acc.hitRate(), acc.total(), acc.correct(), 0);
    }

    @Transactional(readOnly = true)
    public PreferencesResponse getPreferences(UUID userId) {
        UserPreference p = preferences.findByUserId(userId).orElseThrow(PreferencesNotFoundException::new);
        List<TrendCategory> cats = Arrays.stream(p.getCategories()).map(TrendCategory::valueOf).toList();
        return new PreferencesResponse(cats, p.getNotifyHour());
    }

    @Transactional
    public PreferencesResponse savePreferences(UUID userId, PreferencesRequest req) {
        String[] cats = req.categories().stream().map(Enum::name).toArray(String[]::new);
        UserPreference existing = preferences.findByUserId(userId).orElse(null);
        if (existing == null) {
            preferences.save(new UserPreference(userId, cats, (short) req.notifyHour()));
        } else {
            existing.update(cats, (short) req.notifyHour());
        }
        return new PreferencesResponse(req.categories(), req.notifyHour());
    }

    @Transactional(readOnly = true)
    public LedgerListResponse ledger(UUID userId) {
        List<LedgerEntryResponse> items = ledger.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(e -> new LedgerEntryResponse(
                        wordFor(e),
                        e.getKind().name(),
                        e.getDelta().doubleValue(),
                        e.getReason(),
                        e.getCreatedAt()))
                .toList();
        return new LedgerListResponse(items, null);
    }

    private GradeStatus computeGradeStatus(UUID userId) {
        int judged = judgedCount(userId);
        double ti = TrustIndex.compute(hitCount(userId), missCount(userId), ParameterSet.defaults());
        double as = activeScore(userId);
        return GradePolicy.evaluate(judged, ti, as);
    }

    private int judgedCount(UUID userId) {
        return (int) (hitCount(userId) + missCount(userId));
    }

    private long hitCount(UUID userId) {
        return submissions.countByUserIdAndResult(userId, SubmissionResult.HIT);
    }

    private long missCount(UUID userId) {
        return submissions.countByUserIdAndResult(userId, SubmissionResult.MISS);
    }

    private double activeScore(UUID userId) {
        List<ActiveScore.Aged> aged = new ArrayList<>();
        var now = clock.instant();
        for (ScoreLedgerEntry e : ledger.findByUserIdOrderByCreatedAtDesc(userId)) {
            long ageDays = Duration.between(e.getCreatedAt(), now).toDays();
            aged.add(new ActiveScore.Aged(e.getDelta().doubleValue(), Math.max(0, ageDays)));
        }
        return ActiveScore.compute(aged, ParameterSet.defaults());
    }

    private String wordFor(ScoreLedgerEntry e) {
        if (e.getSubmissionId() == null) return "계정 조정";
        return submissions.findById(e.getSubmissionId())
                .flatMap(s -> trends.findById(s.getTrendItemId()))
                .map(TrendItem::getCanonicalName)
                .orElse("(삭제된 항목)");
    }
}
```

- [ ] **Step 5: MeController에 라우트 추가**

`MeController.java` 전체를 교체:

```java
package kr.trendstage.apipublic.web;

import jakarta.validation.Valid;
import kr.trendstage.apipublic.service.MeService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** OpenAPI /v1/me/grade, /v1/me/ledger, /v1/me/summary, /v1/me/preferences 대응. 인증 필요(PublicSecurityConfig). */
@RestController
public class MeController {

    private final MeService service;

    public MeController(MeService service) { this.service = service; }

    @GetMapping("/v1/me/grade")
    public GradeStatusResponse grade(Authentication auth) {
        return service.grade(userId(auth));
    }

    @GetMapping("/v1/me/ledger")
    public LedgerListResponse ledger(Authentication auth) {
        return service.ledger(userId(auth));
    }

    @GetMapping("/v1/me/summary")
    public MeSummaryResponse summary(Authentication auth) {
        return service.summary(userId(auth));
    }

    @GetMapping("/v1/me/preferences")
    public PreferencesResponse getPreferences(Authentication auth) {
        return service.getPreferences(userId(auth));
    }

    @PutMapping("/v1/me/preferences")
    public PreferencesResponse putPreferences(Authentication auth, @Valid @RequestBody PreferencesRequest req) {
        return service.savePreferences(userId(auth), req);
    }

    private UUID userId(Authentication auth) {
        return (UUID) auth.getPrincipal();
    }
}
```

- [ ] **Step 6: 전체 백엔드 빌드 + 테스트 확인**

Run: `cd backend && ./gradlew.bat build`
Expected: BUILD SUCCESSFUL (전체 모듈 컴파일 + 모든 테스트 통과)

- [ ] **Step 7: 로컬 서버로 통합 확인**

`backend/app/.env`에 `FIREBASE_CREDENTIALS_PATH`가 이미 설정돼 있음(이전 세션에서 완료). 서버 기동:

```bash
cd backend && ./gradlew.bat :app:bootRun
```

Flyway 로그에 `V20__user_preferences.sql` 마이그레이션 성공이 찍히는지 확인. 그 다음 실제 Firebase ID 토큰으로(앱에서 로그인한 계정) curl:

```bash
curl -s http://192.168.0.56:8080/v1/me/summary -H "Authorization: Bearer <ID_TOKEN>"
curl -s http://192.168.0.56:8080/v1/me/preferences -H "Authorization: Bearer <ID_TOKEN>"
# 위는 404({"status":404,...}) 나와야 정상(아직 저장 안 함)
curl -s -X PUT http://192.168.0.56:8080/v1/me/preferences -H "Authorization: Bearer <ID_TOKEN>" -H "Content-Type: application/json" -d '{"categories":["MEME","SLANG"],"notifyHour":8}'
curl -s http://192.168.0.56:8080/v1/me/preferences -H "Authorization: Bearer <ID_TOKEN>"
# 이번엔 200 + 방금 저장한 값
curl -s http://192.168.0.56:8080/v1/me/grade -H "Authorization: Bearer <ID_TOKEN>"
# requirements[].kind가 JUDGED_COUNT/TRUST_INDEX/ACTIVE_SCORE로 나오는지 확인
```

Expected: 위 순서대로 정상 동작. 서버는 `./gradlew.bat --stop`으로 종료.

- [ ] **Step 8: 커밋**

```bash
git add backend/api-public backend/domain-core backend/persistence
git commit -m "feat(api): implement GET /v1/me/summary and GET/PUT /v1/me/preferences (was unimplemented despite being in OpenAPI)"
```

---

## Task 7: 프론트 — 타입 + 훅 추가

**Files:**
- Modify: `app/src/api/types.ts`
- Modify: `app/src/api/hooks.ts`

- [ ] **Step 1: GradeRequirement 타입에 kind 추가**

`types.ts`의 `GradeRequirement` interface를 교체:

```typescript
export type GradeRequirementKind = "JUDGED_COUNT" | "TRUST_INDEX" | "ACTIVE_SCORE";
export interface GradeRequirement {
  kind: GradeRequirementKind;
  label: string;
  current: number;
  required: number;
  met: boolean;
  basis: string;
}
```

- [ ] **Step 2: useMyPreferences 훅 추가 (404 → null)**

`hooks.ts` 상단 import를 교체:

```typescript
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError } from "./client";
import type {
  GradeStatus, Ledger, MeSummary, Preferences, SubmissionCreate, SubmissionMine,
  TrendDetail, TrendList, VoteResult, WatchItem,
} from "./types";
```

`useWatch` 정의 바로 아래에 추가:

`enabled` 파라미터는 로그인 안 한 상태에서 이 요청을 아예 안 나가게 하려고 있다(App.tsx의 온보딩 게이트가 비로그인일 땐 `enabled: false`로 호출 — Task 11).

```typescript
export const useMyPreferences = (enabled = true) =>
  useQuery({
    queryKey: qk.prefs,
    enabled,
    queryFn: async (): Promise<Preferences | null> => {
      try {
        return await api.get<Preferences>("/v1/me/preferences");
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
  });
```

- [ ] **Step 3: 타입체크**

Run: `cd app && npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add app/src/api/types.ts app/src/api/hooks.ts
git commit -m "feat(app): add GradeRequirementKind type and useMyPreferences hook"
```

---

## Task 8: 프론트 — PreferencesForm 공용 컴포넌트 추출

**Files:**
- Create: `app/src/components/PreferencesForm.tsx`
- Modify: `app/src/screens/OnboardingScreen.tsx`

**컨텍스트:** `OnboardingScreen.tsx`는 3단계 위저드(0: 인트로, 1: 카테고리 선택, 2: 알림 시간)다. 카테고리 선택 + 알림 시간 부분(step 1, 2의 렌더링과 상태)을 `SettingsScreen`(Task 9)과 공유하기 위해 `PreferencesForm`으로 뽑아낸다. 인트로(step 0)와 "다음/완료" 버튼 흐름은 온보딩 고유 로직이라 그대로 `OnboardingScreen`에 남긴다.

- [ ] **Step 1: PreferencesForm 컴포넌트 작성**

온보딩은 카테고리 선택과 시간 선택을 별도 스텝으로 보여주고, 설정 화면은 둘을 한 화면에서 같이 보여준다 — `section` prop으로 두 쓰임을 모두 지원한다.

```tsx
import React from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import type { Category } from "../api/types";
import { C } from "../theme";

export const CATS: { key: Category; label: string }[] = [
  { key: "MEME", label: "밈·신조어" }, { key: "PRODUCT", label: "상품" }, { key: "PERSON_CHANNEL", label: "인물·채널" },
  { key: "CHALLENGE", label: "챌린지" }, { key: "SLANG", label: "슬랭" }, { key: "ETC", label: "기타" },
];
export const TIMES: { h: number; label: string; sub: string }[] = [
  { h: 7, label: "아침 7시", sub: "출근길 전에" }, { h: 8, label: "아침 8시", sub: "출근길에 딱" },
  { h: 12, label: "점심 12시", sub: "밥 먹으면서" }, { h: 22, label: "밤 10시", sub: "자기 전에" },
];

export function PreferencesForm({
  cats, onToggleCat, hour, onSelectHour, section = "both",
}: {
  cats: Category[];
  onToggleCat: (k: Category) => void;
  hour: number;
  onSelectHour: (h: number) => void;
  section?: "categories" | "time" | "both";
}) {
  return (
    <View>
      {section !== "time" && (
        <View>
          <Text style={s.h2}>관심 분야 3개만{"\n"}골라주세요</Text>
          <Text style={s.p}>{cats.length}/3 선택</Text>
          <View style={s.chipWrap}>
            {CATS.map((c) => {
              const on = cats.includes(c.key);
              return (
                <Pressable key={c.key} onPress={() => onToggleCat(c.key)} style={[s.chip, on && { backgroundColor: C.ink, borderColor: C.ink }]}>
                  <Text style={{ color: on ? "#fff" : C.ink, fontWeight: "500", fontSize: 14.5 }}>{c.label}</Text>
                </Pressable>
              );
            })}
          </View>
        </View>
      )}

      {section !== "categories" && (
        <View style={section === "both" ? { marginTop: 30 } : undefined}>
          <Text style={s.h2}>몇 시에 알려드릴까요</Text>
          <View style={{ gap: 9, marginTop: 16 }}>
            {TIMES.map((t) => {
              const on = hour === t.h;
              return (
                <Pressable key={t.h} onPress={() => onSelectHour(t.h)} style={[s.timeRow, { borderColor: on ? C.ink : "rgba(20,19,15,0.1)", borderWidth: 1.5 }]}>
                  <View>
                    <Text style={s.timeLabel}>{t.label}</Text>
                    <Text style={s.timeSub}>{t.sub}</Text>
                  </View>
                  <View style={[s.radio, { borderWidth: on ? 6 : 1.5, borderColor: on ? C.ink : "rgba(20,19,15,0.18)" }]} />
                </Pressable>
              );
            })}
          </View>
        </View>
      )}
    </View>
  );
}

const s = StyleSheet.create({
  h2: { fontSize: 24, fontWeight: "700", letterSpacing: -0.6, color: C.ink, lineHeight: 32 },
  p: { fontSize: 14, color: C.sub, marginTop: 8 },
  chipWrap: { flexDirection: "row", flexWrap: "wrap", gap: 8, marginTop: 16 },
  chip: { paddingHorizontal: 16, paddingVertical: 13, borderRadius: 100, borderWidth: 1, borderColor: "rgba(20,19,15,0.12)", backgroundColor: "#fff" },
  timeRow: { flexDirection: "row", justifyContent: "space-between", alignItems: "center", padding: 17, borderRadius: 15, backgroundColor: "#fff" },
  timeLabel: { fontSize: 16, fontWeight: "600", color: C.ink },
  timeSub: { fontSize: 12.5, color: C.sub, marginTop: 3 },
  radio: { width: 20, height: 20, borderRadius: 20 },
});
```

- [ ] **Step 2: OnboardingScreen이 PreferencesForm을 쓰도록 교체**

`OnboardingScreen.tsx` 전체를 교체(step 1/2 렌더링만 `PreferencesForm` 호출로 바뀌고, step 0 인트로·진행바·다음 버튼 로직은 동일):

```tsx
import React, { useState } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { useSavePreferences } from "../api/hooks";
import type { Category } from "../api/types";
import { PreferencesForm } from "../components/PreferencesForm";
import { Screen } from "../components/ui";
import { C } from "../theme";

export default function OnboardingScreen({ onDone }: { onDone: () => void }) {
  const save = useSavePreferences();
  const [step, setStep] = useState(0);
  const [cats, setCats] = useState<Category[]>([]);
  const [hour, setHour] = useState(8);

  const toggleCat = (k: Category) =>
    setCats((prev) => (prev.includes(k) ? prev.filter((c) => c !== k) : prev.length < 3 ? [...prev, k] : prev));

  const canNext = step === 1 ? cats.length === 3 : true;
  const next = () => {
    if (step < 2) return setStep(step + 1);
    save.mutate({ categories: cats, notifyHour: hour }, { onSettled: onDone }); // 실패해도 진입(오프라인 관용)
  };

  return (
    <Screen edges={["top", "bottom"]} style={s.wrap}>
      <View style={s.steps}>
        {[0, 1, 2].map((i) => <View key={i} style={[s.stepBar, { backgroundColor: i <= step ? C.ink : "rgba(20,19,15,0.15)" }]} />)}
      </View>

      <View style={{ flex: 1, justifyContent: "center" }}>
        {step === 0 && (
          <View>
            <View style={s.bars}>
              {[[C.seed, 16], [C.rising, 38], [C.peak, 70], [C.fading, 30], ["rgba(20,19,15,0.12)", 10]].map(([c, h], i) => (
                <View key={i} style={{ width: 26, height: h as number, borderRadius: 4, backgroundColor: c as string }} />
              ))}
            </View>
            <Text style={s.h2}>유행은 뜨는 게 아니라{"\n"}지나가는 겁니다.</Text>
            <Text style={s.p}>매일 아침 5개. 각각이 지금 어느 단계이고 며칠 남았는지 알려드릴게요.</Text>
          </View>
        )}

        {step === 1 && <PreferencesForm section="categories" cats={cats} onToggleCat={toggleCat} hour={hour} onSelectHour={() => {}} />}
        {step === 2 && <PreferencesForm section="time" cats={cats} onToggleCat={() => {}} hour={hour} onSelectHour={setHour} />}
      </View>

      <Pressable onPress={next} disabled={!canNext || save.isPending} style={[s.cta, { backgroundColor: canNext ? C.ink : "rgba(20,19,15,0.1)" }]}>
        <Text style={{ color: canNext ? "#fff" : "rgba(20,19,15,0.35)", fontWeight: "600", fontSize: 16 }}>
          {step === 0 ? "시작하기" : step === 1 ? (cats.length < 3 ? `${3 - cats.length}개 더 골라주세요` : "다음") : "오늘의 5개 보기"}
        </Text>
      </Pressable>
    </Screen>
  );
}

const s = StyleSheet.create({
  wrap: { padding: 26, paddingTop: 20, paddingBottom: 20 },
  steps: { flexDirection: "row", gap: 5 },
  stepBar: { height: 3, width: 34, borderRadius: 9 },
  bars: { flexDirection: "row", gap: 7, alignItems: "flex-end", height: 74, marginBottom: 34 },
  h2: { fontSize: 30, fontWeight: "700", letterSpacing: -0.7, color: C.ink, lineHeight: 40 },
  p: { fontSize: 15, color: C.sub, lineHeight: 25, marginTop: 14 },
  cta: { paddingVertical: 17, borderRadius: 15, alignItems: "center" },
});
```

- [ ] **Step 3: 타입체크**

Run: `cd app && npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add app/src/components/PreferencesForm.tsx app/src/screens/OnboardingScreen.tsx
git commit -m "refactor(app): extract PreferencesForm from OnboardingScreen for reuse in SettingsScreen"
```

---

## Task 9: 프론트 — SettingsScreen 신규 + 네비게이션 배선

**Files:**
- Create: `app/src/screens/SettingsScreen.tsx`
- Modify: `app/src/navigation/types.ts`
- Modify: `app/src/navigation/RootNavigator.tsx`

- [ ] **Step 1: navigation 타입에 MeStack 추가**

`types.ts` 전체를 교체:

```typescript
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";

export type HomeStackParamList = {
  Home: undefined;
  Detail: { id: string };
};
export type HomeNav = NativeStackNavigationProp<HomeStackParamList>;

export type MeStackParamList = {
  Me: undefined;
  Settings: undefined;
};
export type MeNav = NativeStackNavigationProp<MeStackParamList>;
```

- [ ] **Step 2: SettingsScreen 작성**

```tsx
import React, { useEffect, useState } from "react";
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import { useMyPreferences, useSavePreferences } from "../api/hooks";
import type { Category } from "../api/types";
import { PreferencesForm } from "../components/PreferencesForm";
import { H1, Screen } from "../components/ui";
import type { MeNav } from "../navigation/types";
import { C } from "../theme";

export default function SettingsScreen() {
  const nav = useNavigation<MeNav>();
  const prefs = useMyPreferences();
  const save = useSavePreferences();
  const [cats, setCats] = useState<Category[]>([]);
  const [hour, setHour] = useState(8);

  useEffect(() => {
    if (prefs.data) {
      setCats(prefs.data.categories);
      setHour(prefs.data.notifyHour);
    }
  }, [prefs.data]);

  const toggleCat = (k: Category) =>
    setCats((prev) => (prev.includes(k) ? prev.filter((c) => c !== k) : prev.length < 3 ? [...prev, k] : prev));

  const onSave = () => {
    save.mutate({ categories: cats, notifyHour: hour }, { onSuccess: () => nav.goBack() });
  };

  if (prefs.isLoading) {
    return (
      <Screen edges={["top", "bottom"]} style={s.center}>
        <ActivityIndicator color={C.ink} />
      </Screen>
    );
  }

  return (
    <Screen edges={["top", "bottom"]} style={s.wrap}>
      <H1>설정</H1>
      <View style={{ marginTop: 20, flex: 1 }}>
        <PreferencesForm cats={cats} onToggleCat={toggleCat} hour={hour} onSelectHour={setHour} />
      </View>
      <Pressable onPress={onSave} disabled={cats.length !== 3 || save.isPending} style={[s.cta, { backgroundColor: cats.length === 3 ? C.ink : "rgba(20,19,15,0.1)" }]}>
        <Text style={{ color: cats.length === 3 ? "#fff" : "rgba(20,19,15,0.35)", fontWeight: "600", fontSize: 16 }}>
          {cats.length !== 3 ? `관심 분야 ${3 - cats.length}개 더 골라주세요` : "저장"}
        </Text>
      </Pressable>
    </Screen>
  );
}

const s = StyleSheet.create({
  wrap: { padding: 20, paddingTop: 8, paddingBottom: 20, flex: 1 },
  center: { flex: 1, alignItems: "center", justifyContent: "center" },
  cta: { paddingVertical: 17, borderRadius: 15, alignItems: "center" },
});
```

- [ ] **Step 3: RootNavigator에 MeStack 배선**

`RootNavigator.tsx`에서 `MeScreen`/`GatedMe` 관련 부분을 교체. 전체 파일을 교체:

```tsx
import React from "react";
import { Text } from "react-native";
import { createBottomTabNavigator } from "@react-navigation/bottom-tabs";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { C } from "../theme";
import type { HomeStackParamList, MeStackParamList } from "./types";
import HomeScreen from "../screens/HomeScreen";
import DetailScreen from "../screens/DetailScreen";
import SearchScreen from "../screens/SearchScreen";
import SubmitScreen from "../screens/SubmitScreen";
import WatchScreen from "../screens/WatchScreen";
import MeScreen from "../screens/MeScreen";
import SettingsScreen from "../screens/SettingsScreen";
import { AuthGate } from "../components/AuthGate";

const GatedSubmit = () => <AuthGate><SubmitScreen /></AuthGate>;
const GatedWatch = () => <AuthGate><WatchScreen /></AuthGate>;

const Stack = createNativeStackNavigator<HomeStackParamList>();
const MeStackNav = createNativeStackNavigator<MeStackParamList>();
const Tab = createBottomTabNavigator();

function HomeStack() {
  return (
    <Stack.Navigator screenOptions={{ headerShown: false, contentStyle: { backgroundColor: C.bg } }}>
      <Stack.Screen name="Home" component={HomeScreen} />
      <Stack.Screen name="Detail" component={DetailScreen} options={{ headerShown: true, title: "", headerBackTitle: "오늘의 5개", headerStyle: { backgroundColor: C.bg }, headerShadowVisible: false }} />
    </Stack.Navigator>
  );
}

function MeStack() {
  return (
    <MeStackNav.Navigator screenOptions={{ headerShown: false, contentStyle: { backgroundColor: C.bg } }}>
      <MeStackNav.Screen name="Me" component={MeScreen} />
      <MeStackNav.Screen name="Settings" component={SettingsScreen} options={{ headerShown: true, title: "설정", headerStyle: { backgroundColor: C.bg }, headerShadowVisible: false }} />
    </MeStackNav.Navigator>
  );
}

const GatedMe = () => <AuthGate><MeStack /></AuthGate>;

const icon = (glyph: string) => ({ color }: { color: string }) => <Text style={{ color, fontSize: 18 }}>{glyph}</Text>;

export default function RootNavigator() {
  return (
    <Tab.Navigator
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: C.ink,
        tabBarInactiveTintColor: "rgba(20,19,15,0.32)",
        tabBarStyle: { backgroundColor: C.bg, borderTopColor: C.line },
      }}
    >
      <Tab.Screen name="홈" component={HomeStack} options={{ tabBarIcon: icon("⌂") }} />
      <Tab.Screen name="검색" component={SearchScreen} options={{ tabBarIcon: icon("⌕") }} />
      <Tab.Screen name="제보" component={GatedSubmit} options={{ tabBarIcon: icon("＋") }} />
      <Tab.Screen name="워치" component={GatedWatch} options={{ tabBarIcon: icon("◉") }} />
      <Tab.Screen name="나" component={GatedMe} options={{ tabBarIcon: icon("☺") }} />
    </Tab.Navigator>
  );
}
```

- [ ] **Step 4: 타입체크**

Run: `cd app && npx tsc --noEmit`
Expected: 에러 없음 (Task 10에서 `MeScreen`에 "설정" 진입점을 추가하기 전까지는 `SettingsScreen`으로의 네비게이션 진입점이 없다 — 정상. `MeStack` 배선 자체는 지금 컴파일된다.)

- [ ] **Step 5: 커밋**

```bash
git add app/src/screens/SettingsScreen.tsx app/src/navigation/types.ts app/src/navigation/RootNavigator.tsx
git commit -m "feat(app): add SettingsScreen and wire it into a MeStack for navigation from the Me tab"
```

---

## Task 10: 프론트 — MeScreen 재구성

**Files:**
- Modify: `app/src/screens/MeScreen.tsx`

**컨텍스트:** 현재 `MeScreen.tsx`는 이미 헤더(이메일+로그아웃, 이전 세션에서 완료), 등급 카드, 통계 카드(silently 죽어있던 `useMeSummary`), 원장을 갖고 있다. 이번 태스크에서: 등급 카드를 `kind` 기반 쉬운 문구로 바꾸고, 스트릭 자리(placeholder)+제보권(실데이터) 스트립을 추가하고, 통계 카드를 적중률 하나로 줄이고, 워치 요약·제보 현황 요약 섹션을 새로 추가하고, 하단에 설정 진입점을 추가한다.

- [ ] **Step 1: MeScreen.tsx 전체 교체**

```tsx
import React from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import { useMeSummary, useMyGrade, useMyLedger, useMySubmissions, useWatch } from "../api/hooks";
import type { GradeRequirementKind } from "../api/types";
import { Card, H1, Muted, Screen, StateView } from "../components/ui";
import type { MeNav } from "../navigation/types";
import { useAuth } from "../state/auth";
import { C } from "../theme";

const REQ_TEXT: Record<GradeRequirementKind, (current: number, required: number) => string> = {
  JUDGED_COUNT: (c, r) => `판정 완료된 제보 ${Math.round(c)}건 (목표 ${Math.round(r)}건)`,
  TRUST_INDEX: (c, r) => `예측 적중률 ${Math.round(c * 100)}% (목표 ${Math.round(r * 100)}%)`,
  ACTIVE_SCORE: (c, r) => `활동 점수 ${Math.round(c)}점 (목표 ${Math.round(r)}점)`,
};
const REQ_LABEL: Record<GradeRequirementKind, string> = {
  JUDGED_COUNT: "판정 완료된 제보",
  TRUST_INDEX: "예측 적중률",
  ACTIVE_SCORE: "활동 점수",
};

export default function MeScreen() {
  const nav = useNavigation<MeNav>();
  const { user, signOut } = useAuth();
  const grade = useMyGrade();
  const ledger = useMyLedger();
  const summary = useMeSummary();
  const watch = useWatch();
  const mySubs = useMySubmissions();

  return (
    <Screen>
    <ScrollView style={{ flex: 1 }} contentContainerStyle={{ padding: 20, paddingTop: 8, paddingBottom: 40 }}>
      <View style={{ flexDirection: "row", justifyContent: "space-between", alignItems: "center" }}>
        <H1>나</H1>
        <Pressable onPress={() => signOut()} hitSlop={8}>
          <Text style={s.logout}>로그아웃</Text>
        </Pressable>
      </View>
      {!!user?.email && <Muted style={{ marginTop: 4 }}>{user.email}</Muted>}

      {/* 등급 */}
      <View style={{ marginTop: 16 }}>
        <StateView query={grade}>
          {(g) => (
            <Card>
              <View style={{ flexDirection: "row", justifyContent: "space-between", alignItems: "flex-start" }}>
                <View style={s.gradePill}>
                  <View style={[s.dot, { backgroundColor: C.rising }]} />
                  <Text style={s.gradeCode}>{g.grade}</Text>
                  <Text style={s.gradeName}>{g.gradeName}</Text>
                </View>
                <View style={{ alignItems: "flex-end" }}>
                  <Muted style={{ fontSize: 11 }}>판정 완료</Muted>
                  <Text style={s.judged}>{g.judgedCount}</Text>
                </View>
              </View>

              <View style={s.divider} />
              <Muted style={{ fontSize: 12, marginBottom: 13 }}>다음 등급까지 — {g.nextGrade}</Muted>
              <View style={{ gap: 13 }}>
                {g.requirements.map((r, i) => {
                  const pct = Math.min(100, (r.current / r.required) * 100);
                  const col = r.met ? C.rising : C.ink;
                  return (
                    <View key={i}>
                      <View style={{ flexDirection: "row", justifyContent: "space-between" }}>
                        <Text style={s.reqLabel}>{REQ_LABEL[r.kind]}</Text>
                        <Text style={[s.reqFig, { color: col }]}>{r.current} / {r.required}</Text>
                      </View>
                      <View style={s.track}><View style={{ width: `${pct}%`, height: "100%", borderRadius: 9, backgroundColor: col }} /></View>
                      <Muted style={{ fontSize: 11.5, marginTop: 6 }}>{REQ_TEXT[r.kind](r.current, r.required)}</Muted>
                    </View>
                  );
                })}
              </View>
              {!!g.note && <Muted style={s.note}>{g.note}</Muted>}
            </Card>
          )}
        </StateView>
      </View>

      {/* 스트릭 · 제보권 */}
      <View style={{ flexDirection: "row", gap: 9, marginTop: 11 }}>
        <Card style={{ flex: 1, padding: 15 }}>
          <Muted style={{ fontSize: 12 }}>🔥 스트릭</Muted>
          <Text style={s.devSoon}>개발 예정</Text>
        </Card>
        <Card style={{ flex: 1, padding: 15 }}>
          <Muted style={{ fontSize: 12 }}>이번 주 제보권</Muted>
          {summary.data ? (
            <Text style={s.statValue}>{summary.data.quotaUsed} / {summary.data.quotaMax}</Text>
          ) : (
            <Text style={s.devSoon}>불러오는 중</Text>
          )}
        </Card>
      </View>

      {/* 적중률 */}
      {summary.data && (
        <Card style={{ marginTop: 9, padding: 17 }}>
          <Muted style={{ fontSize: 12 }}>예측 적중률</Muted>
          <Text style={{ fontSize: 26, fontWeight: "700", color: C.ink, marginTop: 8 }}>
            {Math.round(summary.data.voteHitRate * 100)}%
          </Text>
          <Muted style={{ fontSize: 11.5, marginTop: 5 }}>
            투표 {summary.data.votesTotal}회 중 {summary.data.votesCorrect}회 적중
          </Muted>
        </Card>
      )}

      {/* 워치 요약 */}
      <View style={{ marginTop: 11 }}>
        <StateView query={watch} empty={(d) => d.length === 0}>
          {(items) => (
            <Card>
              <Text style={s.sectionTitle}>워치 중인 키워드</Text>
              <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 7, marginTop: 12 }}>
                {items.slice(0, 5).map((w) => (
                  <View key={w.keyword} style={s.watchChip}>
                    <Text style={s.watchChipText}>{w.keyword}</Text>
                  </View>
                ))}
              </View>
              <Muted style={{ fontSize: 11.5, marginTop: 10 }}>{items.length}개 워치 중</Muted>
            </Card>
          )}
        </StateView>
      </View>

      {/* 내 제보 현황 */}
      <View style={{ marginTop: 11 }}>
        <StateView query={mySubs} empty={(d) => d.length === 0}>
          {(subs) => {
            const pending = subs.filter((s) => s.status === "PENDING").length;
            const hit = subs.filter((s) => s.status === "HIT").length;
            const miss = subs.filter((s) => s.status === "MISS").length;
            return (
              <Card>
                <Text style={s.sectionTitle}>내 제보 현황</Text>
                <View style={{ flexDirection: "row", gap: 18, marginTop: 12 }}>
                  <SubStat label="판정 대기" value={pending} color={C.ink} />
                  <SubStat label="적중" value={hit} color={C.rising} />
                  <SubStat label="실패" value={miss} color={C.fading} />
                </View>
              </Card>
            );
          }}
        </StateView>
      </View>

      {/* 원장 */}
      <View style={{ marginTop: 11 }}>
        <StateView query={ledger} empty={(d) => d.items.length === 0}>
          {(l) => (
            <Card>
              <Text style={s.ledgerTitle}>점수 원장</Text>
              <View style={{ gap: 12, marginTop: 14 }}>
                {l.items.map((e, i) => (
                  <View key={i} style={{ flexDirection: "row", justifyContent: "space-between", alignItems: "flex-start", gap: 12 }}>
                    <View style={{ flex: 1 }}>
                      <Text style={s.lWord}>{e.word}</Text>
                      <Muted style={{ fontSize: 11.5, marginTop: 3 }}>{e.reason}</Muted>
                    </View>
                    <Text style={[s.lDelta, { color: e.delta > 0 ? C.rising : e.delta < 0 ? C.fading : C.faint }]}>
                      {e.delta > 0 ? `+${e.delta}` : e.delta}
                    </Text>
                  </View>
                ))}
              </View>
            </Card>
          )}
        </StateView>
      </View>

      <Pressable onPress={() => nav.navigate("Settings")} style={s.settingsRow}>
        <Text style={s.settingsText}>⚙ 설정 — 관심 분야 · 알림 시간</Text>
      </Pressable>
    </ScrollView>
    </Screen>
  );
}

function SubStat({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <View>
      <Text style={{ fontSize: 20, fontWeight: "700", color }}>{value}</Text>
      <Muted style={{ fontSize: 11, marginTop: 2 }}>{label}</Muted>
    </View>
  );
}

const s = StyleSheet.create({
  gradePill: { flexDirection: "row", alignItems: "center", gap: 7, paddingVertical: 5, paddingLeft: 8, paddingRight: 11, borderRadius: 100, backgroundColor: "rgba(27,158,82,0.11)" },
  dot: { width: 6, height: 6, borderRadius: 9 },
  gradeCode: { fontWeight: "700", color: C.rising, fontSize: 12 },
  gradeName: { fontWeight: "600", color: C.rising, fontSize: 12 },
  judged: { fontSize: 22, fontWeight: "700", color: C.ink, marginTop: 6 },
  divider: { height: 1, backgroundColor: "rgba(20,19,15,0.08)", marginVertical: 16 },
  reqLabel: { fontSize: 13, fontWeight: "600", color: C.ink },
  reqFig: { fontSize: 11.5, fontWeight: "500" },
  track: { height: 5, borderRadius: 9, backgroundColor: "rgba(20,19,15,0.08)", overflow: "hidden", marginTop: 6 },
  note: { fontSize: 12.5, marginTop: 16, padding: 13, borderRadius: 12, backgroundColor: "rgba(20,19,15,0.045)" },
  logout: { fontSize: 12.5, fontWeight: "600", color: C.sub },
  devSoon: { fontSize: 13, fontWeight: "600", color: C.faint, marginTop: 8 },
  statValue: { fontSize: 20, fontWeight: "700", color: C.ink, marginTop: 6 },
  sectionTitle: { fontSize: 13, fontWeight: "600", color: C.ink },
  watchChip: { paddingHorizontal: 12, paddingVertical: 8, borderRadius: 100, backgroundColor: "rgba(20,19,15,0.045)" },
  watchChipText: { fontSize: 12.5, color: C.ink, fontWeight: "500" },
  ledgerTitle: { fontSize: 13, fontWeight: "600", color: C.ink },
  lWord: { fontSize: 13.5, fontWeight: "600", color: C.ink },
  lDelta: { fontSize: 13.5, fontWeight: "700" },
  settingsRow: { marginTop: 18, alignItems: "center", padding: 10 },
  settingsText: { fontSize: 12.5, color: C.sub, fontWeight: "500" },
});
```

- [ ] **Step 2: 타입체크**

Run: `cd app && npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add app/src/screens/MeScreen.tsx
git commit -m "feat(app): redesign Me tab — friendly grade wording, quota/hit-rate summary, watch + submission summaries, settings entry point"
```

---

## Task 11: 프론트 — 온보딩 게이팅을 로그인+preferences 존재 여부로 수정

**Files:**
- Modify: `app/App.tsx`

- [ ] **Step 1: App.tsx 전체 교체**

```tsx
import React from "react";
import { ActivityIndicator, View } from "react-native";
import { StatusBar } from "expo-status-bar";
import { SafeAreaProvider } from "react-native-safe-area-context";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { NavigationContainer } from "@react-navigation/native";
import RootNavigator from "./src/navigation/RootNavigator";
import OnboardingScreen from "./src/screens/OnboardingScreen";
import { useMyPreferences } from "./src/api/hooks";
import { ReadsProvider } from "./src/state/reads";
import { AuthProvider, useAuth } from "./src/state/auth";
import { C } from "./src/theme";

const qc = new QueryClient({
  defaultOptions: { queries: { retry: 1, staleTime: 30_000 } },
});

/**
 * 온보딩 게이트. 비로그인(둘러보기)은 항상 통과. 로그인 상태면 서버에 저장된
 * preferences 존재 여부로 딱 1번만 온보딩을 보여준다 — 로컬 플래그가 아니라 서버가 진실.
 */
function Gate({ children }: { children: React.ReactNode }) {
  const { user, initializing } = useAuth();
  const prefs = useMyPreferences(!!user && !initializing);

  if (initializing) {
    return (
      <View style={{ flex: 1, alignItems: "center", justifyContent: "center", backgroundColor: C.bg }}>
        <ActivityIndicator color={C.ink} />
      </View>
    );
  }
  if (!user) return <>{children}</>;
  if (prefs.isLoading) {
    return (
      <View style={{ flex: 1, alignItems: "center", justifyContent: "center", backgroundColor: C.bg }}>
        <ActivityIndicator color={C.ink} />
      </View>
    );
  }
  if (prefs.data === null) {
    return <OnboardingScreen onDone={() => prefs.refetch()} />;
  }
  return <>{children}</>;
}

export default function App() {
  return (
    <SafeAreaProvider>
      <QueryClientProvider client={qc}>
        <AuthProvider>
          <ReadsProvider>
            <StatusBar style="dark" />
            <Gate>
              <NavigationContainer>
                <RootNavigator />
              </NavigationContainer>
            </Gate>
          </ReadsProvider>
        </AuthProvider>
      </QueryClientProvider>
    </SafeAreaProvider>
  );
}
```

`useMyPreferences(!!user && !initializing)`는 Task 7에서 이미 `enabled` 파라미터를 받도록 만들어둔 훅이다 — 비로그인 상태나 auth 초기화 중에는 `enabled: false`라 요청 자체가 안 나간다.

- [ ] **Step 2: 타입체크**

Run: `cd app && npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add app/App.tsx
git commit -m "fix(app): gate onboarding on login + saved preferences instead of an in-memory flag that reset on every reload"
```

---

## Task 12: 전체 수동 검증 (실기기/Expo Go)

이 프로젝트는 지금까지 RN 화면 변경을 브라우저 프리뷰가 아니라 Expo Go 실기기로 검증해왔다. 이번에도 동일하게 진행한다.

- [ ] **Step 1: 백엔드 기동**

```bash
cd backend && ./gradlew.bat :app:bootRun
```

Flyway 로그에서 `V20__user_preferences.sql` 적용 확인.

- [ ] **Step 2: Expo 앱 재시작**

```bash
cd app && npx expo start -c
```

- [ ] **Step 3: 시나리오 확인**

1. 앱 최초 진입(비로그인) → 온보딩 없이 바로 홈/둘러보기 진입되는지 확인.
2. 이메일 로그인(기존 계정, preferences 미저장 상태) → 로그인 직후 온보딩(카테고리 3개 선택 + 시간 선택)이 뜨는지 확인.
3. 온보딩 완료 → 홈 진입. 앱 완전 재시작(`r` 리로드가 아니라 Expo Go 앱 자체 재진입) → 로그인 상태 유지된 채로 **온보딩이 다시 안 뜨는지** 확인(핵심 회귀 포인트).
4. "나" 탭 진입 → 등급 카드 문구가 "예측 적중률 42% (목표 45%)" 같은 쉬운 말로 나오는지, 이번 주 제보권 스트립에 실제 숫자가 나오는지, 스트릭 칸엔 "개발 예정"만 나오는지 확인.
5. "나" 탭 하단 "⚙ 설정" 진입 → 기존에 온보딩에서 고른 카테고리/시간이 프리필돼 보이는지, 바꿔서 저장 → 뒤로가기 → 다시 들어가서 바뀐 값이 유지되는지 확인.
6. 워치 탭에서 키워드 1개 이상 워치 추가 → "나" 탭으로 돌아와 워치 요약 섹션에 나오는지 확인.
7. 로그아웃 버튼 동작 확인(로그아웃 후 SignInScreen으로 전환되는지).

- [ ] **Step 4: 문제 발견 시 수정 후 재확인, 통과하면 종료**

백엔드 서버는 `./gradlew.bat --stop`으로 종료.
