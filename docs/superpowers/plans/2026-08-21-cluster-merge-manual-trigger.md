# cluster_merge 수동 실행 (관리자 배치 트리거) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OPERATOR 이상 권한의 관리자가 `cluster_merge` 배치를 관리자 콘솔에서 즉시 수동 실행할 수 있게 한다.

**Architecture:** `ClusterMergeJob`(scheduler 모듈)의 후보 순회·분류 로직을 `:merge` 모듈의 새 `ClusterMergeCandidateService`로 옮긴다 — `:merge`는 이미 `api-admin`과 `scheduler` 양쪽이 의존하는 모듈이라(`MergeService`가 이미 그렇게 공유됨), 여기 두면 컴파일 타임 의존성 문제 없이 두 진입점(크론 / 관리자 API)이 같은 로직·같은 락을 공유한다. `ClusterMergeJob`은 `@Scheduled` 진입점만 남기고 얇아진다. 관리자 API는 `LockProvider`로 명시적 락 획득을 시도해 크론과 겹치면 정직하게 409를 반환한다.

**Tech Stack:** Spring Boot(Java 17), ShedLock(JDBC provider), React+TypeScript(admin 콘솔)

**설계 근거:** `docs/superpowers/specs/2026-08-21-cluster-merge-manual-trigger-design.md`

---

### Task 1: `:merge` 모듈에 shedlock-core 의존성 추가

**Files:**
- Modify: `backend/merge/build.gradle.kts`

`LockProvider`/`LockConfiguration` 타입(`net.javacrumbs.shedlock.core.*`)이 필요한데 `:merge`는 현재 shedlock을 전혀 의존하지 않는다. 실제 `LockProvider` 빈 구현(`JdbcTemplateLockProvider`)은 `:scheduler`의 `ShedLockConfig`에 그대로 두고, `:merge`는 인터페이스 타입만 컴파일 타임에 보면 된다.

- [ ] **Step 1: 의존성 추가**

`backend/merge/build.gradle.kts`의 `dependencies` 블록에 추가:

```kotlin
    implementation("net.javacrumbs.shedlock:shedlock-core:5.13.0")
```

- [ ] **Step 2: 빌드 확인**

Run: `cd backend && ./gradlew :merge:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/merge/build.gradle.kts
git commit -m "build(merge): add shedlock-core dependency for LockProvider type"
```

---

### Task 2: `ClusterMergeCandidateService.classify()` 순수 함수 — TDD

**Files:**
- Create: `backend/merge/src/main/java/kr/trendstage/merge/ClusterMergeCandidateService.java`
- Test: `backend/merge/src/test/java/kr/trendstage/merge/ClusterMergeCandidateServiceTest.java`

임계값 분류 로직만 먼저 순수 정적 메서드로 뽑아 테스트한다(이 모듈의 기존 테스트 관례 — `MergeComputationTest`처럼 Mockito 없는 순수 함수 테스트를 따른다. 이 프로젝트 전체에 Mockito 의존성이 없으므로 새로 추가하지 않는다).

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.trendstage.merge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClusterMergeCandidateServiceTest {

    @Test void 유사도_0_85_이상은_자동병합() {
        assertEquals(ClusterMergeCandidateService.ProcessOutcome.AUTO_MERGED,
                ClusterMergeCandidateService.classify(0.85));
        assertEquals(ClusterMergeCandidateService.ProcessOutcome.AUTO_MERGED,
                ClusterMergeCandidateService.classify(0.9));
    }

    @Test void 유사도_0_75_이상_0_85_미만은_큐적재() {
        assertEquals(ClusterMergeCandidateService.ProcessOutcome.QUEUED,
                ClusterMergeCandidateService.classify(0.75));
        assertEquals(ClusterMergeCandidateService.ProcessOutcome.QUEUED,
                ClusterMergeCandidateService.classify(0.8499));
    }

    @Test void 유사도_0_75_미만은_별개확정() {
        assertEquals(ClusterMergeCandidateService.ProcessOutcome.SEPARATED,
                ClusterMergeCandidateService.classify(0.7499));
        assertEquals(ClusterMergeCandidateService.ProcessOutcome.SEPARATED,
                ClusterMergeCandidateService.classify(0.0));
    }
}
```

- [ ] **Step 2: 테스트 실행 → 컴파일 실패 확인**

Run: `cd backend && ./gradlew :merge:test --tests ClusterMergeCandidateServiceTest`
Expected: FAIL — `ClusterMergeCandidateService`가 존재하지 않음

- [ ] **Step 3: 최소 구현**

```java
package kr.trendstage.merge;

