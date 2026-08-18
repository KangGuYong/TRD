# 앱 API 1단계: 상세 · 투표 · 동의 · 등급 · 원장 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `backend/api-public`에 5개 엔드포인트(`GET /v1/trends/{id}`, `POST /v1/trends/{id}/vote`, `POST /v1/trends/{id}/endorse`, `GET /v1/me/grade`, `GET /v1/me/ledger`)를 구현해 앱의 상세 화면·투표·나 탭이 실제 데이터로 동작하게 한다.

**Architecture:** 기존 `TrendController`/`TrendQueryService`를 확장하고, `SubmissionController`/`SubmissionService` 옆에 대칭적으로 `MeController`/`MeService`, `TrendInteractionService`(투표·동의 전용, 쓰기 트랜잭션)를 신설한다. 새 테이블·엔티티는 없음 — 전부 기존 `Vote`/`Endorsement`/`Verdict`/`ScoreLedgerEntry`/`Submission` 엔티티와 `domain-core`의 기존 순수 함수(`GradePolicy`/`TrustIndex`/`ActiveScore`)를 재사용한다.

**Tech Stack:** Spring Boot 3.3.5 / Java 17, Spring Data JPA, PostgreSQL. `backend/api-public` 모듈에는 컨트롤러/서비스 계층 단위 테스트가 이 프로젝트에 존재하지 않는다(순수 함수만 `domain-core`에서 테스트됨) — 기존 관례를 따라 이번 작업도 **빌드 컴파일 + curl 수동 검증**으로 확인한다. 새 테스트 파일을 만들지 않는다.

참고 스펙: [docs/superpowers/specs/2026-08-18-app-trend-detail-vote-grade-design.md](../specs/2026-08-18-app-trend-detail-vote-grade-design.md)

---

### Task 1: 상세 응답 레코드 + TrendItem 조회 실패 예외

**Files:**
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/TrendDetailResponse.java`
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/PropagationStepResponse.java`
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/TrendNotFoundException.java`
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/ApiExceptionHandler.java`

- [ ] **Step 1: `TrendDetailResponse` 레코드 작성**

`TrendSummaryResponse`(기존)와 필드가 겹치지만 OpenAPI `TrendDetail`이 flat 스키마(`allOf`)이므로 별도 flat 레코드로 만든다.

```java
package kr.trendstage.apipublic.web;

import java.util.List;
import java.util.UUID;

/** OpenAPI TrendDetail(TrendSummary + 확장 필드) 대응. */
public record TrendDetailResponse(
        UUID id,
        String word,
        String meaning,
        String stage,
        String stageLabel,
        String lifeText,
        String pathText,
        int reachedCount,
        String ageShort,
        String verdict,          // 판정 전(PENDING/JUDGING)이면 null — 조기 판정 노출 금지
        String verdictWhy,
        String reachLevel,       // HIT일 때만, 그 외 null
        String origin,           // 데이터 없음 — 항상 null
        String example,          // 데이터 없음 — 항상 null
        String ageSentence,      // 데이터 없음 — 항상 null
        List<String> ages,       // 데이터 없음 — 항상 빈 리스트
        List<PropagationStepResponse> propagationPath,
        String voteCount,        // 투표 0건이면 null
        boolean watched          // 워치 테이블 없음 — 항상 false(2단계에서 교체)
) {}
```

- [ ] **Step 2: `PropagationStepResponse` 레코드 작성**

```java
package kr.trendstage.apipublic.web;

public record PropagationStepResponse(String channel, String date, String note, boolean reached) {}
```

- [ ] **Step 3: `TrendNotFoundException` 작성**

```java
package kr.trendstage.apipublic.web;

/** 존재하지 않거나 병합되어 사라진(MERGED) 트렌드 항목 조회(404). */
public class TrendNotFoundException extends RuntimeException {
    public TrendNotFoundException(String message) { super(message); }
}
```

- [ ] **Step 4: `ApiExceptionHandler`에 404 매핑 추가**

`backend/api-public/src/main/java/kr/trendstage/apipublic/web/ApiExceptionHandler.java`의 기존 `@ExceptionHandler(SubmissionValidationException.class)` 메서드 뒤에 추가:

```java
    @ExceptionHandler(TrendNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handle(TrendNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem(404, e.getMessage()));
    }
```

- [ ] **Step 5: 컴파일 확인**

