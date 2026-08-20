# A1 · 2인 승인 실행 경로 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `approval_requests`에 쌓이기만 하던 2인 승인 요청을 실제로 승인/반려할 수 있게 하고, `PARAM_APPLY`가 2/2 승인되면 파라미터 드래프트가 실제로 운영에 반영되게 한다.

**Architecture:** `ApprovalRequest`/`ParameterDraft` 엔티티에 빠져있던 상태 전이 메서드를 채우고, 액션타입→실행 로직을 매핑하는 `ApprovalExecutor` 레지스트리(`api-admin`)를 만든다. "현재 운영 파라미터 값"을 읽는 로직은 `scheduler`(배치)와 `api-admin`(ADM-600 화면의 "현재값" 표시) 양쪽이 필요하므로 두 모듈이 공통으로 의존하는 `persistence` 모듈에 `CurrentParameterSetResolver`로 한 번만 구현한다.

**Tech Stack:** Spring Boot 3.3.5 / Java 17, JPA(비관적 락), Jackson(`ObjectMapper`), React + TypeScript + TanStack Query.

**설계 문서:** `docs/superpowers/specs/2026-08-20-admin-approvals-design.md`

**테스트 전략:** 이 저장소는 순수 함수(`domain-core`, `merge` 모듈)만 JUnit 단위 테스트로 검증하고, JPA/Spring이 얽힌 서비스·컨트롤러는 기존 관례대로(`docs/superpowers/plans/2026-08-14-adm600-param-studio.md` Task 14 참고) 컴파일 확인 + 앱 기동 후 브라우저/curl/psql 수동 검증으로 확인한다. 이번 작업은 새 순수 함수를 추가하지 않으므로(엔티티 상태 전이·레지스트리 디스패치는 글루 코드) 신규 JUnit 테스트 파일은 없다 — 마지막 Task에서 기존 `domain-core`/`merge`/`audit` 테스트가 깨지지 않았는지 회귀 확인한다.

---

### Task 1: persistence — `ApprovalRequest` 엔티티에 승인자·상태전이 메서드 추가

**Files:**
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/ApprovalRequest.java`

DB 컬럼(`approver_1`/`approver_2`/`resolved_at`, V6)은 이미 있는데 엔티티에 매핑이 안 돼 있었다. 승인/반려가 실제로 상태를 바꿀 수 있도록 채운다.

- [ ] **Step 1: 파일 전체 교체**

```java
package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import kr.trendstage.persistence.type.ApprovalStatus;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 2인 승인 요청. DB CHECK 제약(approver1_not_requester 등)이 요청자≠승인자를 강제한다(V6).
 * 상태 전이는 ApprovalService(api-admin)가 행 잠금(findByIdForUpdate) 하에서만 호출한다.
 */