public class ClusterMergeCandidateService {

    public enum ProcessOutcome { AUTO_MERGED, QUEUED, SEPARATED }

    private static final double AUTO_MERGE_THRESHOLD = 0.85;
    private static final double QUEUE_THRESHOLD = 0.75;

    static ProcessOutcome classify(double similarity) {
        if (similarity >= AUTO_MERGE_THRESHOLD) return ProcessOutcome.AUTO_MERGED;
        if (similarity >= QUEUE_THRESHOLD) return ProcessOutcome.QUEUED;
        return ProcessOutcome.SEPARATED;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew :merge:test --tests ClusterMergeCandidateServiceTest`
Expected: PASS (3/3)

- [ ] **Step 5: 커밋**

```bash
git add backend/merge/src/main/java/kr/trendstage/merge/ClusterMergeCandidateService.java backend/merge/src/test/java/kr/trendstage/merge/ClusterMergeCandidateServiceTest.java
git commit -m "feat(merge): add ClusterMergeCandidateService.classify() threshold logic"
```

---

### Task 3: `ClusterMergeCandidateService` 전체 오케스트레이션 — `runNow()` / `tryRunNow()`

**Files:**
- Modify: `backend/merge/src/main/java/kr/trendstage/merge/ClusterMergeCandidateService.java`

`ClusterMergeJob.processOne()`의 로직을 그대로 옮겨오되, `boolean` 리턴 대신 `ProcessOutcome`을 리턴하도록 바꾸고 `classify()`를 사용한다. 이 오케스트레이션 자체(DB·임베딩 서비스 연동)는 이 모듈의 기존 관례상 JUnit 커버리지가 없다 — `MergeService`/`TrendEmbeddingDao` 등도 마찬가지로 단위테스트 없이 통합 동작으로만 검증되어 왔다. Task 14의 E2E로 검증한다.

- [ ] **Step 1: 전체 구현**

`ClusterMergeCandidateService.java`를 아래로 교체(Task 2에서 만든 `classify`/`ProcessOutcome`는 유지):

```java
package kr.trendstage.merge;

import kr.trendstage.persistence.entity.MergeQueueEntry;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.MergeQueueRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.TrendState;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * cluster_merge 후보 순회·분류·처리. 크론(ClusterMergeJob)과 관리자 수동 트리거
 * (api-admin BatchJobController) 양쪽이 이 서비스를 호출한다 — 둘 다 :merge 모듈에
 * 이미 의존하고 있어(MergeService와 동일 전례) 여기 두면 모듈 의존 방향이 깨지지 않는다.
 */
@Service
public class ClusterMergeCandidateService {

    private static final Logger log = LoggerFactory.getLogger(ClusterMergeCandidateService.class);

    public enum ProcessOutcome { AUTO_MERGED, QUEUED, SEPARATED }

    public record ClusterMergeResult(int candidates, int autoMerged, int queued, int separated, int failed) {}

    private static final double AUTO_MERGE_THRESHOLD = 0.85;
    private static final double QUEUE_THRESHOLD = 0.75;
    /** 관리자 수동 트리거 전용 명시적 락의 최대 보유 시간 — @SchedulerLock(cron)의 lockAtMostFor와 동일 값. */
    private static final Duration MANUAL_LOCK_AT_MOST_FOR = Duration.ofMinutes(60);

    private final TrendItemRepository trendItems;
    private final MergeQueueRepository mergeQueue;
    private final EmbeddingClient embeddingClient;
    private final TrendEmbeddingDao embeddingDao;
    private final MergeService mergeService;
    private final LockProvider lockProvider;
    private final Clock clock;

    public ClusterMergeCandidateService(TrendItemRepository trendItems, MergeQueueRepository mergeQueue,
                                         EmbeddingClient embeddingClient, TrendEmbeddingDao embeddingDao,
                                         MergeService mergeService, LockProvider lockProvider, Clock clock) {
        this.trendItems = trendItems;
        this.mergeQueue = mergeQueue;
        this.embeddingClient = embeddingClient;
        this.embeddingDao = embeddingDao;
        this.mergeService = mergeService;
        this.lockProvider = lockProvider;
        this.clock = clock;
    }

    static ProcessOutcome classify(double similarity) {
        if (similarity >= AUTO_MERGE_THRESHOLD) return ProcessOutcome.AUTO_MERGED;
        if (similarity >= QUEUE_THRESHOLD) return ProcessOutcome.QUEUED;
        return ProcessOutcome.SEPARATED;
    }

    /** 크론(@SchedulerLock으로 이미 보호됨) 전용 진입점. */
    public ClusterMergeResult runNow() {
        List<UUID> candidateIds = trendItems.findByStateInAndMergeCheckedAtIsNull(
                        List.of(TrendState.DRAFT, TrendState.PENDING, TrendState.JUDGING, TrendState.RESOLVED))
                .stream().map(TrendItem::getId).toList();

        int autoMerged = 0, queued = 0, separated = 0, failed = 0;
        for (UUID candidateId : candidateIds) {
            try {
                switch (processOne(candidateId)) {
                    case AUTO_MERGED -> autoMerged++;
                    case QUEUED -> queued++;
                    case SEPARATED -> separated++;
                }
            } catch (Exception e) {
                log.error("cluster_merge 처리 실패 item={} : {}", candidateId, e.getMessage(), e);
                failed++;
            }
        }
        return new ClusterMergeResult(candidateIds.size(), autoMerged, queued, separated, failed);
    }

    /**
     * 관리자 수동 트리거 전용 진입점. 크론과 같은 락 이름("cluster_merge")을 명시적으로 획득 시도한다 —
     * 실패하면(크론이 돌고 있거나 다른 관리자가 동시에 눌렀으면) Optional.empty()를 반환한다.
     * @SchedulerLock 어노테이션과 달리 "락을 못 잡아서 조용히 스킵"이 아니라 호출자가 명확히 알 수 있다.
     */
    public Optional<ClusterMergeResult> tryRunNow() {
        LockConfiguration lockConfig = new LockConfiguration(
                clock.instant(), "cluster_merge", MANUAL_LOCK_AT_MOST_FOR, Duration.ZERO);
        Optional<SimpleLock> lock = lockProvider.lock(lockConfig);
        if (lock.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(runNow());
        } finally {
            lock.get().unlock();
        }
    }

    /**
     * @return 자동 병합이 일어났으면 true.
     *
     * 주의: 같은 클래스 안에서 self-invocation은 Spring AOP 프록시를 거치지 않아 @Transactional이
     * 조용히 무효화된다 — 그래서 여기선 트랜잭션 경계를 기대하지 않고, 체크 완료 표시(markMergeChecked)를
     * save()로 직접 커밋한다. 병합·큐 적재는 각각 별도 빈(MergeService)·리포지토리 호출로 자체 트랜잭션을 갖는다.
     */
    private ProcessOutcome processOne(UUID candidateId) {
        TrendItem candidate = trendItems.findById(candidateId).orElseThrow();
        float[] vec = embeddingClient.embed(candidate.getCanonicalName());
        embeddingDao.updateEmbedding(candidate.getId(), vec);

        var match = embeddingDao.findMostSimilar(candidate.getId(), vec);
        Instant now = clock.instant();

        if (match.isEmpty()) {
            markChecked(candidate, now);
            return ProcessOutcome.SEPARATED;
        }

        double similarity = match.get().similarity();
        TrendItem other = trendItems.findById(match.get().trendItemId()).orElseThrow();
        ProcessOutcome outcome = classify(similarity);

        switch (outcome) {
            case AUTO_MERGED -> {
                TrendItem survivor = candidate.getFirstSeenAt().isBefore(other.getFirstSeenAt()) ? candidate : other;
                TrendItem loser = survivor == candidate ? other : candidate;
                mergeService.merge(survivor.getId(), loser.getId(), null, null,
                        "cluster_merge 자동 병합 (유사도 %.4f)".formatted(similarity));
                markChecked(trendItems.findById(candidateId).orElseThrow(), now);
            }
            case QUEUED -> {
                BigDecimal simScore = BigDecimal.valueOf(similarity).setScale(4, RoundingMode.HALF_UP);
                mergeQueue.save(new MergeQueueEntry(candidate.getId(), other.getId(), simScore));
                markChecked(candidate, now);
            }
            case SEPARATED -> markChecked(candidate, now);
        }
        return outcome;
    }

    private void markChecked(TrendItem item, Instant now) {
        item.markMergeChecked(now);
        trendItems.save(item);
    }
}
```

- [ ] **Step 2: 빌드 + 기존 테스트 확인**

Run: `cd backend && ./gradlew :merge:build`
Expected: BUILD SUCCESSFUL, `ClusterMergeCandidateServiceTest`(Task 2) 여전히 3/3 PASS

- [ ] **Step 3: 커밋**

```bash
git add backend/merge/src/main/java/kr/trendstage/merge/ClusterMergeCandidateService.java
git commit -m "feat(merge): add ClusterMergeCandidateService.runNow()/tryRunNow() orchestration"
```

---

### Task 4: `ClusterMergeJob`을 얇은 크론 래퍼로 축소

**Files:**
- Modify: `backend/scheduler/src/main/java/kr/trendstage/scheduler/ClusterMergeJob.java`

- [ ] **Step 1: 전체 교체**

```java
package kr.trendstage.scheduler;

import kr.trendstage.merge.ClusterMergeCandidateService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 임베딩 유사도 병합(03 §2③④) 크론 진입점. 실제 처리는 ClusterMergeCandidateService(:merge)가
 * 담당한다 — 관리자 수동 트리거(BatchJobController, api-admin)와 로직·락을 공유하기 위함.
 * 멱등: trend_items.merge_checked_at으로 이미 처리한 항목은 매일 다시 비교하지 않는다.
 */
@Component
public class ClusterMergeJob {

    private static final Logger log = LoggerFactory.getLogger(ClusterMergeJob.class);

    private final ClusterMergeCandidateService candidateService;

    public ClusterMergeJob(ClusterMergeCandidateService candidateService) {
        this.candidateService = candidateService;
    }

    /** 일 1회(CLAUDE.md 배치 표). */
    @Scheduled(cron = "${jobs.cluster-merge.cron:0 0 17 * * *}")
    @SchedulerLock(name = "cluster_merge", lockAtMostFor = "PT60M", lockAtLeastFor = "PT5M")
    public void run() {
        ClusterMergeCandidateService.ClusterMergeResult result = candidateService.runNow();
        log.info("cluster_merge 완료: 후보 {} · 자동병합 {} · 큐적재 {} · 별개확정 {} · 실패 {}",
                result.candidates(), result.autoMerged(), result.queued(), result.separated(), result.failed());
    }
}
```

- [ ] **Step 2: 전체 백엔드 빌드 확인**

Run: `cd backend && ./gradlew build -x test`
Expected: BUILD SUCCESSFUL (scheduler가 :merge의 새 서비스를 정상적으로 참조하는지 컴파일 확인)

- [ ] **Step 3: 커밋**

```bash
git add backend/scheduler/src/main/java/kr/trendstage/scheduler/ClusterMergeJob.java
git commit -m "refactor(scheduler): reduce ClusterMergeJob to a thin cron wrapper over ClusterMergeCandidateService"
```

---

### Task 5: 409 예외 매핑

**Files:**
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/BatchJobAlreadyRunningException.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminApiExceptionHandler.java`

- [ ] **Step 1: 예외 클래스**

```java
package kr.trendstage.apiadmin.web;

public class BatchJobAlreadyRunningException extends RuntimeException {
    public BatchJobAlreadyRunningException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: 핸들러 추가**

`AdminApiExceptionHandler.java`의 `AdminValidationException` 핸들러 바로 아래에 추가:

```java
    @ExceptionHandler(BatchJobAlreadyRunningException.class)
    public ResponseEntity<Map<String, Object>> handle(BatchJobAlreadyRunningException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(409, e.getMessage()));
    }
```

- [ ] **Step 3: 빌드 확인**

Run: `cd backend && ./gradlew :api-admin:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/BatchJobAlreadyRunningException.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminApiExceptionHandler.java
git commit -m "feat(api-admin): map BatchJobAlreadyRunningException to 409"
```

---

### Task 6: `BatchJobController` — 관리자 API

**Files:**
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/BatchJobController.java`

**Files:** (module dependency)
- Modify: `backend/api-admin/build.gradle.kts` — 이미 `implementation(project(":merge"))`가 있는지 먼저 확인. **있으면 이 파일은 수정 불필요.**

- [ ] **Step 0: 의존성 확인**

Run: `grep 'project(":merge")' backend/api-admin/build.gradle.kts`
Expected: 이미 존재(이 세션 앞부분에서 확인됨 — `MergeQueueController`가 `MergeService`를 쓰고 있어 이미 의존 중). 없다면 `implementation(project(":merge"))`를 `dependencies` 블록에 추가.

- [ ] **Step 1: 컨트롤러 작성**

```java
package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.merge.ClusterMergeCandidateService;
import kr.trendstage.merge.ClusterMergeCandidateService.ClusterMergeResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/** 배치 잡 수동 실행. 이번 스코프는 cluster_merge 하나뿐(verdict_runner/grade_recalc는 별도 검토 필요). */
@RestController
@RequestMapping("/admin/batch-jobs")
public class BatchJobController {

    private final ClusterMergeCandidateService clusterMergeCandidateService;
    private final AuditLogService auditLogService;

    public BatchJobController(ClusterMergeCandidateService clusterMergeCandidateService,
                               AuditLogService auditLogService) {
        this.clusterMergeCandidateService = clusterMergeCandidateService;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/cluster-merge/run")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ClusterMergeResult runClusterMerge(@AuthenticationPrincipal AdminPrincipal actor) {
        Optional<ClusterMergeResult> result = clusterMergeCandidateService.tryRunNow();
        if (result.isEmpty()) {
            throw new BatchJobAlreadyRunningException("이미 실행 중입니다");
        }
        ClusterMergeResult r = result.get();
        auditLogService.record(actor.id(), actor.role(), "CLUSTER_MERGE_MANUAL_TRIGGER", "BATCH_JOB", null,
                Map.of("candidates", r.candidates(), "autoMerged", r.autoMerged(),
                        "queued", r.queued(), "separated", r.separated(), "failed", r.failed()));
        return r;
    }
}
```

- [ ] **Step 2: 빌드 확인**

Run: `cd backend && ./gradlew :api-admin:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/BatchJobController.java
git commit -m "feat(api-admin): add BatchJobController POST /admin/batch-jobs/cluster-merge/run"
```

---

### Task 7: `api-spec` openapi.yaml 갱신

**Files:**
- Modify: `backend/api-spec/openapi.yaml`

기존 `/admin/*` 경로들과 동일한 스타일로 추가. `ReportSubmissionCandidate` 등 기존 스키마 작성 스타일을 참고한다.

- [ ] **Step 1: 경로 추가**

`paths:` 아래, `/admin/reports` 근처(비슷한 admin 그룹)에 추가:

```yaml
  /admin/batch-jobs/cluster-merge/run:
    post:
      tags: [admin-batch-jobs]
      summary: cluster_merge 배치 수동 실행 (OPERATOR 이상)
      responses:
        "200":
          description: 실행 완료
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/ClusterMergeResult"
        "409":
          description: 이미 실행 중(크론 또는 다른 관리자가 실행 중)
```

`components/schemas:` 아래에 추가:

```yaml
    ClusterMergeResult:
      type: object
      required: [candidates, autoMerged, queued, separated, failed]
      properties:
        candidates: { type: integer }
        autoMerged: { type: integer }
        queued: { type: integer }
        separated: { type: integer }
        failed: { type: integer }
```

`tags:` 목록에 `admin-batch-jobs` 없으면 추가(기존 태그 정의 스타일 참고).

- [ ] **Step 2: YAML 유효성 확인**

Run: `python -c "import yaml; d = yaml.safe_load(open('backend/api-spec/openapi.yaml', encoding='utf-8')); print('paths:', len(d['paths'])); print('schemas:', len(d['components']['schemas'])); assert '/admin/batch-jobs/cluster-merge/run' in d['paths']; assert 'ClusterMergeResult' in d['components']['schemas']; print('OK')"`
Expected: `OK` 출력, 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add backend/api-spec/openapi.yaml
git commit -m "docs(api-spec): add POST /admin/batch-jobs/cluster-merge/run"
```

---

### Task 8: admin 프론트 — 타입 + API 훅

**Files:**
- Modify: `admin/src/api/types.ts`
- Modify: `admin/src/api/hooks.ts`

- [ ] **Step 1: 타입 추가**

`admin/src/api/types.ts`에 추가(기존 `SeedAccuracyRow` 인터페이스 근처):

```typescript
export interface ClusterMergeResult {
  candidates: number;
  autoMerged: number;
  queued: number;
  separated: number;
  failed: number;
}
```

- [ ] **Step 2: 훅 추가**

`admin/src/api/hooks.ts` 상단 import에 `ClusterMergeResult` 추가, `registerSeed` 근처에 추가:

```typescript
export const triggerClusterMerge = () =>
  api.post<ClusterMergeResult>("/admin/batch-jobs/cluster-merge/run");
```

- [ ] **Step 3: 타입체크**

Run: `cd admin && npm run typecheck`
Expected: 에러 없음(아직 이 함수를 쓰는 곳이 없어 미사용 경고는 나지 않음 — export된 함수라 tsc가 unused로 잡지 않음)

- [ ] **Step 4: 커밋**

```bash
git add admin/src/api/types.ts admin/src/api/hooks.ts
git commit -m "feat(admin): add ClusterMergeResult type and triggerClusterMerge hook"
```

---

### Task 9: admin 프론트 — 역할 게이트 + fixture

**Files:**
- Modify: `admin/src/state/role.tsx`
- Modify: `admin/src/fixtures.ts`

- [ ] **Step 1: 권한 게이트 추가**

`admin/src/state/role.tsx`의 `CAN` 객체에 추가(`seedRegister` 바로 아래):

```typescript
  batchTrigger: (r: Role) => r === "OPERATOR" || r === "ADMIN",
```

- [ ] **Step 2: fixture 추가**

`admin/src/fixtures.ts`에 데모용 고정 결과 추가(기존 `fxSeedAccuracy` 근처 스타일 참고):

```typescript
export const fxClusterMergeResult: ClusterMergeResult = {
  candidates: 8, autoMerged: 1, queued: 2, separated: 5, failed: 0,
};
```

(`ClusterMergeResult` import를 `fixtures.ts` 상단 `import type { ... } from "./api/types"`에 추가)

- [ ] **Step 3: 타입체크**

Run: `cd admin && npm run typecheck`
Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add admin/src/state/role.tsx admin/src/fixtures.ts
git commit -m "feat(admin): add CAN.batchTrigger and cluster-merge demo fixture"
```

---

### Task 10: admin 프론트 — `BatchJobsScreen.tsx` 신설

**Files:**
- Create: `admin/src/screens/BatchJobsScreen.tsx`

`SeedScreen.tsx`와 동일 톤·구조(Card/Btn/토스트 패턴)로 작성한다. 이 코드베이스엔 확인 모달 컴포넌트가 없으므로(전체 화면 grep 결과 `window.confirm` 포함 어떤 확인 다이얼로그 패턴도 없음) 브라우저 네이티브 `window.confirm`으로 실행 전 확인만 넣는다.

- [ ] **Step 1: 화면 작성**

```typescript
import React, { useState } from "react";
import { USE_FIXTURES, ApiError } from "../api/client";
import { triggerClusterMerge } from "../api/hooks";
import * as fx from "../fixtures";
import type { ClusterMergeResult } from "../api/types";
import { Card, Btn } from "../components/ui";
import { C } from "../theme";
import { useRole, CAN } from "../state/role";

export default function BatchJobsScreen() {
  const { role } = useRole();
  const canRun = CAN.batchTrigger(role);

  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ClusterMergeResult | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2600); };

  const run = async () => {
    if (!window.confirm("지금 cluster_merge를 실행하시겠습니까?")) return;

    if (USE_FIXTURES) {
      setResult(fx.fxClusterMergeResult);
      flash("(데모) cluster_merge 실행 완료");
      return;
    }

    setBusy(true);
    try {
      const r = await triggerClusterMerge();
      setResult(r);
      flash("cluster_merge 실행 완료");
    } catch (e) {
      flash(e instanceof ApiError ? e.message : "실행에 실패했습니다");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ maxWidth: 800 }}>
      <Card>
        <b style={{ fontSize: 13 }}>cluster_merge</b>
        <div style={{ font: "500 11.5px Pretendard", color: C.faint, marginTop: 6, lineHeight: 1.6 }}>
          완전일치로 안 걸러진 항목의 임베딩 유사도를 계산해 자동병합(≥0.85)/큐적재(0.75~0.85)/별개확정(그 미만)으로 분류합니다.
          평소엔 매일 자동으로 돌지만, 여기서 즉시 실행할 수 있습니다.
        </div>

        <div style={{ marginTop: 16 }}>
          <Btn tone="primary" disabled={!canRun || busy} onClick={run}>
            {busy ? "실행 중…" : "지금 실행"}
          </Btn>
          {!canRun && (
            <span style={{ marginLeft: 10, font: "500 11.5px Pretendard", color: C.faint }}>OPERATOR 이상만 실행할 수 있습니다</span>
          )}
        </div>

        {result && (
          <div style={{ marginTop: 16, padding: 12, borderRadius: 8, background: "#f7f7f8", font: "600 12px ui-monospace, monospace" }}>
            후보 {result.candidates}건 · 자동병합 {result.autoMerged} · 큐적재 {result.queued} · 별개확정 {result.separated}
            {" · "}
            <span style={{ color: result.failed > 0 ? C.fading : C.ink }}>실패 {result.failed}</span>
          </div>
        )}
      </Card>

      {toast && <div style={{ position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)", background: C.ink, color: "#fff", padding: "12px 18px", borderRadius: 10, font: "500 12.5px Pretendard" }}>{toast}</div>}
    </div>
  );
}
```

- [ ] **Step 2: 타입체크**

Run: `cd admin && npm run typecheck`
Expected: 에러 없음(아직 어디서도 import 안 해서 미사용 파일 경고 없음)

- [ ] **Step 3: 커밋**

```bash
git add admin/src/screens/BatchJobsScreen.tsx
git commit -m "feat(admin): add BatchJobsScreen (cluster_merge manual trigger UI)"
```

---

### Task 11: admin 프론트 — 라우팅·내비게이션 등록

**Files:**
- Modify: `admin/src/components/Layout.tsx`
- Modify: `admin/src/App.tsx`

`ADM-900`을 새 화면 ID로 쓴다("정책" 그룹, 감사 로그·관리자 계정과 같은 운영 관리류).

- [ ] **Step 1: `Layout.tsx` — ScreenId + NAV**

`ScreenId` 유니온에 `"ADM-900"` 추가:

```typescript
export type ScreenId = "ADM-010" | "ADM-100" | "ADM-110" | "ADM-111" | "ADM-200" | "ADM-410" | "ADM-500" | "ADM-600" | "ADM-620" | "ADM-311" | "ADM-700" | "ADM-800" | "ADM-900" | "stub";
```

`"정책"` 그룹 배열의 `{ id: "ADM-800", ... }` 바로 아래에 추가:

```typescript
    { id: "ADM-900", label: "배치 관리", code: "ADM-900" },
```

- [ ] **Step 2: `App.tsx` — import + TITLE + 렌더**

`import ReportQueueScreen from "./screens/ReportQueueScreen";` 아래에 추가:

```typescript
import BatchJobsScreen from "./screens/BatchJobsScreen";
```

`TITLE` 객체의 `"ADM-800": "관리자 계정",` 바로 아래에 추가:

```typescript
  "ADM-900": "배치 관리",
```

`{screen === "ADM-800" && <AdminAccountsScreen />}` 바로 아래에 추가:

```typescript
      {screen === "ADM-900" && <BatchJobsScreen />}
```

- [ ] **Step 3: 타입체크**

Run: `cd admin && npm run typecheck`
Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add admin/src/components/Layout.tsx admin/src/App.tsx
git commit -m "feat(admin): wire ADM-900 batch jobs screen into nav and routing"
```

---

### Task 12: 스펙 문서에 남겨둔 감사 로그 `details` 노출 갭 — 이번 스코프 아님, 확인만

**Files:** 없음(코드 변경 없음)

스펙 문서의 "열려 있는 후속 과제"에 적어둔 대로, `GET /admin/audit-log`가 `details`를 응답에 안 내려주는 건 이번 작업 스코프가 아니다(히스토리 UI 자체를 안 만들기로 했으므로). 이 태스크는 실수로 스코프에 끌려들어오지 않도록 명시적으로 "안 한다"를 박아두는 용도 — 실행할 작업 없음, 다음 태스크로.

- [ ] **Step 1: 확인만** — `AuditLogController.java`/`AuditLogEntryResponse`를 이번 플랜에서 건드리지 않았는지 최종 diff에서 확인(Task 14에서 함께 확인).

---

### Task 13: 전체 백엔드 빌드 + 프론트 타입체크 최종 확인

**Files:** 없음(검증만)

- [ ] **Step 1: 백엔드**

Run: `cd backend && ./gradlew build`
Expected: BUILD SUCCESSFUL, 기존 3개 테스트 스위트(domain-core/audit/merge) 전부 PASS(merge는 이제 4개 테스트 — 기존 5개 MergeComputationTest + 신규 3개 ClusterMergeCandidateServiceTest)

- [ ] **Step 2: admin 프론트**

Run: `cd admin && npm run typecheck`
Expected: 에러 없음

- [ ] **Step 3: 실패 시** — 실패한 태스크로 돌아가 고친다. 통과하면 다음 태스크로.

---

### Task 14: E2E 검증

**Files:** 없음(검증만, 코드 변경 없음)

이전에 관리자가 직접 넣어둔 시드 데이터(`backend/persistence/src/main/resources/db/seed/merge_queue_test_data.sql`, `무라벨 탄산수 챌린지` 항목)를 지우고, 이번에 만든 기능으로 실제 병합 큐를 채울 수 있는지 확인한다.

- [ ] **Step 1: 기존 시드 데이터 제거**

```bash
docker exec -i trd-db psql -U postgres -d trd -c "
DELETE FROM merge_queue WHERE id = '5eed0000-0009-0003-0000-000000000001';
DELETE FROM submissions WHERE trend_item_id IN ('5eed0000-0009-0001-0000-000000000001','5eed0000-0009-0001-0000-000000000002');
DELETE FROM trend_items WHERE id IN ('5eed0000-0009-0001-0000-000000000001','5eed0000-0009-0001-0000-000000000002');
DELETE FROM users WHERE id IN ('5eed0000-0009-0000-0000-00000000000a','5eed0000-0009-0000-0000-00000000000b');
"
```

Expected: 각 DELETE가 정상 실행됨(대상 없으면 0 rows 삭제도 정상)

- [ ] **Step 2: 트렌드 항목 하나를 병합 재검사 대상으로 리셋**

배치가 뭔가 처리할 게 있어야 한다 — `merge_checked_at`이 NULL이고 상태가 DRAFT/PENDING/JUDGING/RESOLVED인 기존 항목이 최소 1건 필요. 없으면 아무 항목이나 리셋:

```bash
docker exec -i trd-db psql -U postgres -d trd -c "
UPDATE trend_items SET merge_checked_at = NULL
WHERE id = (SELECT id FROM trend_items WHERE state IN ('DRAFT','PENDING','JUDGING','RESOLVED') LIMIT 1);
"
```

- [ ] **Step 3: 백엔드 기동**

임베딩 서비스(`trd-embeddings`, 포트 6000) 컨테이너가 healthy 상태인지 먼저 확인:

Run: `docker ps --filter "name=trd-embeddings" --format "{{.Status}}"`
Expected: `Up ... (healthy)`

백엔드를 로컬에서 기동(`FIREBASE_CREDENTIALS_PATH` 등 이전 세션에서 쓰던 더미 자격증명 재사용 가능).

- [ ] **Step 4: 관리자 로그인 후 "배치 관리" 화면에서 "지금 실행" 클릭**

브라우저로 admin 콘솔 접속 → OPERATOR 이상 역할로 로그인(또는 fixture 모드가 아닌 실 로그인 세션) → 사이드바 "배치 관리"(ADM-900) → "지금 실행" 클릭 → confirm 다이얼로그 승인.

Expected: 결과 카드에 `후보 N건 · 자동병합 ... · 큐적재 ... · 별개확정 ... · 실패 0` 표시.

- [ ] **Step 5: DB로 실제 반영 확인**

```bash
docker exec -i trd-db psql -U postgres -d trd -c "SELECT status, count(*) FROM merge_queue GROUP BY status;"
```

Expected: 방금 실행으로 새 `PENDING` 행이 생겼으면(큐적재 케이스가 있었다면) 카운트 증가 확인. 자동병합 케이스가 있었다면 `SELECT state FROM trend_items WHERE id = <loser-id>`가 `MERGED`인지 확인.

- [ ] **Step 6: 감사 로그 확인**

```bash
docker exec -i trd-db psql -U postgres -d trd -c "SELECT action, target_type, created_at FROM admin_audit_log WHERE action = 'CLUSTER_MERGE_MANUAL_TRIGGER' ORDER BY id DESC LIMIT 1;"
```

Expected: 방금 실행 기록이 1건 존재.

- [ ] **Step 7: 409(이미 실행 중) 경로 확인**

`shedlock` 테이블에 락을 수동으로 걸어 "실행 중" 상태를 시뮬레이션:

```bash
docker exec -i trd-db psql -U postgres -d trd -c "
INSERT INTO shedlock (name, lock_until, locked_at, locked_by)
VALUES ('cluster_merge', now() + interval '10 minutes', now(), 'e2e-test-simulated-lock')
ON CONFLICT (name) DO UPDATE SET lock_until = excluded.lock_until, locked_at = excluded.locked_at, locked_by = excluded.locked_by;
"
```

관리자 콘솔에서 다시 "지금 실행" 클릭.

Expected: 토스트로 "이미 실행 중입니다" 표시(성공 결과 카드가 아님).

정리:

```bash
docker exec -i trd-db psql -U postgres -d trd -c "DELETE FROM shedlock WHERE name = 'cluster_merge' AND locked_by = 'e2e-test-simulated-lock';"
```

- [ ] **Step 8: 서버 정리**

E2E용으로 띄운 백엔드/프론트 프로세스를 전부 종료한다(이전 세션에서 반복됐던 실수 — `TaskStop`만으로 부모 프로세스가 안 죽는 경우가 있었으니, `netstat`/`Get-Process`로 실제 포트가 닫혔는지 재확인).

- [ ] **Step 9: 결과 보고**

사용자에게 최종 결과(성공한 항목, 발견된 문제와 수정 내역)를 요약 보고한다.