```bash
cd backend && ./gradlew :api-public:compileJava
```
Expected: BUILD SUCCESSFUL (아직 아무도 이 클래스들을 안 쓰므로 미사용 경고만 있을 수 있음, 에러 없어야 함)

- [ ] **Step 6: 커밋**

```bash
git add backend/api-public/src/main/java/kr/trendstage/apipublic/web/TrendDetailResponse.java backend/api-public/src/main/java/kr/trendstage/apipublic/web/PropagationStepResponse.java backend/api-public/src/main/java/kr/trendstage/apipublic/web/TrendNotFoundException.java backend/api-public/src/main/java/kr/trendstage/apipublic/web/ApiExceptionHandler.java
git commit -m "feat(app-api): add TrendDetail response records + 404 exception"
```

---

### Task 2: `TrendQueryService.detail()`

**Files:**
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/service/TrendQueryService.java`

- [ ] **Step 1: 필요한 리포지토리 주입 추가**

생성자에 `VerdictRepository verdicts`, `VoteRepository votes`를 추가한다(기존 `TrendItemRepository trends`, `SubmissionRepository submissions`는 이미 있음).

```java
import kr.trendstage.domain.verdict.VerdictResult;
import kr.trendstage.persistence.entity.Verdict;
import kr.trendstage.persistence.repo.VerdictRepository;
import kr.trendstage.persistence.repo.VoteRepository;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

// ...

    private static final DateTimeFormatter PATH_DATE = DateTimeFormatter.ofPattern("MM-dd").withZone(ZoneId.of("Asia/Seoul"));

    private final VerdictRepository verdicts;
    private final VoteRepository votes;

    public TrendQueryService(TrendItemRepository trends, SubmissionRepository submissions,
                             VerdictRepository verdicts, VoteRepository votes) {
        this.trends = trends; this.submissions = submissions;
        this.verdicts = verdicts; this.votes = votes;
    }
```

- [ ] **Step 2: `detail()` 메서드 추가**

```java
    @Transactional(readOnly = true)
    public TrendDetailResponse detail(UUID id) {
        TrendItem item = trends.findById(id)
                .filter(i -> i.getState() != TrendState.MERGED)
                .orElseThrow(() -> new TrendNotFoundException("존재하지 않는 항목입니다"));

        TrendSummaryResponse base = toSummary(item);

        Verdict current = verdicts.findCurrentByTrendItemId(id).orElse(null);
        String verdict = null, verdictWhy = null, reachLevel = null;
        if (current != null && current.getResult() != VerdictResult.VOID) {
            boolean hit = current.getResult() == VerdictResult.HIT;
            verdict = hit ? "적중했어요" : "빗나갔어요";
            int distinctSubmitters = (int) submissions.findByTrendItemIdAndResultNot(id, kr.trendstage.persistence.type.SubmissionResult.VOID)
                    .stream().map(Submission::getUserId).distinct().count();
            verdictWhy = String.format("서로 다른 제보자 %d명 확인 · T=%s", distinctSubmitters, current.getScoreT().toPlainString());
            reachLevel = current.getReachLevel() != null ? current.getReachLevel().name() : null;
        }

        List<PropagationStepResponse> propagationPath = buildPropagationPath(id);

        long willTrend = votes.countByTrendItemIdAndWillTrend(id, true);
        long wontTrend = votes.countByTrendItemIdAndWillTrend(id, false);
        long totalVotes = willTrend + wontTrend;
        String voteCount = totalVotes == 0 ? null
                : String.format("%,d명 참여 · 뜬다 %d%%", totalVotes, Math.round(willTrend * 100.0 / totalVotes));

        return new TrendDetailResponse(
                base.id(), base.word(), base.meaning(), base.stage(), base.stageLabel(),
                base.lifeText(), base.pathText(), base.reachedCount(), base.ageShort(),
                verdict, verdictWhy, reachLevel,
                null, null, null, List.of(),
                propagationPath, voteCount, false);
    }

    private List<PropagationStepResponse> buildPropagationPath(UUID trendItemId) {
        List<Submission> subs = submissions.findByTrendItemIdAndResultNot(trendItemId, kr.trendstage.persistence.type.SubmissionResult.VOID);
        Map<String, Instant> firstSeenByPlatform = new LinkedHashMap<>();
        subs.stream()
                .filter(s -> s.getSourcePlatform() != null)
                .sorted(Comparator.comparing(Submission::getCreatedAt))
                .forEach(s -> firstSeenByPlatform.putIfAbsent(s.getSourcePlatform(), s.getCreatedAt()));
        return firstSeenByPlatform.entrySet().stream()
                .map(e -> new PropagationStepResponse(e.getKey(), PATH_DATE.format(e.getValue()), null, true))
                .toList();
    }
