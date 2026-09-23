# SP2 병합 무결성 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 병합이 판정된 항목을 건드려 이중 점수를 내는 경로를 막고, 병합 결정을 행 잠금·멱등키로 한 번만 실행되게 하며, 병합된 항목 이름으로 들어온 제보가 승자에 합류하게 한다.

**Architecture:** `merge` 모듈에 `MergeGuard`(병합 가능 판정)와 `MergeConflictException`을 두고, `MergeService`가 두 항목을 id 오름차순으로 `FOR UPDATE` 잠근 뒤 가드를 건다. 큐 결정은 api-admin의 새 `MergeDecisionService`가 큐 행 잠금 → `Idempotency-Key` 확인 → 결정 실행을 한 트랜잭션으로 묶고, `merge_queue.decision_key UNIQUE`(V29)가 멱등을 DB로 보장한다. 병합된 항목 따라가기와 동시 첫 제보 합류는 persistence 모듈의 `TrendItemLookup`·`TrendItemCreator`가 맡아 제보·워치·시딩이 공유한다.

**Tech Stack:** Java 17, Spring Boot 3.3.5, Spring Data JPA, Flyway, PostgreSQL 16 + pgvector, Testcontainers, JUnit 5/AssertJ, MockMvc; React 18 + Vite + TypeScript.

**Spec:** `docs/superpowers/specs/2026-09-21-sp2-merge-integrity-design.md`

---

## 전제 (모든 태스크 공통)

- 브랜치: `sp2-merge-integrity`(SP1 브랜치 `sp1-verdict-pipeline` 위). 시작 전 `git -C /d/project/TRD branch --show-current`로 확인한다. 다른 브랜치로 전환하지 않는다.
- 작업 트리의 미추적 사용자 파일(`06-pitch-deck-outline.md`, `AGENTS.md`, `hs_err_*.log`, `replay_*.log`, `backend/persistence/src/main/resources/db/seed/merge_queue_test_data.sql`)은 건드리지 않는다. **`git add -A`·`git add .` 금지.** 항상 파일명을 지정한다. `git stash`의 기존 항목을 pop하지 않는다.
- `backend/app/.env`는 사용자 소유 파일이다. **읽기·수정 금지.**
- 백엔드 명령은 `backend/`에서 `./gradlew …`(Bash 도구). 통합 테스트는 Docker가 실행 중이어야 한다(`docker info`). 결과는 `app/build/test-results/test/*.xml`에서 확인한다.
- 이미 적용된 Flyway 마이그레이션(V1~V28)은 **절대 수정하지 않는다.** 새 마이그레이션은 V29 하나.
- 통합 테스트는 DB 하나를 모든 테스트 클래스가 공유한다. 항목·키는 항상 `Fixtures`의 랜덤 값을 쓰고, `JudgeService.closeDue`처럼 전역 상태를 바꾸는 호출은 쓰지 않는다.
- 커밋 메시지 끝에 반드시 다음 줄:
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  ```
- 순서: Task 1~8 백엔드(API 먼저) → Task 9 계약 → Task 10 콘솔 → Task 11 문서 → Task 12 최종 검증·PR.

## 파일 구조

| 파일 | 책임 | 태스크 |
|---|---|---|
| `backend/persistence/src/main/resources/db/migration/V29__sp2_merge_integrity.sql` | `decision_key` UNIQUE, 처리 대기 행만 부분 UNIQUE | 1 |
| `backend/persistence/.../entity/MergeQueueEntry.java` | `decisionKey`, `resolve(…, key)` | 1 |
| `backend/persistence/.../repo/MergeQueueRepository.java` | `findByIdForUpdate`, `findByDecisionKey` | 1 |
| `backend/app/src/test/java/kr/trendstage/support/Fixtures.java` | 병합 테스트 헬퍼 | 1, 4 |
| `backend/app/src/test/java/kr/trendstage/merge/SchemaV29Test.java` | V29 제약 | 1 |
| `backend/domain-core/.../verdict/DeadlineWindow.java` | `afterMerge`, 상수 | 2 |
| `backend/domain-core/src/test/.../DeadlineWindowTest.java` | 순수 테스트 | 2 |
| `backend/api-admin/.../verdict/VerdictAdminService.java` | 상한을 공용 상수로 | 2 |
| `backend/merge/.../MergeComputation.java` | 시딩 제외·동순위 선점 순위 | 3 |
| `backend/merge/src/test/.../MergeComputationTest.java` | 순수 테스트 | 3 |
| `backend/merge/.../MergeConflictException.java` | 409 사유 | 4 |
| `backend/merge/.../MergeGuard.java` | 병합 가능 판정 | 4 |
| `backend/merge/.../MergeService.java` | 잠금·가드·마감 보장·미리보기 | 4 |
| `backend/app/src/test/java/kr/trendstage/merge/MergeGuardTest.java`, `MergeRecomputeTest.java` | 가드·재계산 | 4 |
| `backend/app/src/test/java/kr/trendstage/merge/MergeConcurrencyTest.java` | 동시성 | 5 |
| `backend/merge/.../ClusterMergeDecider.java` | 자동 경로의 결정 + 확인 표시 트랜잭션 | 6 |
| `backend/merge/.../ClusterMergeCandidateService.java` | 스캔 범위, 결과 집계 | 6 |
| `backend/api-admin/.../web/BatchJobController.java` | 감사 detail 필드 | 6 |
| `backend/app/src/test/java/kr/trendstage/merge/ClusterMergeGuardTest.java` | 자동 경로 가드 | 6 |
| `backend/api-admin/.../merge/MergeDecisionService.java` | 큐 결정·멱등키 | 7 |
| `backend/api-admin/.../merge/IdempotencyKeyRequiredException.java`, `IdempotencyKeyMismatchException.java` | 400·422 | 7 |
| `backend/api-admin/.../web/Problems.java` | 오류 본문 생성 | 7 |
| `backend/api-admin/.../web/AdminApiExceptionHandler.java` | 매핑 | 7 |
| `backend/api-admin/.../web/MergeQueueController.java` | 헤더·응답·미리보기 | 3, 4, 7 |
| `backend/app/src/test/java/kr/trendstage/merge/MergeIdempotencyTest.java`, `MergeQueueApiTest.java` | 멱등·API | 7 |
| `backend/persistence/.../trend/TrendItemLookup.java`, `TrendItemCreator.java` | 병합 체인 따라가기, 동시 첫 제보 합류 | 8 |
| `backend/api-public/.../service/SubmissionService.java`, `WatchService.java` | 사용처 | 8 |
| `backend/api-admin/.../seed/AdminSeedService.java` | 사용처 | 8 |
| `backend/app/src/test/java/kr/trendstage/merge/TombstoneJoinTest.java` | 합류 | 8 |
| `backend/api-spec/openapi.yaml` | 계약 | 9 |
| `admin/src/api/client.ts`, `hooks.ts`, `types.ts`, `admin/src/fixtures.ts`, `admin/src/screens/MergeQueueScreen.tsx`, `admin/src/screens/BatchJobsScreen.tsx` | 콘솔 | 10 |
| `CLAUDE.md`, `02`·`03`·`05` 문서, 상위 스펙 | 문서 | 11 |

(`…`는 `src/main/java/kr/trendstage/<모듈 패키지>`의 줄임. 예: `backend/merge/.../MergeService.java` = `backend/merge/src/main/java/kr/trendstage/merge/MergeService.java`, `backend/persistence/.../entity/X.java` = `backend/persistence/src/main/java/kr/trendstage/persistence/entity/X.java`, `backend/api-admin/.../web/X.java` = `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/X.java`.)

---

### Task 1: V29 스키마 + 큐 엔티티·리포지토리

**Files:**
- Create: `backend/persistence/src/main/resources/db/migration/V29__sp2_merge_integrity.sql`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/MergeQueueEntry.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/MergeQueueRepository.java`
- Modify: `backend/app/src/test/java/kr/trendstage/support/Fixtures.java`
- Test: `backend/app/src/test/java/kr/trendstage/merge/SchemaV29Test.java`

- [ ] **Step 1: 테스트 헬퍼 추가**

`Fixtures.java`의 `private UUID insertSubmission(...)` 바로 위에 추가:

```java
    // ── 병합(SP2) ─────────────────────────────────────────────

    /** 처리 대기 병합 큐 행. */
    public UUID mergeQueueEntry(UUID newItem, UUID oldItem, double similarity) {
        return jdbc.queryForObject("INSERT INTO merge_queue (new_trend_item_id, old_trend_item_id, similarity) "
                + "VALUES (?, ?, ?) RETURNING id", UUID.class, newItem, oldItem, similarity);
    }

    public String queueStatus(UUID queueId) {
        return jdbc.queryForObject("SELECT status::text FROM merge_queue WHERE id = ?", String.class, queueId);
    }

    /** loser를 survivor로 병합된 tombstone으로 만든다(제보는 옮기지 않는다 — 상태만). */
    public void merged(UUID loser, UUID survivor) {
        jdbc.update("UPDATE trend_items SET state = 'MERGED', merged_into = ? WHERE id = ?", survivor, loser);
    }

    public void setState(UUID itemId, String state) {
        jdbc.update("UPDATE trend_items SET state = ?::trend_state WHERE id = ?", state, itemId);
    }

    /** 상태는 그대로 두고 현행 판정 행만 만든다(상태·판정 행 불일치 가드 테스트용). */
    public void verdictRow(UUID itemId) {
        jdbc.update("INSERT INTO verdicts (trend_item_id, result, score_t, judged_at, evidence_json) "
                + "VALUES (?, 'MISS', 0.1, now(), '{}'::jsonb)", itemId);
    }

    public String key(UUID itemId) {
        return jdbc.queryForObject("SELECT normalized_key FROM trend_items WHERE id = ?", String.class, itemId);
    }

    public UUID itemOf(UUID submissionId) {
        return jdbc.queryForObject("SELECT trend_item_id FROM submissions WHERE id = ?", UUID.class, submissionId);
    }

    /** 선점 순위(뷰). 순위가 없으면(시딩·VOID) null. */
    public Integer orderRank(UUID submissionId) {
        List<Integer> r = jdbc.queryForList("SELECT order_rank FROM submission_order_rank WHERE id = ?",
                Integer.class, submissionId);
        return r.isEmpty() ? null : r.get(0);
    }

    public Instant deadlineOverride(UUID itemId) {
        Timestamp t = jdbc.queryForObject("SELECT judgment_deadline_override FROM trend_items WHERE id = ?",
                Timestamp.class, itemId);
        return t == null ? null : t.toInstant();
    }

    public void setDeadlineOverride(UUID itemId, Instant at) {
        jdbc.update("UPDATE trend_items SET judgment_deadline_override = ? WHERE id = ?", Timestamp.from(at), itemId);
    }

    public Instant voidedAt(UUID submissionId) {
        Timestamp t = jdbc.queryForObject("SELECT voided_at FROM submissions WHERE id = ?", Timestamp.class, submissionId);
        return t == null ? null : t.toInstant();
    }

    public Instant mergeCheckedAt(UUID itemId) {
        Timestamp t = jdbc.queryForObject("SELECT merge_checked_at FROM trend_items WHERE id = ?", Timestamp.class, itemId);
        return t == null ? null : t.toInstant();
    }

    public int auditCount(String action, UUID targetId) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_log WHERE action = ? AND target_id = ?",
                Integer.class, action, targetId);
    }
```

파일 상단 import에 `java.util.List`가 없으면 추가한다.

- [ ] **Step 2: 실패하는 테스트 작성**

```java
package kr.trendstage.merge;

import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V29: 처리된 항목은 다시 큐에 들어갈 수 있고(처리 대기만 1건), 멱등키는 전역 유일하다. */
class SchemaV29Test extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void resolvedItemCanBeQueuedAgainButOnlyOnePendingPerNewItem() {
        UUID a = fx.item(T0), b = fx.item(T0), c = fx.item(T0);
        UUID first = fx.mergeQueueEntry(a, b, 0.80);
        jdbc.update("UPDATE merge_queue SET status = 'SKIPPED' WHERE id = ?", first);

        UUID again = fx.mergeQueueEntry(a, c, 0.80);   // V28까지는 전역 UNIQUE라 실패했다

        assertThat(fx.queueStatus(again)).isEqualTo("PENDING");
        assertThatThrownBy(() -> fx.mergeQueueEntry(a, b, 0.80))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void decisionKeyIsGloballyUnique() {
        UUID q1 = fx.mergeQueueEntry(fx.item(T0), fx.item(T0), 0.80);
        UUID q2 = fx.mergeQueueEntry(fx.item(T0), fx.item(T0), 0.80);
        String key = "k-" + UUID.randomUUID();
        jdbc.update("UPDATE merge_queue SET decision_key = ? WHERE id = ?", key, q1);

        assertThatThrownBy(() -> jdbc.update("UPDATE merge_queue SET decision_key = ? WHERE id = ?", key, q2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 3: 실패 확인**

Run (in `backend/`): `./gradlew :app:test --tests kr.trendstage.merge.SchemaV29Test`
Expected: FAIL — `resolvedItemCanBeQueuedAgain…`는 두 번째 삽입에서 `merge_queue_one_open_per_new` 위반, `decisionKeyIsGloballyUnique`는 `decision_key` 컬럼 없음.

- [ ] **Step 4: 마이그레이션 작성**

`V29__sp2_merge_integrity.sql`:

```sql
-- V29 · SP2 병합 무결성
-- 1) 결정 요청의 멱등키(Idempotency-Key). 같은 키 재요청은 이전 결과를 돌려준다 — 코드 가드가 아니라 DB 제약.
ALTER TABLE merge_queue ADD COLUMN decision_key VARCHAR(80);
ALTER TABLE merge_queue ADD CONSTRAINT merge_queue_decision_key_key UNIQUE (decision_key);
COMMENT ON COLUMN merge_queue.decision_key IS '결정 요청의 Idempotency-Key. 처리 대기 행과 V29 이전 처리 행은 NULL.';

-- 2) "새 항목당 1건"은 처리 대기 행에만. 전역 UNIQUE는 한 번 처리된 항목의 재적재를 영구히 막았다.
ALTER TABLE merge_queue DROP CONSTRAINT merge_queue_one_open_per_new;
CREATE UNIQUE INDEX merge_queue_one_pending_per_new ON merge_queue (new_trend_item_id) WHERE status = 'PENDING';
```

- [ ] **Step 5: 엔티티 수정**

`MergeQueueEntry.java`에서 `resolvedBy` 필드 다음에 추가:

```java
    @Column(name = "decision_key", length = 80)
    private String decisionKey;
```

기존 `resolve(...)`를 다음 두 메서드로 교체한다(3인자 버전은 Task 7에서 호출부가 사라지면 지운다):

```java
    /** 결정 기록. key는 결정 요청의 Idempotency-Key — 배치가 정리하는 행은 null. */
    public void resolve(MergeQueueStatus status, UUID resolvedBy, Instant at, String key) {
        this.status = status;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = at;
        this.decisionKey = key;
    }

    public void resolve(MergeQueueStatus status, UUID resolvedBy, Instant at) {
        resolve(status, resolvedBy, at, null);
    }
```

getter 추가: `public String getDecisionKey() { return decisionKey; }`

- [ ] **Step 6: 리포지토리 수정**

`MergeQueueRepository.java` 전체를 교체:

```java
package kr.trendstage.persistence.repo;

