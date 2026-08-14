# ADM-600 파라미터 스튜디오 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ADM-600 파라미터 스튜디오를 fixture-only 상태에서 실제 백엔드(드래프트 생성·수정, 과거 180일 판정 재시뮬레이션, 2인 승인 요청 생성)로 전환한다.

**Architecture:** 기존 순수 판정 엔진(`ParameterSet`/`VerdictEngine`)을 재사용하는 `ParamSimulation` 순수 함수를 `domain-core`에 추가하고, `parameter_drafts`/`approval_requests`(V6에 이미 존재)를 다루는 엔티티·서비스·컨트롤러를 `persistence`/`api-admin`에 추가한다. 시뮬레이션은 `Verdict.evidence_json`에 동결된 신호만 읽는 완전 읽기 전용 연산이다.

**Tech Stack:** Spring Boot 3.3.5 / Java 17, JPA, Jackson(`ObjectMapper`), React + TypeScript + TanStack Query.

**설계 문서:** `docs/superpowers/specs/2026-08-14-adm600-param-studio-design.md`

---

### Task 1: persistence — enum 3종 (ParamStatus, ApplyMode, ApprovalStatus)

**Files:**
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/type/ParamStatus.java`
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/type/ApplyMode.java`
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/type/ApprovalStatus.java`

DB의 `param_status`/`apply_mode`/`approval_status` PG enum(V2__enums.sql)과 이름·순서를 정확히 맞춘다.

- [ ] **Step 1: 세 파일 작성**

```java
package kr.trendstage.persistence.type;

/** parameter_drafts.status (PG enum param_status). */
public enum ParamStatus { DRAFT, REVIEW, APPROVED, APPLIED, ROLLED_BACK }
```

```java
package kr.trendstage.persistence.type;

/** parameter_drafts.apply_mode (PG enum apply_mode). */
public enum ApplyMode { SCHEDULED, RETROACTIVE }
```

```java
package kr.trendstage.persistence.type;

/** approval_requests.status (PG enum approval_status). */
public enum ApprovalStatus { PENDING, PARTIAL, APPROVED, REJECTED, EXECUTED }
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :persistence:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/persistence/src/main/java/kr/trendstage/persistence/type/ParamStatus.java backend/persistence/src/main/java/kr/trendstage/persistence/type/ApplyMode.java backend/persistence/src/main/java/kr/trendstage/persistence/type/ApprovalStatus.java
git commit -m "feat(persistence): add ParamStatus/ApplyMode/ApprovalStatus enums"
```

---

### Task 2: persistence — ParameterDraft 엔티티 + 리포지토리

**Files:**
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/ParameterDraft.java`
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/ParameterDraftRepository.java`

`parameter_drafts`는 append-only 대상이 아니므로(V7 트리거 목록에 없음) 일반 setter를 노출한다. `AdminAccount.java` 패턴(가변 엔티티, `@Version` 없음 — 여기선 낙관적 락이 필요하므로 추가)을 참고.

- [ ] **Step 1: `ParameterDraft.java` 작성**

```java
package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import kr.trendstage.persistence.type.ApplyMode;
import kr.trendstage.persistence.type.ParamStatus;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * ADM-600 파라미터 드래프트. append-only 아님(V7 트리거 대상 외) — 시뮬 전까지 값을 계속 고친다.
 * payload/simResult는 JSON 문자열로 저장(엔진이 읽는 ParameterSet과 별개 — 컨트롤러/서비스에서 변환).
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
}
```

- [ ] **Step 2: `ParameterDraftRepository.java` 작성**

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
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `cd backend && ./gradlew :persistence:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add backend/persistence/src/main/java/kr/trendstage/persistence/entity/ParameterDraft.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/ParameterDraftRepository.java
git commit -m "feat(persistence): add ParameterDraft entity + repository"
```

---

### Task 3: persistence — ApprovalRequest 엔티티 + 리포지토리 + Verdict 조회 추가

**Files:**
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/ApprovalRequest.java`
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/ApprovalRequestRepository.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/VerdictRepository.java`

- [ ] **Step 1: `ApprovalRequest.java` 작성**

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
 * 이번 스코프는 요청 생성(PENDING)까지만 — 실제 승인 클릭 플로우는 범위 밖.
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

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ApprovalStatus status = ApprovalStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ApprovalRequest() {}

    public ApprovalRequest(String actionType, UUID targetRef, UUID requestedBy, String payload) {
        this.actionType = actionType;
        this.targetRef = targetRef;
        this.requestedBy = requestedBy;
        this.payload = payload;
    }

    public UUID getId() { return id; }
    public String getActionType() { return actionType; }
    public UUID getTargetRef() { return targetRef; }
    public UUID getRequestedBy() { return requestedBy; }
    public ApprovalStatus getStatus() { return status; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 2: `ApprovalRequestRepository.java` 작성**

```java
package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.ApprovalRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, UUID> {}
```

- [ ] **Step 3: `VerdictRepository.java`에 시뮬레이션용 조회 메서드 추가**

`backend/persistence/src/main/java/kr/trendstage/persistence/repo/VerdictRepository.java`의 `List<Verdict> findByTrendItemIdOrderByCreatedAtAsc(UUID trendItemId);` 줄 바로 아래에 추가:

```java

    /**
     * ADM-600 시뮬레이션용: 최근 N일 내 판정된 "현재" 판정(다른 행에 superseded 안 됨) 중 VOID 제외.
     * VOID는 파라미터(threshold/target)와 무관한 별도 사유(어뷰징·중복)라 재평가 대상이 아니다.
     */
    @Query("SELECT v FROM Verdict v WHERE v.judgedAt >= :since AND v.result <> kr.trendstage.domain.verdict.VerdictResult.VOID " +
           "AND NOT EXISTS (SELECT 1 FROM Verdict v2 WHERE v2.supersedes = v.id)")
    List<Verdict> findCurrentNonVoidSince(@Param("since") Instant since);