```

(`Instant`는 이미 파일 상단에 import 돼 있음 — `import java.time.Instant;`)

- [ ] **Step 2b: 이 시점에 참조하는 타입들의 import 정리**

파일 상단에 다음이 이미 있는지 확인하고 없으면 추가: `kr.trendstage.apipublic.web.TrendDetailResponse`, `kr.trendstage.apipublic.web.PropagationStepResponse`, `kr.trendstage.apipublic.web.TrendNotFoundException`.

- [ ] **Step 3: 컴파일 확인**

```bash
cd backend && ./gradlew :api-public:compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add backend/api-public/src/main/java/kr/trendstage/apipublic/service/TrendQueryService.java
git commit -m "feat(app-api): TrendQueryService.detail() — verdict/propagation/vote 조립"
```

---

### Task 3: `GET /v1/trends/{id}` 라우팅 + 수동 검증

**Files:**
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/TrendController.java`

- [ ] **Step 1: 컨트롤러에 엔드포인트 추가**

```java
import java.util.UUID;

// ...

    @GetMapping("/{id}")
    public TrendDetailResponse detail(@PathVariable UUID id) {
        return service.detail(id);
    }
```

(`@RequestMapping("/v1/trends")`가 클래스 레벨에 이미 있으므로 `/{id}`만 붙이면 `/v1/trends/{id}`가 된다.)

- [ ] **Step 2: 빌드**

```bash
cd backend && ./gradlew :api-public:compileJava :app:compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 백엔드 기동 후 curl로 수동 검증**

기존에 떠 있는 백엔드가 있으면 재시작(코드 반영 위해), 없으면 `./gradlew :app:bootRun`으로 기동 후:

```bash
curl -s http://localhost:8080/v1/trends?daily=true
```
위 응답의 `items[0].id`를 가져와서:
```bash
curl -s http://localhost:8080/v1/trends/<위에서 얻은 id>
```
Expected: 200, `word`/`meaning`/`pathText` 등이 목록 응답과 일치하고 `verdict`/`verdictWhy`는 판정 전이면 `null`, `watched`는 `false`.

존재하지 않는 UUID로도 확인:
```bash
curl -s -w "\n%{http_code}\n" http://localhost:8080/v1/trends/00000000-0000-0000-0000-000000000000
```
Expected: 404 + `{"type":"about:blank","status":404,...}`

- [ ] **Step 4: 커밋**

```bash
git add backend/api-public/src/main/java/kr/trendstage/apipublic/web/TrendController.java
git commit -m "feat(app-api): GET /v1/trends/{id} 라우팅"
```

---

### Task 4: 투표 — `TrendInteractionService.vote()` + 응답 레코드

**Files:**
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/VoteRequest.java`
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/VoteResultResponse.java`
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/service/TrendInteractionService.java`

- [ ] **Step 1: 요청/응답 레코드**

```java
package kr.trendstage.apipublic.web;

import jakarta.validation.constraints.NotNull;

public record VoteRequest(@NotNull Boolean willTrend) {}
```

```java
package kr.trendstage.apipublic.web;

public record VoteResultResponse(boolean willTrend, String voteCount) {}
```

- [ ] **Step 2: `TrendInteractionService` 작성 (투표만 우선)**

`SubmissionService` 패턴(생성자 주입, `@Transactional`, 필요한 예외를 web 패키지에 둠)을 그대로 따른다.