@Entity
@Table(name = "approval_requests")
public class ApprovalRequest {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "action_type", nullable = false, length = 40)
    private String actionType;

    @Column(name = "target_ref", nullable = false)
    private UUID targetRef;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "approver_1")
    private UUID approver1;

    @Column(name = "approver_2")
    private UUID approver2;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ApprovalStatus status = ApprovalStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected ApprovalRequest() {}

    public ApprovalRequest(String actionType, UUID targetRef, UUID requestedBy, String payload) {
        this.actionType = actionType;
        this.targetRef = targetRef;
        this.requestedBy = requestedBy;
        this.payload = payload;
    }

    /** 1차 승인. */
    public void approveFirst(UUID approverId) {
        this.approver1 = approverId;
        this.status = ApprovalStatus.PARTIAL;
    }

    /** 2차 승인 — 승인자가 1차 승인자와 달라야 한다는 검증은 ApprovalService가 먼저 한다. */
    public void approveSecond(UUID approverId, Instant now) {
        this.approver2 = approverId;
        this.status = ApprovalStatus.APPROVED;
        this.resolvedAt = now;
    }

    /** 2/2 승인 후 대상 작업 실행까지 성공했을 때. */
    public void markExecuted() {
        this.status = ApprovalStatus.EXECUTED;
    }

    public void reject(Instant now) {
        this.status = ApprovalStatus.REJECTED;
        this.resolvedAt = now;
    }

    public UUID getId() { return id; }
    public String getActionType() { return actionType; }
    public UUID getTargetRef() { return targetRef; }
    public UUID getRequestedBy() { return requestedBy; }
    public UUID getApprover1() { return approver1; }
    public UUID getApprover2() { return approver2; }
    public ApprovalStatus getStatus() { return status; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :persistence:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/persistence/src/main/java/kr/trendstage/persistence/entity/ApprovalRequest.java
git commit -m "feat(persistence): add approver/resolvedAt state transitions to ApprovalRequest"
```

---

### Task 2: persistence — `ApprovalRequestRepository`에 큐 조회·행 잠금 메서드 추가

**Files:**
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/ApprovalRequestRepository.java`

두 관리자가 동시에 같은 요청을 승인하는 경합을 막으려면 행 잠금 조회가 필요하다(CLAUDE.md: "검수자 동시 처리 충돌은 실제로 발생한다").

- [ ] **Step 1: 파일 전체 교체**

```java
package kr.trendstage.persistence.repo;

import jakarta.persistence.LockModeType;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.type.ApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, UUID> {

    /** 승인 대기 큐(ADM-620) — PENDING/PARTIAL만. 처리 완료된 요청은 큐에서 빠진다. */
    List<ApprovalRequest> findByStatusInOrderByCreatedAtAsc(List<ApprovalStatus> statuses);

    /** 승인/반려 처리 중 행 잠금 — 두 승인자의 동시 클릭 경합 직렬화. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ApprovalRequest a where a.id = :id")
    Optional<ApprovalRequest> findByIdForUpdate(@Param("id") UUID id);
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :persistence:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/persistence/src/main/java/kr/trendstage/persistence/repo/ApprovalRequestRepository.java
git commit -m "feat(persistence): add pending-queue and row-lock queries to ApprovalRequestRepository"
```

---

### Task 3: persistence — jackson-databind 의존성 추가 + `ParameterDraft`에 적용·복원·직렬화 메서드 추가

**Files:**
- Modify: `backend/persistence/build.gradle.kts`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/ParameterDraft.java`

`applied_at` 컬럼(V6)은 있는데 엔티티에 없었다. 승인 실행 시 `APPLIED`로 전이시킬 메서드, 반려 시 `DRAFT`로 되돌릴 메서드, 그리고 `ParamStudioService`(시뮬레이션)와 `CurrentParameterSetResolver`(Task 5)가 공유할 payload→`ParameterSet` 변환 메서드가 필요하다. 이 변환에 `ObjectMapper`를 쓰므로 `persistence` 모듈에 jackson-databind를 명시적으로 추가한다(지금까지는 Hibernate가 내부적으로만 썼고 엔티티 코드가 직접 import한 적이 없었다).

- [ ] **Step 1: `persistence/build.gradle.kts`에 의존성 추가**

`backend/persistence/build.gradle.kts`의 `dependencies` 블록을 다음으로 교체:

```kotlin
dependencies {
    // 소비 모듈(api-public/scheduler 등)이 엔티티·리포지토리 타입을 쓰므로 api로 노출.
    api(project(":domain-core"))
    api("org.springframework.boot:spring-boot-starter-data-jpa")

    // ParameterDraft.toParameterSet()이 payload(JSONB)를 파싱하는 데 필요(버전은 BOM이 결정).
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // 구현 세부(마이그레이션·드라이버·벡터)는 전파 불필요.
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation("com.pgvector:pgvector:0.1.6")
}
```

- [ ] **Step 2: `ParameterDraft.java` 전체 교체**

```java
package kr.trendstage.persistence.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.persistence.type.ApplyMode;
import kr.trendstage.persistence.type.ParamStatus;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * ADM-600 파라미터 드래프트. append-only 아님(V7 트리거 대상 외) — 시뮬 전까지 값을 계속 고친다.
 * payload/simResult는 JSON 문자열로 저장(엔진이 읽는 ParameterSet과 별개 — toParameterSet()으로 변환).
 */
@Entity
@Table(name = "parameter_drafts")
public class ParameterDraft {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ParamStatus status = ParamStatus.DRAFT;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sim_result", columnDefinition = "jsonb")
    private String simResult;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "apply_mode", nullable = false)
    private ApplyMode applyMode = ApplyMode.SCHEDULED;

    @Column(name = "approval_id")
    private UUID approvalId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Version
    private Long version;

    protected ParameterDraft() {}

    public ParameterDraft(UUID authorId, String payload) {
        this.authorId = authorId;
        this.payload = payload;
    }

    public UUID getId() { return id; }
    public UUID getAuthorId() { return authorId; }
    public ParamStatus getStatus() { return status; }
    public String getPayload() { return payload; }
    public String getSimResult() { return simResult; }
    public ApplyMode getApplyMode() { return applyMode; }
    public UUID getApprovalId() { return approvalId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAppliedAt() { return appliedAt; }

    public void updatePayload(String payload) {
        this.payload = payload;
        this.simResult = null;
    }

    public void recordSimResult(String simResult) {
        this.simResult = simResult;
    }

    public void moveToReview(UUID approvalId) {
        this.status = ParamStatus.REVIEW;
        this.approvalId = approvalId;
    }

    /** 2인 승인 2/2 실행(ApprovalExecutor) 시 호출 — 이후 ParameterSetProvider가 이 값을 읽는다. */
    public void markApplied(Instant appliedAt) {
        this.status = ParamStatus.APPLIED;
        this.appliedAt = appliedAt;
    }

    /** 승인 요청 반려 시 호출 — 재수정 가능하도록 되돌린다. */
    public void returnToDraft() {
        this.status = ParamStatus.DRAFT;
        this.approvalId = null;
    }

    /**
     * payload(JSONB)를 ParameterSet으로 변환. submitterTarget/hitThreshold 2개만 드래프트가
     * 편집하고 나머지 14개 필드는 defaults() 고정값을 쓴다(ADM-600 스코프, ParamSimulation과 동일 가정).
     */
    public ParameterSet toParameterSet(ObjectMapper objectMapper) {
        try {
            var node = objectMapper.readTree(payload);
            ParameterSet d = ParameterSet.defaults();
            return new ParameterSet(
                    node.get("submitterTarget").asInt(), node.get("hitThreshold").asDouble(),
                    d.bandL2, d.bandL3, d.bandL4,
                    d.mL1, d.mL2, d.mL3, d.mL4,
                    d.wRank1, d.wRank2, d.wRank3, d.wRankRest,
                    d.halflifeDays, d.tiAlpha, d.tiBeta);
        } catch (Exception e) {
            throw new IllegalStateException("payload 파싱 실패: draft=" + id, e);
        }
    }
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `cd backend && ./gradlew :persistence:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add backend/persistence/build.gradle.kts backend/persistence/src/main/java/kr/trendstage/persistence/entity/ParameterDraft.java
git commit -m "feat(persistence): add ParameterDraft.markApplied/returnToDraft/toParameterSet"
```

---

### Task 4: persistence — `ParameterDraftRepository`에 최신 적용본 조회 메서드 추가

**Files:**
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/ParameterDraftRepository.java`

- [ ] **Step 1: 파일 전체 교체**

```java
package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.ParameterDraft;
import kr.trendstage.persistence.type.ParamStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ParameterDraftRepository extends JpaRepository<ParameterDraft, UUID> {
    Optional<ParameterDraft> findFirstByStatusInOrderByCreatedAtDesc(List<ParamStatus> statuses);

    /** 운영에 반영된 최신 파라미터 — CurrentParameterSetResolver가 사용. */
    Optional<ParameterDraft> findFirstByStatusOrderByAppliedAtDesc(ParamStatus status);
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :persistence:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/persistence/src/main/java/kr/trendstage/persistence/repo/ParameterDraftRepository.java
git commit -m "feat(persistence): add findFirstByStatusOrderByAppliedAtDesc to ParameterDraftRepository"
```

---

### Task 5: persistence — `CurrentParameterSetResolver` 신규 (운영 파라미터 단일 소스)

**Files:**
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/params/CurrentParameterSetResolver.java`

`scheduler`(배치 엔진)와 `api-admin`(ADM-600 "현재값" 표시) 둘 다 "지금 운영에 반영된 파라미터"를 읽어야 하는데, 두 모듈은 서로 의존하지 않는 형제 모듈이라 로직을 한쪽에 두면 다른 쪽에서 재사용할 수 없다. 둘 다 이미 의존하는 `persistence`에 둔다.

- [ ] **Step 1: 파일 작성**

```java
package kr.trendstage.persistence.params;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.persistence.repo.ParameterDraftRepository;
import kr.trendstage.persistence.type.ParamStatus;
import org.springframework.stereotype.Component;

/**
 * 운영에 반영된(가장 최근 APPLIED) 파라미터 값. 없으면 defaults().
 * verdict_runner(scheduler)와 ADM-600 "현재값" 표시(api-admin)가 같은 값을 봐야 하므로
 * 두 모듈이 공통으로 의존하는 persistence에 둔다.
 */
@Component
public class CurrentParameterSetResolver {

    private final ParameterDraftRepository drafts;
    private final ObjectMapper objectMapper;

    public CurrentParameterSetResolver(ParameterDraftRepository drafts, ObjectMapper objectMapper) {
        this.drafts = drafts;
        this.objectMapper = objectMapper;
    }

    public ParameterSet resolve() {
        return drafts.findFirstByStatusOrderByAppliedAtDesc(ParamStatus.APPLIED)
                .map(d -> d.toParameterSet(objectMapper))
                .orElseGet(ParameterSet::defaults);
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :persistence:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/persistence/src/main/java/kr/trendstage/persistence/params/CurrentParameterSetResolver.java
git commit -m "feat(persistence): add CurrentParameterSetResolver shared by scheduler and api-admin"
```

---

### Task 6: scheduler — `ParameterSetProvider`가 실제 적용본을 읽도록 배선

**Files:**
- Modify: `backend/scheduler/src/main/java/kr/trendstage/scheduler/ParameterSetProvider.java`

`// TODO: parameter_drafts(APPLIED) 최신본 조회` 스텁을 제거한다. `VerdictRunner`/`GradeRecalcJob` 등 기존 주입 지점은 그대로 `ParameterSetProvider`를 쓰므로 이 파일만 바뀐다.

- [ ] **Step 1: 파일 전체 교체**

```java
package kr.trendstage.scheduler;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.persistence.params.CurrentParameterSetResolver;
import org.springframework.stereotype.Component;

/**
 * 현재 유효한 파라미터를 제공. parameter_drafts의 APPLIED 최신본을 읽는다(ADM-600 2인 승인 실행 시 갱신).
 * 엔진은 이 값을 주입받으므로 운영/시뮬레이션이 같은 코드를 공유한다.
 */
@Component
public class ParameterSetProvider {

    private final CurrentParameterSetResolver resolver;

    public ParameterSetProvider(CurrentParameterSetResolver resolver) {
        this.resolver = resolver;
    }

    public ParameterSet current() {
        return resolver.resolve();
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :scheduler:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/scheduler/src/main/java/kr/trendstage/scheduler/ParameterSetProvider.java
git commit -m "fix(scheduler): read APPLIED parameter draft instead of hardcoded defaults"
```

---

### Task 7: api-admin — `ParamStudioService`가 공유 리졸버·엔티티 변환 메서드를 쓰도록 정리

**Files:**
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/params/ParamStudioService.java`

`currentOperationalParams()`가 항상 `ParameterSet.defaults()`만 반환하던 것을 고친다(안 고치면 승인이 실행돼도 ADM-600 화면의 "현재값 →" 표시가 계속 예전 기본값으로 나온다 — CLAUDE.md가 경고하는 "에러도 안 나고 값만 조용히 틀린" 부류의 버그). 동시에 `simulate()`가 쓰던 private `toParameterSet(String)`을 `ParameterDraft.toParameterSet(ObjectMapper)`(Task 3)로 대체해 중복을 없앤다.

- [ ] **Step 1: 파일 전체 교체**

```java
package kr.trendstage.apiadmin.params;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.apiadmin.auth.DraftLockedException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.domain.params.ParamSimulation;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.params.SimulationSummary;
import kr.trendstage.domain.params.VerdictSnapshot;
import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.VerdictResult;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.entity.ParameterDraft;
import kr.trendstage.persistence.entity.Verdict;
import kr.trendstage.persistence.params.CurrentParameterSetResolver;
import kr.trendstage.persistence.repo.ApprovalRequestRepository;
import kr.trendstage.persistence.repo.ParameterDraftRepository;
import kr.trendstage.persistence.repo.VerdictRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.ParamStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ADM-600. 드래프트→시뮬레이션→2인 승인 요청까지만 다룬다(설계서 범위).
 * 시뮬레이션은 evidence_json에 동결된 신호만 읽는 읽기 전용 연산 — submissions 재조회 없음.
 */
@Service
public class ParamStudioService {

    private static final int SIM_WINDOW_DAYS = 180;
    private static final List<ParamStatus> ACTIVE_STATUSES = List.of(ParamStatus.DRAFT, ParamStatus.REVIEW);
    /** parameter_draft 동시 쓰기 직렬화용 고정 advisory lock 키. 임의의 상수. */
    private static final long PARAM_DRAFT_LOCK_KEY = 457_829_316L;

    private final ParameterDraftRepository drafts;
    private final ApprovalRequestRepository approvals;
    private final VerdictRepository verdicts;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final EntityManager entityManager;
    private final CurrentParameterSetResolver currentParameterSetResolver;

    public ParamStudioService(ParameterDraftRepository drafts, ApprovalRequestRepository approvals,
                               VerdictRepository verdicts, AuditLogService auditLogService,
                               ObjectMapper objectMapper, Clock clock, EntityManager entityManager,
                               CurrentParameterSetResolver currentParameterSetResolver) {
        this.drafts = drafts;
        this.approvals = approvals;
        this.verdicts = verdicts;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.entityManager = entityManager;
        this.currentParameterSetResolver = currentParameterSetResolver;
    }

    @Transactional
    public ParameterDraft getOrCreateActiveDraft(UUID actorId) {
        // pg_advisory_xact_lock으로 check-then-create 연산을 직렬화: 두 관리자가 동시에 active draft 생성하는 경합 방지
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:key)")
                .setParameter("key", PARAM_DRAFT_LOCK_KEY)
                .getSingleResult();

        return drafts.findFirstByStatusInOrderByCreatedAtDesc(ACTIVE_STATUSES)
                .orElseGet(() -> drafts.save(new ParameterDraft(actorId, defaultPayloadJson())));
    }

    @Transactional
    public ParameterDraft updateDraftValues(UUID actorId, AdminRole actorRole, int submitterTarget, double hitThreshold) {
        if (submitterTarget < 1) {
            throw new AdminValidationException("목표 제보자 수는 1 이상이어야 합니다");
        }
        ParameterDraft draft = getOrCreateActiveDraft(actorId);
        if (draft.getStatus() == ParamStatus.REVIEW) {
            throw new DraftLockedException("승인 대기 중인 드래프트는 수정할 수 없습니다");
        }
        draft.updatePayload(payloadJson(submitterTarget, hitThreshold));
        auditLogService.record(actorId, actorRole, "PARAM_DRAFT_UPDATE", "PARAMETER_DRAFT", draft.getId(), Map.of(
                "submitterTarget", submitterTarget, "hitThreshold", hitThreshold));
        return draft;
    }

    @Transactional
    public ParameterDraft simulate(UUID actorId, AdminRole actorRole) {
        ParameterDraft draft = getOrCreateActiveDraft(actorId);
        ParameterSet draftParams = draft.toParameterSet(objectMapper);

        Instant since = clock.instant().minus(Duration.ofDays(SIM_WINDOW_DAYS));
        List<VerdictSnapshot> snapshots = verdicts.findCurrentNonVoidSince(since).stream()
                .map(this::toSnapshot)
                .toList();

        SimulationSummary summary = ParamSimulation.run(snapshots, draftParams);
        draft.recordSimResult(simResultJson(summary));

        auditLogService.record(actorId, actorRole, "PARAM_SIMULATE", "PARAMETER_DRAFT", draft.getId(), Map.of(
                "changed", summary.changed(), "total", summary.total()));
        return draft;
    }

    @Transactional
    public ParameterDraft requestApproval(UUID actorId, AdminRole actorRole, String reason) {
        requireReason(reason);
        ParameterDraft draft = getOrCreateActiveDraft(actorId);
        if (draft.getSimResult() == null) {
            throw new AdminValidationException("시뮬레이션을 먼저 실행해야 승인 요청을 보낼 수 있습니다");
        }
        if (draft.getStatus() == ParamStatus.REVIEW) {
            throw new DraftLockedException("이미 승인 대기 중인 드래프트입니다");
        }
        ApprovalRequest approval = approvals.save(new ApprovalRequest(
                "PARAM_APPLY", draft.getId(), actorId,
                "{\"reason\":\"%s\"}".formatted(reason == null ? "" : reason.replace("\"", "'"))));
        draft.moveToReview(approval.getId());

        auditLogService.record(actorId, actorRole, "PARAM_APPROVAL_REQUEST", "PARAMETER_DRAFT", draft.getId(), Map.of(
                "reason", reason == null ? "" : reason, "approvalRequestId", approval.getId().toString()));
        return draft;
    }

    public ParameterSet currentOperationalParams() {
        return currentParameterSetResolver.resolve();
    }

    private VerdictSnapshot toSnapshot(Verdict v) {
        try {
            var node = objectMapper.readTree(v.getEvidenceJson());
            int distinctSubmitters = node.get("distinctSubmitters").asInt();
            int distinctPlatforms = node.get("distinctPlatforms").asInt();
            ReachLevel reach = v.getReachLevel();
            return new VerdictSnapshot(v.getResult(), reach, distinctSubmitters, distinctPlatforms);
        } catch (Exception e) {
            throw new IllegalStateException("evidence_json 파싱 실패: verdict=" + v.getId(), e);
        }
    }

    private String defaultPayloadJson() {
        ParameterSet d = ParameterSet.defaults();
        return payloadJson(d.submitterTarget, d.hitThreshold);
    }

    private String payloadJson(int submitterTarget, double hitThreshold) {
        return "{\"submitterTarget\":%d,\"hitThreshold\":%s}".formatted(submitterTarget, hitThreshold);
    }

    private String simResultJson(SimulationSummary s) {
        return "{\"changed\":%d,\"total\":%d,\"missToHit\":%d,\"hitToMiss\":%d,\"reachChanged\":%d}"
                .formatted(s.changed(), s.total(), s.missToHit(), s.hitToMiss(), s.reachChanged());
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AdminValidationException("사유는 필수입니다");
        }
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :api-admin:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/params/ParamStudioService.java
git commit -m "fix(api-admin): ParamStudioService reads real current parameters, dedupe payload parsing"
```

---

### Task 8: api-admin — `ApprovalExecutor` 인터페이스 + `ApprovalConflictException`

**Files:**
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/approval/ApprovalExecutor.java`
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/approval/ApprovalConflictException.java`

- [ ] **Step 1: 두 파일 작성**

```java
package kr.trendstage.apiadmin.approval;

import kr.trendstage.persistence.entity.ApprovalRequest;

/**
 * approval_requests.action_type별 실행 로직. B3(제재)/B4(등급조정·원장조정)는 각자 이 인터페이스를
 * 구현하는 @Component만 추가하면 ApprovalService 수정 없이 등록된다.
 */
public interface ApprovalExecutor {
    /** approval_requests.action_type과 정확히 일치해야 한다(예: "PARAM_APPLY"). */
    String actionType();

    /** 2/2 승인 시 호출. 예외를 던지면 승인 트랜잭션 전체가 롤백된다(승인 요청은 PARTIAL로 남아 재시도 가능). */
    void execute(ApprovalRequest request);

    /** 반려 시 대상 리소스를 원복. 기본은 아무 것도 하지 않음(대상이 없거나 원복이 불필요한 액션타입). */
    default void onReject(ApprovalRequest request) {}
}
```

```java
package kr.trendstage.apiadmin.approval;

/** 승인 상태 전이 규칙 위반(요청자 본인 승인, 중복 승인, 이미 처리된 요청 등) — 409로 매핑. */
public class ApprovalConflictException extends RuntimeException {
    public ApprovalConflictException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :api-admin:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/approval/ApprovalExecutor.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/approval/ApprovalConflictException.java
git commit -m "feat(api-admin): add ApprovalExecutor registry interface and ApprovalConflictException"
```

---

### Task 9: api-admin — `ParamApplyExecutor`

**Files:**
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/approval/ParamApplyExecutor.java`

`PARAM_APPLY`의 유일한 실 구현체. 2/2 승인 시 드래프트를 `APPLIED`로, 반려 시 `DRAFT`로 되돌린다.

- [ ] **Step 1: 파일 작성**

```java
package kr.trendstage.apiadmin.approval;

import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.entity.ParameterDraft;
import kr.trendstage.persistence.repo.ParameterDraftRepository;
import kr.trendstage.persistence.type.ParamStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class ParamApplyExecutor implements ApprovalExecutor {

    private final ParameterDraftRepository drafts;
    private final Clock clock;

    public ParamApplyExecutor(ParameterDraftRepository drafts, Clock clock) {
        this.drafts = drafts;
        this.clock = clock;
    }

    @Override
    public String actionType() {
        return "PARAM_APPLY";
    }

    @Override
    public void execute(ApprovalRequest request) {
        ParameterDraft draft = drafts.findById(request.getTargetRef())
                .orElseThrow(() -> new IllegalStateException("대상 드래프트 없음: " + request.getTargetRef()));
        if (draft.getStatus() != ParamStatus.REVIEW) {
            throw new AdminValidationException("드래프트가 이미 처리된 상태입니다: " + draft.getStatus());
        }
        draft.markApplied(clock.instant());
    }

    @Override
    public void onReject(ApprovalRequest request) {
        drafts.findById(request.getTargetRef()).ifPresent(ParameterDraft::returnToDraft);
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :api-admin:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/approval/ParamApplyExecutor.java
git commit -m "feat(api-admin): add ParamApplyExecutor (PARAM_APPLY approval execution)"
```

---

### Task 10: api-admin — `ApprovalService`

**Files:**
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/approval/ApprovalService.java`

승인/반려의 상태 전이·실행 디스패치·감사 로그를 담당하는 핵심 서비스.

- [ ] **Step 1: 파일 작성**

```java
package kr.trendstage.apiadmin.approval;

import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.repo.ApprovalRequestRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.ApprovalStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ApprovalService {

    private final ApprovalRequestRepository approvals;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final Map<String, ApprovalExecutor> executors;

    public ApprovalService(ApprovalRequestRepository approvals, List<ApprovalExecutor> executorBeans,
                            AuditLogService auditLogService, Clock clock) {
        this.approvals = approvals;
        this.auditLogService = auditLogService;
        this.clock = clock;
        this.executors = executorBeans.stream()
                .collect(Collectors.toMap(ApprovalExecutor::actionType, e -> e));
    }

    public List<ApprovalRequest> listPending() {
        return approvals.findByStatusInOrderByCreatedAtAsc(List.of(ApprovalStatus.PENDING, ApprovalStatus.PARTIAL));
    }

    @Transactional
    public ApprovalRequest approve(UUID actorId, AdminRole actorRole, UUID id) {
        ApprovalRequest req = requirePending(id);
        if (req.getRequestedBy().equals(actorId)) {
            throw new ApprovalConflictException("요청자 본인은 승인할 수 없습니다");
        }

        if (req.getStatus() == ApprovalStatus.PENDING) {
            req.approveFirst(actorId);
            auditLogService.record(actorId, actorRole, "APPROVAL_APPROVE", req.getActionType(), req.getTargetRef(),
                    Map.of("approvalRequestId", id.toString(), "stage", "1/2"));
            return req;
        }

        // PARTIAL
        if (req.getApprover1().equals(actorId)) {
            throw new ApprovalConflictException("이미 승인했습니다");
        }
        req.approveSecond(actorId, clock.instant());
        auditLogService.record(actorId, actorRole, "APPROVAL_APPROVE", req.getActionType(), req.getTargetRef(),
                Map.of("approvalRequestId", id.toString(), "stage", "2/2"));

        executorFor(req.getActionType()).execute(req);
        req.markExecuted();
        auditLogService.record(actorId, actorRole, "APPROVAL_EXECUTE", req.getActionType(), req.getTargetRef(),
                Map.of("approvalRequestId", id.toString()));
        return req;
    }

    @Transactional
    public ApprovalRequest reject(UUID actorId, AdminRole actorRole, UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AdminValidationException("반려 사유는 필수입니다");
        }
        ApprovalRequest req = requirePending(id);
        req.reject(clock.instant());
        if (executors.containsKey(req.getActionType())) {
            executors.get(req.getActionType()).onReject(req);
        }
        auditLogService.record(actorId, actorRole, "APPROVAL_REJECT", req.getActionType(), req.getTargetRef(),
                Map.of("approvalRequestId", id.toString(), "reason", reason));
        return req;
    }

    private ApprovalRequest requirePending(UUID id) {
        ApprovalRequest req = approvals.findByIdForUpdate(id)
                .orElseThrow(() -> new AdminValidationException("존재하지 않는 승인 요청입니다"));
        if (req.getStatus() != ApprovalStatus.PENDING && req.getStatus() != ApprovalStatus.PARTIAL) {
            throw new ApprovalConflictException("이미 처리된 요청입니다: " + req.getStatus());
        }
        return req;
    }

    private ApprovalExecutor executorFor(String actionType) {
        ApprovalExecutor executor = executors.get(actionType);
        if (executor == null) {
            throw new IllegalStateException("등록된 실행기가 없는 actionType: " + actionType);
        }
        return executor;
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :api-admin:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/approval/ApprovalService.java
git commit -m "feat(api-admin): add ApprovalService (approve/reject state machine + execution dispatch)"
```

---

### Task 11: api-admin — `ApprovalConflictException`을 409로 매핑

**Files:**
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminApiExceptionHandler.java`

- [ ] **Step 1: import 추가 + 핸들러 추가**

`import kr.trendstage.apiadmin.auth.SelfModificationException;` 다음 줄에 추가:

```java
import kr.trendstage.apiadmin.approval.ApprovalConflictException;
```

`DuplicateLoginIdException` 핸들러(409) 바로 아래에 추가:

```java
    @ExceptionHandler(ApprovalConflictException.class)
    public ResponseEntity<Map<String, Object>> handle(ApprovalConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(409, e.getMessage()));
    }
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :api-admin:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminApiExceptionHandler.java
git commit -m "feat(api-admin): map ApprovalConflictException to 409"
```

---

### Task 12: api-admin — `ApprovalController`

**Files:**
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/ApprovalController.java`

`AuditLogController`와 동일한 패턴(관리자 표시명 조회, KST 포맷)을 따른다.

- [ ] **Step 1: 파일 작성**

```java
package kr.trendstage.apiadmin.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.trendstage.apiadmin.approval.ApprovalService;
import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/** ADM-620 승인 대기함. 권한(02 §1.1): 파라미터 적용 등 2인 승인 확정은 ADMIN, 조회는 ADMIN/AUDITOR. */
@RestController
@RequestMapping("/admin/approvals")
public class ApprovalController {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final ApprovalService service;
    private final AdminAccountRepository accounts;
    private final ObjectMapper objectMapper;

    public ApprovalController(ApprovalService service, AdminAccountRepository accounts, ObjectMapper objectMapper) {
        this.service = service;
        this.accounts = accounts;
        this.objectMapper = objectMapper;
    }

    public record ApprovalRequestResponse(String id, String actionType, String targetRef,
                                           String requestedByName, int approvals, String status,
                                           String reason, String createdAt, String resolvedAt) {}
    public record RejectRequest(String reason) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
    public List<ApprovalRequestResponse> list() {
        return service.listPending().stream().map(this::toResponse).toList();
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ApprovalRequestResponse approve(@PathVariable UUID id, @AuthenticationPrincipal AdminPrincipal actor) {
        return toResponse(service.approve(actor.id(), actor.role(), id));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ApprovalRequestResponse reject(@PathVariable UUID id, @RequestBody RejectRequest req,
                                           @AuthenticationPrincipal AdminPrincipal actor) {
        return toResponse(service.reject(actor.id(), actor.role(), id, req.reason()));
    }

    private ApprovalRequestResponse toResponse(ApprovalRequest req) {
        String requestedByName = accounts.findById(req.getRequestedBy())
                .map(AdminAccount::getDisplayName)
                .orElse(req.getRequestedBy().toString());
        int approvalCount = (req.getApprover1() != null ? 1 : 0) + (req.getApprover2() != null ? 1 : 0);
        return new ApprovalRequestResponse(
                req.getId().toString(), req.getActionType(), req.getTargetRef().toString(),
                requestedByName, approvalCount, req.getStatus().name(), extractReason(req.getPayload()),
                DISPLAY_FORMAT.format(req.getCreatedAt()),
                req.getResolvedAt() == null ? null : DISPLAY_FORMAT.format(req.getResolvedAt()));
    }

    private String extractReason(String payloadJson) {
        try {
            var node = objectMapper.readTree(payloadJson);
            return node.has("reason") ? node.get("reason").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :api-admin:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/ApprovalController.java
git commit -m "feat(api-admin): add ApprovalController (GET/approve/reject)"
```

---

### Task 13: backend — 전체 컴파일 + 기존 테스트 회귀 확인

**Files:** 없음(검증 전용)

- [ ] **Step 1: 전체 모듈 컴파일**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL (persistence/scheduler/api-admin/api-public/app 등 전 모듈)

- [ ] **Step 2: 기존 순수 함수 테스트 회귀 확인**

Run: `cd backend && ./gradlew :domain-core:test :merge:test :audit:test`
Expected: BUILD SUCCESSFUL — `ParamSimulationTest`/`MergeComputationTest`/`HashChainerTest`가 이번 변경으로 깨지지 않았는지 확인(이번 작업은 이 모듈들을 건드리지 않지만, `persistence`의 `ParameterSet` 관련 변경과 무관함을 확인하는 안전망).

- [ ] **Step 3 (실패 시): 실패한 태스크로 돌아가 수정**

---

### Task 14: api-spec — `openapi.yaml`에 반려 엔드포인트 추가 + 응답 스키마 보강

**Files:**
- Modify: `backend/api-spec/openapi.yaml`

기존 문서엔 `approve`만 있고 `reject`가 없다. `ApprovalRequest` 스키마도 실제 응답 필드(targetRef/reason/createdAt/resolvedAt)를 반영하도록 보강한다.

- [ ] **Step 1: `/admin/approvals/{id}/approve` 다음에 reject 경로 추가**

`  /admin/approvals/{id}/approve:` 블록(`'409': { description: 요청자 본인이거나 이미 승인함 }`으로 끝나는 부분) 바로 다음, `  /admin/audit-log:` 바로 앞에 삽입:

```yaml
  /admin/approvals/{id}/reject:
    post:
      tags: [admin-governance]
      summary: 반려 — 대상을 DRAFT 등 재수정 가능한 상태로 되돌린다
      security: [{ cookieAuth: [] }]
      parameters: [{ name: id, in: path, required: true, schema: { type: string, format: uuid } }]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [reason]
              properties:
                reason: { type: string, description: 반려 사유(필수) }
      responses:
        '200': { description: 반려됨 }
        '409': { description: 이미 처리된 요청 }
```

- [ ] **Step 2: `ApprovalRequest` 스키마 필드 보강**

기존:

```yaml
    ApprovalRequest:
      type: object
      properties:
        id: { type: string, format: uuid }
        actionType: { type: string, enum: [SANCTION, GRADE_ADJUST, PARAM_APPLY, LEDGER_ADJ_OVER100] }
        requestedBy: { type: string }
        status: { type: string, enum: [PENDING, PARTIAL, APPROVED, REJECTED, EXECUTED] }
        approvals: { type: integer, description: 현재 승인 수 (0~2) }
```

교체:

```yaml
    ApprovalRequest:
      type: object
      properties:
        id: { type: string, format: uuid }
        actionType: { type: string, enum: [SANCTION, GRADE_ADJUST, PARAM_APPLY, LEDGER_ADJ_OVER100] }
        targetRef: { type: string, format: uuid }
        requestedByName: { type: string }
        status: { type: string, enum: [PENDING, PARTIAL, APPROVED, REJECTED, EXECUTED] }
        approvals: { type: integer, description: 현재 승인 수 (0~2) }
        reason: { type: [string, 'null'] }
        createdAt: { type: string }
        resolvedAt: { type: [string, 'null'] }
```

- [ ] **Step 3: 커밋**

```bash
git add backend/api-spec/openapi.yaml
git commit -m "docs(api-spec): add reject endpoint, enrich ApprovalRequest schema"
```

---

### Task 15: admin frontend — `ApprovalRequestView` 타입 추가

**Files:**
- Modify: `admin/src/api/types.ts`

- [ ] **Step 1: 파일 끝에 추가**

```typescript
export interface ApprovalRequestView {
  id: string;
  actionType: "SANCTION" | "GRADE_ADJUST" | "PARAM_APPLY" | "LEDGER_ADJ_OVER100";
  targetRef: string;
  requestedByName: string;
  approvals: number;
  status: "PENDING" | "PARTIAL" | "APPROVED" | "REJECTED" | "EXECUTED";
  reason: string | null;
  createdAt: string;
  resolvedAt: string | null;
}
```

- [ ] **Step 2: 커밋**

```bash
git add admin/src/api/types.ts
git commit -m "feat(admin): add ApprovalRequestView type"
```

---

### Task 16: admin frontend — API 훅 추가

**Files:**
- Modify: `admin/src/api/hooks.ts`

- [ ] **Step 1: import에 `ApprovalRequestView` 추가**

`import type { ... QueueSummary, SeedAccuracyRow, ...}` 목록에 `ApprovalRequestView`를 알파벳 순서에 맞게 추가(`AdminAccountSummary` 다음).

- [ ] **Step 2: 파일 끝에 훅 추가**

```typescript
export const useApprovals = () =>
  useData<ApprovalRequestView[]>(["admin", "approvals"], "/admin/approvals", fx.fxApprovals);

export const approveApproval = (id: string) =>
  api.post<ApprovalRequestView>(`/admin/approvals/${id}/approve`);

export const rejectApproval = (id: string, reason: string) =>
  api.post<ApprovalRequestView>(`/admin/approvals/${id}/reject`, { reason });
```

- [ ] **Step 3: 커밋**

```bash
git add admin/src/api/hooks.ts
git commit -m "feat(admin): add useApprovals/approveApproval/rejectApproval hooks"
```

---

### Task 17: admin frontend — `fxApprovals` 픽스처

**Files:**
- Modify: `admin/src/fixtures.ts`

- [ ] **Step 1: import에 `ApprovalRequestView` 추가**

파일 상단 `import type { ... } from "./api/types";` 목록에 `ApprovalRequestView` 추가.

- [ ] **Step 2: `fxAdminAccounts` 바로 다음에 추가**

```typescript
export const fxApprovals: ApprovalRequestView[] = [
  { id: "ap1", actionType: "PARAM_APPLY", targetRef: "pd-17", requestedByName: "김운영", approvals: 0, status: "PENDING", reason: "O1 백테스트 결과 반영 — submitterTarget 20→18", createdAt: "2026-08-07 11:20", resolvedAt: null },
  { id: "ap2", actionType: "PARAM_APPLY", targetRef: "pd-12", requestedByName: "박관리", approvals: 1, status: "PARTIAL", reason: "hitThreshold 0.20→0.22 보정", createdAt: "2026-08-05 09:40", resolvedAt: null },
];
```

- [ ] **Step 3: 커밋**

```bash
git add admin/src/fixtures.ts
git commit -m "feat(admin): add fxApprovals fixture"
```

---

### Task 18: admin frontend — `CAN.approvalConfirm` 권한 추가

**Files:**
- Modify: `admin/src/state/role.tsx`

- [ ] **Step 1: `CAN` 객체에 항목 추가**

`ledgerAdj: (r: Role) => r === "ADMIN",` 다음 줄에 추가:

```typescript
  approvalConfirm: (r: Role) => r === "ADMIN",
```

- [ ] **Step 2: 커밋**

```bash
git add admin/src/state/role.tsx
git commit -m "feat(admin): add CAN.approvalConfirm capability"
```

---

### Task 19: admin frontend — `ApprovalsScreen.tsx` 신규

**Files:**
- Create: `admin/src/screens/ApprovalsScreen.tsx`

`AdminAccountsScreen.tsx`의 테이블·토스트 패턴을 따른다.

- [ ] **Step 1: 파일 작성**

```tsx
import React, { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useApprovals, approveApproval, rejectApproval } from "../api/hooks";
import { ApiError, USE_FIXTURES } from "../api/client";
import { Card, StateView, Btn } from "../components/ui";
import { C } from "../theme";
import { useRole, CAN } from "../state/role";
import { useAuth } from "../state/auth";
import type { ApprovalRequestView } from "../api/types";

const ACTION_LABEL: Record<ApprovalRequestView["actionType"], string> = {
  PARAM_APPLY: "파라미터 적용",
  SANCTION: "유저 제재",
  GRADE_ADJUST: "등급 조정",
  LEDGER_ADJ_OVER100: "원장 상쇄(100+)",
};

const STATUS_LABEL: Record<ApprovalRequestView["status"], string> = {
  PENDING: "대기",
  PARTIAL: "1/2 승인",
  APPROVED: "승인됨",
  REJECTED: "반려됨",
  EXECUTED: "실행됨",
};

/** ADM-620 승인 대기함. ADM-320/600/610 등 여러 화면이 공통으로 만드는 approval_requests를 한 곳에서 처리. */
export default function ApprovalsScreen() {
  const q = useApprovals();
  const { role } = useRole();
  const { state: authState } = useAuth();
  const principal = authState.status === "authenticated" ? authState.principal : null;
  const qc = useQueryClient();
  const [reason, setReason] = useState("");
  const [toast, setToast] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2800); };
  const canConfirm = CAN.approvalConfirm(role);

  const approve = async (row: ApprovalRequestView) => {
    if (USE_FIXTURES) { flash(`(데모) ${row.id} 승인 처리됨`); return; }
    setBusyId(row.id);
    try {
      await approveApproval(row.id);
      await qc.invalidateQueries({ queryKey: ["admin", "approvals"] });
      flash(`${ACTION_LABEL[row.actionType]} 승인 처리됨`);
    } catch (e) {
      flash(e instanceof ApiError ? e.message : "승인에 실패했습니다");
    } finally {
      setBusyId(null);
    }
  };

  const reject = async (row: ApprovalRequestView) => {
    if (!reason.trim()) { flash("반려 사유를 입력하세요"); return; }
    if (USE_FIXTURES) { flash(`(데모) ${row.id} 반려됨 — ${reason}`); return; }
    setBusyId(row.id);
    try {
      await rejectApproval(row.id, reason);
      await qc.invalidateQueries({ queryKey: ["admin", "approvals"] });
      flash(`${ACTION_LABEL[row.actionType]} 반려됨`);
      setReason("");
    } catch (e) {
      flash(e instanceof ApiError ? e.message : "반려에 실패했습니다");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div style={{ maxWidth: 1100 }}>
      <div style={{ marginBottom: 12, display: "flex", gap: 8, alignItems: "center" }}>
        <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="반려 사유 (반려 시 필수 · 감사 로그에 기록됩니다)"
          style={{ flex: 1, padding: "10px 13px", borderRadius: 9, border: `1px solid ${C.line}`, font: "500 12.5px Pretendard" }} />
      </div>

      <Card style={{ padding: 0, overflow: "hidden" }}>
        <div style={{ display: "grid", gridTemplateColumns: "140px 90px 110px 1fr 90px 130px 170px", padding: "13px 20px", borderBottom: `1px solid ${C.line}`, background: "rgba(20,19,15,0.02)" }}>
          {["액션", "대상", "요청자", "사유", "승인현황", "생성시각", ""].map((h) => (
            <span key={h} style={{ font: "600 10.5px Pretendard", letterSpacing: ".06em", color: C.faint }}>{h}</span>
          ))}
        </div>
        <StateView query={q}>
          {(rows) => (
            <>
              {rows.length === 0 && (
                <div style={{ padding: "26px 20px", font: "500 13px Pretendard", color: C.faint }}>대기 중인 승인 요청이 없습니다.</div>
              )}
              {rows.map((row) => {
                const isRequester = principal != null && row.requestedByName === principal.displayName;
                const disabled = !canConfirm || isRequester || busyId === row.id;
                return (
                  <div key={row.id} style={{ display: "grid", gridTemplateColumns: "140px 90px 110px 1fr 90px 130px 170px", alignItems: "center", padding: "14px 20px", borderBottom: "1px solid rgba(20,19,15,0.05)" }}>
                    <span style={{ font: "600 11px ui-monospace, monospace", padding: "3px 6px", borderRadius: 5, justifySelf: "start", background: "rgba(20,19,15,0.06)", color: C.ink }}>
                      {ACTION_LABEL[row.actionType]}
                    </span>
                    <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{row.targetRef.slice(0, 8)}</span>
                    <span style={{ font: "600 12.5px Pretendard" }}>{row.requestedByName}</span>
                    <span style={{ font: "500 12px Pretendard", color: C.sub }}>{row.reason ?? "-"}</span>
                    <span style={{ font: "700 12px ui-monospace, monospace", color: row.approvals > 0 ? C.peak : C.faint }}>
                      {STATUS_LABEL[row.status]}
                    </span>
                    <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{row.createdAt}</span>
                    <div style={{ display: "flex", gap: 6, justifySelf: "end" }}>
                      <Btn tone="primary" disabled={disabled} title={isRequester ? "요청자 본인은 승인할 수 없습니다" : !canConfirm ? "ADMIN 필요" : undefined}
                        onClick={() => approve(row)}>승인</Btn>
                      <Btn tone="danger" disabled={disabled} title={isRequester ? "요청자 본인은 반려할 수 없습니다" : !canConfirm ? "ADMIN 필요" : undefined}
                        onClick={() => reject(row)}>반려</Btn>
                    </div>
                  </div>
                );
              })}
            </>
          )}
        </StateView>
      </Card>

      {toast && <div style={{ position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)", background: C.ink, color: "#fff", padding: "12px 18px", borderRadius: 10, font: "500 12.5px Pretendard" }}>{toast}</div>}
    </div>
  );
}
```

- [ ] **Step 2: 커밋**

```bash
git add admin/src/screens/ApprovalsScreen.tsx
git commit -m "feat(admin): add ApprovalsScreen (ADM-620 승인 대기함)"
```

---

### Task 20: admin frontend — 네비게이션·라우팅 연결

**Files:**
- Modify: `admin/src/components/Layout.tsx`
- Modify: `admin/src/App.tsx`

- [ ] **Step 1: `Layout.tsx` — `ScreenId`에 `"ADM-620"` 추가**

```typescript
export type ScreenId = "ADM-010" | "ADM-100" | "ADM-110" | "ADM-111" | "ADM-200" | "ADM-500" | "ADM-600" | "ADM-620" | "ADM-311" | "ADM-700" | "ADM-800" | "stub";
```

- [ ] **Step 2: `Layout.tsx` — `NAV`의 "정책" 그룹에 항목 추가**

`{ id: "ADM-600", label: "파라미터 스튜디오", code: "ADM-600" },` 바로 다음 줄에 추가:

```typescript
    { id: "ADM-620", label: "승인 대기함", code: "ADM-620" },
```

- [ ] **Step 3: `App.tsx` — import 및 라우팅 추가**

`import ParamStudioScreen from "./screens/ParamStudioScreen";` 다음 줄에 추가:

```typescript
import ApprovalsScreen from "./screens/ApprovalsScreen";
```

`TITLE` 맵의 `"ADM-600": "파라미터 스튜디오",` 다음 줄에 추가:

```typescript
  "ADM-620": "승인 대기함",
```

`{screen === "ADM-600" && <ParamStudioScreen />}` 다음 줄에 추가:

```tsx
      {screen === "ADM-620" && <ApprovalsScreen />}
```

- [ ] **Step 4: 커밋**

```bash
git add admin/src/components/Layout.tsx admin/src/App.tsx
git commit -m "feat(admin): wire ADM-620 approvals screen into nav and routing"
```

---

### Task 21: admin frontend — typecheck 검증

**Files:** 없음(검증 전용)

- [ ] **Step 1: typecheck 실행**

Run: `cd admin && npx tsc --noEmit`
Expected: 에러 없음.

- [ ] **Step 2 (에러 발견 시): 수정 후 재검증**

---

### Task 22: end-to-end 검증

**Files:** 없음(검증 전용)

- [ ] **Step 1: 백엔드 기동**

로컬 dev DB(도커) 기동 상태에서 `cd backend && ./gradlew :app:bootRun`.

- [ ] **Step 2: `curl`로 승인 플로우 직접 확인 (심 없이 실 API로)**

1. 관리자 2명(A: OPERATOR 또는 ADMIN 요청자, B/C: 서로 다른 ADMIN 승인자 2명)으로 로그인해 세션 쿠키 확보.
2. B로 로그인한 상태에서 ADM-600 화면(또는 curl로 `POST /admin/params/draft` 흐름) 통해 `PARAM_APPLY` 승인 요청 하나 생성.
3. `curl -b cookieC.txt -X POST http://localhost:8080/admin/approvals/{id}/approve` — 1차 승인, 응답 `status: "PARTIAL"`, `approvals: 1` 확인.
4. 요청자 본인(B) 계정으로 같은 approve 호출 → `409` 확인.
5. C와 다른 ADMIN(D)로 `POST /admin/approvals/{id}/approve` — 2차 승인, 응답 `status: "EXECUTED"` 확인.
6. `psql`로 `parameter_drafts.status`가 해당 draft에서 `APPLIED`로, `applied_at`이 채워졌는지 확인.
7. `admin_audit_log`에 `APPROVAL_APPROVE`(stage 1/2, 2/2) · `APPROVAL_EXECUTE` 3개 행이 쌓였는지 확인.

- [ ] **Step 3: 반려 플로우 확인**

1. 새 `PARAM_APPLY` 승인 요청을 하나 더 생성.
2. `POST /admin/approvals/{id}/reject` (`{"reason": "테스트 반려"}`) 호출 → `status: "REJECTED"` 확인.
3. `psql`로 해당 `parameter_drafts.status`가 `DRAFT`로, `approval_id`가 `NULL`로 돌아갔는지 확인.
4. ADM-600 화면에서 그 드래프트가 다시 수정 가능한 상태로 보이는지 확인(REVIEW 락 풀림).

- [ ] **Step 4: 프론트엔드 브라우저 확인**

`VITE_USE_FIXTURES=false`로 admin dev 서버 기동 후 ADM-620 화면 진입 → 목록이 실제 API에서 채워지는지, 승인/반려 버튼이 역할(ADMIN)과 요청자 본인 여부에 따라 올바르게 비활성화되는지 확인.

- [ ] **Step 5 (문제 발견 시): 원인 파악 후 관련 태스크로 돌아가 수정, 재검증**