```

파일 상단 import에 `java.time.Instant`가 이미 없다면 추가로 필요한지 확인 — `Verdict.java`가 이미 `java.time.Instant`를 쓰므로 `VerdictRepository.java`에도 `import java.time.Instant;`를 추가한다(현재 파일에 없음).

- [ ] **Step 4: 컴파일 확인**

Run: `cd backend && ./gradlew :persistence:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add backend/persistence/src/main/java/kr/trendstage/persistence/entity/ApprovalRequest.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/ApprovalRequestRepository.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/VerdictRepository.java
git commit -m "feat(persistence): add ApprovalRequest entity + repo, Verdict simulation query"
```

---

### Task 4: domain-core — ParamSimulation 순수 함수 + 단위 테스트

**Files:**
- Create: `backend/domain-core/src/main/java/kr/trendstage/domain/params/VerdictSnapshot.java`
- Create: `backend/domain-core/src/main/java/kr/trendstage/domain/params/SimulationSummary.java`
- Create: `backend/domain-core/src/main/java/kr/trendstage/domain/params/ParamSimulation.java`
- Test: `backend/domain-core/src/test/java/kr/trendstage/domain/ParamSimulationTest.java`

TDD로 진행 — 테스트 먼저 작성.

- [ ] **Step 1: 레코드 2개 작성**

```java
package kr.trendstage.domain.params;

import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.VerdictResult;

/** 과거 판정 1건의 재평가에 필요한 최소 스냅샷 — evidence_json에서 복원. */
public record VerdictSnapshot(VerdictResult result, ReachLevel reach, int distinctSubmitters, int distinctPlatforms) {}
```

```java
package kr.trendstage.domain.params;

/** ADM-600 시뮬레이션 결과 집계. */
public record SimulationSummary(int changed, int total, int missToHit, int hitToMiss, int reachChanged) {}
```

- [ ] **Step 2: 실패하는 테스트 작성**

```java
package kr.trendstage.domain;