```java
package kr.trendstage.apipublic.service;

import kr.trendstage.apipublic.web.EndorseConflictException;
import kr.trendstage.apipublic.web.TrendNotFoundException;
import kr.trendstage.apipublic.web.VoteResultResponse;
import kr.trendstage.persistence.entity.Endorsement;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.entity.Vote;
import kr.trendstage.persistence.repo.EndorsementRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.VoteRepository;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 투표·동의. 둘 다 판정에 입력되지 않는 부가 반응(R1) — 별도 서비스로 분리해 SubmissionService(판정 입력 경로)와 섞이지 않게 한다. */
@Service
public class TrendInteractionService {

    private final TrendItemRepository trends;
    private final VoteRepository votes;
    private final EndorsementRepository endorsements;

    public TrendInteractionService(TrendItemRepository trends, VoteRepository votes, EndorsementRepository endorsements) {
        this.trends = trends; this.votes = votes; this.endorsements = endorsements;
    }

    @Transactional
    public VoteResultResponse vote(UUID trendItemId, UUID userId, boolean willTrend) {
        requireExists(trendItemId);
        Vote v = votes.findByUserIdAndTrendItemId(userId, trendItemId).orElse(null);
        if (v == null) {
            votes.save(new Vote(userId, trendItemId, willTrend));
        } else {
            v.toggleTo(willTrend);
        }
        long yes = votes.countByTrendItemIdAndWillTrend(trendItemId, true);
        long no = votes.countByTrendItemIdAndWillTrend(trendItemId, false);
        long total = yes + no;
        String voteCount = total == 0 ? null
                : String.format("%,d명 참여 · 뜬다 %d%%", total, Math.round(yes * 100.0 / total));
        return new VoteResultResponse(willTrend, voteCount);
    }

    @Transactional
    public void endorse(UUID trendItemId, UUID userId) {
        requireExists(trendItemId);
        if (endorsements.existsByTrendItemIdAndUserId(trendItemId, userId)) {
            throw new EndorseConflictException("이미 동의한 항목입니다");
        }
        endorsements.save(new Endorsement(trendItemId, userId, null));
    }

    private void requireExists(UUID trendItemId) {
        TrendItem item = trends.findById(trendItemId).orElseThrow(() -> new TrendNotFoundException("존재하지 않는 항목입니다"));
        if (item.getState() == TrendState.MERGED) throw new TrendNotFoundException("존재하지 않는 항목입니다");
    }
}
```

(이 스텝은 `EndorseConflictException`을 아직 안 만들었으므로 컴파일이 깨진다 — Task 5에서 만든 뒤 한꺼번에 컴파일 확인한다. 지금은 파일만 작성.)

- [ ] **Step 3: 커밋 없이 다음 태스크로 진행** (컴파일 가능한 단위가 아니므로 Task 5까지 묶어서 커밋)

---

### Task 5: 동의 409 예외 + 라우팅(투표+동의) + 검증

**Files:**
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/EndorseConflictException.java`
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/ApiExceptionHandler.java`
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/TrendController.java`

- [ ] **Step 1: 예외 클래스**

```java
package kr.trendstage.apipublic.web;

/** 이미 동의/제보한 유저가 다시 동의를 시도(409). */
public class EndorseConflictException extends RuntimeException {
    public EndorseConflictException(String message) { super(message); }
}
```

- [ ] **Step 2: `ApiExceptionHandler`에 매핑 추가**

`TrendNotFoundException` 핸들러 뒤에 추가:

```java
    @ExceptionHandler(EndorseConflictException.class)
    public ResponseEntity<Map<String, Object>> handle(EndorseConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(409, e.getMessage()));
    }
```

- [ ] **Step 3: 컨트롤러에 투표·동의 라우팅 추가**

`TrendController`에 서비스 주입 추가(생성자에 `TrendInteractionService interactions` 파라미터 추가) 후:

```java
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;