import jakarta.persistence.LockModeType;
import kr.trendstage.persistence.entity.MergeQueueEntry;
import kr.trendstage.persistence.type.MergeQueueStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MergeQueueRepository extends JpaRepository<MergeQueueEntry, UUID> {
    List<MergeQueueEntry> findByStatusOrderByCreatedAtAsc(MergeQueueStatus status);

    boolean existsByNewTrendItemId(UUID newTrendItemId);

    Optional<MergeQueueEntry> findByNewTrendItemId(UUID newTrendItemId);

    /** 결정 요청이 큐 행을 잠근다 — 검수자 둘이 동시에 같은 후보를 처리하지 못하게(SP2). 잠금 순서: 큐 행 → 항목. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from MergeQueueEntry q where q.id = :id")
    Optional<MergeQueueEntry> findByIdForUpdate(@Param("id") UUID id);

    Optional<MergeQueueEntry> findByDecisionKey(String decisionKey);
}
```

`findByNewTrendItemId`는 V29 이후 한 항목에 여러 행(처리된 것 + 대기 1건)이 있을 수 있어 결과가 2건이면 예외가 난다. `grep -rn "findByNewTrendItemId" backend --include=*.java`로 호출부를 확인하고, 호출부가 없으면 메서드를 지운다. 호출부가 있으면 그대로 두고 보고서에 적는다.

- [ ] **Step 7: 통과 확인**

Run: `./gradlew :app:test --tests kr.trendstage.merge.SchemaV29Test --tests kr.trendstage.ContextSmokeTest`
Expected: PASS (3 tests). `ddl-auto: validate`가 `decision_key` 매핑을 검증한다.

- [ ] **Step 8: 커밋**

```bash
git add backend/persistence/src/main/resources/db/migration/V29__sp2_merge_integrity.sql backend/persistence/src/main/java/kr/trendstage/persistence/entity/MergeQueueEntry.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/MergeQueueRepository.java backend/app/src/test/java/kr/trendstage/support/Fixtures.java backend/app/src/test/java/kr/trendstage/merge/SchemaV29Test.java
git commit -m "feat(persistence): V29 병합 큐 멱등키 UNIQUE, 처리 대기 행만 새 항목당 1건

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: 병합 후 최소 관측 기간 — 순수 함수

**Files:**
- Modify: `backend/domain-core/src/main/java/kr/trendstage/domain/verdict/DeadlineWindow.java`
- Modify: `backend/domain-core/src/test/java/kr/trendstage/domain/DeadlineWindowTest.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/verdict/VerdictAdminService.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`DeadlineWindowTest.java` 클래스 끝(마지막 `}` 앞)에 추가(필요한 import: `java.time.Duration`, `java.util.Optional`이 없으면 추가):

```java
    // ── 병합 후 최소 관측 보장(SP2 K7) ─────────────────────────
    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    @Test void 병합으로_마감이_과거가_되면_병합시점_3일후_상한_D21() {
        // 새 최초 제보 T0, 지금 T0+20일 → 자연 마감 T0+14일(과거). 목표 T0+23일이지만 상한 T0+21일.
        var d = DeadlineWindow.afterMerge(T0, null, T0.plus(Duration.ofDays(20)));
        assertEquals(Optional.of(new DeadlineWindow.MergeDeadline(T0.plus(Duration.ofDays(21)), true)), d);
    }

    @Test void 병합으로_마감이_너무_임박하면_3일을_보장() {
        var d = DeadlineWindow.afterMerge(T0, null, T0.plus(Duration.ofDays(12)));
        assertEquals(Optional.of(new DeadlineWindow.MergeDeadline(T0.plus(Duration.ofDays(15)), false)), d);
    }

    @Test void 마감이_충분히_남았으면_바꾸지_않는다() {
        assertEquals(Optional.empty(), DeadlineWindow.afterMerge(T0, null, T0.plus(Duration.ofDays(5))));
    }

    @Test void 기존_연장이_더_늦으면_병합이_당기지_않는다() {
        Instant override = T0.plus(Duration.ofDays(30));
        assertEquals(Optional.empty(), DeadlineWindow.afterMerge(T0, override, T0.plus(Duration.ofDays(12))));
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :domain-core:test --tests kr.trendstage.domain.DeadlineWindowTest`
Expected: 컴파일 실패 — `afterMerge`, `MergeDeadline` 없음.

- [ ] **Step 3: 구현**

`DeadlineWindow.java`에서 `private static final int JUDGE_WINDOW_DAYS = 14;` 줄을 다음으로 교체하고, `fallsWithin` 다음에 메서드를 추가한다(`java.util.Optional` import 추가):

```java
    private static final int JUDGE_WINDOW_DAYS = 14;

    /** 관측 마감의 절대 상한: 최초 제보 + 21일(기본 14일 + 유예 연장 최대 7일). ADM-200 연장과 병합 보장이 공유한다. */
    public static final int MAX_DEADLINE_DAYS = 21;

    /** 병합으로 마감이 당겨졌을 때 병합 시점부터 보장하는 최소 관측 일수(SP2 K7). 운영 안전장치라 파라미터가 아니다. */
    public static final int MERGE_MIN_OBSERVATION_DAYS = 3;
```

```java
    /** capped = D+21 상한 때문에 최소 관측 3일을 채우지 못함. */
    public record MergeDeadline(Instant override, boolean capped) {}

    /**
     * 병합 뒤 새 최초 제보 시각 기준으로 마감이 병합 시점 + 3일보다 이르면 새 override를 돌려준다.
     * 병합은 마감을 당기지 않는다 — 이미 충분하거나 기존 연장이 더 늦으면 empty.
     * 상한(새 최초 제보 + 21일)이 최소 관측보다 우선한다.
     */
    public static Optional<MergeDeadline> afterMerge(Instant newFirstSeen, Instant currentOverride, Instant now) {
        Instant current = effectiveDeadline(newFirstSeen, currentOverride);
        Instant floor = now.plus(Duration.ofDays(MERGE_MIN_OBSERVATION_DAYS));
        Instant ceiling = newFirstSeen.plus(Duration.ofDays(MAX_DEADLINE_DAYS));
        boolean capped = ceiling.isBefore(floor);
        Instant target = capped ? ceiling : floor;
        if (!current.isBefore(target)) return Optional.empty();
        return Optional.of(new MergeDeadline(target, capped));
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :domain-core:test --tests kr.trendstage.domain.DeadlineWindowTest`
Expected: PASS.

- [ ] **Step 5: ADM-200 연장 상한을 공용 상수로**

`VerdictAdminService.java`의 `extendGrace`에서:

```java
        Instant ceiling = originalDeadline.plus(Duration.ofDays(MAX_GRACE_DAYS));
```

를 다음으로 교체하고, import에 `kr.trendstage.domain.verdict.DeadlineWindow`를 추가한다(`MAX_GRACE_DAYS`는 요청당 연장 일수 검증에 계속 쓴다):

```java
        // 상한 = 최초 제보 + 21일. 병합 후 최소 관측 보장(MergeService)과 같은 상수를 쓴다.
        Instant ceiling = item.getFirstSeenAt().plus(Duration.ofDays(DeadlineWindow.MAX_DEADLINE_DAYS));
```

- [ ] **Step 6: 확인 + 커밋**

Run: `./gradlew :domain-core:test :api-admin:compileJava`
Expected: `BUILD SUCCESSFUL`.

```bash
git add backend/domain-core/src/main/java/kr/trendstage/domain/verdict/DeadlineWindow.java backend/domain-core/src/test/java/kr/trendstage/domain/DeadlineWindowTest.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/verdict/VerdictAdminService.java
git commit -m "feat(domain): 병합 후 최소 관측 3일 보장(상한 D+21) 순수 함수

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: 미리보기 선점 순위를 뷰와 같은 규칙으로

**Files:**
- Modify: `backend/merge/src/main/java/kr/trendstage/merge/MergeComputation.java`
- Modify: `backend/merge/src/test/java/kr/trendstage/merge/MergeComputationTest.java`
- Modify: `backend/merge/src/main/java/kr/trendstage/merge/MergeService.java` (`toInputs`만)
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/MergeQueueController.java` (`OrderEntry`, `buildOrderPreview`)

- [ ] **Step 1: 실패하는 테스트 작성**

`MergeComputationTest.java`의 `input(...)` 헬퍼를 교체하고 헬퍼 하나를 추가한다:

```java
    private static MergeComputation.SubmissionInput input(UUID user, String rawInput, Instant createdAt) {
        return new MergeComputation.SubmissionInput(UUID.randomUUID(), user, rawInput, createdAt, false);
    }

    private static MergeComputation.SubmissionInput seed(UUID user, Instant createdAt) {
        return new MergeComputation.SubmissionInput(UUID.randomUUID(), user, "시딩", createdAt, true);
    }
```

클래스 끝에 추가:

```java
    @Test void 선점_순위는_시딩을_빼고_동시각은_같은_순위() {
        Instant t0 = Instant.parse("2026-08-01T00:00:00Z");
        var s = seed(UUID.randomUUID(), t0);                                   // 가장 이르지만 시딩
        var a = input(UUID.randomUUID(), "x", t0.plus(1, ChronoUnit.HOURS));
        var b = input(UUID.randomUUID(), "x", t0.plus(1, ChronoUnit.HOURS));   // a와 동시각
        var c = input(UUID.randomUUID(), "x", t0.plus(2, ChronoUnit.HOURS));

        var order = MergeComputation.computeCombinedOrder(List.of(c, s, b, a));

        assertEquals(4, order.size());
        assertEquals(Integer.valueOf(1), order.get(0).rank());
        assertEquals(Integer.valueOf(1), order.get(1).rank());
        assertEquals(Integer.valueOf(3), order.get(2).rank());   // RANK: 1,1,3
        assertEquals(c.submissionId(), order.get(2).submissionId());
        assertNull(order.get(3).rank());                          // 시딩은 순위 없이 뒤에
        assertTrue(order.get(3).seed());
        assertEquals(s.submissionId(), order.get(3).submissionId());
    }
```

기존 `선점_순위는_제보_시각_오름차순` 테스트에서 `rank()`를 `int`와 비교하는 `assertEquals(1, …rank())` 형태가 있으면 `assertEquals(Integer.valueOf(1), …rank())`로 바꾼다(`Integer`로 바뀌어 오버로드가 모호해진다).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :merge:test --tests kr.trendstage.merge.MergeComputationTest`
Expected: 컴파일 실패 — `SubmissionInput`에 5번째 인자 없음, `OrderComputed.seed()` 없음.

- [ ] **Step 3: 구현**

`MergeComputation.java`의 두 record와 `computeCombinedOrder`를 교체한다:

```java
    public record SubmissionInput(UUID submissionId, UUID userId, String rawInput, Instant createdAt, boolean seed) {}

    /** rank는 시딩이면 null. */
    public record OrderComputed(UUID submissionId, UUID userId, Integer rank, boolean seed) {}
```

```java
    /**
     * 선점 순위 — submission_order_rank 뷰(V28)와 같은 규칙: 시딩 제외, created_at 오름차순,
     * 동시각은 같은 순위(RANK, 1·1·3). 시딩은 순위 없이 뒤에 붙인다(미리보기 표시용).
     */
    public static List<OrderComputed> computeCombinedOrder(List<SubmissionInput> active) {
        List<SubmissionInput> ranked = active.stream()
                .filter(s -> !s.seed())
                .sorted(Comparator.comparing(SubmissionInput::createdAt))
                .toList();
        List<OrderComputed> result = new ArrayList<>();
        int rank = 0;
        Instant previous = null;
        for (int i = 0; i < ranked.size(); i++) {
            SubmissionInput s = ranked.get(i);
            if (previous == null || !s.createdAt().equals(previous)) {
                rank = i + 1;
                previous = s.createdAt();
            }
            result.add(new OrderComputed(s.submissionId(), s.userId(), rank, false));
        }
        active.stream()
                .filter(SubmissionInput::seed)
                .sorted(Comparator.comparing(SubmissionInput::createdAt))
                .forEach(s -> result.add(new OrderComputed(s.submissionId(), s.userId(), null, true)));
        return result;
    }
```

- [ ] **Step 4: 호출부 수정**

`MergeService.java`의 `toInputs`:

```java
    private static List<MergeComputation.SubmissionInput> toInputs(List<Submission> subs) {
        return subs.stream()
                .map(s -> new MergeComputation.SubmissionInput(
                        s.getId(), s.getUserId(), s.getRawInput(), s.getCreatedAt(), s.isSeed()))
                .toList();
    }
```

`MergeQueueController.java`:
- `public record OrderEntry(String handle, Integer rankBefore, int rankAfter) {}` → `public record OrderEntry(String handle, Integer rankBefore, Integer rankAfter, boolean seed) {}`
- `preview()`의 `new OrderEntry(handleOf(o.userId()), beforeRank.get(o.submissionId()), o.rank())` → `new OrderEntry(handleOf(o.userId()), beforeRank.get(o.submissionId()), o.rank(), o.seed())`
- `buildOrderPreview`를 교체하고 import `kr.trendstage.merge.MergeComputation`을 추가한다:

```java
    /** 목록 카드의 병합 후 순위 칩 — 미리보기와 같은 규칙(시딩 제외, 동순위). */
    private List<String> buildOrderPreview(UUID newTrendItemId, UUID oldTrendItemId) {
        List<Submission> combined = new ArrayList<>();
        combined.addAll(submissions.findByTrendItemIdAndResultNot(newTrendItemId, SubmissionResult.VOID));
        combined.addAll(submissions.findByTrendItemIdAndResultNot(oldTrendItemId, SubmissionResult.VOID));
        List<MergeComputation.SubmissionInput> inputs = combined.stream()
                .map(s -> new MergeComputation.SubmissionInput(
                        s.getId(), s.getUserId(), s.getRawInput(), s.getCreatedAt(), s.isSeed()))
                .toList();
        return MergeComputation.computeCombinedOrder(inputs).stream()
                .map(o -> o.seed()
                        ? "시딩 " + handleOf(o.userId())
                        : "order%d %s".formatted(o.rank(), handleOf(o.userId())))
                .toList();
    }
```

- [ ] **Step 5: 확인**

Run: `./gradlew :merge:test :api-admin:compileJava`
Expected: `BUILD SUCCESSFUL`, `MergeComputationTest` 전부 통과.

- [ ] **Step 6: 커밋**

```bash
git add backend/merge/src/main/java/kr/trendstage/merge/MergeComputation.java backend/merge/src/test/java/kr/trendstage/merge/MergeComputationTest.java backend/merge/src/main/java/kr/trendstage/merge/MergeService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/MergeQueueController.java
git commit -m "fix(merge): 병합 미리보기 선점 순위를 뷰와 같은 규칙으로(시딩 제외, 동순위)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: 병합 가드·행 잠금·마감 보장 (`MergeService`)

**Files:**
- Create: `backend/merge/src/main/java/kr/trendstage/merge/MergeConflictException.java`
- Create: `backend/merge/src/main/java/kr/trendstage/merge/MergeGuard.java`
- Modify: `backend/merge/src/main/java/kr/trendstage/merge/MergeService.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/MergeQueueController.java` (`preview()`, `MergePreviewResponse`)
- Test: `backend/app/src/test/java/kr/trendstage/merge/MergeGuardTest.java`
- Test: `backend/app/src/test/java/kr/trendstage/merge/MergeRecomputeTest.java`

- [ ] **Step 1: 실패하는 가드 테스트 작성**

```java
package kr.trendstage.merge;

import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 스펙 §8 3·4번: 판정 전 상태가 아니거나 판정 행이 있으면 병합하지 않는다(K1·K2). */
class MergeGuardTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    @Autowired MergeService merge;

    private void assertRejected(UUID survivor, UUID loser, MergeConflictException.Reason reason) {
        assertThatThrownBy(() -> merge.merge(survivor, loser, null, null, "테스트"))
                .isInstanceOfSatisfying(MergeConflictException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    @Test
    void judgingItemIsRejectedAsRetryable() {
        UUID s = fx.item(T0), l = fx.item(T0.plus(Duration.ofHours(1)));
        UUID sub = fx.submission(fx.user(), l, 30, T0.plus(Duration.ofHours(1)));
        fx.setState(l, "JUDGING");

        assertRejected(s, l, MergeConflictException.Reason.JUDGING);
        assertThat(fx.itemOf(sub)).isEqualTo(l);   // 아무것도 옮기지 않았다
        assertThat(fx.itemState(l)).isEqualTo("JUDGING");
    }

    @Test
    void resolvedItemIsRejected() {
        UUID s = fx.item(T0), l = fx.item(T0.plus(Duration.ofHours(1)));
        fx.setState(s, "RESOLVED");
        assertRejected(s, l, MergeConflictException.Reason.RESOLVED);
    }

    @Test
    void voidItemIsRejected() {
        UUID s = fx.item(T0), l = fx.item(T0.plus(Duration.ofHours(1)));
        fx.setState(l, "VOID");
        assertRejected(s, l, MergeConflictException.Reason.RESOLVED);
    }

    @Test
    void pendingItemWithVerdictRowIsRejected() {   // 상태와 판정 행이 어긋나도 막힌다
        UUID s = fx.item(T0), l = fx.item(T0.plus(Duration.ofHours(1)));
        fx.verdictRow(l);
        assertRejected(s, l, MergeConflictException.Reason.RESOLVED);
    }

    @Test
    void alreadyMergedLoserIsRejected() {
        UUID s = fx.item(T0), l = fx.item(T0.plus(Duration.ofHours(1))), other = fx.item(T0);
        fx.merged(l, other);
        assertRejected(s, l, MergeConflictException.Reason.TARGET_MERGED);
    }
}
```

- [ ] **Step 2: 실패하는 재계산 테스트 작성**

```java
package kr.trendstage.merge;

import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 스펙 §8 8·9·13번: 병합 뒤 순위 재계산, 최소 관측 보장, 중복 VOID의 제보권 반환. */
class MergeRecomputeTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    @Autowired MergeService merge;

    @Test
    void absorbedEarlierSubmitterTakesFirstRankAndSeedIsExcluded() {
        UUID s = fx.item(T0);
        UUID l = fx.item(T0.plus(Duration.ofHours(1)));
        UUID s1 = fx.submission(fx.user(), s, 30, T0.plus(Duration.ofHours(2)));
        UUID l1 = fx.submission(fx.user(), l, 30, T0.plus(Duration.ofHours(1)));
        UUID seed = fx.seedSubmission(fx.user(), l, T0.plus(Duration.ofMinutes(30)));
        clock.set(T0.plus(Duration.ofHours(3)));

        merge.merge(s, l, null, null, "테스트");

        // 흡수된 쪽 제보가 먼저라 1위 — "기존 최대값 + 1"로 붙이면 이 테스트가 깨진다
        assertThat(fx.orderRank(l1)).isEqualTo(1);
        assertThat(fx.orderRank(s1)).isEqualTo(2);
        assertThat(fx.orderRank(seed)).isNull();
    }

    @Test
    void deadlinePulledIntoThePastGetsMinimumObservationCappedAtD21() {
        UUID s = fx.item(T0.plus(Duration.ofDays(10)));
        UUID l = fx.item(T0);
        clock.set(T0.plus(Duration.ofDays(20)));

        merge.merge(s, l, null, null, "테스트");

        assertThat(fx.deadlineOverride(s)).isEqualTo(T0.plus(Duration.ofDays(21)));
    }

    @Test
    void deadlineTooCloseGetsThreeMoreDays() {
        UUID s = fx.item(T0.plus(Duration.ofDays(10)));
        UUID l = fx.item(T0);
        clock.set(T0.plus(Duration.ofDays(12)));

        merge.merge(s, l, null, null, "테스트");

        assertThat(fx.deadlineOverride(s)).isEqualTo(T0.plus(Duration.ofDays(15)));
    }

    @Test
    void mergeNeverShortensAnExistingLaterDeadline() {
        UUID s = fx.item(T0.plus(Duration.ofDays(10)));
        UUID l = fx.item(T0);
        fx.setDeadlineOverride(s, T0.plus(Duration.ofDays(30)));
        clock.set(T0.plus(Duration.ofDays(12)));

        merge.merge(s, l, null, null, "테스트");

        assertThat(fx.deadlineOverride(s)).isEqualTo(T0.plus(Duration.ofDays(30)));
    }

    @Test
    void mergeWithoutPullForwardLeavesDeadlineAlone() {
        UUID s = fx.item(T0);
        UUID l = fx.item(T0.plus(Duration.ofHours(1)));
        clock.set(T0.plus(Duration.ofDays(1)));

        merge.merge(s, l, null, null, "테스트");

        assertThat(fx.deadlineOverride(s)).isNull();
    }

    @Test
    void dedupVoidRefundsQuotaAndPreviewListsItButNotSeeds() {
        UUID s = fx.item(T0);
        UUID l = fx.item(T0.plus(Duration.ofHours(1)));
        UUID u = fx.user();
        UUID keep = fx.submission(u, s, 30, T0.plus(Duration.ofHours(2)));
        UUID hedge = fx.submission(u, l, 30, T0.plus(Duration.ofHours(3)));
        UUID seedUser = fx.user();
        fx.seedSubmission(seedUser, s, T0.plus(Duration.ofHours(2)));
        UUID seedHedge = fx.seedSubmission(seedUser, l, T0.plus(Duration.ofHours(4)));
        Instant now = T0.plus(Duration.ofHours(5));
        clock.set(now);

        MergeService.PreviewResult preview = merge.preview(s, l);
        assertThat(preview.dedupVoidedSubmissionIds()).containsExactlyInAnyOrder(hedge, seedHedge);
        assertThat(preview.quotaRefundSubmissionIds()).containsExactly(hedge);

        merge.merge(s, l, null, null, "테스트");

        assertThat(fx.submissionResult(hedge)).isEqualTo("VOID");
        assertThat(fx.voidedAt(hedge)).isEqualTo(now);   // voided_at이 속한 주에 제보권 1장 반환(QuotaService)
        assertThat(fx.submissionResult(keep)).isEqualTo("PENDING");
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :app:test --tests kr.trendstage.merge.MergeGuardTest --tests kr.trendstage.merge.MergeRecomputeTest`
Expected: 컴파일 실패 — `MergeConflictException`, `PreviewResult.quotaRefundSubmissionIds()` 없음.

- [ ] **Step 4: 예외 작성**

```java
package kr.trendstage.merge;

/** 병합할 수 없는 상태. api-admin이 409로 매핑하고 type으로 사유를 구분한다(SP2 K2). */
public class MergeConflictException extends RuntimeException {

    public enum Reason {
        /** 판정 중 — 곧 풀리므로 재시도 의미 있음. */
        JUDGING("merge-judging"),
        /** 판정 완료·VOID·판정 행 존재 — 판정 후 병합(Phase 2) 전까지 영구 거부. */
        RESOLVED("merge-resolved"),
        /** 이미 다른 항목으로 병합됨. */
        TARGET_MERGED("merge-target-merged"),
        /** 큐 후보가 이미 다른 요청으로 처리됨. */
        QUEUE_DECIDED("merge-queue-decided");

        private final String type;
        Reason(String type) { this.type = type; }
    }

    private final Reason reason;

    public MergeConflictException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
    public String type() { return reason.type; }
}
```

- [ ] **Step 5: 가드 작성**

```java
package kr.trendstage.merge;

import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.VerdictRepository;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * 병합 가능 = 판정 전 상태(DRAFT·PENDING) + 현행 판정 행 없음(SP2 K1).
 * 두 겹으로 거는 이유: 상태와 판정 행 중 한쪽만 어긋나도 흡수된 제보가 다음 판정에서 원장에 다시 실려 이중 점수가 된다.
 */
@Component
public class MergeGuard {

    private static final Set<TrendState> BEFORE_JUDGMENT = EnumSet.of(TrendState.DRAFT, TrendState.PENDING);

    private final VerdictRepository verdicts;

    public MergeGuard(VerdictRepository verdicts) {
        this.verdicts = verdicts;
    }

    public boolean isMergeable(TrendItem item) {
        return BEFORE_JUDGMENT.contains(item.getState())
                && !verdicts.existsByTrendItemIdAndSupersedesIsNull(item.getId());
    }

    public void require(TrendItem item) {
        if (item.getState() == TrendState.MERGED) {
            throw new MergeConflictException(MergeConflictException.Reason.TARGET_MERGED,
                    "이미 다른 항목으로 병합된 항목입니다");
        }
        if (item.getState() == TrendState.JUDGING) {
            throw new MergeConflictException(MergeConflictException.Reason.JUDGING,
                    "판정 중인 항목입니다 — 잠시 후 다시 시도하세요");
        }
        if (!isMergeable(item)) {
            throw new MergeConflictException(MergeConflictException.Reason.RESOLVED,
                    "이미 판정된 항목은 병합할 수 없습니다(판정 후 병합은 Phase 2)");
        }
    }
}
```

- [ ] **Step 6: `MergeService` 교체**

`MergeService.java` 전체를 교체:

```java
package kr.trendstage.merge;

import kr.trendstage.audit.AuditLogService;
import kr.trendstage.domain.verdict.DeadlineWindow;
import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.SubmissionResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * 병합 실행(03 §3·§4). 배치(cluster_merge, 0.85↑ 자동)와 ADM-100 관리자 확정이 공유한다.
 * actorId가 null이면 배치 자동 실행 — 감사로그에 그대로 기록된다.
 *
 * 두 항목을 id 오름차순으로 SELECT … FOR UPDATE 잠근다. JudgeService가 같은 행 잠금을 잡으므로
 * 병합과 판정·VOID가 직렬화된다(SP2 K3). 가드는 MergeGuard(K1).
 */
@Service
public class MergeService {

    private final TrendItemRepository trendItems;
    private final SubmissionRepository submissions;
    private final MergeGuard guard;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public MergeService(TrendItemRepository trendItems, SubmissionRepository submissions, MergeGuard guard,
                         AuditLogService auditLogService, Clock clock) {
        this.trendItems = trendItems;
        this.submissions = submissions;
        this.guard = guard;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /**
     * 두 항목을 id 오름차순으로 잠근다. 모든 쌍 잠금이 같은 순서를 쓰므로 교착이 없다
     * (순서는 java.util.UUID 비교 — 쌍을 잠그는 코드가 전부 이 메서드를 거치면 일관된다).
     * 호출 측 트랜잭션 안에서만 의미가 있다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, TrendItem> lockPair(UUID a, UUID b) {
        Map<UUID, TrendItem> locked = new HashMap<>();
        for (UUID id : Stream.of(a, b).sorted().toList()) {
            locked.put(id, trendItems.findByIdForUpdate(id)
                    .orElseThrow(() -> new IllegalStateException("항목이 없습니다: " + id)));
        }
        return locked;
    }

    /** survivor(승자)로 loser(패자)를 흡수한다. 승자 결정은 호출 측 책임(first_seen_at 이른 쪽, 03 §4.1). */
    @Transactional
    public void merge(UUID survivorId, UUID loserId, UUID actorId, AdminRole actorRole, String reason) {
        Map<UUID, TrendItem> locked = lockPair(survivorId, loserId);
        TrendItem survivor = locked.get(survivorId);
        TrendItem loser = locked.get(loserId);
        guard.require(survivor);
        guard.require(loser);

        Instant now = clock.instant();
        Instant deadlineBefore = DeadlineWindow.effectiveDeadline(
                survivor.getFirstSeenAt(), survivor.getJudgmentDeadlineOverride());

        dedupSameUserSubmissions(survivorId, loserId, now);
        for (Submission s : submissions.findByTrendItemId(loserId)) {
            s.reassignTrendItem(survivorId);
        }
        // 선점 순위는 submission_order_rank 뷰가 파생하므로 재배정만으로 같은 트랜잭션 안에서 다시 매겨진다
        mergeAliases(survivor, loser);
        survivor.pullForwardFirstSeen(loser.getFirstSeenAt());
        recomputeCanonicalName(survivor);

        // 최소 관측 보장(K7): 당겨진 마감이 병합 시점 + 3일보다 이르면 연장(상한 D+21). 마감을 당기지는 않는다.
        Optional<DeadlineWindow.MergeDeadline> guarded = DeadlineWindow.afterMerge(
                survivor.getFirstSeenAt(), survivor.getJudgmentDeadlineOverride(), now);
        guarded.ifPresent(d -> survivor.extendJudgmentDeadline(d.override()));
        Instant deadlineAfter = DeadlineWindow.effectiveDeadline(
                survivor.getFirstSeenAt(), survivor.getJudgmentDeadlineOverride());

        loser.mergeInto(survivorId);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("loserId", loserId.toString());
        detail.put("loserName", loser.getCanonicalName());
        detail.put("survivorName", survivor.getCanonicalName());
        detail.put("reason", reason == null ? "" : reason);
        if (!deadlineAfter.equals(deadlineBefore)) {
            detail.put("deadlineBefore", deadlineBefore.toString());
            detail.put("deadlineAfter", deadlineAfter.toString());
        }
        if (guarded.map(DeadlineWindow.MergeDeadline::capped).orElse(false)) {
            detail.put("minObservationCapped", true);
        }
        auditLogService.record(actorId, actorRole, "MERGE", "TREND_ITEM", survivorId, detail);
    }

    /** ADM-100 "분리" — 유사도 제안을 기각. 데이터는 건드리지 않고 두 항목을 계속 독립적으로 둔다. */
    public void recordSeparateDecision(UUID actorId, AdminRole actorRole, UUID newTrendItemId, UUID oldTrendItemId, String reason) {
        auditLogService.record(actorId, actorRole, "MERGE_SEPARATE", "TREND_ITEM", newTrendItemId, Map.of(
                "comparedTo", oldTrendItemId.toString(),
                "reason", reason == null ? "" : reason
        ));
    }

    /**
     * ADM-100 병합 후 미리보기(dry-run). DB에 아무것도 쓰지 않는다 — merge()와 같은 순수 함수를
     * 재사용하므로 실제 병합 결과와 어긋날 수 없다.
     */
    @Transactional(readOnly = true)
    public PreviewResult preview(UUID survivorId, UUID loserId) {
        TrendItem survivor = trendItems.findById(survivorId)
                .orElseThrow(() -> new IllegalStateException("승자 항목이 없습니다: " + survivorId));
        TrendItem loser = trendItems.findById(loserId)
                .orElseThrow(() -> new IllegalStateException("패자 항목이 없습니다: " + loserId));

        List<MergeComputation.SubmissionInput> a = toInputs(
                submissions.findByTrendItemIdAndResultNot(survivorId, SubmissionResult.VOID));
        List<MergeComputation.SubmissionInput> b = toInputs(
                submissions.findByTrendItemIdAndResultNot(loserId, SubmissionResult.VOID));

        Set<UUID> voided = MergeComputation.computeDedup(a, b);
        Set<UUID> seedIds = new HashSet<>();
        List<MergeComputation.SubmissionInput> activeAfterDedup = new ArrayList<>();
        for (var s : a) { if (s.seed()) seedIds.add(s.submissionId()); if (!voided.contains(s.submissionId())) activeAfterDedup.add(s); }
        for (var s : b) { if (s.seed()) seedIds.add(s.submissionId()); if (!voided.contains(s.submissionId())) activeAfterDedup.add(s); }
        Set<UUID> quotaRefund = new HashSet<>(voided);
        quotaRefund.removeAll(seedIds);   // 시딩은 제보권 대상이 아니다

        String newCanonicalName = MergeComputation.computeCanonicalName(activeAfterDedup)
                .orElse(survivor.getCanonicalName());
        List<MergeComputation.OrderComputed> orderAfter = MergeComputation.computeCombinedOrder(activeAfterDedup);

        Instant firstSeenAtBefore = survivor.getFirstSeenAt();
        Instant firstSeenAtAfter = loser.getFirstSeenAt().isBefore(firstSeenAtBefore)
                ? loser.getFirstSeenAt() : firstSeenAtBefore;

        Instant deadlineBefore = DeadlineWindow.effectiveDeadline(firstSeenAtBefore, survivor.getJudgmentDeadlineOverride());
        Optional<DeadlineWindow.MergeDeadline> guarded = DeadlineWindow.afterMerge(
                firstSeenAtAfter, survivor.getJudgmentDeadlineOverride(), clock.instant());
        Instant deadlineAfter = guarded.map(DeadlineWindow.MergeDeadline::override)
                .orElse(DeadlineWindow.effectiveDeadline(firstSeenAtAfter, survivor.getJudgmentDeadlineOverride()));

        return new PreviewResult(newCanonicalName, orderAfter, voided, quotaRefund,
                firstSeenAtBefore, firstSeenAtAfter, deadlineBefore, deadlineAfter, guarded.isPresent());
    }

    public record PreviewResult(
            String newCanonicalName,
            List<MergeComputation.OrderComputed> orderAfter,
            Set<UUID> dedupVoidedSubmissionIds,
            Set<UUID> quotaRefundSubmissionIds,
            Instant firstSeenAtBefore,
            Instant firstSeenAtAfter,
            Instant deadlineBefore,
            Instant deadlineAfter,
            boolean deadlineGuarded
    ) {}

    /** 같은 유저가 두 클러스터 모두에 유효 제보를 낸 경우, 늦은 쪽을 VOID(03 §4.4) — 헤지 방지. voided_at이 제보권을 돌려준다. */
    private void dedupSameUserSubmissions(UUID trendItemA, UUID trendItemB, Instant now) {
        List<Submission> a = submissions.findByTrendItemIdAndResultNot(trendItemA, SubmissionResult.VOID);
        List<Submission> b = submissions.findByTrendItemIdAndResultNot(trendItemB, SubmissionResult.VOID);
        Map<UUID, Submission> byId = new HashMap<>();
        for (Submission s : a) byId.put(s.getId(), s);
        for (Submission s : b) byId.put(s.getId(), s);

        Set<UUID> voided = MergeComputation.computeDedup(toInputs(a), toInputs(b));
        for (UUID id : voided) byId.get(id).voidOut(now);
    }

    private void mergeAliases(TrendItem survivor, TrendItem loser) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(Arrays.asList(survivor.getAliases()));
        merged.add(loser.getNormalizedKey());
        merged.addAll(Arrays.asList(loser.getAliases()));
        survivor.setAliases(merged.toArray(new String[0]));
    }

    private void recomputeCanonicalName(TrendItem survivor) {
        List<Submission> active = submissions.findByTrendItemIdAndResultNot(survivor.getId(), SubmissionResult.VOID);
        MergeComputation.computeCanonicalName(toInputs(active)).ifPresent(survivor::setCanonicalName);
    }

    private static List<MergeComputation.SubmissionInput> toInputs(List<Submission> subs) {
        return subs.stream()
                .map(s -> new MergeComputation.SubmissionInput(
                        s.getId(), s.getUserId(), s.getRawInput(), s.getCreatedAt(), s.isSeed()))
                .toList();
    }
}
```

`AuditLogService.record`의 detail 파라미터 타입이 `Map<String, ?>`이므로 `LinkedHashMap<String, Object>`를 그대로 넘길 수 있다.

- [ ] **Step 7: 컨트롤러 미리보기 응답 수정**

`MergeQueueController.java`:

`MergePreviewResponse`를 교체:

```java
    public record MergePreviewResponse(String newCanonicalName, List<OrderEntry> orderRank,
                                        String firstSeenAtBefore, String firstSeenAtAfter,
                                        String deadlineBefore, String deadlineAfter, boolean deadlineGuarded,
                                        List<String> dedupVoidedHandles, List<String> quotaRefundHandles) {}
```

`preview()`에서 `List<String> dedupVoidedHandles = new ArrayList<>();`부터 `return` 문 끝까지를 교체:

```java
        List<String> dedupVoidedHandles = handlesOf(result.dedupVoidedSubmissionIds(), survivor.getId(), loser.getId());
        List<String> quotaRefundHandles = handlesOf(result.quotaRefundSubmissionIds(), survivor.getId(), loser.getId());

        return new MergePreviewResponse(
                result.newCanonicalName(), orderRank,
                DISPLAY_FORMAT.format(result.firstSeenAtBefore()), DISPLAY_FORMAT.format(result.firstSeenAtAfter()),
                DISPLAY_FORMAT.format(result.deadlineBefore()), DISPLAY_FORMAT.format(result.deadlineAfter()),
                result.deadlineGuarded(), dedupVoidedHandles, quotaRefundHandles);
```

`handleOf` 다음에 헬퍼 추가:

```java
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
```

(`baselineShifted`는 판정 모델 피벗 이전의 잔재라 제거한다 — 스펙 §7.)

- [ ] **Step 8: 통과 확인**

Run: `./gradlew :app:test --tests kr.trendstage.merge.MergeGuardTest --tests kr.trendstage.merge.MergeRecomputeTest`
Expected: PASS (11 tests).

- [ ] **Step 9: 전체 확인 + 커밋**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. `ClusterMergeCandidateService`는 여전히 `mergeService.merge`를 부르므로 컴파일된다(동작 변경은 Task 6).

```bash
git add backend/merge/src/main/java/kr/trendstage/merge/MergeConflictException.java backend/merge/src/main/java/kr/trendstage/merge/MergeGuard.java backend/merge/src/main/java/kr/trendstage/merge/MergeService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/MergeQueueController.java backend/app/src/test/java/kr/trendstage/merge/MergeGuardTest.java backend/app/src/test/java/kr/trendstage/merge/MergeRecomputeTest.java
git commit -m "feat(merge): 병합 행 잠금(id 오름차순)·판정 전 상태 가드(409)·최소 관측 보장

판정된 항목(상태 또는 판정 행)은 병합하지 않는다 — 흡수된 제보의 이중 점수 차단.
미리보기에 제보권 반환 대상·마감 조정을 드러낸다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: 동시성 회귀 테스트

**Files:**
- Test: `backend/app/src/test/java/kr/trendstage/merge/MergeConcurrencyTest.java`

> 스펙 §8-2번은 "병합과 **판정**이 동시에"다. 판정 배치 경로(`closeDue` → `judge`)는 `closeDue`가 공유 DB의 **모든** 마감 지난 PENDING 항목을 JUDGING으로 바꿔 다른 테스트 클래스를 오염시키므로, 같은 행 잠금(`JudgeService.lock` = `findByIdForUpdate`)을 잡는 `JudgeService.voidItem`과의 경합으로 검증한다. 직렬화되는 잠금이 같으므로 보장하는 성질(병합과 판정 사건이 같은 항목을 동시에 바꾸지 못함)은 같다.

- [ ] **Step 1: 테스트 작성**

```java
package kr.trendstage.merge;

import kr.trendstage.judge.JudgeConflictException;
import kr.trendstage.judge.JudgeService;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스펙 §8 1·2번. 서비스 빈을 직접 호출한다(MockMvc는 스레드 간 공유가 불안정).
 * 행 잠금이 없으면 두 작업이 같은 옛 상태를 읽고 둘 다 성공한다.
 */
class MergeConcurrencyTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    @Autowired MergeService merge;
    @Autowired JudgeService judge;

    @Test
    void twoConcurrentMergesOfSamePairSucceedOnce() throws Exception {
        UUID s = fx.item(T0), l = fx.item(T0.plusSeconds(60));
        UUID sub = fx.submission(fx.user(), l, 30, T0.plusSeconds(60));
        clock.set(T0.plus(Duration.ofDays(1)));

        List<Throwable> outcomes = runConcurrently(
                () -> merge.merge(s, l, null, null, "A"),
                () -> merge.merge(s, l, null, null, "B"));

        assertThat(outcomes).filteredOn(Objects::isNull).hasSize(1);
        assertThat(outcomes).filteredOn(Objects::nonNull).singleElement()
                .isInstanceOfSatisfying(MergeConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(MergeConflictException.Reason.TARGET_MERGED));
        assertThat(fx.itemOf(sub)).isEqualTo(s);
        assertThat(fx.itemState(l)).isEqualTo("MERGED");
        assertThat(fx.auditCount("MERGE", s)).isEqualTo(1);
    }

    @Test
    void mergeAndVoidOfSameItemAreSerialized() throws Exception {
        UUID s = fx.item(T0), l = fx.item(T0.plusSeconds(60));
        UUID sub = fx.submission(fx.user(), l, 30, T0.plusSeconds(60));
        clock.set(T0.plus(Duration.ofDays(1)));
        Instant now = clock.instant();

        List<Throwable> outcomes = runConcurrently(
                () -> merge.merge(s, l, null, null, "병합"),
                () -> judge.voidItem(l, "VOID", now));

        assertThat(outcomes).filteredOn(Objects::isNull).hasSize(1);
        if (fx.itemState(l).equals("MERGED")) {          // 병합이 먼저 → VOID는 409
            assertThat(outcomes.get(1)).isInstanceOf(JudgeConflictException.class);
            assertThat(fx.itemOf(sub)).isEqualTo(s);
            assertThat(fx.submissionResult(sub)).isEqualTo("PENDING");
        } else {                                          // VOID가 먼저 → 병합은 409
            assertThat(fx.itemState(l)).isEqualTo("VOID");
            assertThat(outcomes.get(0)).isInstanceOfSatisfying(MergeConflictException.class,
                    e -> assertThat(e.reason()).isEqualTo(MergeConflictException.Reason.RESOLVED));
            assertThat(fx.itemOf(sub)).isEqualTo(l);
            assertThat(fx.submissionResult(sub)).isEqualTo("VOID");
        }
    }

    /** 두 작업을 CountDownLatch로 동시에 출발시키고, 각 작업의 예외(성공이면 null)를 순서대로 돌려준다. */
    private static List<Throwable> runConcurrently(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (Runnable task : List.of(first, second)) {
                futures.add(pool.submit((Callable<Throwable>) () -> {
                    start.await();
                    try {
                        task.run();
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            start.countDown();
            List<Throwable> outcomes = new ArrayList<>();
            for (Future<Throwable> f : futures) outcomes.add(f.get(30, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
```

- [ ] **Step 2: 실행**

Run: `./gradlew :app:test --tests kr.trendstage.merge.MergeConcurrencyTest`
Expected: PASS (2 tests). 3번 연달아 돌려 흔들리지 않는지 본다: `for i in 1 2 3; do ./gradlew :app:test --tests kr.trendstage.merge.MergeConcurrencyTest --rerun -q || echo FAIL; done`

- [ ] **Step 3: 잠금이 실제로 지키는지 확인**

`MergeService.lockPair`의 `trendItems.findByIdForUpdate(id)`를 잠시 `trendItems.findById(id)`로 바꾸고 위 반복 명령을 다시 돌린다. 최소 한 번은 FAIL이 나야 한다(두 병합이 모두 성공하거나 VOID·병합이 둘 다 성공). 확인한 뒤 **반드시 `findByIdForUpdate`로 되돌리고** `git diff backend/merge`가 비어 있는지 확인한다. 세 번 모두 통과하면(경합이 재현되지 않으면) 그 사실을 보고서에 적는다 — 테스트를 약하게 바꾸지 않는다.

- [ ] **Step 4: 커밋**

```bash
git add backend/app/src/test/java/kr/trendstage/merge/MergeConcurrencyTest.java
git commit -m "test(merge): 동시 병합·병합과 VOID 경합에서 한 쪽만 성공

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: 자동 병합 경로 — 판정된 항목은 기록만, 결정은 한 트랜잭션

**Files:**
- Create: `backend/merge/src/main/java/kr/trendstage/merge/ClusterMergeDecider.java`
- Modify: `backend/merge/src/main/java/kr/trendstage/merge/ClusterMergeCandidateService.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/BatchJobController.java`
- Test: `backend/app/src/test/java/kr/trendstage/merge/ClusterMergeGuardTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

임베딩 서버(TEI)는 테스트에 없으므로 결정 단계(`ClusterMergeDecider`)를 직접 부른다.

```java
package kr.trendstage.merge;

import kr.trendstage.merge.ClusterMergeCandidateService.ProcessOutcome;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 스펙 §8 5번(K4): 판정된 항목과 닮으면 병합도 큐도 없이 감사 로그만. */
class ClusterMergeGuardTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    @Autowired ClusterMergeDecider decider;

    private int queueRows(UUID newItem) {
        return jdbc.queryForObject("SELECT count(*) FROM merge_queue WHERE new_trend_item_id = ?", Integer.class, newItem);
    }

    @Test
    void judgedLookalikeIsOnlyAudited() {
        UUID candidate = fx.item(T0.plus(Duration.ofDays(1)));
        UUID judged = fx.item(T0);
        fx.setState(judged, "RESOLVED");
        Instant now = T0.plus(Duration.ofDays(2));

        ProcessOutcome outcome = decider.decide(candidate, judged, 0.90, now);

        assertThat(outcome).isEqualTo(ProcessOutcome.SKIPPED_JUDGED);
        assertThat(fx.itemState(candidate)).isEqualTo("PENDING");
        assertThat(fx.itemState(judged)).isEqualTo("RESOLVED");
        assertThat(queueRows(candidate)).isZero();
        assertThat(fx.auditCount("MERGE_SKIPPED_JUDGED", candidate)).isEqualTo(1);
        assertThat(fx.mergeCheckedAt(candidate)).isEqualTo(now);
    }

    @Test
    void pendingLookalikeAboveThresholdIsAutoMerged() {
        UUID candidate = fx.item(T0.plus(Duration.ofDays(1)));
        UUID older = fx.item(T0);
        clock.set(T0.plus(Duration.ofDays(2)));

        ProcessOutcome outcome = decider.decide(candidate, older, 0.90, clock.instant());

        assertThat(outcome).isEqualTo(ProcessOutcome.AUTO_MERGED);
        assertThat(fx.itemState(candidate)).isEqualTo("MERGED");   // 늦게 생긴 쪽이 패자
    }

    @Test
    void grayZoneIsQueued() {
        UUID candidate = fx.item(T0.plus(Duration.ofDays(1)));
        UUID older = fx.item(T0);

        ProcessOutcome outcome = decider.decide(candidate, older, 0.80, T0.plus(Duration.ofDays(2)));

        assertThat(outcome).isEqualTo(ProcessOutcome.QUEUED);
        assertThat(queueRows(candidate)).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:test --tests kr.trendstage.merge.ClusterMergeGuardTest`
Expected: 컴파일 실패 — `ClusterMergeDecider`, `ProcessOutcome.SKIPPED_JUDGED` 없음.

- [ ] **Step 3: `ClusterMergeDecider` 작성**

```java
package kr.trendstage.merge;

import kr.trendstage.audit.AuditLogService;
import kr.trendstage.merge.ClusterMergeCandidateService.ProcessOutcome;
import kr.trendstage.persistence.entity.MergeQueueEntry;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.MergeQueueRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * cluster_merge 후보 하나의 결정 + 확인 표시를 한 트랜잭션으로 묶는다(SP2 §2.3). 임베딩 계산(외부 HTTP)은
 * 호출 측(ClusterMergeCandidateService)이 트랜잭션 밖에서 끝낸다. 같은 클래스 안 호출로는 @Transactional이
 * 걸리지 않아 별도 빈으로 뺐다.
 *
 * 후보 항목은 잠그지 않는다 — 병합이 필요하면 MergeService가 두 항목을 id 오름차순으로 잠근다.
 * 여기서 후보를 먼저 잠그면 반대 순서로 잠그는 관리자 결정과 교착할 수 있다.
 */
@Service
public class ClusterMergeDecider {

    private final TrendItemRepository trendItems;
    private final MergeQueueRepository mergeQueue;
    private final MergeService mergeService;
    private final MergeGuard guard;
    private final AuditLogService auditLogService;

    public ClusterMergeDecider(TrendItemRepository trendItems, MergeQueueRepository mergeQueue,
                               MergeService mergeService, MergeGuard guard, AuditLogService auditLogService) {
        this.trendItems = trendItems;
        this.mergeQueue = mergeQueue;
        this.mergeService = mergeService;
        this.guard = guard;
        this.auditLogService = auditLogService;
    }

    /**
     * @param otherId 가장 닮은 항목(없으면 null)
     * @throws MergeConflictException 병합이 판정·다른 병합과 경합해 거부됨 — 확인 표시가 롤백되어 다음 실행에서 다시 본다
     */
    @Transactional
    public ProcessOutcome decide(UUID candidateId, UUID otherId, double similarity, Instant now) {
        ProcessOutcome outcome = otherId == null ? ProcessOutcome.SEPARATED : ClusterMergeCandidateService.classify(similarity);
        if (outcome == ProcessOutcome.SEPARATED) {
            markChecked(candidateId, now);
            return outcome;
        }

        BigDecimal simScore = BigDecimal.valueOf(similarity).setScale(4, RoundingMode.HALF_UP);
        TrendItem other = trendItems.findById(otherId).orElseThrow();
        if (!guard.isMergeable(other)) {
            // 판정 후 병합은 Phase 2(O9). 병합·큐 없이 기록만 남겨 빈도 근거로 쓴다(K4).
            auditLogService.record(null, null, "MERGE_SKIPPED_JUDGED", "TREND_ITEM", candidateId, Map.of(
                    "comparedTo", otherId.toString(),
                    "comparedState", other.getState().name(),
                    "similarity", simScore.toPlainString()));
            markChecked(candidateId, now);
            return ProcessOutcome.SKIPPED_JUDGED;
        }

        TrendItem candidate = trendItems.findById(candidateId).orElseThrow();
        if (outcome == ProcessOutcome.AUTO_MERGED) {
            TrendItem survivor = candidate.getFirstSeenAt().isBefore(other.getFirstSeenAt()) ? candidate : other;
            TrendItem loser = survivor == candidate ? other : candidate;
            mergeService.merge(survivor.getId(), loser.getId(), null, null,
                    "cluster_merge 자동 병합 (유사도 %s)".formatted(simScore.toPlainString()));
        } else {
            mergeQueue.save(new MergeQueueEntry(candidateId, otherId, simScore));
        }
        markChecked(candidateId, now);
        return outcome;
    }

    private void markChecked(UUID itemId, Instant now) {
        trendItems.findById(itemId).orElseThrow().markMergeChecked(now);   // 관리 엔티티 — 커밋 시 flush
    }
}
```

- [ ] **Step 4: `ClusterMergeCandidateService` 수정**

`ProcessOutcome`·`ClusterMergeResult`를 교체:

```java
    public enum ProcessOutcome { AUTO_MERGED, QUEUED, SEPARATED, SKIPPED_JUDGED, DEFERRED }

    /** skippedJudged = 판정된 항목과 닮아 기록만 함(K4), deferred = 경합으로 거부돼 다음 실행에서 다시 봄. */
    public record ClusterMergeResult(int candidates, int autoMerged, int queued, int separated,
                                     int skippedJudged, int deferred, int failed) {}
```

필드 `private final MergeService mergeService;`를 `private final ClusterMergeDecider decider;`로 바꾸고 생성자 파라미터·대입도 같이 바꾼다(`MergeService` import는 쓰이지 않으면 지운다).

`runNow()`를 교체:

```java
    /** 크론(@SchedulerLock으로 이미 보호됨) 전용 진입점. 판정 전 상태만 후보로 본다(판정된 항목은 병합 대상이 아니다). */
    public ClusterMergeResult runNow() {
        List<UUID> candidateIds = trendItems.findByStateInAndMergeCheckedAtIsNull(
                        List.of(TrendState.DRAFT, TrendState.PENDING))
                .stream().map(TrendItem::getId).toList();

        int autoMerged = 0, queued = 0, separated = 0, skippedJudged = 0, deferred = 0, failed = 0;
        for (UUID candidateId : candidateIds) {
            try {
                switch (processOne(candidateId)) {
                    case AUTO_MERGED -> autoMerged++;
                    case QUEUED -> queued++;
                    case SEPARATED -> separated++;
                    case SKIPPED_JUDGED -> skippedJudged++;
                    case DEFERRED -> deferred++;
                }
            } catch (Exception e) {
                log.error("cluster_merge 처리 실패 item={} : {}", candidateId, e.getMessage(), e);
                failed++;
            }
        }
        return new ClusterMergeResult(candidateIds.size(), autoMerged, queued, separated, skippedJudged, deferred, failed);
    }
```

`processOne`과 `markChecked`를 다음 하나로 교체(Javadoc 포함):

```java
    /**
     * 임베딩 계산·저장은 트랜잭션 밖(외부 HTTP), 결정 + 확인 표시는 ClusterMergeDecider의 트랜잭션 하나.
     * 같은 실행에서 앞 후보에 흡수돼 이미 판정 전 상태가 아니면 건너뛴다.
     */
    private ProcessOutcome processOne(UUID candidateId) {
        TrendItem candidate = trendItems.findById(candidateId).orElseThrow();
        if (candidate.getState() != TrendState.DRAFT && candidate.getState() != TrendState.PENDING) {
            return ProcessOutcome.DEFERRED;
        }
        float[] vec = embeddingClient.embed(candidate.getCanonicalName());
        embeddingDao.updateEmbedding(candidate.getId(), vec);

        var match = embeddingDao.findMostSimilar(candidate.getId(), vec);
        try {
            return decider.decide(candidateId,
                    match.map(TrendEmbeddingDao.SimilarMatch::trendItemId).orElse(null),
                    match.map(TrendEmbeddingDao.SimilarMatch::similarity).orElse(0.0),
                    clock.instant());
        } catch (MergeConflictException e) {
            log.info("cluster_merge 병합 보류 item={} : {}", candidateId, e.getMessage());
            return ProcessOutcome.DEFERRED;
        }
    }
```

사용하지 않게 된 import(`MergeQueueEntry`, `BigDecimal`, `RoundingMode`, `Instant` 등)를 정리한다. `mergeQueue` 필드가 더 이상 쓰이지 않으면 필드·생성자 파라미터에서 지운다.

- [ ] **Step 5: `BatchJobController` 감사 detail**

`BatchJobController.runClusterMerge`의 `Map.of(...)`를 교체:

```java
                Map.of("candidates", r.candidates(), "autoMerged", r.autoMerged(),
                        "queued", r.queued(), "separated", r.separated(),
                        "skippedJudged", r.skippedJudged(), "deferred", r.deferred(), "failed", r.failed()));
```

`grep -rn "ClusterMergeResult(" backend --include=*.java`로 다른 생성 지점이 없는지 확인한다(테스트 포함). 있으면 7인자로 맞춘다.

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :app:test --tests kr.trendstage.merge.ClusterMergeGuardTest && ./gradlew :merge:test`
Expected: PASS. `ClusterMergeCandidateServiceTest`(classify 경계)는 그대로 통과한다.

- [ ] **Step 7: 커밋**

```bash
git add backend/merge/src/main/java/kr/trendstage/merge/ClusterMergeDecider.java backend/merge/src/main/java/kr/trendstage/merge/ClusterMergeCandidateService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/BatchJobController.java backend/app/src/test/java/kr/trendstage/merge/ClusterMergeGuardTest.java
git commit -m "feat(merge): cluster_merge가 판정된 항목은 병합하지 않고 기록만, 결정을 한 트랜잭션으로

후보 스캔에서 JUDGING·RESOLVED를 뺀다. 판정된 항목과 닮으면 MERGE_SKIPPED_JUDGED 감사만.
결정 + 확인 표시를 ClusterMergeDecider 트랜잭션 하나로 묶어 부분 커밋을 없앤다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: 큐 결정 — 행 잠금·멱등키·409 사유

**Files:**
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/merge/MergeDecisionService.java`
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/merge/IdempotencyKeyRequiredException.java`
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/merge/IdempotencyKeyMismatchException.java`
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/Problems.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminApiExceptionHandler.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/MergeQueueController.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/MergeQueueEntry.java` (3인자 `resolve` 삭제)
- Test: `backend/app/src/test/java/kr/trendstage/merge/MergeIdempotencyTest.java`
- Test: `backend/app/src/test/java/kr/trendstage/merge/MergeQueueApiTest.java`

- [ ] **Step 1: 실패하는 서비스 테스트 작성**

```java
package kr.trendstage.merge;

import kr.trendstage.apiadmin.merge.IdempotencyKeyMismatchException;
import kr.trendstage.apiadmin.merge.MergeDecisionService;
import kr.trendstage.apiadmin.merge.MergeDecisionService.Decision;
import kr.trendstage.apiadmin.merge.MergeDecisionService.Outcome;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 스펙 §8 6·7·14번(K5): 같은 키는 같은 결과, 다른 결정·다른 후보면 거부, 사라진 후보는 정리. */
class MergeIdempotencyTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    @Autowired MergeDecisionService decisions;

    private UUID admin;

    private record Candidate(UUID queue, UUID newItem, UUID oldItem, UUID newSub) {}

    @BeforeEach
    void setUp() {
        admin = fx.admin();
        clock.set(T0.plus(Duration.ofDays(1)));
    }

    private Candidate candidate() {
        UUID oldItem = fx.item(T0);
        UUID newItem = fx.item(T0.plus(Duration.ofHours(1)));
        fx.submission(fx.user(), oldItem, 30, T0.plusSeconds(1));
        UUID newSub = fx.submission(fx.user(), newItem, 30, T0.plus(Duration.ofHours(1)));
        return new Candidate(fx.mergeQueueEntry(newItem, oldItem, 0.80), newItem, oldItem, newSub);
    }

    private Outcome decide(Candidate c, Decision d, String key) {
        return decisions.decide(c.queue(), d, key, admin, AdminRole.ADMIN, "테스트");
    }

    private static String newKey() {
        return "k-" + UUID.randomUUID();
    }

    @Test
    void sameKeyReplaysPreviousResult() {
        Candidate c = candidate();
        String key = newKey();

        Outcome first = decide(c, Decision.MERGE, key);
        Outcome second = decide(c, Decision.MERGE, key);

        assertThat(first.body().replayed()).isFalse();
        assertThat(second.body().replayed()).isTrue();
        assertThat(second.body().status()).isEqualTo("MERGED");
        assertThat(second.body().survivorId()).isEqualTo(c.oldItem().toString());
        assertThat(second.body().loserId()).isEqualTo(c.newItem().toString());
        assertThat(fx.itemOf(c.newSub())).isEqualTo(c.oldItem());
        assertThat(fx.auditCount("MERGE", c.oldItem())).isEqualTo(1);   // 두 번째는 실행하지 않았다
    }

    @Test
    void sameKeyWithDifferentDecisionIsRejected() {
        Candidate c = candidate();
        String key = newKey();
        decide(c, Decision.MERGE, key);

        assertThatThrownBy(() -> decide(c, Decision.SEPARATE, key))
                .isInstanceOf(IdempotencyKeyMismatchException.class);
    }

    @Test
    void anotherKeyOnDecidedCandidateIsConflict() {
        Candidate c = candidate();
        decide(c, Decision.MERGE, newKey());

        assertThatThrownBy(() -> decide(c, Decision.MERGE, newKey()))
                .isInstanceOfSatisfying(MergeConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(MergeConflictException.Reason.QUEUE_DECIDED));
    }

    @Test
    void keyReusedOnAnotherCandidateIsRejected() {
        Candidate c1 = candidate(), c2 = candidate();
        String key = newKey();
        decide(c1, Decision.SEPARATE, key);

        assertThatThrownBy(() -> decide(c2, Decision.SEPARATE, key))
                .isInstanceOf(IdempotencyKeyMismatchException.class);
        assertThat(fx.queueStatus(c2.queue())).isEqualTo("PENDING");
    }

    @Test
    void candidateWhoseItemWasMergedElsewhereIsCleanedUp() {
        Candidate c = candidate();
        fx.merged(c.newItem(), fx.item(T0));   // 그사이 자동 병합으로 사라짐

        Outcome o = decide(c, Decision.VOID, newKey());

        assertThat(o.stale()).isTrue();
        assertThat(fx.queueStatus(c.queue())).isEqualTo("SKIPPED");
        assertThat(fx.auditCount("MERGE_QUEUE_STALE", c.newItem())).isEqualTo(1);
    }

    @Test
    void judgingTargetIsRejectedAndCandidateStaysPending() {
        Candidate c = candidate();
        fx.setState(c.oldItem(), "JUDGING");

        assertThatThrownBy(() -> decide(c, Decision.MERGE, newKey()))
                .isInstanceOfSatisfying(MergeConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(MergeConflictException.Reason.JUDGING));
        assertThat(fx.queueStatus(c.queue())).isEqualTo("PENDING");   // 롤백 — 재시도 가능
    }
}
```

- [ ] **Step 2: 실패하는 API 테스트 작성**

```java
package kr.trendstage.merge;

import jakarta.servlet.http.Cookie;
import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스펙 §7: 헤더 필수(400), 결정 응답 본문, 409 type 매핑. */
class MergeQueueApiTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    private UUID adminId;
    private Cookie csrf;

    @BeforeEach
    void setUp() throws Exception {
        adminId = fx.admin();
        clock.set(T0.plus(Duration.ofDays(1)));
        csrf = mvc.perform(get("/admin/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    private UUID candidate(UUID[] itemsOut) {
        UUID oldItem = fx.item(T0);
        UUID newItem = fx.item(T0.plus(Duration.ofHours(1)));
        fx.submission(fx.user(), newItem, 30, T0.plus(Duration.ofHours(1)));
        itemsOut[0] = newItem;
        itemsOut[1] = oldItem;
        return fx.mergeQueueEntry(newItem, oldItem, 0.80);
    }

    private ResultActions postMerge(UUID queueId, String key) throws Exception {
        AdminPrincipal principal = new AdminPrincipal(adminId, "tester", "테스터", AdminRole.ADMIN);
        MockHttpServletRequestBuilder req = post("/admin/merge-queue/{id}/merge", queueId)
                .with(authentication(new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))))
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"테스트\"}");
        if (key != null) req = req.header("Idempotency-Key", key);
        return mvc.perform(req);
    }

    @Test
    void decisionWithoutKeyIs400() throws Exception {
        postMerge(candidate(new UUID[2]), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("idempotency-key-required"));
    }

    @Test
    void decisionReturnsResultBody() throws Exception {
        UUID[] items = new UUID[2];
        UUID queueId = candidate(items);

        postMerge(queueId, "k-" + UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGED"))
                .andExpect(jsonPath("$.decision").value("MERGE"))
                .andExpect(jsonPath("$.survivorId").value(items[1].toString()))
                .andExpect(jsonPath("$.replayed").value(false));
    }

    @Test
    void judgingTargetIs409WithType() throws Exception {
        UUID[] items = new UUID[2];
        UUID queueId = candidate(items);
        fx.setState(items[1], "JUDGING");

        postMerge(queueId, "k-" + UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("merge-judging"));
    }

    @Test
    void staleCandidateIs409AndCleanedUp() throws Exception {
        UUID[] items = new UUID[2];
        UUID queueId = candidate(items);
        fx.merged(items[0], fx.item(T0));

        postMerge(queueId, "k-" + UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("merge-target-merged"));
        org.assertj.core.api.Assertions.assertThat(fx.queueStatus(queueId)).isEqualTo("SKIPPED");
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :app:test --tests kr.trendstage.merge.MergeIdempotencyTest --tests kr.trendstage.merge.MergeQueueApiTest`
Expected: 컴파일 실패 — `MergeDecisionService` 등 없음.

- [ ] **Step 4: 예외·오류 본문 작성**

`IdempotencyKeyRequiredException.java`:

```java
package kr.trendstage.apiadmin.merge;

/** 결정 요청에 Idempotency-Key가 없거나 형식이 틀림 → 400 idempotency-key-required. */
public class IdempotencyKeyRequiredException extends RuntimeException {
    public IdempotencyKeyRequiredException(String message) {
        super(message);
    }
}
```

`IdempotencyKeyMismatchException.java`:

```java
package kr.trendstage.apiadmin.merge;

/** 같은 키로 다른 결정을 보냈거나, 키가 다른 후보에 이미 쓰임 → 422 idempotency-key-mismatch. */
public class IdempotencyKeyMismatchException extends RuntimeException {
    public IdempotencyKeyMismatchException(String message) {
        super(message);
    }
}
```

`Problems.java`:

```java
package kr.trendstage.apiadmin.web;

import java.util.Map;

/** 콘솔 API 오류 본문 {type, status, detail}. type은 콘솔이 분기할 때 쓰고, 분기가 필요 없으면 about:blank. */
public final class Problems {
    private Problems() {}

    public static Map<String, Object> of(int status, String type, String detail) {
        return Map.of("type", type, "status", status, "detail", detail == null ? "" : detail);
    }
}
```

- [ ] **Step 5: 핸들러 매핑**

`AdminApiExceptionHandler.java`:
- import 추가: `kr.trendstage.apiadmin.merge.IdempotencyKeyMismatchException`, `kr.trendstage.apiadmin.merge.IdempotencyKeyRequiredException`, `kr.trendstage.merge.MergeConflictException`
- `JudgeConflictException` 핸들러 다음에 추가:

```java
    /** 병합 거부 — type으로 판정 중(재시도)·판정 완료(영구)·이미 병합·이미 처리를 구분한다(SP2 K2). */
    @ExceptionHandler(MergeConflictException.class)
    public ResponseEntity<Map<String, Object>> handle(MergeConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Problems.of(409, e.type(), e.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyRequiredException.class)
    public ResponseEntity<Map<String, Object>> handle(IdempotencyKeyRequiredException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Problems.of(400, "idempotency-key-required", e.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyMismatchException.class)
    public ResponseEntity<Map<String, Object>> handle(IdempotencyKeyMismatchException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Problems.of(422, "idempotency-key-mismatch", e.getMessage()));
    }
```

- 맨 아래 `problem(...)` 본문을 `return Problems.of(status, "about:blank", detail);`로 바꾼다.

- [ ] **Step 6: `MergeDecisionService` 작성**

```java
package kr.trendstage.apiadmin.merge;

import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.judge.JudgeService;
import kr.trendstage.merge.MergeConflictException;
import kr.trendstage.merge.MergeService;
import kr.trendstage.persistence.entity.MergeQueueEntry;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.MergeQueueRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.MergeQueueStatus;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * ADM-100 큐 결정(병합·분리·VOID). 큐 행 FOR UPDATE → 멱등키 확인 → 항목 쌍 잠금(id 오름차순) → 실행을
 * 한 트랜잭션으로 한다(SP2 §2.4·§3). 멱등은 merge_queue.decision_key UNIQUE가 DB로 보장한다(K5).
 */
@Service
public class MergeDecisionService {

    public enum Decision { MERGE, SEPARATE, VOID }

    public record MergeDecisionResponse(String queueId, Decision decision, String status,
                                        String survivorId, String loserId,
                                        String decidedBy, String decidedAt, boolean replayed) {}

    /** stale = 대상 항목이 이미 다른 항목으로 병합돼 후보를 정리함(컨트롤러가 409 merge-target-merged로 응답). */
    public record Outcome(MergeDecisionResponse body, boolean stale) {}

    private final MergeQueueRepository mergeQueue;
    private final TrendItemRepository trendItems;
    private final MergeService mergeService;
    private final JudgeService judgeService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public MergeDecisionService(MergeQueueRepository mergeQueue, TrendItemRepository trendItems,
                                MergeService mergeService, JudgeService judgeService,
                                AuditLogService auditLogService, Clock clock) {
        this.mergeQueue = mergeQueue;
        this.trendItems = trendItems;
        this.mergeService = mergeService;
        this.judgeService = judgeService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Transactional
    public Outcome decide(UUID queueId, Decision decision, String key, UUID actorId, AdminRole actorRole, String reason) {
        MergeQueueEntry entry = mergeQueue.findByIdForUpdate(queueId)
                .orElseThrow(() -> new AdminValidationException("존재하지 않는 큐 항목입니다"));

        if (key.equals(entry.getDecisionKey())) {
            if (decisionOf(entry.getStatus()) != decision) {
                throw new IdempotencyKeyMismatchException("같은 멱등키로 다른 결정을 보냈습니다");
            }
            return new Outcome(responseOf(entry, true), false);
        }
        if (entry.getStatus() != MergeQueueStatus.PENDING) {
            throw new MergeConflictException(MergeConflictException.Reason.QUEUE_DECIDED,
                    "이미 처리된 후보입니다 (%s)".formatted(entry.getStatus().name()));
        }
        if (mergeQueue.findByDecisionKey(key).isPresent()) {
            throw new IdempotencyKeyMismatchException("이 멱등키는 다른 후보에 이미 쓰였습니다");
        }

        UUID newId = entry.getNewTrendItemId();
        UUID oldId = entry.getOldTrendItemId();
        Map<UUID, TrendItem> items = mergeService.lockPair(newId, oldId);
        TrendItem newItem = items.get(newId);
        TrendItem oldItem = items.get(oldId);
        Instant now = clock.instant();

        boolean stale = switch (decision) {
            case MERGE, SEPARATE -> newItem.getState() == TrendState.MERGED || oldItem.getState() == TrendState.MERGED;
            case VOID -> newItem.getState() == TrendState.MERGED;
        };
        if (stale) {
            // 상태 이름(SEPARATED 등)은 큐 워크플로 묶음(SP2b)에서 정리한다. 키는 기록하지 않는다 — 결정이 아니라 정리다.
            entry.resolve(MergeQueueStatus.SKIPPED, actorId, now, null);
            auditLogService.record(actorId, actorRole, "MERGE_QUEUE_STALE", "TREND_ITEM", newId, Map.of(
                    "queueId", queueId.toString(), "decision", decision.name()));
            return new Outcome(responseOf(entry, false), true);
        }

        switch (decision) {
            case MERGE -> {
                TrendItem survivor = newItem.getFirstSeenAt().isBefore(oldItem.getFirstSeenAt()) ? newItem : oldItem;
                TrendItem loser = survivor == newItem ? oldItem : newItem;
                mergeService.merge(survivor.getId(), loser.getId(), actorId, actorRole, reason);
                entry.resolve(MergeQueueStatus.MERGED, actorId, now, key);
            }
            case SEPARATE -> {
                mergeService.recordSeparateDecision(actorId, actorRole, newId, oldId, reason);
                entry.resolve(MergeQueueStatus.SKIPPED, actorId, now, key);
            }
            case VOID -> {
                // 항목 VOID는 판정 사건이다(P5) — 판정된 항목이면 원장 상쇄까지 JudgeService가 한다
                judgeService.voidItem(newId, reason, now);
                auditLogService.record(actorId, actorRole, "MERGE_VOID", "TREND_ITEM", newId,
                        Map.of("reason", reason == null ? "" : reason));
                entry.resolve(MergeQueueStatus.VOIDED, actorId, now, key);
            }
        }
        return new Outcome(responseOf(entry, false), false);
    }

    private MergeDecisionResponse responseOf(MergeQueueEntry e, boolean replayed) {
        String survivorId = null, loserId = null;
        if (e.getStatus() == MergeQueueStatus.MERGED) {
            TrendItem n = trendItems.findById(e.getNewTrendItemId()).orElseThrow();
            TrendItem o = trendItems.findById(e.getOldTrendItemId()).orElseThrow();
            TrendItem loser = n.getState() == TrendState.MERGED ? n : o;
            loserId = loser.getId().toString();
            survivorId = loser.getMergedInto().toString();
        }
        return new MergeDecisionResponse(e.getId().toString(), decisionOf(e.getStatus()), e.getStatus().name(),
                survivorId, loserId,
                e.getResolvedBy() == null ? null : e.getResolvedBy().toString(),
                e.getResolvedAt() == null ? null : e.getResolvedAt().toString(),
                replayed);
    }

    private static Decision decisionOf(MergeQueueStatus status) {
        return switch (status) {
            case MERGED -> Decision.MERGE;
            case SKIPPED -> Decision.SEPARATE;
            case VOIDED -> Decision.VOID;
            case PENDING -> null;
        };
    }
}
```

`MergeQueueStatus`의 값이 `PENDING, MERGED, VOIDED, SKIPPED` 네 개인지 확인한다. 다르면 switch를 실제 값에 맞춘다.

- [ ] **Step 7: 컨트롤러 결정 엔드포인트 교체**

`MergeQueueController.java`:

1. 필드 `judgeService`, `auditLogService`를 지우고 `private final MergeDecisionService decisions;`를 추가한다. 생성자도 맞춘다(파라미터 `JudgeService judgeService, AuditLogService auditLogService` 제거, `MergeDecisionService decisions` 추가). 사용하지 않게 된 import를 정리하고 다음을 추가한다:

```java
import kr.trendstage.apiadmin.merge.IdempotencyKeyRequiredException;
import kr.trendstage.apiadmin.merge.MergeDecisionService;
import kr.trendstage.apiadmin.merge.MergeDecisionService.Decision;
import kr.trendstage.apiadmin.merge.MergeDecisionService.Outcome;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
```

2. 클래스 상단 상수: `static final int MAX_IDEMPOTENCY_KEY_LENGTH = 80;`

3. `merge`, `separate`, `voidCandidate` 세 메서드를 다음으로 교체한다(`@Transactional`은 서비스가 맡으므로 컨트롤러에서 지운다):

```java
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
```

4. 클래스 Javadoc 첫 줄 뒤에 한 줄 추가: `결정 요청은 Idempotency-Key 필수(SP2 K5) — 같은 키 재요청은 이전 결과를 돌려준다.`

- [ ] **Step 8: 3인자 `resolve` 삭제**

`grep -rn "\.resolve(" backend --include=*.java`로 `MergeQueueEntry.resolve`의 3인자 호출이 남았는지 확인하고, 없으면 `MergeQueueEntry`의 3인자 오버로드를 지운다.

- [ ] **Step 9: 통과 확인**

Run: `./gradlew :app:test --tests kr.trendstage.merge.MergeIdempotencyTest --tests kr.trendstage.merge.MergeQueueApiTest`
Expected: PASS (10 tests).

- [ ] **Step 10: 전체 확인 + 커밋**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/merge/MergeDecisionService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/merge/IdempotencyKeyRequiredException.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/merge/IdempotencyKeyMismatchException.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/Problems.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminApiExceptionHandler.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/MergeQueueController.java backend/persistence/src/main/java/kr/trendstage/persistence/entity/MergeQueueEntry.java backend/app/src/test/java/kr/trendstage/merge/MergeIdempotencyTest.java backend/app/src/test/java/kr/trendstage/merge/MergeQueueApiTest.java
git commit -m "feat(api-admin): 병합 큐 결정에 행 잠금·Idempotency-Key·409 사유

같은 키 재요청은 이전 결과를 돌려주고, 다른 결정·다른 후보면 422, 이미 처리된 후보는 409.
대상 항목이 그사이 병합됐으면 후보를 정리하고 409 merge-target-merged.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 8: 병합된 항목 이름으로 들어온 제보·워치·시딩 합류, 동시 첫 제보

**Files:**
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/trend/TrendItemLookup.java`
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/trend/TrendItemCreator.java`
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/service/SubmissionService.java`
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/service/WatchService.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/seed/AdminSeedService.java`
- Test: `backend/app/src/test/java/kr/trendstage/merge/TombstoneJoinTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.trendstage.merge;

import kr.trendstage.apiadmin.seed.AdminSeedService;
import kr.trendstage.apipublic.service.SubmissionService;
import kr.trendstage.apipublic.service.WatchService;
import kr.trendstage.apipublic.web.DuplicateSubmissionException;
import kr.trendstage.apipublic.web.SubmissionCreateRequest;
import kr.trendstage.apipublic.web.WatchItemResponse;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.TrendCategory;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 스펙 §8 10·11·12번(K6, §4.2): 병합된 항목 이름은 승자로 합류, 같은 새 이름 동시 첫 제보는 한 항목으로. */
class TombstoneJoinTest extends AbstractIntegrationTest {

    @Autowired SubmissionService submissions;
    @Autowired WatchService watches;
    @Autowired AdminSeedService seeds;

    private Instant now;

    @BeforeEach
    void setUp() {
        now = clock.instant();
    }

    private static SubmissionCreateRequest req(String name) {
        return new SubmissionCreateRequest(name, TrendCategory.MEME, "X", "https://example.com", 30, false, "설명");
    }

    private UUID itemOfUserSubmission(UUID userId) {
        return jdbc.queryForObject("SELECT trend_item_id FROM submissions WHERE user_id = ?", UUID.class, userId);
    }

    @Test
    void submissionOnMergedNameJoinsSurvivor() {
        UUID s = fx.item(now.minus(Duration.ofDays(1)));
        UUID l = fx.item(now.minus(Duration.ofDays(1)));
        fx.merged(l, s);
        UUID u = fx.user();

        submissions.create(u, req(fx.key(l)));   // 지금까지는 422 item-closed

        assertThat(itemOfUserSubmission(u)).isEqualTo(s);
    }

    @Test
    void twoHopChainIsFollowed() {
        UUID s = fx.item(now.minus(Duration.ofDays(1)));
        UUID l = fx.item(now.minus(Duration.ofDays(1)));
        UUID l2 = fx.item(now.minus(Duration.ofDays(1)));
        fx.merged(l, s);
        fx.merged(l2, l);
        UUID u = fx.user();

        submissions.create(u, req(fx.key(l2)));

        assertThat(itemOfUserSubmission(u)).isEqualTo(s);
    }

    @Test
    void userWhoAlreadySubmittedOnSurvivorIsDuplicate() {
        UUID s = fx.item(now.minus(Duration.ofDays(1)));
        UUID l = fx.item(now.minus(Duration.ofDays(1)));
        fx.merged(l, s);
        UUID u = fx.user();
        fx.submission(u, s, 30, now.minus(Duration.ofHours(1)));

        assertThatThrownBy(() -> submissions.create(u, req(fx.key(l))))
                .isInstanceOf(DuplicateSubmissionException.class);
    }

    @Test
    void watchOnMergedChainShowsSurvivorStage() {
        UUID s = fx.item(now.minus(Duration.ofDays(1)));
        UUID sub1 = fx.submission(fx.user(), s, 30, now.minus(Duration.ofHours(2)));
        fx.submission(fx.user(), s, 30, now.minus(Duration.ofHours(1)));
        jdbc.update("UPDATE submissions SET source_platform = '인스타' WHERE id = ?", sub1);   // 플랫폼 2곳 → RISING
        UUID l = fx.item(now.minus(Duration.ofDays(1)));
        UUID l2 = fx.item(now.minus(Duration.ofDays(1)));
        fx.merged(l, s);
        fx.merged(l2, l);   // 한 단계만 따라가면 제보 없는 l에서 멈춰 SEED가 된다
        UUID u = fx.user();

        watches.add(u, fx.key(l2));
        List<WatchItemResponse> list = watches.list(u);

        assertThat(list).singleElement().extracting(WatchItemResponse::stage).isEqualTo("RISING");
    }

    @Test
    void seedOnMergedNameJoinsSurvivor() {
        UUID s = fx.item(now.minus(Duration.ofDays(1)));
        UUID l = fx.item(now.minus(Duration.ofDays(1)));
        fx.merged(l, s);
        UUID admin = fx.admin();

        AdminSeedService.SeedSubmissionResult r = seeds.registerSeed(admin, AdminRole.ADMIN,
                new AdminSeedService.SeedSubmissionRequest(fx.key(l), "MEME", "X", "https://example.com", 30, "설명"));

        assertThat(r.trendItemId()).isEqualTo(s.toString());
    }

    @Test
    void concurrentFirstSubmissionsOfSameNewNameConvergeOnOneItem() throws Exception {
        String name = "동시첫제보-" + UUID.randomUUID().toString().substring(0, 8);
        UUID u1 = fx.user(), u2 = fx.user();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (UUID u : List.of(u1, u2)) {
            futures.add(pool.submit(() -> {
                start.await();
                submissions.create(u, req(name));
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);   // 500(UNIQUE 위반)이면 여기서 ExecutionException
        pool.shutdown();

        Integer items = jdbc.queryForObject("SELECT count(*) FROM trend_items WHERE normalized_key = ?",
                Integer.class, name.toLowerCase());
        assertThat(items).isEqualTo(1);
        assertThat(itemOfUserSubmission(u1)).isEqualTo(itemOfUserSubmission(u2));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:test --tests kr.trendstage.merge.TombstoneJoinTest`
Expected: FAIL — 병합된 이름 제보는 `ItemClosedException`, 두 단계 워치는 `SEED`, 시딩은 패자 id, 동시 첫 제보는 `ExecutionException`(UNIQUE 위반). `userWhoAlreadySubmittedOnSurvivorIsDuplicate`는 지금도 `ItemClosedException`이라 실패.

- [ ] **Step 3: `TrendItemLookup` 작성**

```java
package kr.trendstage.persistence.trend;

import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 완전일치 조회가 병합된 항목(tombstone)을 만나면 merged_into를 따라 살아 있는 승자까지 간다(SP2 K6).
 * 패자의 normalized_key는 watches FK가 참조해 비울 수 없으므로(V24) 조회 쪽에서 따라간다.
 * 제보·워치·시딩이 이 컴포넌트 하나를 쓴다.
 */
@Component
public class TrendItemLookup {

    static final int MAX_HOPS = 10;

    private final TrendItemRepository trendItems;

    public TrendItemLookup(TrendItemRepository trendItems) {
        this.trendItems = trendItems;
    }

    public Optional<TrendItem> findLiveByNormalizedKey(String normalizedKey) {
        return trendItems.findByNormalizedKey(normalizedKey).map(this::followToLive);
    }

    /** 순환이나 비정상적으로 긴 체인은 데이터 손상이다 — 조용히 넘기지 않는다. */
    public TrendItem followToLive(TrendItem item) {
        TrendItem current = item;
        Set<UUID> seen = new HashSet<>();
        while (current.getState() == TrendState.MERGED) {
            if (!seen.add(current.getId()) || seen.size() > MAX_HOPS) {
                throw new IllegalStateException("병합 체인이 순환하거나 너무 깁니다: " + item.getId());
            }
            UUID next = current.getMergedInto();
            current = trendItems.findById(next)
                    .orElseThrow(() -> new IllegalStateException("병합 승자가 없습니다: " + next));
        }
        return current;
    }
}
```

- [ ] **Step 4: `TrendItemCreator` 작성**

```java
package kr.trendstage.persistence.trend;

import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.TrendCategory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 새 항목 생성. 같은 키가 동시에 처음 들어와도 500 없이 먼저 생긴 항목에 합류한다(SP2 §4.2).
 * INSERT … ON CONFLICT DO NOTHING은 예외를 던지지 않아 호출 트랜잭션을 abort시키지 않는다
 * (PostgreSQL에서 UNIQUE 위반 예외는 트랜잭션 전체를 실패 상태로 만든다).
 */
@Component
public class TrendItemCreator {

    /** created = 이 호출이 새로 만들었음. false면 먼저 생긴 항목에 합류 — 호출 측이 마감·중복 검사를 해야 한다. */
    public record Result(TrendItem item, boolean created) {}

    private final JdbcTemplate jdbc;
    private final TrendItemRepository trendItems;
    private final TrendItemLookup lookup;

    public TrendItemCreator(JdbcTemplate jdbc, TrendItemRepository trendItems, TrendItemLookup lookup) {
        this.jdbc = jdbc;
        this.trendItems = trendItems;
        this.lookup = lookup;
    }

    public Result createOrJoin(String canonicalName, String normalizedKey, TrendCategory category, Instant firstSeenAt) {
        List<UUID> inserted = jdbc.queryForList(
                "INSERT INTO trend_items (canonical_name, normalized_key, category, state, first_seen_at) "
                        + "VALUES (?, ?, ?::trend_category, 'PENDING', ?) "
                        + "ON CONFLICT (normalized_key) DO NOTHING RETURNING id",
                UUID.class, canonicalName, normalizedKey, category.name(), Timestamp.from(firstSeenAt));
        if (!inserted.isEmpty()) {
            return new Result(trendItems.findById(inserted.get(0)).orElseThrow(), true);
        }
        TrendItem existing = lookup.findLiveByNormalizedKey(normalizedKey)
                .orElseThrow(() -> new IllegalStateException("키 충돌 뒤 항목을 찾지 못했습니다: " + normalizedKey));
        return new Result(existing, false);
    }
}
```

- [ ] **Step 5: `SubmissionService` 수정**

1. import 추가: `kr.trendstage.persistence.trend.TrendItemCreator`, `kr.trendstage.persistence.trend.TrendItemLookup`. `TrendState` import는 `requireOpen`에서 계속 쓴다.
2. 필드·생성자에 `TrendItemLookup lookup`, `TrendItemCreator creator`를 추가한다:

```java
    private final TrendItemLookup lookup;
    private final TrendItemCreator creator;

    public SubmissionService(TrendItemRepository trends, SubmissionRepository submissions,
                             SubmissionOrderRankRepository orderRanks, UserRepository users,
                             QuotaService quotaService, TrendItemLookup lookup, TrendItemCreator creator, Clock clock) {
        this.trends = trends; this.submissions = submissions; this.orderRanks = orderRanks;
        this.users = users; this.quotaService = quotaService; this.lookup = lookup; this.creator = creator;
        this.clock = clock;
    }
```

3. `create()`의 `TrendItem item = trends.findByNormalizedKey(normalized).orElse(null);`부터 `if (item == null) { … }` 블록 끝까지를 교체:

```java
        // 병합된 항목 이름이면 승자로 합류한다(SP2 K6). 제보의 normalized_key에는 유저가 입력한 키가 그대로 남는다.
        TrendItem item = lookup.findLiveByNormalizedKey(normalized).orElse(null);
        if (item != null) {
            requireOpenAndNotDuplicate(item, userId, now);
        }

        QuotaService.Quota quota = quotaService.of(userId, now);
        if (quota.remaining() == 0) {
            throw new QuotaExhaustedException("이번 주 제보권 %d/%d장을 모두 썼습니다 · 월요일 00:00에 다시 채워집니다"
                    .formatted(quota.used(), quota.max()));
        }

        if (item == null) {
            TrendItemCreator.Result created = creator.createOrJoin(req.name(), normalized, req.category(), now);
            item = created.item();
            if (!created.created()) {
                requireOpenAndNotDuplicate(item, userId, now);   // 동시 첫 제보 — 먼저 생긴 항목에 합류
            }
        }
```

4. `requireOpen` 위에 메서드를 추가하고, `requireOpen`에서 MERGED 분기(주석 포함 4줄)를 지운다:

```java
    private void requireOpenAndNotDuplicate(TrendItem item, UUID userId, Instant now) {
        requireOpen(item, now);
        boolean dup = submissions.existsByTrendItemIdAndUserIdAndResultNot(item.getId(), userId, SubmissionResult.VOID);
        if (dup) {
            long rank = submissions.countByTrendItemIdAndResultNot(item.getId(), SubmissionResult.VOID);
            throw new DuplicateSubmissionException(item.getId(), (int) rank,
                    "이미 제보한 항목입니다 — 동의로 전환할 수 있습니다");
        }
    }
```

`requireOpen`은 이제 다음만 남는다:

```java
    /** 관측 창(기본 D+14, 유예 연장 반영)이 열려 있는 PENDING 항목만 제보를 받는다(J6). 거부해도 제보권은 쓰지 않는다. */
    private static void requireOpen(TrendItem item, Instant now) {
        Instant deadline = DeadlineWindow.effectiveDeadline(item.getFirstSeenAt(), item.getJudgmentDeadlineOverride());
        if (item.getState() != TrendState.PENDING || !now.isBefore(deadline)) {
            throw new ItemClosedException("관측이 끝난 트렌드입니다 — 판정이 끝났거나 진행 중이라 새 제보를 받지 않습니다");
        }
    }
```

- [ ] **Step 6: `WatchService` 수정**

생성자에 `TrendItemLookup lookup`을 추가하고(필드도), `toResponse`의 처음 네 줄:

```java
        TrendItem item = trends.findByNormalizedKey(w.getNormalizedKey()).orElse(null);
        if (item != null && item.getState() == TrendState.MERGED && item.getMergedInto() != null) {
            item = trends.findById(item.getMergedInto()).orElse(item);
        }
```

를 다음으로 교체한다:

```java
        // 병합 체인을 끝까지 따라간다(한 단계만 보면 중간 tombstone에서 멈춘다)
        TrendItem item = lookup.findLiveByNormalizedKey(w.getNormalizedKey()).orElse(null);
```

`TrendState` import가 다른 곳에서 쓰이는지 확인하고 안 쓰이면 지운다.

- [ ] **Step 7: `AdminSeedService` 수정**

생성자에 `TrendItemLookup lookup`, `TrendItemCreator creator`를 추가하고(필드도), `registerSeed`의

```java
        TrendItem item = trendItems.findByNormalizedKey(normalized).orElse(null);
        if (item != null) {
            boolean dup = submissions.existsByTrendItemIdAndUserIdAndResultNot(
                    item.getId(), seedUserId, SubmissionResult.VOID);
            if (dup) throw new AdminValidationException("이미 이 계정으로 시딩한 항목입니다");
        } else {
            item = trendItems.save(new TrendItem(req.name(), normalized, category, now));
            item.transitionTo(TrendState.PENDING);
        }
```

를 다음으로 교체한다:

```java
        // 병합된 항목 이름이면 승자로(SP2 K6), 새 이름이면 생성 — 동시 생성은 먼저 생긴 항목에 합류
        TrendItem item = lookup.findLiveByNormalizedKey(normalized)
                .orElseGet(() -> creator.createOrJoin(req.name(), normalized, category, now).item());
        boolean dup = submissions.existsByTrendItemIdAndUserIdAndResultNot(
                item.getId(), seedUserId, SubmissionResult.VOID);
        if (dup) throw new AdminValidationException("이미 이 계정으로 시딩한 항목입니다");
```

`TrendState` import가 더 이상 쓰이지 않으면 지운다. 람다 안에서 쓰는 `normalized`, `category`, `now`는 effectively final이어야 한다 — 재대입이 있으면 지역 변수를 하나 더 둔다.

- [ ] **Step 8: 통과 확인**

Run: `./gradlew :app:test --tests kr.trendstage.merge.TombstoneJoinTest --tests kr.trendstage.submission.SubmissionQuotaIntegrationTest`
Expected: PASS. 제보권 테스트(SP1)가 그대로 통과해야 한다.

- [ ] **Step 9: 전체 확인 + 커밋**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

```bash
git add backend/persistence/src/main/java/kr/trendstage/persistence/trend/TrendItemLookup.java backend/persistence/src/main/java/kr/trendstage/persistence/trend/TrendItemCreator.java backend/api-public/src/main/java/kr/trendstage/apipublic/service/SubmissionService.java backend/api-public/src/main/java/kr/trendstage/apipublic/service/WatchService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/seed/AdminSeedService.java backend/app/src/test/java/kr/trendstage/merge/TombstoneJoinTest.java
git commit -m "feat(persistence,api-public): 병합된 항목 이름은 승자로 합류, 동시 첫 제보는 한 항목으로

패자 키는 watches FK 때문에 비울 수 없어 조회가 merged_into 체인을 따라간다.
제보·워치·시딩이 같은 TrendItemLookup을 쓰고, 새 항목은 ON CONFLICT로 만들어 500을 없앤다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 9: API 계약 (`openapi.yaml`)

**Files:**
- Modify: `backend/api-spec/openapi.yaml`

- [ ] **Step 1: 구현되지 않은 병합 엔드포인트 교체**

`/admin/merge-queue/{id}/action:` 블록 전체(`post:`부터 `'409': { description: 다른 검수자가 이미 처리함 — 낙관적 락 충돌(03 §6) }`까지)를 다음으로 교체한다:

```yaml
  /admin/merge-queue/{id}/preview:
    get:
      tags: [admin-queues]
      summary: 병합 후 미리보기(dry-run) — 선점 순위(시딩 제외)·중복 VOID·제보권 반환·관측 마감 조정
      security: [{ cookieAuth: [] }]
      parameters:
        - { name: id, in: path, required: true, schema: { type: string, format: uuid } }
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: { $ref: '#/components/schemas/MergePreview' }
        '422': { description: 없는 후보이거나 이미 처리됨 }
  /admin/merge-queue/{id}/merge:
    post:
      tags: [admin-queues]
      summary: 병합 확정 (REVIEWER 이상) — 늦게 생긴 항목을 먼저 생긴 항목으로 흡수
      description: |
        두 항목을 id 오름차순으로 행 잠금한 뒤, 둘 다 판정 전 상태이고 현행 판정 행이 없을 때만 병합한다.
        병합으로 관측 마감이 병합 시점 + 3일보다 이르면 그 시점으로 연장한다(상한 최초 제보 + 21일).
      security: [{ cookieAuth: [] }]
      parameters:
        - { name: id, in: path, required: true, schema: { type: string, format: uuid } }
        - $ref: '#/components/parameters/IdempotencyKey'
      requestBody:
        content:
          application/json:
            schema: { $ref: '#/components/schemas/MergeDecisionRequest' }
      responses:
        '200': { $ref: '#/components/responses/MergeDecided' }
        '400': { $ref: '#/components/responses/IdempotencyKeyRequired' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '409': { $ref: '#/components/responses/MergeConflict' }
        '422': { $ref: '#/components/responses/IdempotencyKeyMismatch' }
  /admin/merge-queue/{id}/separate:
    post:
      tags: [admin-queues]
      summary: 별도 항목으로 분리 (REVIEWER 이상) — 후보 기각, 데이터 변경 없음
      security: [{ cookieAuth: [] }]
      parameters:
        - { name: id, in: path, required: true, schema: { type: string, format: uuid } }
        - $ref: '#/components/parameters/IdempotencyKey'
      requestBody:
        content:
          application/json:
            schema: { $ref: '#/components/schemas/MergeDecisionRequest' }
      responses:
        '200': { $ref: '#/components/responses/MergeDecided' }
        '400': { $ref: '#/components/responses/IdempotencyKeyRequired' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '409': { $ref: '#/components/responses/MergeConflict' }
        '422': { $ref: '#/components/responses/IdempotencyKeyMismatch' }
  /admin/merge-queue/{id}/void:
    post:
      tags: [admin-queues]
      summary: 새 항목 VOID (OPERATOR 이상) — 판정된 항목이면 원장 상쇄까지
      security: [{ cookieAuth: [] }]
      parameters:
        - { name: id, in: path, required: true, schema: { type: string, format: uuid } }
        - $ref: '#/components/parameters/IdempotencyKey'
      requestBody:
        content:
          application/json:
            schema: { $ref: '#/components/schemas/MergeDecisionRequest' }
      responses:
        '200': { $ref: '#/components/responses/MergeDecided' }
        '400': { $ref: '#/components/responses/IdempotencyKeyRequired' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '409': { $ref: '#/components/responses/MergeConflict' }
        '422': { $ref: '#/components/responses/IdempotencyKeyMismatch' }
```

- [ ] **Step 2: 컴포넌트 추가**

`components:` 아래 `parameters:` 섹션이 있으면 거기에, 없으면 `components:` 바로 아래에 새로 만든다:

```yaml
  parameters:
    IdempotencyKey:
      name: Idempotency-Key
      in: header
      required: true
      schema: { type: string, minLength: 1, maxLength: 80 }
      description: 결정 요청의 멱등키. 같은 키 재요청은 이전 결과를 그대로 돌려준다(merge_queue.decision_key UNIQUE).
```

`components.responses:`에 추가:

```yaml
    MergeDecided:
      description: 처리됨(같은 키 재요청이면 replayed=true)
      content: { application/json: { schema: { $ref: '#/components/schemas/MergeDecisionResponse' } } }
    MergeConflict:
      description: |
        병합 불가. type으로 구분 — merge-judging(판정 중, 재시도 의미 있음) · merge-resolved(판정 완료·VOID·판정 행 존재, 영구)
        · merge-target-merged(대상이 이미 병합됨, 후보를 정리함) · merge-queue-decided(다른 요청이 이미 처리함)
      content: { application/json: { schema: { $ref: '#/components/schemas/Problem' } } }
    IdempotencyKeyRequired:
      description: Idempotency-Key 헤더 없음 또는 형식 오류 (type idempotency-key-required)
      content: { application/json: { schema: { $ref: '#/components/schemas/Problem' } } }
    IdempotencyKeyMismatch:
      description: 같은 키로 다른 결정, 또는 키가 다른 후보에 이미 쓰임 (type idempotency-key-mismatch)
      content: { application/json: { schema: { $ref: '#/components/schemas/Problem' } } }
```

`components.schemas:`에 추가(`ClusterMergeResult` 근처):

```yaml
    MergeDecisionRequest:
      type: object
      properties:
        reason: { type: string, description: 감사 로그 기록 }
    MergeDecisionResponse:
      type: object
      required: [queueId, decision, status, replayed]
      properties:
        queueId: { type: string, format: uuid }
        decision: { type: string, enum: [MERGE, SEPARATE, VOID] }
        status: { type: string, enum: [MERGED, SKIPPED, VOIDED] }
        survivorId: { type: [string, 'null'], format: uuid }
        loserId: { type: [string, 'null'], format: uuid }
        decidedBy: { type: [string, 'null'], format: uuid }
        decidedAt: { type: [string, 'null'], format: date-time }
        replayed: { type: boolean }
    MergePreview:
      type: object
      properties:
        newCanonicalName: { type: string }
        orderRank:
          type: array
          items:
            type: object
            properties:
              handle: { type: string }
              rankBefore: { type: [integer, 'null'] }
              rankAfter: { type: [integer, 'null'], description: 시딩이면 null }
              seed: { type: boolean }
        firstSeenAtBefore: { type: string }
        firstSeenAtAfter: { type: string }
        deadlineBefore: { type: string }
        deadlineAfter: { type: string }
        deadlineGuarded: { type: boolean, description: 병합으로 최소 관측 3일 보장이 적용됨 }
        dedupVoidedHandles: { type: array, items: { type: string } }
        quotaRefundHandles: { type: array, items: { type: string }, description: 중복 VOID로 제보권이 돌아가는 제보자(시딩 제외) }
```

`ClusterMergeResult` 스키마를 교체:

```yaml
    ClusterMergeResult:
      type: object
      required: [candidates, autoMerged, queued, separated, skippedJudged, deferred, failed]
      properties:
        candidates: { type: integer }
        autoMerged: { type: integer }
        queued: { type: integer }
        separated: { type: integer }
        skippedJudged: { type: integer, description: 판정된 항목과 닮아 병합하지 않고 기록만 함 }
        deferred: { type: integer, description: 판정·다른 병합과 경합해 다음 실행에서 다시 봄 }
        failed: { type: integer }
```

`Problem.type` 설명(약 965행)을 교체:

```yaml
        type: { type: string, description: "about:blank | quota-exhausted | item-closed | merge-judging | merge-resolved | merge-target-merged | merge-queue-decided | idempotency-key-required | idempotency-key-mismatch" }
```

제보 422 설명(약 137행)의 `item-closed: 관측이 끝난 항목(마감 이후·판정 중·판정 완료·VOID·병합됨).`에서 `·병합됨`을 지우고 문장 끝에 ` 병합된 항목 이름으로 제보하면 병합 승자에 합류한다.`를 덧붙인다.

- [ ] **Step 3: 검증 + 커밋**

Run: `python -c "import yaml;yaml.safe_load(open('backend/api-spec/openapi.yaml',encoding='utf-8'))" && grep -n "merge-queue/{id}/action" backend/api-spec/openapi.yaml`
Expected: 첫 명령 오류 없음, 두 번째는 출력 없음. python yaml이 없으면 들여쓰기를 눈으로 대조한다.

```bash
git add backend/api-spec/openapi.yaml
git commit -m "docs(api-spec): 병합 큐 결정 계약을 구현에 맞춤 — Idempotency-Key, 409 type, 미리보기 필드

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 10: ADM-100 콘솔

**Files:**
- Modify: `admin/src/api/client.ts`
- Modify: `admin/src/api/hooks.ts`
- Modify: `admin/src/api/types.ts`
- Modify: `admin/src/fixtures.ts`
- Modify: `admin/src/screens/MergeQueueScreen.tsx`
- Modify: `admin/src/screens/BatchJobsScreen.tsx`

- [ ] **Step 1: `client.ts` — 오류 type, 요청별 헤더**

`ApiError`를 교체:

```ts
export class ApiError extends Error {
  /** type: 서버 오류 본문의 type(about:blank면 undefined) — 화면이 사유별로 안내할 때 쓴다. */
  constructor(public status: number, message: string, public type?: string) {
    super(message);
  }
}
```

`request` 시그니처와 헤더·오류 처리부를 교체한다(나머지는 그대로):

```ts
async function request<T>(method: string, path: string, body?: unknown, extraHeaders?: Record<string, string>): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json", ...extraHeaders };
```

```ts
  if (!res.ok) {
    let detail = res.statusText;
    let type: string | undefined;
    try {
      const p = await res.json();
      detail = p.detail ?? p.message ?? detail;
      type = p.type && p.type !== "about:blank" ? p.type : undefined;
    } catch {
      /* non-json */
    }
    throw new ApiError(res.status, detail, type);
  }
```

`api.post`를 교체:

```ts
  post: <T>(p: string, b?: unknown, h?: Record<string, string>) => request<T>("POST", p, b, h),
```

- [ ] **Step 2: `types.ts`**

`OrderEntry`, `MergePreview`, `ClusterMergeResult`를 교체하고 `MergeDecisionResponse`를 추가한다:

```ts
export interface OrderEntry {
  handle: string;
  rankBefore: number | null;
  /** 시딩이면 null */
  rankAfter: number | null;
  seed: boolean;
}

export interface MergePreview {
  newCanonicalName: string;
  orderRank: OrderEntry[];
  firstSeenAtBefore: string;
  firstSeenAtAfter: string;
  deadlineBefore: string;
  deadlineAfter: string;
  /** 병합으로 관측 기간이 줄어 최소 3일 보장이 적용됨 */
  deadlineGuarded: boolean;
  dedupVoidedHandles: string[];
  /** 중복 VOID로 제보권이 돌아가는 제보자(시딩 제외) */
  quotaRefundHandles: string[];
}

export interface MergeDecisionResponse {
  queueId: string;
  decision: "MERGE" | "SEPARATE" | "VOID";
  status: "MERGED" | "SKIPPED" | "VOIDED";
  survivorId: string | null;
  loserId: string | null;
  decidedBy: string | null;
  decidedAt: string | null;
  replayed: boolean;
}
```

```ts
export interface ClusterMergeResult {
  candidates: number;
  autoMerged: number;
  queued: number;
  separated: number;
  /** 판정된 항목과 닮아 병합하지 않고 기록만 함 */
  skippedJudged: number;
  /** 판정·다른 병합과 경합해 다음 실행에서 다시 봄 */
  deferred: number;
  failed: number;
}
```

- [ ] **Step 3: `hooks.ts` — 멱등키**

`client` import에 `ApiError`를 추가하고(예: `import { api, ApiError, USE_FIXTURES } from "./client";` — 실제 import 줄에 맞춰서), `types` import에 `MergeDecisionResponse`를 추가한다. `decideMergeCandidate`를 교체:

```ts
/**
 * 병합 큐 결정. 결정마다 멱등키를 하나 만들고, 네트워크 오류·5xx일 때만 같은 키로 한 번 재시도한다 —
 * 서버가 이미 처리했으면 같은 결과를 돌려준다(SP2 K5). 4xx는 서버가 판단을 끝낸 것이라 재시도하지 않는다.
 */
export async function decideMergeCandidate(
  id: string,
  action: "merge" | "separate" | "void",
  reason: string,
): Promise<MergeDecisionResponse> {
  const key = crypto.randomUUID();
  const send = () =>
    api.post<MergeDecisionResponse>(`/admin/merge-queue/${id}/${action}`, { reason }, { "Idempotency-Key": key });
  try {
    return await send();
  } catch (e) {
    if (e instanceof ApiError && e.status < 500) throw e;
    return await send();
  }
}
```

- [ ] **Step 4: `fixtures.ts`**

`fxMergePreview`를 교체:

```ts
export const fxMergePreview: MergePreview = {
  newCanonicalName: "새싹 챌린지",
  orderRank: [
    { handle: "user_4410", rankBefore: 1, rankAfter: 1, seed: false },
    { handle: "user_1122", rankBefore: 2, rankAfter: 2, seed: false },
    { handle: "user_8821", rankBefore: 1, rankAfter: 3, seed: false },
    { handle: "seed_ops", rankBefore: null, rankAfter: null, seed: true },
  ],
  firstSeenAtBefore: "2026-08-11 09:00",
  firstSeenAtAfter: "2026-08-10 21:00",
  deadlineBefore: "2026-08-25 09:00",
  deadlineAfter: "2026-08-27 14:00",
  deadlineGuarded: true,
  dedupVoidedHandles: ["user_1122"],
  quotaRefundHandles: ["user_1122"],
};
```

`fxClusterMergeResult`를 교체:

```ts
export const fxClusterMergeResult: ClusterMergeResult = {
  candidates: 8, autoMerged: 1, queued: 2, separated: 4, skippedJudged: 1, deferred: 0, failed: 0,
};
```

- [ ] **Step 5: `MergeQueueScreen.tsx`**

(a) 컴포넌트 함수 바깥(파일 상단 import 다음)에 추가:

```tsx
/** 서버 409·422 type별 안내(SP2 K2). 없는 type은 서버 detail을 그대로 보여준다. */
const DECISION_MESSAGES: Record<string, string> = {
  "merge-judging": "판정 중인 항목입니다. 잠시 후 다시 시도하세요",
  "merge-resolved": "이미 판정된 항목은 병합할 수 없습니다 (판정 후 병합은 Phase 2)",
  "merge-queue-decided": "이미 처리된 후보입니다 — 목록을 새로 고칩니다",
  "merge-target-merged": "대상 항목이 이미 다른 항목으로 병합됐습니다 — 목록을 새로 고칩니다",
  "idempotency-key-mismatch": "요청이 꼬였습니다. 새로고침 후 다시 시도하세요",
};
```

(b) `act`의 `catch` 블록을 교체:

```tsx
    } catch (e) {
      if (e instanceof ApiError && e.type && DECISION_MESSAGES[e.type]) {
        flash(DECISION_MESSAGES[e.type]);
        if (e.type === "merge-queue-decided" || e.type === "merge-target-merged") {
          await qc.invalidateQueries({ queryKey: ["admin", "merge-queue"] });
        }
      } else {
        flash(e instanceof ApiError ? e.message : `${label} 처리에 실패했습니다`);
      }
    } finally {
```

(c) 상단의 단축키 안내(`{[["J/K", "이동"], ["M", "병합"], ["S", "분리"], ["V", "VOID"]].map(...)}`를 감싼 `<span style={{ display: "flex", gap: 5 }}> … </span>`)를 통째로 지운다 — 연결된 핸들러가 없다.

(d) "보류 → 다음" 버튼을 교체:

```tsx
                    <Btn disabled title="미구현 — 큐 워크플로 묶음(SP2b)에서 서버와 연결">보류 (미구현)</Btn>
```

(e) 미리보기 모달의 순위 목록 항목을 교체:

```tsx
                      <div key={i} style={{ display: "flex", justifyContent: "space-between", font: "500 12px ui-monospace, monospace" }}>
                        <span>{o.handle}</span>
                        <span>{o.seed ? "시딩 · 순위 없음" : `${o.rankBefore ?? "-"} → ${o.rankAfter}`}</span>
                      </div>
```

(f) 최초 목격 시각 블록의 `{previewData.baselineShifted && ( … )}`를 지우고, 그 블록(`</div>`로 닫히는 "최초 목격 시각" 영역) 바로 다음에 추가:

```tsx
                <div style={{ marginTop: 14 }}>
                  <Label>관측 마감</Label>
                  <div style={{ font: "500 12.5px Pretendard" }}>{previewData.deadlineBefore} → {previewData.deadlineAfter}</div>
                  {previewData.deadlineGuarded && (
                    <div style={{ marginTop: 8, padding: "9px 12px", borderRadius: 8, background: "rgba(216,150,60,0.1)", color: C.sub, font: "600 11.5px Pretendard" }}>
                      병합으로 관측 기간이 줄어 병합 시점부터 최소 3일을 보장합니다 (상한: 최초 제보 + 21일)
                    </div>
                  )}
                </div>
```

(g) `dedupVoidedHandles` 블록 다음에 추가:

```tsx
                {previewData.quotaRefundHandles.length > 0 && (
                  <div style={{ marginTop: 8, padding: "9px 12px", borderRadius: 8, background: "rgba(20,19,15,0.05)", font: "500 11.5px Pretendard", color: C.sub }}>
                    제보권 1장이 반환될 제보자: {previewData.quotaRefundHandles.join(", ")}
                  </div>
                )}
```

(h) `flash(\`${label} 처리됨 (감사 로그 기록)\`)`는 그대로 둔다(병합·분리·VOID는 서버가 감사 로그를 남긴다).

- [ ] **Step 6: `BatchJobsScreen.tsx`**

결과 요약 줄(`후보 {result.candidates}건 · 자동병합 … · 별개확정 {result.separated}`)의 끝 `{result.separated}` 뒤에 ` · 판정된 항목과 유사 {result.skippedJudged} · 보류 {result.deferred}`를 덧붙인다.

- [ ] **Step 7: 빌드 + 커밋**

Run: `npm run build --prefix admin`
Expected: 오류 없음. `baselineShifted`·`rankAfter` 숫자 가정이 남아 있으면 타입 오류로 드러난다.

```bash
git add admin/src/api/client.ts admin/src/api/hooks.ts admin/src/api/types.ts admin/src/fixtures.ts admin/src/screens/MergeQueueScreen.tsx admin/src/screens/BatchJobsScreen.tsx
git commit -m "feat(admin): ADM-100 결정에 멱등키·409 사유별 안내, 미리보기에 시딩·제보권 반환·마감 조정

보류 버튼(서버 호출 없던 가짜)은 미구현 표시, 동작하지 않던 단축키 안내는 제거.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 11: 문서

**Files:**
- Modify: `CLAUDE.md`, `03-merge-clustering.md`, `05-screen-endpoint-map.md`, `02-admin-console.md`, `docs/superpowers/specs/2026-09-17-design-review-design.md`

- [ ] **Step 1: `CLAUDE.md`**

(a) "⚠ first_seen_at 이 바뀌면 order_rank 재계산, 판정 후면 병합 금지" 절의 `> **미구현(SP2).** …` 인용 블록 전체를 다음으로 교체:

```markdown
> **구현(SP2).** `MergeService`가 두 항목을 id 오름차순으로 `SELECT … FOR UPDATE` 잠근 뒤 `MergeGuard`로 판정 전 상태(DRAFT·PENDING) + 현행 판정 행 없음을 확인하고, 아니면 409(`merge-judging`·`merge-resolved`)로 거부한다. `cluster_merge`는 판정된 항목과 닮으면 병합하지 않고 감사 로그 `MERGE_SKIPPED_JUDGED`만 남긴다. 순위 재계산은 뷰라서 자동이고 `MergeRecomputeTest`로 건다. 병합으로 관측 마감이 병합 시점 + 3일보다 당겨지면 그 시점까지 연장한다(상한 최초 제보 + 21일).
```

(b) 배치 표 `cluster_merge` 행 비고를 `임베딩 병합 + 관리자 큐 적재. 판정된 항목과 닮으면 병합하지 않고 기록만(SP2). ADM-900에서 수동 실행 가능`으로 바꾼다.

(c) "코드 작성 시 규약"의 `병합 연산은 **단일 트랜잭션 + 행 잠금 + 멱등성 키**.` 줄 끝에 ` 큐 결정은 `Idempotency-Key` 필수 — `merge_queue.decision_key` UNIQUE(V29)가 보장, 잠금 순서는 큐 행 → 항목(id 오름차순).`을 덧붙인다.

- [ ] **Step 2: `03-merge-clustering.md`**

`grep -n "미구현\|SP2\|잠금\|멱등\|Idempotency\|tombstone\|MERGED\|baseline" 03-merge-clustering.md`로 해당 절을 찾아 다음을 실제 구현으로 고친다:
- 트랜잭션 절차: 두 항목 id 오름차순 `FOR UPDATE`, 가드(판정 전 상태 + 판정 행 없음), 409 type 두 종류, 최소 관측 3일(상한 D+21).
- 동시성 절: 큐 결정은 큐 행 잠금 → 항목 잠금 순서, 멱등키(`decision_key` UNIQUE, 같은 키 재요청은 이전 결과, 다른 결정 422, 이미 처리 409). "(SP2에서 구현)" 같은 미래형 표기는 현재형으로.
- 완전일치 조회: 병합된 항목 이름은 `merged_into` 체인을 따라 승자로 합류(제보·워치·시딩 공통, `TrendItemLookup`), 동시 첫 제보는 `ON CONFLICT`로 한 항목에 수렴.
- 큐 상태 모델(클레임·보류·에스컬레이션)과 정규화 규칙은 **다음 묶음(SP2b)** 표기를 유지하거나 새로 단다. 구현된 것처럼 쓰지 않는다.

- [ ] **Step 3: `05-screen-endpoint-map.md`**

ADM-100 행들: 병합·분리·VOID 엔드포인트 비고에 `Idempotency-Key 필수 · 409 type(merge-judging·merge-resolved·merge-target-merged·merge-queue-decided) · 422 idempotency-key-mismatch`를, 미리보기에 `시딩 제외 순위·제보권 반환 대상·관측 마감 조정`을 적는다. "보류"는 `미구현(SP2b)`로 표기한다. openapi와 구현이 어긋난다는 기존 서술이 있으면 해소됐다고 고친다.

- [ ] **Step 4: `02-admin-console.md`**

ADM-100 절에서 J/K/M/S/V 단축키와 "보류 → 다음"이 동작하는 것처럼 적힌 부분을 `미구현(SP2b)`으로 표기한다. 멱등키·409 안내 문구를 한 줄로 적는다.

- [ ] **Step 5: 상위 스펙**

`docs/superpowers/specs/2026-09-17-design-review-design.md` §3 표의 SP2 행 범위 끝에 ` → 무결성 묶음은 `2026-09-21-sp2-merge-integrity-design.md`, 큐 워크플로(클레임·보류·에스컬레이션·ADM-100 UI)·정규화는 SP2b`를 덧붙인다.

- [ ] **Step 6: 검증 + 커밋**

Run: `grep -n "미구현(SP2)" CLAUDE.md 03-merge-clustering.md 05-screen-endpoint-map.md 02-admin-console.md`
Expected: 이번에 구현한 항목(가드·잠금·멱등키·tombstone 합류)에 대한 `미구현(SP2)` 표기가 남아 있지 않다. 큐 워크플로·정규화 표기는 `SP2b`로 바뀌어 있다.

```bash
git add CLAUDE.md 03-merge-clustering.md 05-screen-endpoint-map.md 02-admin-console.md docs/superpowers/specs/2026-09-17-design-review-design.md
git commit -m "docs: SP2 병합 무결성 반영 — 가드·잠금·멱등키·병합된 이름 합류, 큐 워크플로·정규화는 SP2b

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 12: 최종 검증 + PR

- [ ] **Step 1: 전체 테스트·빌드**

Run (in `backend/`): `./gradlew build`
Expected: `BUILD SUCCESSFUL`, 실패 0. 테스트 수를 기록한다(SP1 브랜치 기준 102개 + 이번 추가분).

Run: `npm run build --prefix admin`
Expected: 오류 없음.

- [ ] **Step 2: 정적 확인**

Run: `grep -n "findById(" backend/merge/src/main/java/kr/trendstage/merge/MergeService.java`
Expected: `preview()`의 읽기 전용 조회만 남는다(`merge()` 경로는 `lockPair`의 `findByIdForUpdate`).

Run: `grep -rn "merge-queue/{id}/action" backend/api-spec/openapi.yaml`
Expected: 출력 없음.

- [ ] **Step 3: 브라우저 검증 (일회용 DB)**

사용자 DB를 건드리지 않도록 일회용 컨테이너를 쓴다.

```bash
docker rm -f trd-verify-db >/dev/null 2>&1; docker run -d --name trd-verify-db -e POSTGRES_DB=trd -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=pw -p 5434:5432 pgvector/pgvector:pg16
```

`backend/`에서 백그라운드 실행: `DB_URL=jdbc:postgresql://localhost:5434/trd DB_USER=postgres DB_PASSWORD=pw ADMIN_BOOTSTRAP_LOGIN_ID=verify-admin ADMIN_BOOTSTRAP_PASSWORD=verify-pass-1234 ./gradlew :app:bootRun`. `curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/admin/auth/csrf`가 200이 될 때까지 기다린다.

`docker exec -i trd-verify-db psql -U postgres -d trd`로 데이터를 넣는다: 유저 3명, 항목 A(PENDING, first_seen = now − 2일), 항목 B(PENDING, first_seen = now − 1일, 시딩 제보 1건 포함), 항목 C(RESOLVED), 각 항목에 제보 1~2건(`evidence_url` NOT NULL), 큐 행 두 개(B→A 0.80, B′→C 0.80 — B′는 PENDING 새 항목). `preview_start`로 `admin` 구성을 띄우고 `verify-admin`으로 로그인한다.

확인 항목(각각 스크린샷 또는 네트워크 로그):
1. ADM-100에서 B→A "병합 후 미리보기": 시딩 행이 "시딩 · 순위 없음", 관측 마감 줄 표시.
2. B→A "병합": 네트워크 요청에 `Idempotency-Key` 헤더(`read_network_requests`), 200. 같은 후보가 목록에서 사라짐.
3. B′→C "병합": 409, 토스트 "이미 판정된 항목은 병합할 수 없습니다 (판정 후 병합은 Phase 2)".
4. "보류 (미구현)" 버튼 비활성, 단축키 안내 없음.

끝나면 `preview_stop`, 8080 프로세스 종료(`netstat -ano | grep ':8080 '`로 PID 확인 후 `taskkill //PID <pid> //F`), `docker rm -f trd-verify-db`.

- [ ] **Step 4: 작업 트리 확인**

Run: `git status --short`
Expected: 이번 태스크 파일은 전부 커밋됨. 미추적 사용자 파일만 남는다.

- [ ] **Step 5: 푸시 + PR**

```bash
git push -u origin sp2-merge-integrity
```

SP1(PR #6)이 아직 병합 전이면 base는 `sp1-verdict-pipeline`, 병합됐으면 `main`. `gh pr view 6 --json state`로 확인한다.

```bash
gh pr create --base sp1-verdict-pipeline --head sp2-merge-integrity --title "SP2: 병합 무결성 — 판정된 항목 병합 금지·행 잠금·멱등키·병합된 이름 합류" --body "$(cat <<'EOF'
## 요약
- 병합 가드: 두 항목을 id 오름차순으로 행 잠금하고, 판정 전 상태 + 현행 판정 행 없음일 때만 병합. 아니면 409(`merge-judging` 재시도 / `merge-resolved` 영구). 흡수된 제보가 다음 판정에서 원장에 다시 실리던 이중 점수 경로 차단
- `cluster_merge`: 판정된 항목과 닮으면 병합·큐 없이 감사 로그 `MERGE_SKIPPED_JUDGED`만. 후보 스캔에서 JUDGING·RESOLVED 제외, 결정 + 확인 표시를 한 트랜잭션으로
- 큐 결정: 큐 행 잠금 + `Idempotency-Key` 필수(`merge_queue.decision_key` UNIQUE, V29). 같은 키 재요청은 이전 결과, 다른 결정·다른 후보는 422, 이미 처리된 후보는 409. 대상이 그사이 병합됐으면 후보 정리 후 409
- 처리된 항목도 다시 큐에 들어갈 수 있게(처리 대기 행만 부분 UNIQUE)
- 병합된 항목 이름으로 제보·워치·시딩 → 승자로 합류(체인 추적). 같은 새 이름 동시 첫 제보는 500 없이 한 항목으로
- 병합으로 관측 마감이 당겨지면 병합 시점부터 최소 3일 보장(상한 D+21)
- 미리보기 선점 순위를 뷰와 같은 규칙으로(시딩 제외·동순위), 제보권 반환 대상·마감 조정 표시
- 콘솔 ADM-100: 멱등키 전송·재시도, 409 사유별 안내. 가짜였던 보류 버튼은 미구현 표시

스펙: `docs/superpowers/specs/2026-09-21-sp2-merge-integrity-design.md` · 계획: `docs/superpowers/plans/2026-09-21-sp2-merge-integrity.md`

## 검증
- `./gradlew build` 통과 — 병합 통합 테스트 신설(가드·동시성·멱등·재계산·합류·자동 경로)
- `npm run build --prefix admin` 통과
- 브라우저(일회용 DB): 미리보기의 시딩·마감 표시, 병합 요청의 Idempotency-Key, 판정된 항목 병합 시 409 안내

## 범위 밖 (SP2b)
큐 워크플로(클레임 15분·보류·보류 3회 에스컬레이션)와 ADM-100 UI, 정규화 규칙·기존 키 백필, aliases 조회 활용, 분리 실구현. 판정 후 병합(ADJ)은 Phase 2.

## 머지 순서
SP1(PR #6) 병합 후 이 PR의 base를 `main`으로 바꿔 병합합니다.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

(SP1이 이미 병합됐으면 `--base main`으로 바꾸고 "머지 순서" 절을 지운다.)

Expected: PR URL 출력. 병합은 사용자가 GitHub에서 한다.