import kr.trendstage.domain.params.ParamSimulation;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.params.SimulationSummary;
import kr.trendstage.domain.params.VerdictSnapshot;
import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.VerdictResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParamSimulationTest {
    private final ParameterSet defaults = ParameterSet.defaults(); // submitterTarget=20, hitThreshold=0.20

    @Test void 변동_없으면_모두_0() {
        // distinctSubmitters=12 → T=0.6 → HIT L3 (기존과 동일 파라미터)
        var snap = new VerdictSnapshot(VerdictResult.HIT, ReachLevel.L3, 12, 2);
        SimulationSummary r = ParamSimulation.run(List.of(snap), defaults);
        assertEquals(0, r.changed());
        assertEquals(1, r.total());
        assertEquals(0, r.missToHit());
        assertEquals(0, r.hitToMiss());
        assertEquals(0, r.reachChanged());
    }

    @Test void 임계값을_낮추면_MISS가_HIT으로_바뀐다() {
        // 원래 distinctSubmitters=3 → T=0.15 → MISS(threshold 0.20 기준)
        var snap = new VerdictSnapshot(VerdictResult.MISS, null, 3, 1);
        ParameterSet lowered = new ParameterSet(20, 0.10, 0.35, 0.55, 0.75,
                0.2, 0.5, 1.0, 1.5, 1.0, 0.6, 0.4, 0.2, 90, 2.0, 3.0); // hitThreshold만 0.10으로
        SimulationSummary r = ParamSimulation.run(List.of(snap), lowered);
        assertEquals(1, r.changed());
        assertEquals(1, r.missToHit());
        assertEquals(0, r.hitToMiss());
    }

    @Test void target을_높이면_HIT이_MISS로_바뀐다() {
        // 원래 distinctSubmitters=5, target=20 → T=0.25 → HIT L1
        var snap = new VerdictSnapshot(VerdictResult.HIT, ReachLevel.L1, 5, 1);
        ParameterSet raised = new ParameterSet(40, 0.20, 0.35, 0.55, 0.75,
                0.2, 0.5, 1.0, 1.5, 1.0, 0.6, 0.4, 0.2, 90, 2.0, 3.0); // target 40 → T=0.125 < 0.20
        SimulationSummary r = ParamSimulation.run(List.of(snap), raised);
        assertEquals(1, r.changed());
        assertEquals(1, r.hitToMiss());
    }

    @Test void target을_낮추면_reach가_상승한다() {
        // 원래 distinctSubmitters=12, target=20 → T=0.6 → L3
        var snap = new VerdictSnapshot(VerdictResult.HIT, ReachLevel.L3, 12, 2);
        ParameterSet lowered = new ParameterSet(10, 0.20, 0.35, 0.55, 0.75,
                0.2, 0.5, 1.0, 1.5, 1.0, 0.6, 0.4, 0.2, 90, 2.0, 3.0); // target 10 → T=1.0 → L4
        SimulationSummary r = ParamSimulation.run(List.of(snap), lowered);
        assertEquals(1, r.changed());
        assertEquals(1, r.reachChanged());
        assertEquals(0, r.missToHit());
        assertEquals(0, r.hitToMiss());
    }
}
```

- [ ] **Step 3: 테스트 실행해서 실패 확인 (컴파일 에러 — ParamSimulation 없음)**

Run: `cd backend && ./gradlew :domain-core:test --tests ParamSimulationTest`
Expected: FAIL (compilation error, `ParamSimulation` cannot be resolved)

- [ ] **Step 4: `ParamSimulation.java` 구현**

```java
package kr.trendstage.domain.params;

import kr.trendstage.domain.verdict.SubmissionSignal;
import kr.trendstage.domain.verdict.VerdictEngine;
import kr.trendstage.domain.verdict.VerdictOutcome;
import kr.trendstage.domain.verdict.VerdictResult;

import java.util.List;

/**
 * ADM-600 시뮬레이션 순수 함수. 운영이 쓰는 VerdictEngine을 그대로 재사용해 과거 판정을
 * 새 파라미터로 재평가하고 실제 결과와 비교한다 — 드리프트 방지(운영/시뮬레이션 같은 엔진).
 */
public final class ParamSimulation {
    private ParamSimulation() {}