// ...

    @PostMapping("/{id}/vote")
    public VoteResultResponse vote(@PathVariable UUID id, Authentication auth, @Valid @RequestBody VoteRequest req) {
        return interactions.vote(id, userId(auth), req.willTrend());
    }

    @PostMapping("/{id}/endorse")
    public ResponseEntity<Void> endorse(@PathVariable UUID id, Authentication auth) {
        interactions.endorse(id, userId(auth));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private UUID userId(Authentication auth) {
        return (UUID) auth.getPrincipal();
    }
```

- [ ] **Step 4: 컴파일**

```bash
cd backend && ./gradlew :api-public:compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add backend/api-public/src/main/java/kr/trendstage/apipublic/service/TrendInteractionService.java backend/api-public/src/main/java/kr/trendstage/apipublic/web/VoteRequest.java backend/api-public/src/main/java/kr/trendstage/apipublic/web/VoteResultResponse.java backend/api-public/src/main/java/kr/trendstage/apipublic/web/EndorseConflictException.java backend/api-public/src/main/java/kr/trendstage/apipublic/web/ApiExceptionHandler.java backend/api-public/src/main/java/kr/trendstage/apipublic/web/TrendController.java
git commit -m "feat(app-api): POST /v1/trends/{id}/vote, /endorse"
```

---

### Task 6: 투표/동의 수동 검증 (인증 필요 — Firebase 토큰 확보 필요)

**Files:** 없음(검증만)

- [ ] **Step 1: 로그인 없이 401 확인**

```bash
curl -s -w "\n%{http_code}\n" -X POST http://localhost:8080/v1/trends/<id>/vote -H 'Content-Type: application/json' -d '{"willTrend":true}'
```
Expected: 401

- [ ] **Step 2: 인증 토큰이 있다면 투표 확인**

앱에서 로그인한 계정의 Firebase ID 토큰을 확보할 수 있으면(브라우저 콘솔에서 `auth.currentUser.getIdToken()` 또는 앱 디버그 로그):

```bash
curl -s -X POST http://localhost:8080/v1/trends/<id>/vote \
  -H "Authorization: Bearer <ID_TOKEN>" -H 'Content-Type: application/json' \
  -d '{"willTrend":true}'
```
Expected: 200, `{"willTrend":true,"voteCount":"1명 참여 · 뜬다 100%"}`. 같은 요청 `willTrend:false`로 재호출 시 토글되어 `voteCount`가 바뀌는지 확인(신규 insert가 아니라 update인지는 DB에서 `select count(*) from votes where trend_item_id=...`로 1행만 있는지 확인).

토큰 확보가 어려우면 이 스텝은 건너뛰고 401 확인만으로 충분(핵심 로직은 컴파일 타임에 이미 검증됨). 사용자에게 토큰 확보 방법을 물어보거나 스킵 후 다음 태스크로 진행.

- [ ] **Step 3: 관찰 결과를 다음 태스크 진행 전 간단히 기록** (커밋 불필요)

---

### Task 7: 등급 응답 레코드 + `MeService.grade()`

**Files:**
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/GradeRequirementResponse.java`
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/GradeStatusResponse.java`
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/service/MeService.java`

- [ ] **Step 1: 응답 레코드**

```java
package kr.trendstage.apipublic.web;

public record GradeRequirementResponse(String label, double current, double required, boolean met, String basis) {}
```

```java
package kr.trendstage.apipublic.web;

import java.util.List;

public record GradeStatusResponse(
        String grade, String gradeName, double trustIndex, double activeScore,
        int judgedCount, String nextGrade, List<GradeRequirementResponse> requirements, String note
) {}
```

- [ ] **Step 2: 등급 이름 매핑**

공식 명칭은 [01-system-design.md:208-213](../../../01-system-design.md:208)에 이미 정의돼 있다 — 추측하지 말고 그대로 쓴다: **L0 관찰자 · L1 제보자 · L2 탐지자 · L3 분석가 · L4 선구자**. `MeService`에 상수로 추가:

```java
    private static final Map<Grade, String> GRADE_NAMES = Map.of(
            Grade.L0, "관찰자", Grade.L1, "제보자", Grade.L2, "탐지자", Grade.L3, "분석가", Grade.L4, "선구자"
    );
```

- [ ] **Step 3: `MeService` 작성**

```java
package kr.trendstage.apipublic.service;

import kr.trendstage.apipublic.web.GradeRequirementResponse;
import kr.trendstage.apipublic.web.GradeStatusResponse;
import kr.trendstage.domain.grade.Grade;
import kr.trendstage.domain.grade.GradePolicy;
import kr.trendstage.domain.grade.GradeStatus;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.ActiveScore;
import kr.trendstage.domain.score.TrustIndex;
import kr.trendstage.persistence.entity.ScoreLedgerEntry;
import kr.trendstage.persistence.repo.ScoreLedgerRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.type.SubmissionResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 등급·원장 조회. GradeRecalcJob과 동일한 순수 함수 조합을 매 요청 실시간으로 호출한다 —
 * user_grades 스냅샷은 주 1회만 갱신되므로 그걸 읽으면 최대 일주일 묵은 값을 보여줄 수 있다.
 */
@Service
public class MeService {

    private static final Map<Grade, String> GRADE_NAMES = Map.of(
            Grade.L0, "관찰자", Grade.L1, "제보자", Grade.L2, "탐지자", Grade.L3, "분석가", Grade.L4, "선구자"
    );

    private final SubmissionRepository submissions;
    private final ScoreLedgerRepository ledger;
    private final Clock clock;

    public MeService(SubmissionRepository submissions, ScoreLedgerRepository ledger, Clock clock) {
        this.submissions = submissions; this.ledger = ledger; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public GradeStatusResponse grade(UUID userId) {
        int hit = (int) submissions.countByUserIdAndResult(userId, SubmissionResult.HIT);
        int miss = (int) submissions.countByUserIdAndResult(userId, SubmissionResult.MISS);
        int judged = hit + miss;

        ParameterSet p = ParameterSet.defaults();
        double ti = TrustIndex.compute(hit, miss, p);

        List<ActiveScore.Aged> aged = new ArrayList<>();
        var now = clock.instant();
        for (ScoreLedgerEntry e : ledger.findByUserIdOrderByCreatedAtDesc(userId)) {
            long ageDays = Duration.between(e.getCreatedAt(), now).toDays();
            aged.add(new ActiveScore.Aged(e.getDelta().doubleValue(), Math.max(0, ageDays)));
        }
        double as = ActiveScore.compute(aged, p);

        GradeStatus status = GradePolicy.evaluate(judged, ti, as);

        List<GradeRequirementResponse> reqs = status.requirements().stream()
                .map(r -> new GradeRequirementResponse(r.label(), r.current(), r.required(), r.met(), r.basis()))
                .toList();

        String note = status.current() == status.next() ? "최고 등급입니다" : null;

        return new GradeStatusResponse(
                status.current().name(), GRADE_NAMES.get(status.current()),
                ti, as, judged, GRADE_NAMES.get(status.next()), reqs, note);
    }
}
```

`ParameterSet.defaults()`가 실제로 있는지 확인(있음 — `TrendItemAdminService`에서 이미 사용). `GradeRequirement` 레코드의 정확한 접근자 이름(`label()/current()/required()/met()/basis()`)은 `domain-core/src/main/java/kr/trendstage/domain/grade/GradeRequirement.java`를 열어 확인하고 다르면 맞춰 고친다.

- [ ] **Step 4: `GradeRequirement.java` 필드명 확인**

```bash
cat backend/domain-core/src/main/java/kr/trendstage/domain/grade/GradeRequirement.java
```
레코드 필드명이 Step 3의 `.label()` 등과 다르면 `MeService.grade()`의 매핑 라인을 실제 필드명에 맞춰 수정.

- [ ] **Step 5: 컴파일 확인**

```bash
cd backend && ./gradlew :api-public:compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add backend/api-public/src/main/java/kr/trendstage/apipublic/web/GradeRequirementResponse.java backend/api-public/src/main/java/kr/trendstage/apipublic/web/GradeStatusResponse.java backend/api-public/src/main/java/kr/trendstage/apipublic/service/MeService.java
git commit -m "feat(app-api): MeService.grade() — GradeRecalcJob과 동일 조합 실시간 계산"
```

---

### Task 8: 원장 응답 레코드 + `MeService.ledger()`

**Files:**
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/LedgerEntryResponse.java`
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/LedgerListResponse.java`
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/service/MeService.java`

- [ ] **Step 1: 응답 레코드**

```java
package kr.trendstage.apipublic.web;

import java.time.Instant;

public record LedgerEntryResponse(String word, String kind, double delta, String reason, Instant createdAt) {}
```

```java
package kr.trendstage.apipublic.web;

import java.util.List;

public record LedgerListResponse(List<LedgerEntryResponse> items, String nextCursor) {}
```

- [ ] **Step 2: `MeService`에 `ledger()` 추가**

`TrendItemRepository`를 생성자에 추가 주입하고:

```java
    private final TrendItemRepository trends;

    public MeService(SubmissionRepository submissions, ScoreLedgerRepository ledger,
                     TrendItemRepository trends, Clock clock) {
        this.submissions = submissions; this.ledger = ledger; this.trends = trends; this.clock = clock;
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

    private String wordFor(ScoreLedgerEntry e) {
        // ScoreLedgerEntry에는 trendItemId가 직접 없다 — submissionId로 Submission -> TrendItem을 거쳐야 함.
        // (아래 Step 3에서 실제 필드 확인 후 조정)
        return "";
    }
```

- [ ] **Step 3: `wordFor` 구현 — `ScoreLedgerEntry`에서 트렌드 항목명까지의 실제 경로 확인**

`ScoreLedgerEntry`는 `submissionId`(nullable, ADJ는 null)만 갖고 있다. `SubmissionRepository.findById(submissionId)`로 `Submission`을 얻고, 그 `getTrendItemId()`로 `trends.findById(...)`해서 `getCanonicalName()`을 가져온다. `submissionId`가 null(ADJ 원장)이면 `"계정 조정"` 같은 고정 문구를 반환한다.

```java
    private String wordFor(ScoreLedgerEntry e) {
        if (e.getSubmissionId() == null) return "계정 조정";
        return submissions.findById(e.getSubmissionId())
                .flatMap(s -> trends.findById(s.getTrendItemId()))
                .map(TrendItem::getCanonicalName)
                .orElse("(삭제된 항목)");
    }
```

`SubmissionRepository`에 `findById`는 `JpaRepository` 기본 메서드라 이미 있음. `import kr.trendstage.persistence.entity.TrendItem;`과 `import kr.trendstage.persistence.repo.TrendItemRepository;` 추가 필요.

- [ ] **Step 4: 컴파일 확인**

```bash
cd backend && ./gradlew :api-public:compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add backend/api-public/src/main/java/kr/trendstage/apipublic/web/LedgerEntryResponse.java backend/api-public/src/main/java/kr/trendstage/apipublic/web/LedgerListResponse.java backend/api-public/src/main/java/kr/trendstage/apipublic/service/MeService.java
git commit -m "feat(app-api): MeService.ledger()"
```

---

### Task 9: `MeController` 라우팅 + 최종 빌드/검증

**Files:**
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/MeController.java`

- [ ] **Step 1: 컨트롤러 작성 (`SubmissionController` 패턴 그대로)**

```java
package kr.trendstage.apipublic.web;

import kr.trendstage.apipublic.service.MeService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** OpenAPI /v1/me/grade, /v1/me/ledger 대응. 인증 필요(PublicSecurityConfig). */
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

    private UUID userId(Authentication auth) {
        return (UUID) auth.getPrincipal();
    }
}
```

- [ ] **Step 2: 전체 빌드**

```bash
cd backend && ./gradlew :api-public:compileJava :app:compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 백엔드 재기동 후 curl 종단 검증**

```bash
# 401 확인 (토큰 없이)
curl -s -w "\n%{http_code}\n" http://localhost:8080/v1/me/grade
curl -s -w "\n%{http_code}\n" http://localhost:8080/v1/me/ledger
```
Expected: 둘 다 401

토큰 확보 가능하면(Task 6과 동일한 방법):
```bash
curl -s http://localhost:8080/v1/me/grade -H "Authorization: Bearer <ID_TOKEN>"
curl -s http://localhost:8080/v1/me/ledger -H "Authorization: Bearer <ID_TOKEN>"
```
Expected: 200. `grade`는 신규 유저면 `grade:"L0", trustIndex:0.4, activeScore:0, judgedCount:0` 근처 값, `nextGrade:"L1"`, `requirements` 3개(판정완료/TI/AS) 배열. `ledger`는 판정 이력이 있으면 `items` 채워짐, 없으면 빈 배열.

- [ ] **Step 4: 앱에서 실기기/시뮬레이터로 최종 확인**

`app/`을 Expo Go로 열고: 홈 → 카드 클릭 → 상세 화면이 "연결 실패" 없이 뜨는지, 투표 버튼이 동작하는지, "나" 탭에서 등급/원장 섹션이 로딩 스피너에서 멈추지 않고 실제 값(혹은 신규 유저 초기값)을 보여주는지 확인. (에뮬레이터/기기가 없으면 이 스텝은 사용자에게 위임 — curl 검증까지가 이 계획의 범위.)

- [ ] **Step 5: 커밋**

```bash
git add backend/api-public/src/main/java/kr/trendstage/apipublic/web/MeController.java
git commit -m "feat(app-api): GET /v1/me/grade, /v1/me/ledger 라우팅"
```

---

## Out of Scope (다음 단계)

- `GET/POST/DELETE /v1/me/watch` (워치) — 새 테이블 필요
- `PUT /v1/me/preferences` (온보딩 설정 저장) — 새 테이블 필요
- `GET /v1/me/summary`, `POST /v1/me/reads` (스트릭·제보권quota·읽음수) — "제보권 수량" 등 product 결정 필요
- `GET /v1/trends?q=` 검색 파라미터 처리 (현재 무시됨)
- `origin`/`example`/`ageSentence`/`ages` 실데이터 (원천 데이터 자체가 시스템에 없음 — 별도 기획 필요)