    public static SimulationSummary run(List<VerdictSnapshot> snapshots, ParameterSet p) {
        int missToHit = 0, hitToMiss = 0, reachChanged = 0;
        for (VerdictSnapshot v : snapshots) {
            SubmissionSignal sig = new SubmissionSignal(v.distinctSubmitters(), v.distinctPlatforms());
            VerdictOutcome after = VerdictEngine.evaluate(sig, p);
            if (v.result() == VerdictResult.MISS && after.result() == VerdictResult.HIT) {
                missToHit++;
            } else if (v.result() == VerdictResult.HIT && after.result() == VerdictResult.MISS) {
                hitToMiss++;
            } else if (v.result() == VerdictResult.HIT && after.result() == VerdictResult.HIT
                    && v.reach() != after.reach()) {
                reachChanged++;
            }
        }
        int changed = missToHit + hitToMiss + reachChanged;
        return new SimulationSummary(changed, snapshots.size(), missToHit, hitToMiss, reachChanged);
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd backend && ./gradlew :domain-core:test --tests ParamSimulationTest`
Expected: PASS (4 tests)

- [ ] **Step 6: 커밋**

```bash
git add backend/domain-core/src/main/java/kr/trendstage/domain/params/VerdictSnapshot.java backend/domain-core/src/main/java/kr/trendstage/domain/params/SimulationSummary.java backend/domain-core/src/main/java/kr/trendstage/domain/params/ParamSimulation.java backend/domain-core/src/test/java/kr/trendstage/domain/ParamSimulationTest.java
git commit -m "feat(domain-core): add ParamSimulation pure function for ADM-600"
```

---

### Task 5: api-admin — DraftLockedException + 예외 핸들러 매핑

**Files:**
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/auth/DraftLockedException.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminApiExceptionHandler.java`

REVIEW 상태 드래프트 수정 시도 → 409. `DuplicateLoginIdException` 패턴을 그대로 따른다.

- [ ] **Step 1: 예외 클래스 작성**

```java
package kr.trendstage.apiadmin.auth;

public class DraftLockedException extends RuntimeException {
    public DraftLockedException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: 핸들러에 매핑 추가**

`AdminApiExceptionHandler.java`의 `DuplicateLoginIdException` 핸들러 바로 아래에 추가:

```java

    @ExceptionHandler(DraftLockedException.class)
    public ResponseEntity<Map<String, Object>> handle(DraftLockedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(409, e.getMessage()));
    }
```

import 문에 `kr.trendstage.apiadmin.auth.DraftLockedException` 추가.

- [ ] **Step 3: 컴파일 확인**

Run: `cd backend && ./gradlew :api-admin:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/auth/DraftLockedException.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminApiExceptionHandler.java
git commit -m "feat(api-admin): add DraftLockedException (409) for ADM-600"
```

---

### Task 6: api-admin — ParamStudioService

**Files:**
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/params/ParamStudioService.java`

`VerdictAdminService`와 동일하게 `api-admin` 모듈 내 전용 패키지에 둔다(별도 모듈 불필요 — 이 서비스는 컨트롤러 전용 오케스트레이션).

- [ ] **Step 1: 서비스 작성**

```java
package kr.trendstage.apiadmin.params;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final ParameterDraftRepository drafts;
    private final ApprovalRequestRepository approvals;
    private final VerdictRepository verdicts;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ParamStudioService(ParameterDraftRepository drafts, ApprovalRequestRepository approvals,
                               VerdictRepository verdicts, AuditLogService auditLogService,
                               ObjectMapper objectMapper, Clock clock) {
        this.drafts = drafts;
        this.approvals = approvals;
        this.verdicts = verdicts;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ParameterDraft getOrCreateActiveDraft(UUID actorId) {
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
        ParameterSet draftParams = toParameterSet(draft.getPayload());

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
        // ParameterSetProvider(scheduler 모듈 전용, 내용은 어차피 defaults() 고정)와 동일한 값.
        // APPLIED 드래프트를 실제로 반영하는 배선은 이번 스코프 밖(설계서 비범위 참고).
        return ParameterSet.defaults();
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

    private ParameterSet toParameterSet(String payloadJson) {
        try {
            var node = objectMapper.readTree(payloadJson);
            ParameterSet d = ParameterSet.defaults();
            return new ParameterSet(
                    node.get("submitterTarget").asInt(), node.get("hitThreshold").asDouble(),
                    d.bandL2, d.bandL3, d.bandL4,
                    d.mL1, d.mL2, d.mL3, d.mL4,
                    d.wRank1, d.wRank2, d.wRank3, d.wRankRest,
                    d.halflifeDays, d.tiAlpha, d.tiBeta);
        } catch (Exception e) {
            throw new IllegalStateException("payload 파싱 실패", e);
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
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :api-admin:compileJava`
Expected: BUILD SUCCESSFUL (Jackson `ObjectMapper`는 `spring-boot-starter-web`이 이미 전이 의존성으로 제공 — `@Bean` 등록 불필요, Spring Boot 자동설정이 빈으로 노출)

- [ ] **Step 3: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/params/ParamStudioService.java
git commit -m "feat(api-admin): add ParamStudioService (draft/simulate/approval-request)"
```

---

### Task 7: api-admin — ParamStudioController

**Files:**
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/ParamStudioController.java`

`MergeQueueController`의 `@AuthenticationPrincipal AdminPrincipal` 패턴을 따른다.

- [ ] **Step 1: 컨트롤러 작성**

```java
package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.apiadmin.params.ParamStudioService;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.persistence.entity.ParameterDraft;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;

/** ADM-600. 드래프트 편집·시뮬레이션·승인요청은 OPERATOR 이상(02 §1.1 paramDraft와 동일 권한). */
@RestController
@RequestMapping("/admin/params")
public class ParamStudioController {

    private final ParamStudioService service;
    private final ObjectMapper objectMapper;

    public ParamStudioController(ParamStudioService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public record UpdateDraftRequest(int submitterTarget, double hitThreshold) {}
    public record ApprovalRequestBody(String reason) {}
    public record SimulationSummaryResponse(int changed, int total, int missToHit, int hitToMiss, int reachChanged) {}
    public record ParameterDraftResponse(String draftId, String status,
                                          int submitterTarget, int currentSubmitterTarget,
                                          double hitThreshold, double currentHitThreshold,
                                          SimulationSummaryResponse simResult) {}

    @GetMapping("/draft")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ParameterDraftResponse getDraft(@AuthenticationPrincipal AdminPrincipal actor) {
        return toResponse(service.getOrCreateActiveDraft(actor.id()));
    }

    @PutMapping("/draft")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ParameterDraftResponse updateDraft(@RequestBody UpdateDraftRequest req,
                                               @AuthenticationPrincipal AdminPrincipal actor) {
        return toResponse(service.updateDraftValues(actor.id(), actor.role(), req.submitterTarget(), req.hitThreshold()));
    }

    @PostMapping("/draft/simulate")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ParameterDraftResponse simulate(@AuthenticationPrincipal AdminPrincipal actor) {
        return toResponse(service.simulate(actor.id(), actor.role()));
    }

    @PostMapping("/draft/request-approval")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ParameterDraftResponse requestApproval(@RequestBody(required = false) ApprovalRequestBody req,
                                                   @AuthenticationPrincipal AdminPrincipal actor) {
        String reason = req == null ? null : req.reason();
        return toResponse(service.requestApproval(actor.id(), actor.role(), reason));
    }

    private ParameterDraftResponse toResponse(ParameterDraft draft) {
        try {
            var payload = objectMapper.readTree(draft.getPayload());
            ParameterSet current = service.currentOperationalParams();
            SimulationSummaryResponse sim = null;
            if (draft.getSimResult() != null) {
                var s = objectMapper.readTree(draft.getSimResult());
                sim = new SimulationSummaryResponse(
                        s.get("changed").asInt(), s.get("total").asInt(),
                        s.get("missToHit").asInt(), s.get("hitToMiss").asInt(), s.get("reachChanged").asInt());
            }
            return new ParameterDraftResponse(
                    draft.getId().toString(), draft.getStatus().name(),
                    payload.get("submitterTarget").asInt(), current.submitterTarget,
                    payload.get("hitThreshold").asDouble(), current.hitThreshold,
                    sim);
        } catch (Exception e) {
            throw new IllegalStateException("드래프트 응답 변환 실패: " + draft.getId(), e);
        }
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :api-admin:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/ParamStudioController.java
git commit -m "feat(api-admin): add ParamStudioController (GET/PUT draft, simulate, request-approval)"
```

---

### Task 8: 백엔드 전체 빌드 검증

**Files:** 없음(검증 전용)

- [ ] **Step 1: 전체 빌드**

Run: `cd backend && ./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 전체 테스트**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL, `ParamSimulationTest` 4건 포함 전부 PASS

- [ ] **Step 3 (문제 발견 시): 수정 후 재검증, 통과하면 다음 태스크로**

---

### Task 9: frontend — 타입 교체 (`admin/src/api/types.ts`)

**Files:**
- Modify: `admin/src/api/types.ts:47-61`

기존 `ParameterPayload`/`SimulationResult`(등급 필드 포함)를 제거하고 백엔드 응답과 일치하는 타입으로 교체한다.

- [ ] **Step 1: 47~61번 줄 교체**

`export interface ParameterPayload { ... }`부터 `export interface SimulationResult { ... }`까지(현재 47~61번 줄) 전체를 아래로 교체:

```ts
export interface SimulationSummary {
  changed: number;
  total: number;
  missToHit: number;
  hitToMiss: number;
  reachChanged: number;
}
export interface ParameterDraftView {
  draftId: string;
  status: "DRAFT" | "REVIEW";
  submitterTarget: number;
  currentSubmitterTarget: number;
  hitThreshold: number;
  currentHitThreshold: number;
  simResult: SimulationSummary | null;
}
```

- [ ] **Step 2: 커밋**

```bash
git add admin/src/api/types.ts
git commit -m "feat(admin): replace ParameterPayload/SimulationResult with ParameterDraftView"
```

(다음 태스크들에서 다른 파일의 참조를 마저 고치므로, 이 시점에는 타입 에러가 나는 게 정상 — 태스크 12에서 한꺼번에 typecheck)

---

### Task 10: frontend — 훅 추가 (`admin/src/api/hooks.ts`)

**Files:**
- Modify: `admin/src/api/hooks.ts`

- [ ] **Step 1: import 줄 수정**

1번째 줄 근처의 타입 import에 `MergePreview` 뒤에 `ParameterDraftView`를 추가:

```ts
import type { AdminAccountSummary, AdminUserDetail, AuditEntry, MergeCandidate, MergePreview, ParameterDraftView, QueueSummary, VerdictListResponse } from "./types";
```

- [ ] **Step 2: 훅 4개 추가**

`fetchMergePreview` 정의 바로 아래에 추가:

```ts

export const useParamDraft = () =>
  useData<ParameterDraftView>(["admin", "param-draft"], "/admin/params/draft", fx.fxParamDraft);

export const updateParamDraft = (body: { submitterTarget: number; hitThreshold: number }) =>
  api.put<ParameterDraftView>("/admin/params/draft", body);

export const simulateParamDraft = () =>
  api.post<ParameterDraftView>("/admin/params/draft/simulate");

export const requestParamApproval = (reason: string) =>
  api.post<ParameterDraftView>("/admin/params/draft/request-approval", { reason });
```

- [ ] **Step 3: 커밋**

```bash
git add admin/src/api/hooks.ts
git commit -m "feat(admin): add useParamDraft/updateParamDraft/simulateParamDraft/requestParamApproval hooks"
```

---

### Task 11: frontend — fixture 교체 (`admin/src/fixtures.ts`)

**Files:**
- Modify: `admin/src/fixtures.ts:1-2, 65-83`

- [ ] **Step 1: import 줄 수정 (1~2번 줄)**

```ts
import type { AdminAccountSummary, AdminUserDetail, AuditEntry, ImminentItem, JudgedItem, MergeCandidate, MergePreview, ParameterDraftView, QueueSummary, SimulationSummary, VerdictListResponse } from "./api/types";
```

- [ ] **Step 2: `fxParams`/`fxSimulation` (65~83번 줄) 을 `fxParamDraft`로 교체**

```ts
export const fxParamDraft: ParameterDraftView = {
  draftId: "pd-17",
  status: "DRAFT",
  submitterTarget: 20,
  currentSubmitterTarget: 20,
  hitThreshold: 0.2,
  currentHitThreshold: 0.2,
  simResult: null,
};
```

- [ ] **Step 3: 커밋**

```bash
git add admin/src/fixtures.ts
git commit -m "feat(admin): replace fxParams/fxSimulation fixture with fxParamDraft"
```

---

### Task 12: frontend — `ParamStudioScreen.tsx` 재작성

**Files:**
- Modify: `admin/src/screens/ParamStudioScreen.tsx` (전체 교체)

로컬 state(`target`/`hit`)는 입력 중 값만 담고, "적용" 버튼을 눌러야 서버에 반영된다. 적용 성공 시 서버가 `simResult: null`로 리셋한 draft를 돌려주므로 그 응답을 그대로 캐시에 반영한다.

- [ ] **Step 1: 전체 파일 교체**

```tsx
import React, { useEffect, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { requestParamApproval, simulateParamDraft, updateParamDraft, useParamDraft } from "../api/hooks";
import { Card, Btn } from "../components/ui";
import { C } from "../theme";
import { useRole, CAN } from "../state/role";

/** ADM-600. 드래프트 → 시뮬 → 2인 승인 → 예약. 시뮬 없이는 승인요청 불가(안전장치). */
export default function ParamStudioScreen() {
  const { role } = useRole();
  const queryClient = useQueryClient();
  const { data: draft, isLoading } = useParamDraft();

  const [target, setTarget] = useState(20);
  const [hit, setHit] = useState(0.2);
  const [reason, setReason] = useState("");
  const [toast, setToast] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (draft) { setTarget(draft.submitterTarget); setHit(draft.hitThreshold); }
  }, [draft?.draftId, draft?.submitterTarget, draft?.hitThreshold]);

  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2600); };

  const dirty = draft != null && (target !== draft.submitterTarget || hit !== draft.hitThreshold);
  const locked = draft?.status === "REVIEW";

  const apply = async () => {
    setBusy(true);
    try {
      const next = await updateParamDraft({ submitterTarget: target, hitThreshold: hit });
      queryClient.setQueryData(["admin", "param-draft"], next);
      flash("드래프트에 적용됨 — 시뮬레이션이 필요합니다");
    } finally { setBusy(false); }
  };

  const runSimulation = async () => {
    setBusy(true);
    try {
      const next = await simulateParamDraft();
      queryClient.setQueryData(["admin", "param-draft"], next);
      flash("시뮬레이션 완료 (과거 180일 재판정)");
    } finally { setBusy(false); }
  };

  const requestApproval = async () => {
    setBusy(true);
    try {
      const next = await requestParamApproval(reason);
      queryClient.setQueryData(["admin", "param-draft"], next);
      flash("승인 요청 생성됨 (0/2) · 기본 예약 적용");
    } finally { setBusy(false); }
  };

  if (isLoading || !draft) return <div style={{ padding: 24, font: "500 13px Pretendard", color: C.faint }}>불러오는 중...</div>;

  const canEdit = CAN.paramDraft(role) && !locked;

  return (
    <div style={{ maxWidth: 1000 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12, padding: "14px 18px", borderRadius: 12, background: "rgba(223,164,0,0.1)", border: "1px solid rgba(223,164,0,0.28)", marginBottom: 16 }}>
        <span style={{ font: "600 12.5px Pretendard" }}>파라미터는 즉시 반영되지 않습니다 — 드래프트 → 시뮬레이션 → 2인 승인 → 예약 적용.</span>
        <span style={{ marginLeft: "auto", font: "500 11.5px ui-monospace, monospace", color: C.faint }}>
          드래프트 #{draft.draftId.slice(0, 8)} · {draft.status === "REVIEW" ? "승인 대기중" : "작성중"}
        </span>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
        <Card>
          <b style={{ fontSize: 13 }}>판정 파라미터</b>
          <div style={{ font: "500 11.5px Pretendard", color: C.faint, marginTop: 6, lineHeight: 1.6 }}>
            외부 지표 없이 제보 자체가 판정 근거입니다 — 서로 다른 후속 제보자 수만 씁니다(R1).
          </div>
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginTop: 18, paddingTop: 14, borderTop: `1px solid ${C.line}` }}>
            <span style={{ font: "600 12.5px Pretendard" }}>목표 제보자 수 (T=1.0 기준)</span>
            <span style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{draft.currentSubmitterTarget} →</span>
              <span style={{ font: "700 13px ui-monospace, monospace", color: target !== draft.currentSubmitterTarget ? C.peak : C.ink }}>{target}</span>
              <MiniBtn disabled={!canEdit} onClick={() => setTarget((v) => Math.max(1, v - 1))}>−</MiniBtn>
              <MiniBtn disabled={!canEdit} onClick={() => setTarget((v) => v + 1)}>+</MiniBtn>
            </span>
          </div>
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginTop: 14 }}>
            <span style={{ font: "600 12.5px Pretendard" }}>판정 임계값 (HIT)</span>
            <span style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{draft.currentHitThreshold.toFixed(2)} →</span>
              <span style={{ font: "700 13px ui-monospace, monospace", color: hit !== draft.currentHitThreshold ? C.peak : C.ink }}>{hit.toFixed(2)}</span>
              <MiniBtn disabled={!canEdit} onClick={() => setHit((v) => Math.max(0, Math.round((v - 0.01) * 100) / 100))}>−</MiniBtn>
              <MiniBtn disabled={!canEdit} onClick={() => setHit((v) => Math.round((v + 0.01) * 100) / 100)}>+</MiniBtn>
            </span>
          </div>
          <div style={{ marginTop: 14 }}>
            <Btn disabled={!canEdit || !dirty || busy} onClick={apply}>적용</Btn>
          </div>
        </Card>

        <Card>
          <b style={{ fontSize: 13 }}>시뮬레이션 — 과거 180일 재판정</b>
          {draft.simResult ? (
            <div style={{ marginTop: 16 }}>
              <div style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
                <span style={{ font: "700 32px Pretendard", letterSpacing: "-0.03em" }}>{draft.simResult.changed}</span>
                <span style={{ font: "500 12.5px Pretendard", color: C.sub }}>
                  / {draft.simResult.total}건 판정 변동 ({draft.simResult.total > 0 ? Math.round((draft.simResult.changed / draft.simResult.total) * 100) : 0}%)
                </span>
              </div>
              <div style={{ display: "flex", flexDirection: "column", gap: 9, marginTop: 18 }}>
                <SimRow label="MISS → HIT" count={draft.simResult.missToHit} tone="up" />
                <SimRow label="HIT → MISS" count={draft.simResult.hitToMiss} tone="down" />
                <SimRow label="HIT 확산 레벨 변경" count={draft.simResult.reachChanged} tone="neutral" />
              </div>
            </div>
          ) : (
            <div style={{ padding: "26px 0", textAlign: "center", font: "500 13px Pretendard", color: C.faint, lineHeight: 1.6 }}>
              시뮬레이션을 돌리기 전에는 승인 요청을 보낼 수 없습니다.
            </div>
          )}
        </Card>
      </div>

      <div style={{ display: "flex", gap: 8, marginTop: 12, alignItems: "center" }}>
        <Btn disabled={!CAN.paramDraft(role) || locked || dirty || busy} title={dirty ? "먼저 적용하세요" : undefined} onClick={runSimulation}>시뮬레이션 실행</Btn>
        <input
          placeholder="승인 요청 사유"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          disabled={!CAN.paramDraft(role) || locked}
          style={{ padding: "8px 10px", borderRadius: 8, border: `1px solid ${C.line}`, font: "500 12px Pretendard", width: 220 }}
        />
        <Btn tone="primary" disabled={!draft.simResult || locked || !CAN.paramDraft(role) || busy} title={!draft.simResult ? "시뮬레이션 먼저" : undefined} onClick={requestApproval}>
          승인 요청 (예약·비소급)
        </Btn>
        <span style={{ marginLeft: "auto", font: "500 11.5px Pretendard", color: C.faint }}>
          적용 승인은 {CAN.paramApply(role) ? "가능(ADMIN 2인)" : "ADMIN 2인 필요"}
        </span>
      </div>

      {toast && <div style={{ position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)", background: C.ink, color: "#fff", padding: "12px 18px", borderRadius: 10, font: "500 12.5px Pretendard" }}>{toast}</div>}
    </div>
  );
}

function SimRow({ label, count, tone }: { label: string; count: number; tone: "up" | "down" | "neutral" }) {
  const col = tone === "up" ? C.rising : tone === "down" ? C.fading : C.ink;
  const bg = tone === "up" ? "rgba(27,158,82,0.09)" : tone === "down" ? "rgba(216,72,60,0.08)" : "rgba(20,19,15,0.04)";
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10, padding: "11px 13px", borderRadius: 10, background: bg }}>
      <span style={{ font: "600 12.5px Pretendard", color: col }}>{label}</span>
      <span style={{ marginLeft: "auto", font: "700 13px ui-monospace, monospace", color: col }}>{count}건</span>
    </div>
  );
}

function MiniBtn({ children, onClick, disabled }: { children: React.ReactNode; onClick: () => void; disabled?: boolean }) {
  return (
    <button onClick={onClick} disabled={disabled} style={{ width: 24, height: 24, borderRadius: 6, border: "1px solid rgba(20,19,15,0.12)", background: "#fff", cursor: disabled ? "not-allowed" : "pointer", font: "600 13px Pretendard", color: C.ink, opacity: disabled ? 0.5 : 1 }}>{children}</button>
  );
}
```

- [ ] **Step 2: 커밋**

```bash
git add admin/src/screens/ParamStudioScreen.tsx
git commit -m "feat(admin): wire ParamStudioScreen to real ADM-600 API"
```

---

### Task 13: frontend — typecheck 검증

**Files:** 없음(검증 전용)

- [ ] **Step 1: typecheck 실행**

Run: `cd admin && npx tsc --noEmit`
Expected: 에러 없음. `C.rising`/`C.fading`이 `theme.ts`에 이미 존재하는지 먼저 확인(ADM-600 이전 fixture에서도 쓰던 이름이라 존재할 것) — 없으면 `theme.ts`의 실제 색상 키로 교체.

- [ ] **Step 2 (에러 발견 시): 수정 후 재검증**

---

### Task 14: end-to-end 브라우저 검증

**Files:** 없음(검증 전용)

- [ ] **Step 1: 백엔드 기동 확인**

로컬 dev DB(도커) 기동 상태에서 `./gradlew :app:bootRun` 또는 기존 dev 실행 방식으로 백엔드 구동.

- [ ] **Step 2: 프론트 dev 서버 기동 + 브라우저 접속**

`VITE_USE_FIXTURES=false`로 admin dev 서버 기동 후 ADM-600 화면 진입.

- [ ] **Step 3: 시나리오 검증**

1. 화면 진입 시 드래프트가 자동 생성/조회되는지 확인(네트워크 탭에서 `GET /admin/params/draft` 200)
2. `+`/`−`로 값 변경 → "적용" 클릭 → `PUT /admin/params/draft` 200, `simResult`가 화면에서 사라지는지(리셋) 확인
3. "시뮬레이션 실행" 클릭 → `POST /admin/params/draft/simulate` 200, 결과 카드에 실제 변동 건수 표시 확인
4. 사유 입력 후 "승인 요청" 클릭 → `POST /admin/params/draft/request-approval` 200, 상태가 "승인 대기중"으로 바뀌고 이후 입력 필드들이 비활성화(REVIEW 락)되는지 확인
5. `psql`로 `parameter_drafts`/`approval_requests`에 실제 행이 쌓였는지, `admin_audit_log`에 `PARAM_DRAFT_UPDATE`/`PARAM_SIMULATE`/`PARAM_APPROVAL_REQUEST` 3개 액션이 기록됐는지 확인

- [ ] **Step 4 (문제 발견 시): 원인 파악 후 관련 태스크로 돌아가 수정, 재검증**
