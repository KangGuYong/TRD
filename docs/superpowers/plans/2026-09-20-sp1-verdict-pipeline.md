# SP1 판정 파이프라인 정합성 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 판정이 DB에 실제로 남게 하고(`JudgeService`), 시딩 제외·VOID 사건화·제보권·원장 원값·등급을 스펙대로 동작하게 한다. 판정 공식(T = 시딩 제외 제보자 수 / submitterTarget)은 바꾸지 않는다.

**Architecture:** 순수 함수(domain-core)를 먼저 바꾸고, 새 `judge` 모듈의 `JudgeService` 하나에 배치 판정·재판정·항목 VOID·미리보기를 모은다(항목 행 잠금 + 공개 메서드 단위 트랜잭션). 제보권은 저장 카운터 없이 제보 행(`created_at`·`voided_at`)에서 파생하고 유저 행 잠금으로 집행한다. 원장은 원값 + 행별 반감기·감쇠 기준 시각(J1). 스키마 변경은 V28 하나.

**Tech Stack:** Java 17, Spring Boot 3.3.5, Spring Data JPA(Hibernate 6), Flyway, PostgreSQL 16 + pgvector, Testcontainers 1.21, JUnit 5, AssertJ, Mockito, MockMvc; Expo(React Native) + TanStack Query, React 18 + Vite.

**Spec:** `docs/superpowers/specs/2026-09-20-sp1-verdict-pipeline-design.md` (결정 J1~J8)

---

## 전제 (모든 태스크 공통)

- 브랜치: `sp1-verdict-pipeline`. 시작 전 `git branch --show-current`로 확인하고 다른 브랜치로 전환하지 않는다.
- **`git add -A`·`git add .` 금지.** 항상 파일명을 지정한다.
- `backend/app/.env`는 사용자 소유 파일이다(있다면). 읽기·수정 금지.
- V1~V27 마이그레이션은 **절대 수정하지 않는다**. SP1의 스키마 변경은 Task 1의 V28 하나다.
- 스펙의 결정 J1~J8을 구현 중에 바꾸지 않는다. 계획대로 되지 않으면 멈추고 보고한다.
- 커밋 메시지 끝에 반드시 다음 줄:
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  ```
- **테스트 실행.** 통합 테스트는 Docker가 실행 중이어야 한다(`docker info`).
  - JDK 17이 있는 환경: `backend/`에서 `./gradlew <태스크>`.
  - JDK가 없는 이 PC: 저장소(또는 워크트리) 루트에서 아래 명령. `<태스크>` 자리에 이 문서의 Gradle 인자를 그대로 넣는다.
    ```bash
    MSYS_NO_PATHCONV=1 docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$(cygpath -w "$PWD/backend"):/src:ro" -v trd-gradle-test-cache:/root/.gradle -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal eclipse-temurin:17-jdk bash -c 'cp -r /src /workspace && cd /workspace && sed -i "s/\r$//" gradlew && ./gradlew --no-daemon <태스크>'
    ```
    끝나면 `docker volume rm trd-gradle-test-cache`로 캐시를 지워도 된다(다음 실행이 느려질 뿐).
- **DB 초기화.** 이 브랜치로 앱을 로컬 DB에 띄우기 전에 기존 판정 데이터를 비운다(V28이 남은 판정을 발견하면 기동을 멈춘다 — J7). Docker: `docker compose -f infra/docker-compose.yml down` 후 `docker volume rm infra_trd-db`.
- **공유 테스트 DB.** 모든 통합 테스트 클래스가 컨테이너 하나를 공유한다(`AbstractIntegrationTest`). 이름·키는 항상 랜덤으로 만들고, 단언은 자기가 만든 데이터로만 한다. 배치(`VerdictRunner.run()`, `GradeRecalcJob.run()`)는 DB 전체를 처리하므로 개수 단언을 하지 않는다.
- **ShedLock.** 배치의 `@SchedulerLock(lockAtLeastFor = "PT1M")` 때문에 1분 안에 두 번 부르면 두 번째는 조용히 건너뛴다. 테스트에서 배치를 부를 때는 반드시 먼저 `AbstractIntegrationTest.releaseBatchLock(name)`(lock_until을 과거로)을 부른다. **행을 DELETE하면 안 된다** — `JdbcTemplateLockProvider`가 행이 있다고 기억해 UPDATE만 시도하므로 락을 못 잡고 조용히 건너뛴다(Task 4 구현 중 확인). 헬퍼는 Task 4에서 추가한다.
- 순서: Task 1~8 백엔드 → Task 9 API 계약 → Task 10 프론트 → Task 11 문서 → Task 12 최종 검증·PR. **각 태스크가 끝나면 전체 컴파일(`compileJava compileTestJava`)과 해당 테스트가 통과해야 한다.**

## 파일 구조

| 파일 | 책임 | 태스크 |
|---|---|---|
| `backend/persistence/src/main/resources/db/migration/V28__sp1_verdict_pipeline.sql` | 초기화 가드, 원장 감쇠 기준, 멱등 제약, 제보 VOID·판정 시각, 시딩 제외 순위 뷰 | 1 |
| `backend/persistence/.../entity/Submission.java` | `voidedAt`·`resolvedAt`, `markResult(r, at)`·`voidOut(at)`, 생성 시각 받는 생성자 | 1, 7 |
| `backend/persistence/.../entity/ScoreLedgerEntry.java` | `halflifeDays`·`decayAnchorAt`, 판정·재판정·수동 ADJ 팩토리 | 1 |
| `backend/app/src/test/java/kr/trendstage/support/Fixtures.java` | JDBC 테스트 데이터 | 1, 5, 8 |
| `backend/app/src/test/java/kr/trendstage/support/AbstractIntegrationTest.java` | `fx` 필드 | 1 |
| `backend/domain-core/.../score/{ScoreEngine,ScoreInput,SubmissionRef,ActiveScore,VerdictComputation,VerdictPlan}.java` | 원값 Δ, 행별 감쇠, 시딩 원장 제외, VOID는 유효 0건일 때만 | 2, 3, 6 |
| `backend/domain-core/.../grade/{SubmissionQuota,GradePolicy}.java` | 주 경계·사용량·잔여, `progressToward`·`nextOf` | 2 |
| `backend/domain-core/.../verdict/TrendSignal.java` (신설), `VerdictEngine.java`, `SubmissionSignal.java`(삭제) | 판정 신호 | 3, 6 |
| `backend/domain-core/.../params/{VerdictSnapshot,ParamSimulation}.java` | 판정 근거 신호로 시뮬레이션 | 6 |
| `backend/judge/**` (신설 모듈) | `JudgeService`·`VerdictEvidence`·`ParamsSnapshot`·예외 | 4, 5, 6 |
| `backend/scheduler/.../VerdictRunner.java` | 목록 순회만 | 4 |
| `backend/scheduler/.../GradeRecalcJob.java`, `ParameterSetProvider.java`(삭제) | KST 주 경계, TI 180일, 강등 없음 | 8 |
| `backend/persistence/.../grade/GradeInputsReader.java` (신설) | 등급 입력(판정완료·TI 창·AS) 공용 조회 | 8 |
| `backend/api-admin/.../verdict/VerdictAdminService.java`, `web/MergeQueueController.java`, `web/AdminApiExceptionHandler.java` | JudgeService 위임, 예외 매핑 | 5 |
| `backend/merge/.../MergeService.java` | `voidOut(Instant)`, `voidTrendItem` 삭제 | 1, 5 |
| `backend/api-admin/.../trend/TrendItemAdminService.java`, `params/ParamStudioService.java`, `seed/AdminSeedService.java` | 미리보기·시뮬레이션·현재 파라미터 | 2, 6 |
| `backend/api-public/.../service/{QuotaService,SubmissionService,MeService}.java`, `web/{QuotaExhaustedException,ItemClosedException,ApiExceptionHandler}.java` | 제보권 집행, 관측 마감, 공식 등급 | 7, 8 |
| `backend/app/src/test/java/kr/trendstage/{judge,submission,grade}/*Test.java` | 통합 테스트 | 1, 4~8 |
| `backend/api-spec/openapi.yaml` | 422 type, 제보권·등급 설명 | 9 |
| `app/src/screens/SubmitScreen.tsx`, `admin/src/screens/{VerdictScreen,TrendDetailScreen}.tsx`, `admin/src/api/types.ts` | 제보권 표시, 재판정 안내, 시딩 순위 | 10 |
| `CLAUDE.md`, `01`~`05`, `README.md`, `infra/docker-compose.yml`, `infra/.env.example` | 문서·설정 | 8, 11 |

---

### Task 1: V28 스키마 + 엔티티(제보 VOID·판정 시각, 원장 감쇠 기준)

**Files:**
- Create: `backend/persistence/src/main/resources/db/migration/V28__sp1_verdict_pipeline.sql`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/Submission.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/ScoreLedgerEntry.java`
- Modify: `backend/scheduler/src/main/java/kr/trendstage/scheduler/VerdictRunner.java` (호출 인자만)
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/verdict/VerdictAdminService.java` (호출 인자만)
- Modify: `backend/merge/src/main/java/kr/trendstage/merge/MergeService.java`
- Create: `backend/app/src/test/java/kr/trendstage/support/Fixtures.java`
- Modify: `backend/app/src/test/java/kr/trendstage/support/AbstractIntegrationTest.java`
- Test: `backend/app/src/test/java/kr/trendstage/judge/SchemaV28Test.java`

- [ ] **Step 1: 테스트 데이터 헬퍼 작성**

`Fixtures.java`:

```java
package kr.trendstage.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * 판정 파이프라인 통합 테스트 데이터. JDBC로 직접 넣어 created_at·first_seen_at을 과거로 정할 수 있다.
 * 모든 테스트 클래스가 DB 하나를 공유하므로 이름·키는 항상 랜덤이다.
 */
public class Fixtures {

    private final JdbcTemplate jdbc;

    public Fixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID user() {
        return jdbc.queryForObject("INSERT INTO users (handle) VALUES (?) RETURNING id", UUID.class, "u_" + rand());
    }

    /** PENDING 항목. 관측 마감 = firstSeenAt + 14일. */
    public UUID item(Instant firstSeenAt) {
        String key = "k_" + rand();
        return jdbc.queryForObject(
                "INSERT INTO trend_items (canonical_name, normalized_key, category, state, first_seen_at) "
                        + "VALUES (?, ?, 'MEME', 'PENDING', ?) RETURNING id",
                UUID.class, key, key, Timestamp.from(firstSeenAt));
    }

    public UUID submission(UUID userId, UUID itemId, int confidence, Instant createdAt) {
        return insertSubmission(userId, itemId, confidence, createdAt, false);
    }

    public UUID seedSubmission(UUID userId, UUID itemId, Instant createdAt) {
        return insertSubmission(userId, itemId, 30, createdAt, true);
    }

    public void voidSubmission(UUID submissionId, Instant at) {
        jdbc.update("UPDATE submissions SET result = 'VOID', voided_at = ? WHERE id = ?", Timestamp.from(at), submissionId);
    }

    public String submissionResult(UUID submissionId) {
        return jdbc.queryForObject("SELECT result::text FROM submissions WHERE id = ?", String.class, submissionId);
    }

    public String itemState(UUID itemId) {
        return jdbc.queryForObject("SELECT state::text FROM trend_items WHERE id = ?", String.class, itemId);
    }

    /** 이 항목의 판정 체인에 귀속된 원장의 유저별 합(ADJ 포함). */
    public BigDecimal ledgerSum(UUID userId, UUID itemId) {
        return jdbc.queryForObject(
                "SELECT coalesce(sum(l.delta), 0) FROM score_ledger l JOIN verdicts v ON v.id = l.verdict_id "
                        + "WHERE l.user_id = ? AND v.trend_item_id = ?",
                BigDecimal.class, userId, itemId);
    }

    public int ledgerRows(UUID itemId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM score_ledger l JOIN verdicts v ON v.id = l.verdict_id WHERE v.trend_item_id = ?",
                Integer.class, itemId);
    }

    private UUID insertSubmission(UUID userId, UUID itemId, int confidence, Instant createdAt, boolean seed) {
        String key = jdbc.queryForObject("SELECT normalized_key FROM trend_items WHERE id = ?", String.class, itemId);
        return jdbc.queryForObject(
                "INSERT INTO submissions (user_id, trend_item_id, raw_input, normalized_key, confidence, "
                        + "source_platform, evidence_url, one_line, created_at, is_seed) "
                        + "VALUES (?, ?, ?, ?, ?, 'X', 'https://example.com', '설명', ?, ?) RETURNING id",
                UUID.class, userId, itemId, key, key, confidence, Timestamp.from(createdAt), seed);
    }

    static String rand() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
```

`AbstractIntegrationTest.java`의 필드·`@BeforeEach`를 바꾼다:

```java
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MutableClock clock;
    protected Fixtures fx;

    @BeforeEach
    void resetClock() {
        clock.reset();
        fx = new Fixtures(jdbc);
    }
```

- [ ] **Step 2: 실패하는 테스트 작성**

`SchemaV28Test.java`:

```java
package kr.trendstage.judge;

import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.type.SubmissionResult;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V28 제약·뷰, 제보 엔티티의 VOID·판정 시각 매핑. */
class SchemaV28Test extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired SubmissionRepository submissions;
    @Autowired TransactionTemplate tx;

    @Test
    void voidSubmissionRequiresVoidedAt() {
        UUID s = fx.submission(fx.user(), fx.item(T0), 30, T0);
        assertThatThrownBy(() -> jdbc.update("UPDATE submissions SET result = 'VOID' WHERE id = ?", s))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void judgedSubmissionRequiresResolvedAt() {
        UUID s = fx.submission(fx.user(), fx.item(T0), 30, T0);
        assertThatThrownBy(() -> jdbc.update("UPDATE submissions SET result = 'HIT' WHERE id = ?", s))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void orderRankViewExcludesSeeds() {
        UUID item = fx.item(T0);
        UUID seed = fx.seedSubmission(fx.user(), item, T0);
        UUID first = fx.submission(fx.user(), item, 30, T0.plusSeconds(60));
        UUID second = fx.submission(fx.user(), item, 30, T0.plusSeconds(120));

        List<UUID> ids = jdbc.queryForList(
                "SELECT id FROM submission_order_rank WHERE trend_item_id = ? ORDER BY order_rank", UUID.class, item);
        List<Integer> ranks = jdbc.queryForList(
                "SELECT order_rank FROM submission_order_rank WHERE trend_item_id = ? ORDER BY order_rank", Integer.class, item);

        assertThat(ids).containsExactly(first, second).doesNotContain(seed);
        assertThat(ranks).containsExactly(1, 2);
    }

    @Test
    void ledgerAllowsOneRowPerVerdictAndSubmission() {
        UUID user = fx.user();
        UUID item = fx.item(T0);
        UUID s = fx.submission(user, item, 30, T0);
        UUID v = insertVerdict(item, null);
        insertLedger(user, s, v);
        assertThatThrownBy(() -> insertLedger(user, s, v)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void verdictChainCannotFork() {
        UUID item = fx.item(T0);
        UUID original = insertVerdict(item, null);
        insertVerdict(item, original);
        assertThatThrownBy(() -> insertVerdict(item, original)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ledgerRowRequiresDecayBasis() {
        UUID user = fx.user();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO score_ledger (user_id, kind, delta, reason) VALUES (?, 'ADJ', 1, '사유')", user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void submissionEntityRecordsVoidAndFirstResolveTime() {
        UUID a = fx.submission(fx.user(), fx.item(T0), 30, T0);
        UUID b = fx.submission(fx.user(), fx.item(T0), 30, T0);
        Instant at = Instant.parse("2026-09-20T00:00:00Z");

        tx.executeWithoutResult(status -> {
            submissions.findById(a).orElseThrow().voidOut(at);
            Submission sb = submissions.findById(b).orElseThrow();
            sb.markResult(SubmissionResult.HIT, at);
            sb.markResult(SubmissionResult.MISS, at.plusSeconds(3600)); // 재판정 — 처음 판정 시각 유지
        });

        assertThat(jdbc.queryForObject("SELECT voided_at FROM submissions WHERE id = ?", Timestamp.class, a).toInstant())
                .isEqualTo(at);
        assertThat(fx.submissionResult(b)).isEqualTo("MISS");
        assertThat(jdbc.queryForObject("SELECT resolved_at FROM submissions WHERE id = ?", Timestamp.class, b).toInstant())
                .isEqualTo(at);
    }

    /** VOID 판정으로 넣는다 — 근거가 비어 있어도 ADM-600 시뮬레이션(공유 DB 전체를 읽는다)이 VOID는 건너뛴다. */
    private UUID insertVerdict(UUID item, UUID supersedes) {
        return jdbc.queryForObject(
                "INSERT INTO verdicts (trend_item_id, result, score_t, judged_at, evidence_json, supersedes) "
                        + "VALUES (?, 'VOID', NULL, now(), '{}'::jsonb, ?) RETURNING id",
                UUID.class, item, supersedes);
    }

    private void insertLedger(UUID user, UUID submission, UUID verdict) {
        jdbc.update("INSERT INTO score_ledger (user_id, submission_id, verdict_id, kind, delta, reason, "
                + "halflife_days, decay_anchor_at) VALUES (?, ?, ?, 'MISS', -15, '근거', 90, now())",
                user, submission, verdict);
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :app:test --tests kr.trendstage.judge.SchemaV28Test`
Expected: 컴파일 실패 — `Submission.voidOut(Instant)`, `markResult(SubmissionResult, Instant)` 없음.

- [ ] **Step 4: 마이그레이션 작성**

`V28__sp1_verdict_pipeline.sql`:

```sql
-- V28 · SP1 판정 파이프라인 정합성 (docs/superpowers/specs/2026-09-20-sp1-verdict-pipeline-design.md)

-- 0) 초기화 확인(J7). 감쇠가 곱해진 옛 원장·옛 판정 근거 형식과 섞이지 않게 한다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM verdicts) OR EXISTS (SELECT 1 FROM score_ledger) THEN
        RAISE EXCEPTION 'SP1(V28): 기존 판정·원장이 남아 있습니다. 개발 DB를 초기화하세요 (docs/superpowers/specs/2026-09-20-sp1-verdict-pipeline-design.md §0)';
    END IF;
END $$;

-- 1) 원장 행별 감쇠 기준(J1, O11=(b)). 0)에서 비어 있음을 확인했으므로 기본값 없이 NOT NULL.
ALTER TABLE score_ledger
    ADD COLUMN halflife_days   INTEGER     NOT NULL CHECK (halflife_days > 0),
    ADD COLUMN decay_anchor_at TIMESTAMPTZ NOT NULL;
COMMENT ON COLUMN score_ledger.delta IS '원값 점수(감쇠 없음). 감쇠는 AS 조회 때 halflife_days·decay_anchor_at으로 계산한다.';
COMMENT ON COLUMN score_ledger.halflife_days IS '이 행을 기록할 때의 반감기(일). 파라미터가 바뀌어도 과거 행의 감쇠는 그대로다(비소급).';
COMMENT ON COLUMN score_ledger.decay_anchor_at IS '감쇠 기준 시각. 판정 행은 판정 시각, 재판정·VOID 차액은 원 판정 시각.';

-- 2) 멱등: 한 판정이 한 제보에 원장을 두 번 남기지 않는다. 수동 ADJ(verdict NULL)는 영향 없음.
ALTER TABLE score_ledger
    ADD CONSTRAINT ledger_one_row_per_verdict_submission UNIQUE (verdict_id, submission_id);

-- 3) 재판정 체인은 한 줄 — 같은 판정을 두 판정이 대체하지 못한다.
CREATE UNIQUE INDEX verdict_superseded_once ON verdicts (supersedes) WHERE supersedes IS NOT NULL;

-- 4) 제보: VOID 시각(제보권 반환 기준, J4)·처음 판정 시각(TI 180일 창 기준)
ALTER TABLE submissions
    ADD COLUMN voided_at   TIMESTAMPTZ,
    ADD COLUMN resolved_at TIMESTAMPTZ;
UPDATE submissions SET voided_at   = created_at WHERE result = 'VOID';
UPDATE submissions SET resolved_at = created_at WHERE result IN ('HIT', 'MISS');
ALTER TABLE submissions
    ADD CONSTRAINT submission_void_has_time   CHECK ((result = 'VOID') = (voided_at IS NOT NULL)),
    ADD CONSTRAINT submission_judged_has_time CHECK (result NOT IN ('HIT', 'MISS') OR resolved_at IS NOT NULL);
CREATE INDEX idx_submissions_user_voided   ON submissions (user_id, voided_at)   WHERE voided_at IS NOT NULL;
CREATE INDEX idx_submissions_user_resolved ON submissions (user_id, resolved_at) WHERE resolved_at IS NOT NULL;
COMMENT ON COLUMN submissions.voided_at IS 'VOID된 시각. 이 시각이 속한 주에 제보권 한 장이 반환된다.';
COMMENT ON COLUMN submissions.resolved_at IS '처음 HIT/MISS로 판정된 시각(재판정해도 유지). TI 180일 창의 기준.';

-- 5) 선점 순위에서 시딩 제외(J3). 시딩은 클러스터 앵커 역할만 한다.
CREATE OR REPLACE VIEW submission_order_rank AS
SELECT id,
       trend_item_id,
       CAST(RANK() OVER (PARTITION BY trend_item_id ORDER BY created_at ASC) AS integer) AS order_rank
FROM submissions
WHERE result <> 'VOID' AND NOT is_seed;
```

- [ ] **Step 5: `Submission` 엔티티 수정**

`seed` 필드 다음에 추가:

```java
    /** VOID된 시각 — 이 시각이 속한 주에 제보권이 반환된다(J4). result = VOID와 함께만 존재(DB CHECK). */
    @Column(name = "voided_at")
    private Instant voidedAt;

    /** 처음 HIT/MISS로 판정된 시각 — TI 180일 창의 기준. 재판정해도 유지한다. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;
```

기존 `markResult(SubmissionResult r)`와 `voidOut()`을 아래로 교체한다:

```java
    /** 판정 결과 기록. VOID면 {@link #voidOut}. HIT/MISS는 처음 판정된 시각만 남긴다. */
    public void markResult(SubmissionResult r, Instant at) {
        if (r == SubmissionResult.VOID) {
            voidOut(at);
            return;
        }
        this.result = r;
        if (this.resolvedAt == null && (r == SubmissionResult.HIT || r == SubmissionResult.MISS)) {
            this.resolvedAt = at;
        }
    }

    /** VOID 처리(항목 VOID·같은 유저 중복, 01 §4.4). voided_at이 제보권 반환 시점이다(J4). 이미 VOID면 그대로 둔다. */
    public void voidOut(Instant at) {
        if (this.result == SubmissionResult.VOID) return;
        this.result = SubmissionResult.VOID;
        this.voidedAt = at;
    }

    public Instant getVoidedAt() { return voidedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
```

- [ ] **Step 6: `ScoreLedgerEntry` 엔티티 수정**

`approvalId` 필드 다음에 추가:

```java
    /** 이 행을 기록할 때의 반감기(일). AS 조회 감쇠에 쓴다 — 파라미터가 바뀌어도 과거 행은 그대로(J1). */
    @Column(name = "halflife_days", nullable = false)
    private int halflifeDays;

    /** 감쇠 기준 시각. 판정 행은 판정 시각, 재판정·VOID 차액은 원 판정 시각(J1). */
    @Column(name = "decay_anchor_at", nullable = false)
    private Instant decayAnchorAt;
```

팩토리 두 개를 교체하고 하나를 추가한다:

```java
    /** 판정 발생 원장(HIT/MISS). delta는 원값 — 감쇠는 AS 조회 때(J1). */
    public static ScoreLedgerEntry ofVerdict(UUID userId, UUID submissionId, UUID verdictId, LedgerKind kind,
                                             BigDecimal delta, String reason, int halflifeDays, Instant decayAnchorAt) {
        ScoreLedgerEntry e = new ScoreLedgerEntry();
        e.userId = userId; e.submissionId = submissionId; e.verdictId = verdictId;
        e.kind = kind; e.delta = delta; e.reason = reason;
        e.halflifeDays = halflifeDays; e.decayAnchorAt = decayAnchorAt;
        return e;
    }

    /** 재판정·항목 VOID의 제보 단위 차액(ADJ). 원 판정과 같은 감쇠 기준이어야 AS에서 정확히 상쇄된다. */
    public static ScoreLedgerEntry verdictAdjustment(UUID userId, UUID submissionId, UUID verdictId, BigDecimal delta,
                                                     String reason, int halflifeDays, Instant decayAnchorAt) {
        return ofVerdict(userId, submissionId, verdictId, LedgerKind.ADJ, delta, reason, halflifeDays, decayAnchorAt);
    }

    /** 수동 상쇄 원장(ADJ, ADM-311 — SP3). 사유 필수, 승인자 표시. */
    public static ScoreLedgerEntry adjustment(UUID userId, BigDecimal delta, String reason,
                                              UUID approvedBy, UUID approvalId, int halflifeDays, Instant decayAnchorAt) {
        ScoreLedgerEntry e = new ScoreLedgerEntry();
        e.userId = userId; e.kind = LedgerKind.ADJ; e.delta = delta; e.reason = reason;
        e.approvedBy = approvedBy; e.approvalId = approvalId;
        e.halflifeDays = halflifeDays; e.decayAnchorAt = decayAnchorAt;
        return e;
    }
```

getter 추가:

```java
    public UUID getVerdictId() { return verdictId; }
    public int getHalflifeDays() { return halflifeDays; }
    public Instant getDecayAnchorAt() { return decayAnchorAt; }
```

- [ ] **Step 7: 호출부를 새 시그니처에 맞춘다** (동작 변경 없음 — 이 두 클래스는 Task 4·5에서 교체된다)

`VerdictRunner.java`:
- `sub.markResult(toSubResult(line.kind()));` → `sub.markResult(toSubResult(line.kind()), judgedAt);`
- `ScoreLedgerEntry.ofVerdict(..., BigDecimal.valueOf(line.delta()), line.reason())` → 끝에 `, p.halflifeDays, judgedAt` 인자 추가.

`VerdictAdminService.java`:
- `if (sub != null) sub.markResult(toSubResult(line.kind()));` → `..., now);`
- `reconcileLedger(Verdict oldVerdict, VerdictPlan newPlan, String reason)`에 `int halflifeDays, Instant anchor` 매개변수를 더하고, 내부 `ScoreLedgerEntry.adjustment(userId, diff, reason, null, null)` → `ScoreLedgerEntry.adjustment(userId, diff, reason, null, null, halflifeDays, anchor)`.
- 호출: `reconcileLedger(current, plan, "%s: %s".formatted(auditAction, reason), p.halflifeDays, now);`

`MergeService.java`:
- 필드 `private final Clock clock;`를 추가하고 생성자 마지막 인자로 받는다(`java.time.Clock` import).
- `s.voidOut();` → `s.voidOut(clock.instant());`, `byId.get(id).voidOut();` → `byId.get(id).voidOut(clock.instant());`

- [ ] **Step 8: 통과 확인**

Run: `./gradlew compileJava compileTestJava :app:test --tests kr.trendstage.judge.SchemaV28Test --tests kr.trendstage.ContextSmokeTest`
Expected: PASS (8 tests). `ddl-auto: validate`가 새 컬럼 매핑을 검증한다.

- [ ] **Step 9: 커밋**

```bash
git add backend/persistence/src/main/resources/db/migration/V28__sp1_verdict_pipeline.sql backend/persistence/src/main/java/kr/trendstage/persistence/entity/Submission.java backend/persistence/src/main/java/kr/trendstage/persistence/entity/ScoreLedgerEntry.java backend/scheduler/src/main/java/kr/trendstage/scheduler/VerdictRunner.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/verdict/VerdictAdminService.java backend/merge/src/main/java/kr/trendstage/merge/MergeService.java backend/app/src/test/java/kr/trendstage/support/Fixtures.java backend/app/src/test/java/kr/trendstage/support/AbstractIntegrationTest.java backend/app/src/test/java/kr/trendstage/judge/SchemaV28Test.java
git commit -m "feat(persistence): V28 원장 감쇠 기준·멱등 제약, 제보 VOID·판정 시각, 시딩 제외 순위 뷰

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: 점수 순수 함수 — 원값 Δ, 행별 감쇠, 제보권 계산, 등급 진행 상황

**Files:**
- Modify: `backend/domain-core/src/main/java/kr/trendstage/domain/score/ScoreEngine.java`
- Modify: `backend/domain-core/src/main/java/kr/trendstage/domain/score/ScoreInput.java`
- Modify: `backend/domain-core/src/main/java/kr/trendstage/domain/score/SubmissionRef.java`
- Modify: `backend/domain-core/src/main/java/kr/trendstage/domain/score/VerdictComputation.java` (`ScoreInput` 생성만)
- Modify: `backend/domain-core/src/main/java/kr/trendstage/domain/score/ActiveScore.java`
- Modify: `backend/domain-core/src/main/java/kr/trendstage/domain/grade/SubmissionQuota.java`
- Modify: `backend/domain-core/src/main/java/kr/trendstage/domain/grade/GradePolicy.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/ScoreLedgerRepository.java`
- Modify callers: `VerdictRunner.java`, `VerdictAdminService.java`, `TrendItemAdminService.java` (`SubmissionRef` 5번째 인자), `GradeRecalcJob.java`, `MeService.java` (`ActiveScore`)
- Test: `EngineGoldenTest.java`, `VerdictComputationTest.java`, `ReadModelTest.java`, `SubmissionQuotaTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`EngineGoldenTest.java` — 이 파일은 "리뷰 없이 수정 금지"다. SP1 스펙(J1, 원장 원값)이 그 리뷰다.
- 네 개의 `new ScoreInput(..., 0)`에서 마지막 인자 `0`을 지운다(4인자).
- `반감기_90일이면_절반` 테스트를 삭제하고(감쇠는 `ActiveScore`로 이동 — `ReadModelTest`) 아래를 추가:

```java
    @Test void 원장_Δ에는_감쇠가_없고_근거에도_감쇠항이_없다() {
        ScoreResult r = ScoreEngine.compute(new ScoreInput(VerdictResult.HIT, 30, 1, ReachLevel.L3), p);
        assertEquals(60.0, r.delta(), 1e-9);
        assertEquals("HIT L3 · 확신도 30 × 선점 1위(1.0) × 확산 ×2.0 = +60.0", r.breakdown());
        assertEquals("MISS · 확신도 30 × 0.5 = -15.0",
                ScoreEngine.compute(new ScoreInput(VerdictResult.MISS, 30, 2, null), p).breakdown());
    }

    @Test void 스냅샷_등급_기준으로_다음_등급_진행상황을_낸다() {
        assertEquals(Grade.L1, GradePolicy.nextOf(Grade.L0));
        assertEquals(Grade.L4, GradePolicy.nextOf(Grade.L4));
        List<GradeRequirement> reqs = GradePolicy.progressToward(Grade.L1, 6, 0.40, 35);
        assertEquals(3, reqs.size());
        assertTrue(reqs.stream().allMatch(GradeRequirement::met));
        assertTrue(GradePolicy.progressToward(Grade.L4, 100, 0.9, 999).isEmpty()); // L4는 정원제 — 사다리 밖
    }
```

(import: `kr.trendstage.domain.score.ScoreResult`, `kr.trendstage.domain.grade.GradeRequirement`, `java.util.List`, `static org.junit.jupiter.api.Assertions.assertTrue`)

`ReadModelTest.java`의 `활동점수는_경과일로_감쇠_재적용` 테스트를 아래 두 개로 교체:

```java
    @Test void 활동점수는_행마다_기록된_반감기로_감쇠한다() {
        assertEquals(60.0, ActiveScore.compute(List.of(new ActiveScore.Aged(60, 0, 90))), 1e-9);
        assertEquals(30.0, ActiveScore.compute(List.of(new ActiveScore.Aged(60, 90, 90))), 1e-9);
        // 반감기 60일로 기록된 행은 60일에 절반 — 현재 파라미터와 무관(J1)
        assertEquals(30.0, ActiveScore.compute(List.of(new ActiveScore.Aged(60, 60, 60))), 1e-9);
        assertEquals(52.5, ActiveScore.compute(List.of(
                new ActiveScore.Aged(60, 0, 90), new ActiveScore.Aged(-15, 90, 90))), 1e-9);
    }

    @Test void 재판정_차액은_원_판정과_같은_기준이면_정확히_상쇄된다() {
        // 원 판정 HIT +50, 재판정으로 MISS(−15) → 차액 −65를 원 판정 기준(같은 경과일·반감기)으로 기록
        double withAdj = ActiveScore.compute(List.of(
                new ActiveScore.Aged(50, 30, 90), new ActiveScore.Aged(-65, 30, 90)));
        assertEquals(ActiveScore.compute(List.of(new ActiveScore.Aged(-15, 30, 90))), withAdj, 1e-9);
    }
```

`SubmissionQuotaTest.java`에 추가:

```java
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Test void 주_시작은_월요일_0시_KST다() {
        Instant sundayLate = ZonedDateTime.of(2026, 9, 20, 23, 59, 59, 0, KST).toInstant();   // 일요일
        Instant mondayStart = ZonedDateTime.of(2026, 9, 21, 0, 0, 0, 0, KST).toInstant();
        assertEquals(ZonedDateTime.of(2026, 9, 14, 0, 0, 0, 0, KST).toInstant(), SubmissionQuota.weekStart(sundayLate));
        assertEquals(mondayStart, SubmissionQuota.weekStart(mondayStart));
    }

    @Test void 사용량은_이번주_제출에서_이번주_반환을_뺀다() {
        assertEquals(2, SubmissionQuota.used(2, 0));
        assertEquals(1, SubmissionQuota.used(2, 1));   // 이번 주에 낸 것 하나가 VOID
        assertEquals(0, SubmissionQuota.used(0, 1));   // 지난주 제보가 이번 주 VOID — 음수가 되지 않는다
    }

    @Test void 남은_제보권은_한도를_넘지_않는다() {
        assertEquals(2, SubmissionQuota.remaining(Grade.L0, 0));
        assertEquals(0, SubmissionQuota.remaining(Grade.L0, 2));
        assertEquals(0, SubmissionQuota.remaining(Grade.L0, 5));
        assertEquals(1, SubmissionQuota.remaining(Grade.L1, 2));
    }
```

(import: `java.time.Instant`, `java.time.ZoneId`, `java.time.ZonedDateTime`)

`VerdictComputationTest.java`: 세 곳의 `new SubmissionRef(..., 0)`에서 마지막 인자 `0`을 `false`로 바꾼다(값 동일).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :domain-core:test`
Expected: 컴파일 실패 — `ScoreInput` 4인자 생성자, `ActiveScore.Aged` 3인자, `SubmissionQuota.weekStart/used/remaining`, `GradePolicy.nextOf/progressToward` 없음.

- [ ] **Step 3: 순수 함수 구현**

`ScoreInput.java`:

```java
package kr.trendstage.domain.score;

import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.VerdictResult;

/**
 * 건별 점수 계산 입력. 감쇠 없음 — 원장에는 원값을 기록하고 감쇠는 AS 조회 때 적용한다(J1).
 *
 * @param confidence 확신도 c (10/30/50)
 * @param orderRank  선점 순위(시딩 제외, 판정 시점 동결값 — 03 §3.1)
 * @param reach      확산 규모(HIT만 유효, MISS/VOID는 null 허용)
 */
public record ScoreInput(VerdictResult result, int confidence, int orderRank, ReachLevel reach) {}
```

`ScoreEngine.java`의 클래스 주석과 `compute`를 교체:

```java
/**
 * 건별 점수 Δ(원값)를 산출하는 순수 함수. 01 §5.1.
 *
 * <pre>
 *   HIT :  Δ = + c × w_order × (1 + m)
 *   MISS:  Δ = − c × 0.5
 *   VOID:  Δ = 0
 * </pre>
 *
 * 시간감쇠는 여기서 곱하지 않는다 — 원장에는 원값을 기록하고 감쇠는 {@link ActiveScore}가 조회 때 적용한다
 * (J1. 양쪽에 걸면 이중 적용).
 * 실패 페널티가 이득의 절반(0.5)인 비대칭은 의도된 설계다 — 대칭이면 유저가
 * "이미 뜬 것만" 제보하게 되어 조기경보 목적이 무너진다. 임의 변경 금지.
 */
public final class ScoreEngine {
    private ScoreEngine() {}

    public static ScoreResult compute(ScoreInput in, ParameterSet p) {
        return switch (in.result()) {
            case VOID -> new ScoreResult(0.0, "VOID · 점수 변동 없음");
            case MISS -> {
                double delta = -in.confidence() * 0.5;
                yield new ScoreResult(delta, String.format(Locale.US,
                        "MISS · 확신도 %d × 0.5 = %+.1f", in.confidence(), delta));
            }
            case HIT -> {
                double w = p.orderWeight(in.orderRank());
                double m = p.reachMultiplier(in.reach());
                double delta = in.confidence() * w * (1 + m);
                yield new ScoreResult(delta, String.format(Locale.US,
                        "HIT %s · 확신도 %d × 선점 %d위(%.1f) × 확산 ×%.1f = %+.1f",
                        in.reach(), in.confidence(), in.orderRank(), w, (1 + m), delta));
            }
        };
    }
}
```

`SubmissionRef.java`:

```java
package kr.trendstage.domain.score;

import java.util.UUID;

/**
 * 판정 시점에 동결된 제보 스냅샷. orderRank는 시딩을 뺀 순위(J3, 뷰에서 읽어 이 시점에 고정 — 03 §3.1).
 * 시딩 제보는 seed=true — 결과는 매기되 원장 라인은 만들지 않는다(P2). VOID 제보는 애초에 넘기지 않는다.
 */
public record SubmissionRef(UUID submissionId, UUID userId, int confidence, int orderRank, boolean seed) {}
```

`VerdictComputation.java`: `new ScoreInput(o.result(), s.confidence(), s.orderRank(), o.reach(), s.elapsedDays())` → `new ScoreInput(o.result(), s.confidence(), s.orderRank(), o.reach())`.

`ActiveScore.java` 전체 교체:

```java
package kr.trendstage.domain.score;

import java.util.List;

/**
 * 활동 점수 AS = Σ Δ × 0.5^(경과일 / 반감기). 01 §5.3. 순수 함수.
 * 반감기는 원장 행마다 기록된 값을 쓴다 — 파라미터를 바꿔도 과거 행의 감쇠가 바뀌지 않는다(J1, O11=(b)).
 */
public final class ActiveScore {
    private ActiveScore() {}

    /** 원장 한 행: 원값 Δ, 감쇠 기준 시각(decay_anchor_at)부터의 경과일, 그 행에 기록된 반감기. */
    public record Aged(double delta, long ageDays, int halflifeDays) {}

    public static double compute(List<Aged> entries) {
        double sum = 0;
        for (Aged e : entries) {
            sum += e.delta() * Math.pow(0.5, (double) e.ageDays() / e.halflifeDays());
        }
        return sum;
    }
}
```

`SubmissionQuota.java` 전체 교체:

```java
package kr.trendstage.domain.grade;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

/**
 * 제보권(01 §3.3). 등급별 주간 한도, 이월 없음. 저장 카운터 없이 제보 행에서 파생한다(J4):
 * 이번 주 사용 = 이번 주 제출 − 이번 주 VOID 반환. 리필은 주 경계(월 00:00 KST) 자체라 배치가 없다.
 */
public final class SubmissionQuota {
    private SubmissionQuota() {}

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final Map<Grade, Integer> WEEKLY_LIMIT = Map.of(
            Grade.L0, 2, Grade.L1, 3, Grade.L2, 5, Grade.L3, 8, Grade.L4, 12
    );

    public static int weeklyLimit(Grade grade) {
        return WEEKLY_LIMIT.get(grade);
    }

    /** 제보권 주의 시작 — 그 주 월요일 00:00 KST. */
    public static Instant weekStart(Instant now) {
        return now.atZone(KST).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(KST).toInstant();
    }

    /** 이번 주 사용량 = 이번 주 제출 − 이번 주 VOID 반환(지난주에 낸 것 포함). 0 미만이면 0. */
    public static int used(int submittedThisWeek, int refundedThisWeek) {
        return Math.max(0, submittedThisWeek - refundedThisWeek);
    }

    public static int remaining(Grade grade, int used) {
        return Math.max(0, weeklyLimit(grade) - used);
    }
}
```

`GradePolicy.java`: `evaluate`의 다음 등급·요구 항목 계산을 아래 두 공개 메서드로 옮기고 `evaluate`가 그것을 쓰게 한다.

```java
    /** 다음 등급. L3·L4 다음은 L4(L4는 정원제 — 사다리 밖). */
    public static Grade nextOf(Grade g) {
        return switch (g) {
            case L0 -> Grade.L1; case L1 -> Grade.L2; case L2 -> Grade.L3;
            case L3, L4 -> Grade.L4;
        };
    }

    /**
     * 목표 등급의 요구 항목과 충족 여부. 공식 등급은 주간 스냅샷이고 진행 상황만 실시간으로 보여줄 때 쓴다(J5).
     * 사다리에 없는 등급(L4)이면 빈 목록.
     */
    public static List<GradeRequirement> progressToward(Grade target, int judgedCount, double trustIndex, double activeScore) {
        Req r = LADDER.stream().filter(x -> x.grade == target).findFirst().orElse(null);
        if (r == null) return List.of();
        return List.of(
                req(GradeRequirementKind.JUDGED_COUNT, "판정 완료", judgedCount, r.minJudged, 0),
                req(GradeRequirementKind.TRUST_INDEX, "신뢰도 지수 TI", trustIndex, r.minTi, 2),
                req(GradeRequirementKind.ACTIVE_SCORE, "활동 점수 AS", activeScore, r.minAs, 0));
    }
```

`evaluate`의 끝부분(`Grade next = switch …`부터 `return`까지)을 교체:

```java
        Grade next = nextOf(current);
        return new GradeStatus(current, next, progressToward(next, judgedCount, trustIndex, activeScore));
```

- [ ] **Step 4: 도메인 테스트 통과 확인**

Run: `./gradlew :domain-core:test`
Expected: PASS.

- [ ] **Step 5: 호출부 수정**

`SubmissionRef`의 다섯 번째 인자(경과일 `Duration.between(s.getCreatedAt(), …).toDays()`)를 `s.isSeed()`로 바꾼다:
- `VerdictRunner.java`(`judgeOne` 안 `refs.add(new SubmissionRef(...))`)
- `VerdictAdminService.java`(`applySupersede` 안)
- `TrendItemAdminService.java`(`detail` 안)

`GradeRecalcJob.java`의 AS 계산을 교체:

```java
        List<ActiveScore.Aged> aged = new ArrayList<>();
        for (ScoreLedgerEntry e : ledger.findByUserIdOrderByCreatedAtDesc(u.getId())) {
            long ageDays = Math.max(0, Duration.between(e.getDecayAnchorAt(), now).toDays());
            aged.add(new ActiveScore.Aged(e.getDelta().doubleValue(), ageDays, e.getHalflifeDays()));
        }
        double as = ActiveScore.compute(aged);
```

`MeService.activeScore(UUID)`도 같은 방식으로 교체(`ParameterSet.defaults()` 인자 삭제).

`ScoreLedgerRepository.java`: 쓰이지 않는 `sumDeltaByUser`(주석 "시간감쇠는 엔진에서 이미 delta에 반영"이 SP1 뒤 거짓)와 그 import를 삭제.

- [ ] **Step 6: 전체 확인**

Run: `./gradlew compileJava compileTestJava :domain-core:test :app:test`
Expected: PASS.

- [ ] **Step 7: 커밋**

```bash
git add backend/domain-core/src/main/java/kr/trendstage/domain/score/ScoreEngine.java backend/domain-core/src/main/java/kr/trendstage/domain/score/ScoreInput.java backend/domain-core/src/main/java/kr/trendstage/domain/score/SubmissionRef.java backend/domain-core/src/main/java/kr/trendstage/domain/score/VerdictComputation.java backend/domain-core/src/main/java/kr/trendstage/domain/score/ActiveScore.java backend/domain-core/src/main/java/kr/trendstage/domain/grade/SubmissionQuota.java backend/domain-core/src/main/java/kr/trendstage/domain/grade/GradePolicy.java backend/domain-core/src/test/java/kr/trendstage/domain/EngineGoldenTest.java backend/domain-core/src/test/java/kr/trendstage/domain/VerdictComputationTest.java backend/domain-core/src/test/java/kr/trendstage/domain/ReadModelTest.java backend/domain-core/src/test/java/kr/trendstage/domain/SubmissionQuotaTest.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/ScoreLedgerRepository.java backend/scheduler/src/main/java/kr/trendstage/scheduler/VerdictRunner.java backend/scheduler/src/main/java/kr/trendstage/scheduler/GradeRecalcJob.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/verdict/VerdictAdminService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/trend/TrendItemAdminService.java backend/api-public/src/main/java/kr/trendstage/apipublic/service/MeService.java
git commit -m "feat(domain): 원장 원값 Δ·행별 감쇠 AS, 제보권 주 경계·사용량, 등급 진행 상황

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: 판정 신호 `TrendSignal` — 시딩 제외, VOID는 유효 제보 0건일 때만

**Files:**
- Create: `backend/domain-core/src/main/java/kr/trendstage/domain/verdict/TrendSignal.java`
- Modify: `backend/domain-core/src/main/java/kr/trendstage/domain/verdict/VerdictEngine.java`
- Modify: `backend/domain-core/src/main/java/kr/trendstage/domain/score/VerdictComputation.java`
- Modify: `backend/domain-core/src/main/java/kr/trendstage/domain/score/VerdictPlan.java`
- Modify callers: `VerdictRunner.java`, `VerdictAdminService.java`
- Test: `backend/domain-core/src/test/java/kr/trendstage/domain/TrendSignalTest.java` (신설), `VerdictComputationTest.java`(재작성), `EngineGoldenTest.java`

옛 API(`SubmissionSignal`, `VerdictComputation.run(SubmissionSignal, boolean, …)`)는 `@Deprecated(forRemoval = true)`로 남겨 두고 Task 6에서 지운다. 그 사이 호출부가 하나씩 `JudgeService`로 옮겨 간다.

- [ ] **Step 1: 실패하는 테스트 작성**

`TrendSignalTest.java`:

```java
package kr.trendstage.domain;

import kr.trendstage.domain.verdict.TrendSignal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrendSignalTest {

    private static final Instant T = Instant.parse("2026-09-01T00:00:00Z");

    @Test void 시딩은_제보자_수와_플랫폼_수에서_빠지고_유효_제보_수에는_남는다() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), seed = UUID.randomUUID();
        TrendSignal s = new TrendSignal(T, List.of(
                entry(a, false, "X"), entry(a, false, "X"), entry(b, false, "INSTAGRAM"), entry(seed, true, "YOUTUBE")));
        assertEquals(4, s.validCount());
        assertEquals(2, s.distinctSubmitters());
        assertEquals(2, s.distinctPlatforms());
    }

    static TrendSignal.Entry entry(UUID user, boolean seed, String platform) {
        return new TrendSignal.Entry(UUID.randomUUID(), user, seed, T, platform, T);
    }
}
```

`VerdictComputationTest.java` 전체 교체:

```java
package kr.trendstage.domain;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.SubmissionRef;
import kr.trendstage.domain.score.VerdictComputation;
import kr.trendstage.domain.score.VerdictPlan;
import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.TrendSignal;
import kr.trendstage.domain.verdict.VerdictResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerdictComputationTest {

    private static final Instant T = Instant.parse("2026-09-01T00:00:00Z");
    private final ParameterSet p = ParameterSet.defaults();

    @Test void HIT_클러스터는_선점순위대로_점수가_난다() {
        List<SubmissionRef> refs = refs(12, 0);   // T = 12/20 = 0.6 → HIT L3
        VerdictPlan plan = VerdictComputation.run(signal(refs), refs, p);
        assertEquals(VerdictResult.HIT, plan.result());
        assertEquals(ReachLevel.L3, plan.reach());
        assertEquals(12, plan.ledgerLines().size());
        assertEquals(60.0, plan.ledgerLines().get(0).delta(), 1e-9);   // 30 × 1.0 × 2.0
        assertEquals(36.0, plan.ledgerLines().get(1).delta(), 1e-9);   // 30 × 0.6 × 2.0
    }

    @Test void 제보자가_적으면_MISS로_확신도의_절반을_잃는다() {
        List<SubmissionRef> refs = refs(2, 0);    // T = 0.1
        VerdictPlan plan = VerdictComputation.run(signal(refs), refs, p);
        assertEquals(VerdictResult.MISS, plan.result());
        assertEquals(-15.0, plan.ledgerLines().get(0).delta(), 1e-9);
    }

    @Test void 시딩_4_실유저_3이면_MISS이고_원장은_실유저만() {
        List<SubmissionRef> refs = refs(3, 4);    // n = 3 → T = 0.15 < 0.20 (시딩을 세면 0.35로 HIT였다)
        VerdictPlan plan = VerdictComputation.run(signal(refs), refs, p);
        assertEquals(VerdictResult.MISS, plan.result());
        assertEquals(3, plan.ledgerLines().size());
    }

    @Test void 시딩만_있으면_MISS이고_원장은_없다() {
        List<SubmissionRef> refs = refs(0, 2);
        VerdictPlan plan = VerdictComputation.run(signal(refs), refs, p);
        assertEquals(VerdictResult.MISS, plan.result());
        assertTrue(plan.ledgerLines().isEmpty());
    }

    @Test void 유효_제보가_0건이면_VOID() {
        VerdictPlan plan = VerdictComputation.run(new TrendSignal(T, List.of()), List.of(), p);
        assertEquals(VerdictResult.VOID, plan.result());
        assertTrue(plan.ledgerLines().isEmpty());
    }

    /** 실유저 real명(선점 1..real, 확신도 30) + 시딩 seeds건. */
    private static List<SubmissionRef> refs(int real, int seeds) {
        List<SubmissionRef> out = new ArrayList<>();
        for (int i = 1; i <= real; i++) out.add(new SubmissionRef(UUID.randomUUID(), UUID.randomUUID(), 30, i, false));
        for (int i = 0; i < seeds; i++) out.add(new SubmissionRef(UUID.randomUUID(), UUID.randomUUID(), 30, Integer.MAX_VALUE, true));
        return out;
    }

    private static TrendSignal signal(List<SubmissionRef> refs) {
        return new TrendSignal(T, refs.stream()
                .map(r -> new TrendSignal.Entry(r.submissionId(), r.userId(), r.seed(), T, "X", T)).toList());
    }
}
```

`EngineGoldenTest.java`의 `verdict_밴드는_distinctSubmitters_비율로_정해진다`에서 `new SubmissionSignal(n, k)`를 `signalOf(n)`으로 바꾸고 `SubmissionSignal` import를 지운 뒤 헬퍼를 추가(import `TrendSignal`, `java.time.Instant`, `java.util.UUID`, `java.util.stream.IntStream`) — Task 6에서 `SubmissionSignal`이 삭제되므로 테스트에 남기지 않는다:

```java
    private static TrendSignal signalOf(int distinctSubmitters) {
        Instant t = Instant.parse("2026-09-01T00:00:00Z");
        return new TrendSignal(t, IntStream.range(0, distinctSubmitters)
                .mapToObj(i -> new TrendSignal.Entry(UUID.randomUUID(), UUID.randomUUID(), false, t, "X", t)).toList());
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :domain-core:test`
Expected: 컴파일 실패 — `TrendSignal`, `VerdictPlan.ledgerLines()`, `VerdictComputation.run(TrendSignal, …)` 없음.

- [ ] **Step 3: `TrendSignal` 작성**

```java
package kr.trendstage.domain.verdict;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 판정 입력 = 관측 마감 전에 들어온 비VOID 제보 전부(J6). 외부 지표 없음(R5).
 * 시딩도 담는다 — "유효 제보 0건" 판정과 시딩 결과 기록에 필요하고, 계산 메서드가 시딩을 뺀다(P2, J3).
 * SP4가 쓸 원자료(제보 시각·플랫폼·제보자 가입일)를 지금부터 담아 판정 근거에 동결한다.
 */
public record TrendSignal(Instant deadline, List<Entry> entries) {

    public record Entry(UUID submissionId, UUID userId, boolean seed,
                        Instant submittedAt, String platform, Instant userJoinedAt) {}

    public TrendSignal {
        entries = List.copyOf(entries);
    }

    /** 유효(비VOID) 제보 수 — 시딩 포함. 0이면 판정은 VOID(P5). */
    public int validCount() {
        return entries.size();
    }

    /** 시딩을 뺀 서로 다른 제보자 수 — T의 입력. */
    public int distinctSubmitters() {
        return (int) entries.stream().filter(e -> !e.seed()).map(Entry::userId).distinct().count();
    }

    /** 시딩을 뺀 서로 다른 플랫폼 수(현행 자유 텍스트) — SP4 전까지 판정에 쓰지 않는다. */
    public int distinctPlatforms() {
        return (int) entries.stream().filter(e -> !e.seed()).map(Entry::platform)
                .filter(Objects::nonNull).distinct().count();
    }
}
```

- [ ] **Step 4: `VerdictEngine`·`VerdictPlan`·`VerdictComputation` 수정**

`VerdictEngine.java`: T 비율 계산과 밴드 분류를 private 헬퍼로 빼고 `TrendSignal` 판을 추가한다. 기존 `SubmissionSignal` 두 메서드에는 `@Deprecated(forRemoval = true)`를 붙이고 헬퍼를 쓰게 한다.

```java
    /** T = clip(시딩 제외 제보자 수 / submitterTarget, 0, 1). */
    public static double computeT(TrendSignal sig, ParameterSet p) {
        return ratio(sig.distinctSubmitters(), p);
    }

    public static VerdictOutcome evaluate(TrendSignal sig, ParameterSet p) {
        return classify(computeT(sig, p), p);
    }

    private static double ratio(int distinctSubmitters, ParameterSet p) {
        double r = distinctSubmitters / (double) Math.max(p.submitterTarget, 1);
        return Math.max(0.0, Math.min(1.0, r));
    }

    private static VerdictOutcome classify(double t, ParameterSet p) {
        if (t < p.hitThreshold) return new VerdictOutcome(VerdictResult.MISS, null, t);
        ReachLevel reach;
        if (t < p.bandL2) reach = ReachLevel.L1;
        else if (t < p.bandL3) reach = ReachLevel.L2;
        else if (t < p.bandL4) reach = ReachLevel.L3;
        else reach = ReachLevel.L4;
        return new VerdictOutcome(VerdictResult.HIT, reach, t);
    }
```

`VerdictPlan.java`:

```java
/**
 * 한 항목의 판정 계획(순수 산출물). 원장 라인은 시딩이 아닌 제보만 담는다(P2) — 제보 result는
 * 호출 측이 result를 유효 제보 전부(시딩 포함)에 적용한다. IO 없음 — 시뮬레이션(ADM-600)이 같은 계산을 쓴다.
 */
public record VerdictPlan(VerdictResult result, ReachLevel reach, double t, List<LedgerLine> ledgerLines) {}
```

`VerdictComputation.java`: 새 오버로드를 추가하고 옛 오버로드를 같은 라인 생성 헬퍼로 바꾼다.

```java
    /**
     * 판정 + 원장 라인. 유효 제보가 0건이면 VOID(라인 없음) — 판정 시점의 VOID는 이것뿐이다(P5).
     * 원장 라인은 시딩이 아닌 제보만 만든다(P2).
     */
    public static VerdictPlan run(TrendSignal signal, List<SubmissionRef> subs, ParameterSet p) {
        if (signal.validCount() == 0) {
            return new VerdictPlan(VerdictResult.VOID, null, 0.0, List.of());
        }
        VerdictOutcome o = VerdictEngine.evaluate(signal, p);
        return new VerdictPlan(o.result(), o.reach(), o.t(), ledgerLines(o, subs, p));
    }

    /** @deprecated Task 6에서 삭제 — {@link #run(TrendSignal, List, ParameterSet)}를 쓴다. */
    @Deprecated(forRemoval = true)
    public static VerdictPlan run(SubmissionSignal signal, boolean voidByRule, List<SubmissionRef> subs, ParameterSet p) {
        double t = VerdictEngine.computeT(signal, p);
        if (voidByRule) {
            return new VerdictPlan(VerdictResult.VOID, null, t, List.of());
        }
        VerdictOutcome o = VerdictEngine.evaluate(signal, p);
        return new VerdictPlan(o.result(), o.reach(), o.t(), ledgerLines(o, subs, p));
    }

    private static List<LedgerLine> ledgerLines(VerdictOutcome o, List<SubmissionRef> subs, ParameterSet p) {
        List<LedgerLine> lines = new ArrayList<>();
        for (SubmissionRef s : subs) {
            if (s.seed()) continue;
            ScoreResult r = ScoreEngine.compute(new ScoreInput(o.result(), s.confidence(), s.orderRank(), o.reach()), p);
            lines.add(new LedgerLine(s.userId(), s.submissionId(), o.result(), r.delta(), r.breakdown()));
        }
        return lines;
    }
```

클래스 주석의 "상위(수집·검수)에서 판단해 voidByRule 플래그로 넘긴다" 문단은 "VOID는 사건(항목 VOID·중복 제보)의 결과이고, 판정 시점의 VOID는 유효 제보 0건뿐이다(P5)"로 바꾼다.

- [ ] **Step 5: 옛 호출부를 `ledgerLines`에 맞춘다** (임시 — Task 4·5에서 교체)

`VerdictRunner.judgeOne`의 "5) score_ledger + 제보 결과 반영" 루프를 교체:

```java
        // 5) score_ledger(시딩 제외는 plan이 보장) + 제보 결과(시딩 포함 전부)
        for (LedgerLine line : plan.ledgerLines()) {
            ledger.save(ScoreLedgerEntry.ofVerdict(
                    line.userId(), line.submissionId(), verdict.getId(),
                    toLedgerKind(line.kind()), BigDecimal.valueOf(line.delta()), line.reason(), p.halflifeDays, judgedAt));
        }
        for (Submission s : subEntities) {
            s.markResult(toSubResult(plan.result()), judgedAt);
        }
```

`VerdictAdminService`: `reconcileLedger` 안 `newPlan.lines()` → `newPlan.ledgerLines()`. `applySupersede`의 결과 반영 루프(`for (LedgerLine line : plan.lines()) …`)를 `for (Submission s : subEntities) s.markResult(toSubResult(plan.result()), now);`로 교체.

- [ ] **Step 6: 통과 확인**

Run: `./gradlew compileJava compileTestJava :domain-core:test :app:test`
Expected: PASS (deprecation 경고는 무시).

- [ ] **Step 7: 커밋**

```bash
git add backend/domain-core/src/main/java/kr/trendstage/domain/verdict/TrendSignal.java backend/domain-core/src/main/java/kr/trendstage/domain/verdict/VerdictEngine.java backend/domain-core/src/main/java/kr/trendstage/domain/score/VerdictComputation.java backend/domain-core/src/main/java/kr/trendstage/domain/score/VerdictPlan.java backend/domain-core/src/test/java/kr/trendstage/domain/TrendSignalTest.java backend/domain-core/src/test/java/kr/trendstage/domain/VerdictComputationTest.java backend/domain-core/src/test/java/kr/trendstage/domain/EngineGoldenTest.java backend/scheduler/src/main/java/kr/trendstage/scheduler/VerdictRunner.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/verdict/VerdictAdminService.java
git commit -m "feat(domain): TrendSignal — 시딩 제외 신호, VOID는 유효 제보 0건일 때만(voidByRule 폐기 예정)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: `judge` 모듈과 배치 판정 — 판정이 실제로 저장된다

**Files:**
- Modify: `backend/settings.gradle.kts`
- Create: `backend/judge/build.gradle.kts`
- Create: `backend/judge/src/main/java/kr/trendstage/judge/JudgeService.java`
- Create: `backend/judge/src/main/java/kr/trendstage/judge/VerdictEvidence.java`
- Create: `backend/judge/src/main/java/kr/trendstage/judge/ParamsSnapshot.java`
- Create: `backend/judge/src/main/java/kr/trendstage/judge/JudgeRejectedException.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/TrendItemRepository.java`
- Modify: `backend/scheduler/build.gradle.kts`, `backend/app/build.gradle.kts`
- Rewrite: `backend/scheduler/src/main/java/kr/trendstage/scheduler/VerdictRunner.java`
- Test: `backend/app/src/test/java/kr/trendstage/judge/JudgePipelineTest.java`
- Test: `backend/app/src/test/java/kr/trendstage/judge/VerdictRunnerRetryTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`JudgePipelineTest.java`:

```java
package kr.trendstage.judge;

import kr.trendstage.scheduler.VerdictRunner;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 스펙 §7.2 1~6: 배치 판정이 결과·상태·원장을 남기고, 멱등이며, 시딩·VOID·관측 마감을 지킨다. */
class JudgePipelineTest extends AbstractIntegrationTest {

    private static final Instant FIRST_SEEN = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant AFTER_DEADLINE = FIRST_SEEN.plus(Duration.ofDays(15));

    @Autowired JudgeService judge;
    @Autowired VerdictRunner runner;

    @Test
    void batchPersistsResultsStateAndRawLedger() {   // #1 — self-invocation 회귀
        UUID item = fx.item(FIRST_SEEN);
        UUID a = fx.submission(fx.user(), item, 30, FIRST_SEEN.plusSeconds(1));
        UUID b = fx.submission(fx.user(), item, 10, FIRST_SEEN.plusSeconds(2));
        UUID c = fx.submission(fx.user(), item, 50, FIRST_SEEN.plusSeconds(3));
        clock.set(AFTER_DEADLINE);

        runBatch();

        assertThat(fx.itemState(item)).isEqualTo("RESOLVED");   // 3명 → T = 0.15 → MISS
        for (UUID s : List.of(a, b, c)) {
            assertThat(fx.submissionResult(s)).isEqualTo("MISS");
            assertThat(jdbc.queryForObject("SELECT resolved_at FROM submissions WHERE id = ?", Timestamp.class, s)
                    .toInstant()).isEqualTo(AFTER_DEADLINE);
        }
        assertThat(ledgerDelta(a)).isEqualByComparingTo("-15");   // 원값 −c × 0.5, 감쇠 없음
        assertThat(ledgerDelta(b)).isEqualByComparingTo("-5");
        assertThat(ledgerDelta(c)).isEqualByComparingTo("-25");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM score_ledger WHERE submission_id IN (?, ?, ?) "
                + "AND halflife_days = 90 AND decay_anchor_at = ?", Integer.class, a, b, c, Timestamp.from(AFTER_DEADLINE)))
                .isEqualTo(3);
    }

    @Test
    void judgingTwiceKeepsOneVerdictAndLedger() {   // #2
        UUID item = fx.item(FIRST_SEEN);
        fx.submission(fx.user(), item, 30, FIRST_SEEN.plusSeconds(1));
        judge.closeDue(AFTER_DEADLINE);

        assertThat(judge.judge(item, AFTER_DEADLINE)).isTrue();
        assertThat(judge.judge(item, AFTER_DEADLINE)).isFalse();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM verdicts WHERE trend_item_id = ?", Integer.class, item))
                .isEqualTo(1);
        assertThat(fx.ledgerRows(item)).isEqualTo(1);
    }

    @Test
    void seedsAreExcludedFromSignalRankAndLedger() {   // #3
        UUID item = fx.item(FIRST_SEEN);
        List<UUID> seeds = new ArrayList<>();
        for (int i = 0; i < 4; i++) seeds.add(fx.seedSubmission(fx.user(), item, FIRST_SEEN.plusSeconds(i)));
        List<UUID> real = new ArrayList<>();
        for (int i = 0; i < 3; i++) real.add(fx.submission(fx.user(), item, 30, FIRST_SEEN.plusSeconds(100 + i)));

        judgeAfterDeadline(item);

        assertThat(fx.itemState(item)).isEqualTo("RESOLVED");
        assertThat(verdictResult(item)).isEqualTo("MISS");   // 시딩을 세면 7명 → 0.35 → HIT였다
        for (UUID s : seeds) {
            assertThat(fx.submissionResult(s)).isEqualTo("MISS");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM score_ledger WHERE submission_id = ?", Integer.class, s)).isZero();
        }
        for (int i = 0; i < real.size(); i++) {
            assertThat(jdbc.queryForObject("SELECT (evidence_json->'orderRanks'->>?)::int FROM verdicts WHERE trend_item_id = ?",
                    Integer.class, real.get(i).toString(), item)).isEqualTo(i + 1);
        }
    }

    @Test
    void fourRealSubmittersHitL1WithRawDelta() {   // #4
        UUID item = fx.item(FIRST_SEEN);
        UUID first = fx.submission(fx.user(), item, 50, FIRST_SEEN.plusSeconds(1));
        for (int i = 0; i < 3; i++) fx.submission(fx.user(), item, 30, FIRST_SEEN.plusSeconds(10 + i));

        judgeAfterDeadline(item);

        assertThat(jdbc.queryForObject("SELECT result::text || ':' || reach_level::text FROM verdicts WHERE trend_item_id = ?",
                String.class, item)).isEqualTo("HIT:L1");
        assertThat(ledgerDelta(first)).isEqualByComparingTo("60");   // 50 × 1.0 × 1.2
    }

    @Test
    void itemWithoutValidSubmissionsBecomesVoid() {   // #5
        UUID item = fx.item(FIRST_SEEN);
        UUID s = fx.submission(fx.user(), item, 30, FIRST_SEEN.plusSeconds(1));
        fx.voidSubmission(s, FIRST_SEEN.plusSeconds(2));

        judgeAfterDeadline(item);

        assertThat(fx.itemState(item)).isEqualTo("VOID");
        assertThat(verdictResult(item)).isEqualTo("VOID");
        assertThat(fx.ledgerRows(item)).isZero();
    }

    @Test
    void submissionsAfterDeadlineAreNotCounted() {   // #6(판정 쪽). 제보 단계 차단은 Task 7
        UUID item = fx.item(FIRST_SEEN);
        for (int i = 0; i < 3; i++) fx.submission(fx.user(), item, 30, FIRST_SEEN.plus(Duration.ofDays(13)));
        UUID late = fx.submission(fx.user(), item, 30, FIRST_SEEN.plus(Duration.ofDays(14)).plusSeconds(1));

        judgeAfterDeadline(item);

        assertThat(verdictResult(item)).isEqualTo("MISS");   // 늦은 제보를 세면 4명 → HIT였다
        assertThat(jdbc.queryForObject("SELECT (evidence_json->>'distinctSubmitters')::int FROM verdicts WHERE trend_item_id = ?",
                Integer.class, item)).isEqualTo(3);
        assertThat(fx.submissionResult(late)).isEqualTo("PENDING");
    }

    @Test
    void graceExtensionAfterClosingReturnsItemToPending() {
        UUID item = fx.item(FIRST_SEEN);
        fx.submission(fx.user(), item, 30, FIRST_SEEN.plusSeconds(1));
        judge.closeDue(AFTER_DEADLINE);
        jdbc.update("UPDATE trend_items SET judgment_deadline_override = ? WHERE id = ?",
                Timestamp.from(AFTER_DEADLINE.plus(Duration.ofDays(3))), item);

        assertThat(judge.judge(item, AFTER_DEADLINE)).isFalse();
        assertThat(fx.itemState(item)).isEqualTo("PENDING");
    }

    private void judgeAfterDeadline(UUID item) {
        judge.closeDue(AFTER_DEADLINE);
        judge.judge(item, AFTER_DEADLINE);
    }

    private void runBatch() {
        releaseBatchLock("verdict_runner");
        runner.run();
    }

    private String verdictResult(UUID item) {
        return jdbc.queryForObject("SELECT result::text FROM verdicts WHERE trend_item_id = ?", String.class, item);
    }

    private BigDecimal ledgerDelta(UUID submission) {
        return jdbc.queryForObject("SELECT delta FROM score_ledger WHERE submission_id = ?", BigDecimal.class, submission);
    }
}
```

`VerdictRunnerRetryTest.java`(판정 중 예외 → JUDGING 유지 → 다음 실행에서 판정, 스펙 §7.2 7번):

```java
package kr.trendstage.judge;

import kr.trendstage.scheduler.VerdictRunner;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

class VerdictRunnerRetryTest extends AbstractIntegrationTest {

    private static final Instant FIRST_SEEN = Instant.parse("2026-07-01T00:00:00Z");

    @SpyBean JudgeService judge;
    @Autowired VerdictRunner runner;

    @Test
    void failedItemStaysJudgingAndIsRetriedNextRun() {
        UUID item = fx.item(FIRST_SEEN);
        fx.submission(fx.user(), item, 30, FIRST_SEEN.plusSeconds(1));
        clock.set(FIRST_SEEN.plus(Duration.ofDays(15)));

        doThrow(new IllegalStateException("주입된 실패")).when(judge).judge(eq(item), any());
        runBatch();
        assertThat(fx.itemState(item)).isEqualTo("JUDGING");

        Mockito.reset(judge);
        runBatch();
        assertThat(fx.itemState(item)).isEqualTo("RESOLVED");
    }

    private void runBatch() {
        releaseBatchLock("verdict_runner");
        runner.run();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:test --tests "kr.trendstage.judge.*"`
Expected: 컴파일 실패 — `kr.trendstage.judge.JudgeService` 없음.

- [ ] **Step 3: 모듈 추가**

`settings.gradle.kts`의 `include(`에 `"merge",` 다음 줄로 추가:

```kotlin
    "judge",         // 판정 실행(JudgeService) — scheduler·api-admin 공용. 배치·재판정·VOID·미리보기의 유일한 경로(P6)
```

`backend/judge/build.gradle.kts`:

```kotlin
plugins { id("io.spring.dependency-management") }
dependencyManagement { imports { mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.5") } }
dependencies {
    implementation(project(":domain-core"))
    implementation(project(":persistence"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("com.fasterxml.jackson.core:jackson-databind")   // 판정 근거(evidence_json) 직렬화
}
```

`backend/scheduler/build.gradle.kts`와 `backend/app/build.gradle.kts`의 `dependencies`에 `implementation(project(":judge"))`를 추가한다(각각 `:merge` 다음 줄).

- [ ] **Step 4: 항목 행 잠금 쿼리**

`TrendItemRepository.java`에 추가(import `jakarta.persistence.LockModeType`, `org.springframework.data.jpa.repository.Lock`, `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`):

```java
    /** 판정·재판정·VOID가 같은 항목을 동시에 건드리지 않게 행을 잠근다(SELECT … FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TrendItem t where t.id = :id")
    Optional<TrendItem> findByIdForUpdate(@Param("id") UUID id);
```

- [ ] **Step 5: 판정 근거·파라미터 스냅샷·예외**

`JudgeRejectedException.java`:

```java
package kr.trendstage.judge;

/** 판정 규칙상 받아들일 수 없는 요청(없는 항목, 판정 이력 없음 등). 콘솔 API는 422로 매핑한다. */
public class JudgeRejectedException extends RuntimeException {
    public JudgeRejectedException(String message) {
        super(message);
    }
}
```

`ParamsSnapshot.java`:

```java
package kr.trendstage.judge;

import kr.trendstage.domain.params.ParameterSet;

/**
 * ParameterSet의 JSON 형태. 판정 근거에 동결했다가 재판정에서 되살린다(J2).
 * domain-core에 Jackson을 들이지 않으려고 여기 둔다.
 */
public record ParamsSnapshot(int submitterTarget, double hitThreshold, double bandL2, double bandL3, double bandL4,
                             double mL1, double mL2, double mL3, double mL4,
                             double wRank1, double wRank2, double wRank3, double wRankRest,
                             int halflifeDays, double tiAlpha, double tiBeta) {

    public static ParamsSnapshot of(ParameterSet p) {
        return new ParamsSnapshot(p.submitterTarget, p.hitThreshold, p.bandL2, p.bandL3, p.bandL4,
                p.mL1, p.mL2, p.mL3, p.mL4, p.wRank1, p.wRank2, p.wRank3, p.wRankRest,
                p.halflifeDays, p.tiAlpha, p.tiBeta);
    }

    public ParameterSet toParameterSet() {
        return new ParameterSet(submitterTarget, hitThreshold, bandL2, bandL3, bandL4, mL1, mL2, mL3, mL4,
                wRank1, wRank2, wRank3, wRankRest, halflifeDays, tiAlpha, tiBeta);
    }
}
```

`VerdictEvidence.java`:

```java
package kr.trendstage.judge;

import kr.trendstage.domain.verdict.TrendSignal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * verdicts.evidence_json 형식. 재판정(원 판정 파라미터 — J2)·파라미터 시뮬레이션(신호)·감사의 입력이다.
 * 판정 행과 함께 한 번 기록되면 바뀌지 않는다(append-only). VOID 판정은 params·signal이 null이다.
 */
public record VerdictEvidence(String result, String reach, BigDecimal t, Instant deadline, ParamsSnapshot params,
                              TrendSignal signal, int distinctSubmitters, int distinctPlatforms,
                              Map<UUID, Integer> orderRanks, UUID supersededVerdictId, String adminReason) {}
```

- [ ] **Step 6: `JudgeService` 작성 (배치 판정까지)**

```java
package kr.trendstage.judge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.LedgerLine;
import kr.trendstage.domain.score.SubmissionRef;
import kr.trendstage.domain.score.VerdictComputation;
import kr.trendstage.domain.score.VerdictPlan;
import kr.trendstage.domain.verdict.DeadlineWindow;
import kr.trendstage.domain.verdict.TrendSignal;
import kr.trendstage.domain.verdict.VerdictResult;
import kr.trendstage.persistence.entity.ScoreLedgerEntry;
import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.entity.SubmissionOrderRank;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.entity.UserAccount;
import kr.trendstage.persistence.entity.Verdict;
import kr.trendstage.persistence.params.CurrentParameterSetResolver;
import kr.trendstage.persistence.repo.ScoreLedgerRepository;
import kr.trendstage.persistence.repo.SubmissionOrderRankRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.UserRepository;
import kr.trendstage.persistence.repo.VerdictRepository;
import kr.trendstage.persistence.type.LedgerKind;
import kr.trendstage.persistence.type.SubmissionResult;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 판정 실행의 유일한 경로(P6). 배치(verdict_runner)·관리자 재판정·항목 VOID·ADM-111 미리보기가 공유한다.
 * 공개 메서드마다 트랜잭션이다 — 호출 측은 다른 빈이어야 프록시를 탄다(04 §3, self-invocation 금지).
 * 대상 항목은 행 잠금(SELECT … FOR UPDATE)으로 직렬화한다.
 */
@Service
public class JudgeService {

    private final TrendItemRepository trendItems;
    private final SubmissionRepository submissions;
    private final SubmissionOrderRankRepository orderRanks;
    private final UserRepository users;
    private final VerdictRepository verdicts;
    private final ScoreLedgerRepository ledger;
    private final CurrentParameterSetResolver params;
    private final ObjectMapper objectMapper;

    public JudgeService(TrendItemRepository trendItems, SubmissionRepository submissions,
                        SubmissionOrderRankRepository orderRanks, UserRepository users, VerdictRepository verdicts,
                        ScoreLedgerRepository ledger, CurrentParameterSetResolver params, ObjectMapper objectMapper) {
        this.trendItems = trendItems;
        this.submissions = submissions;
        this.orderRanks = orderRanks;
        this.users = users;
        this.verdicts = verdicts;
        this.ledger = ledger;
        this.params = params;
        this.objectMapper = objectMapper;
    }

    /** 관측 마감이 지난 PENDING 항목을 JUDGING으로 닫는다(J6 — 이후 제보를 받지 않는다). @return 닫은 항목 수 */
    @Transactional
    public int closeDue(Instant now) {
        int closed = 0;
        for (TrendItem item : trendItems.findByStateIn(List.of(TrendState.PENDING))) {
            if (!now.isBefore(deadlineOf(item))) {
                item.transitionTo(TrendState.JUDGING);
                closed++;
            }
        }
        return closed;
    }

    /**
     * 원본 판정. 멱등 — JUDGING이 아니거나 원본 판정이 이미 있으면 아무것도 하지 않는다
     * (최종 방어는 DB의 verdict_one_original_per_item). 유예 연장으로 마감이 다시 미래가 됐으면 PENDING으로 되돌린다.
     *
     * @return 판정했으면 true
     */
    @Transactional
    public boolean judge(UUID itemId, Instant now) {
        TrendItem item = lock(itemId);
        if (item.getState() != TrendState.JUDGING || verdicts.existsByTrendItemIdAndSupersedesIsNull(itemId)) {
            return false;
        }
        Instant deadline = deadlineOf(item);
        if (now.isBefore(deadline)) {
            item.transitionTo(TrendState.PENDING);
            return false;
        }
        ParameterSet p = params.resolve();
        Inputs in = collect(item, deadline);
        VerdictPlan plan = VerdictComputation.run(in.signal(), in.refs(), p);

        Verdict verdict = verdicts.saveAndFlush(new Verdict(itemId, plan.result(), plan.reach(), scoreT(plan),
                now, evidence(plan, in, p, null, null), null));
        for (LedgerLine line : plan.ledgerLines()) {
            ledger.save(ScoreLedgerEntry.ofVerdict(line.userId(), line.submissionId(), verdict.getId(),
                    toLedgerKind(line.kind()), amount(line.delta()), line.reason(), p.halflifeDays, now));
        }
        for (Submission s : in.submissions()) {
            s.markResult(toSubmissionResult(plan.result()), now);
        }
        item.transitionTo(plan.result() == VerdictResult.VOID ? TrendState.VOID : TrendState.RESOLVED);
        return true;
    }

    // ── 공용 ──────────────────────────────────────────────────────────────

    TrendItem lock(UUID itemId) {
        return trendItems.findByIdForUpdate(itemId)
                .orElseThrow(() -> new JudgeRejectedException("존재하지 않는 항목입니다"));
    }

    static Instant deadlineOf(TrendItem item) {
        return DeadlineWindow.effectiveDeadline(item.getFirstSeenAt(), item.getJudgmentDeadlineOverride());
    }

    /** 관측 마감 전 비VOID 제보 → 판정 신호·점수 입력. 선점 순위는 시딩을 뺀 뷰(J3). */
    Inputs collect(TrendItem item, Instant deadline) {
        List<Submission> subs = submissions.findByTrendItemIdAndResultNot(item.getId(), SubmissionResult.VOID).stream()
                .filter(s -> s.getCreatedAt().isBefore(deadline))
                .sorted(Comparator.comparing(Submission::getCreatedAt))
                .toList();
        Map<UUID, Instant> joinedAt = users.findAllById(subs.stream().map(Submission::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getJoinedAt));
        Map<UUID, Integer> ranks = orderRanks.findByTrendItemId(item.getId()).stream()
                .collect(Collectors.toMap(SubmissionOrderRank::getSubmissionId, SubmissionOrderRank::getOrderRank));

        List<TrendSignal.Entry> entries = subs.stream().map(s -> new TrendSignal.Entry(
                s.getId(), s.getUserId(), s.isSeed(), s.getCreatedAt(), s.getSourcePlatform(),
                joinedAt.get(s.getUserId()))).toList();
        List<SubmissionRef> refs = subs.stream().map(s -> new SubmissionRef(
                s.getId(), s.getUserId(), s.getConfidence(),
                ranks.getOrDefault(s.getId(), Integer.MAX_VALUE), s.isSeed())).toList();
        return new Inputs(new TrendSignal(deadline, entries), refs, subs, ranks);
    }

    String evidence(VerdictPlan plan, Inputs in, ParameterSet p, UUID supersededVerdictId, String adminReason) {
        return write(new VerdictEvidence(plan.result().name(), plan.reach() == null ? null : plan.reach().name(),
                scoreT(plan), in.signal().deadline(), ParamsSnapshot.of(p), in.signal(),
                in.signal().distinctSubmitters(), in.signal().distinctPlatforms(), in.ranks(),
                supersededVerdictId, adminReason));
    }

    String write(VerdictEvidence ev) {
        try {
            return objectMapper.writeValueAsString(ev);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("판정 근거 직렬화 실패", e);
        }
    }

    static BigDecimal scoreT(VerdictPlan plan) {
        return plan.result() == VerdictResult.VOID ? null
                : BigDecimal.valueOf(plan.t()).setScale(4, RoundingMode.HALF_UP);
    }

    /** 원장 금액 — score_ledger.delta(NUMERIC(10,4))와 같은 자릿수. 재판정 차액 비교에 부동소수 잡음이 끼지 않게 한다. */
    static BigDecimal amount(double delta) {
        return BigDecimal.valueOf(delta).setScale(4, RoundingMode.HALF_UP);
    }

    static LedgerKind toLedgerKind(VerdictResult r) {
        return switch (r) {
            case HIT -> LedgerKind.HIT;
            case MISS -> LedgerKind.MISS;
            case VOID -> throw new IllegalStateException("VOID 판정에는 원장 라인이 없다");
        };
    }

    static SubmissionResult toSubmissionResult(VerdictResult r) {
        return switch (r) {
            case HIT -> SubmissionResult.HIT;
            case MISS -> SubmissionResult.MISS;
            case VOID -> SubmissionResult.VOID;
        };
    }

    record Inputs(TrendSignal signal, List<SubmissionRef> refs, List<Submission> submissions, Map<UUID, Integer> ranks) {}
}
```

- [ ] **Step 7: `VerdictRunner` 재작성**

```java
package kr.trendstage.scheduler;

import kr.trendstage.judge.JudgeService;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.TrendState;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * D+14 판정 배치 — 목록 순회만 한다. 판정은 JudgeService(별도 빈, 항목마다 트랜잭션)가 한다.
 * 이 클래스에 @Transactional 메서드를 두고 직접 호출하면 프록시를 타지 않아 트랜잭션이 걸리지 않는다(04 §3).
 * 1) 마감 지난 PENDING → JUDGING  2) JUDGING 항목마다 판정. 실패한 항목은 JUDGING으로 남아 다음 실행에서 재시도된다.
 */
@Component
public class VerdictRunner {

    private static final Logger log = LoggerFactory.getLogger(VerdictRunner.class);

    private final JudgeService judgeService;
    private final TrendItemRepository trendItems;
    private final Clock clock;

    public VerdictRunner(JudgeService judgeService, TrendItemRepository trendItems, Clock clock) {
        this.judgeService = judgeService;
        this.trendItems = trendItems;
        this.clock = clock;
    }

    /** 일 1회 03:00 KST(= 18:00 UTC — 컨테이너 JVM은 UTC). */
    @Scheduled(cron = "${jobs.verdict-runner.cron:0 0 18 * * *}")
    @SchedulerLock(name = "verdict_runner", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        Instant now = clock.instant();
        int closed = judgeService.closeDue(now);
        List<UUID> judging = trendItems.findByStateIn(List.of(TrendState.JUDGING)).stream()
                .map(TrendItem::getId).toList();
        int judged = 0;
        int failed = 0;
        for (UUID id : judging) {
            try {
                if (judgeService.judge(id, now)) judged++;
            } catch (RuntimeException e) {
                failed++;
                log.error("판정 실패 item={} — JUDGING으로 남겨 다음 실행에서 재시도: {}", id, e.getMessage(), e);
            }
        }
        log.info("verdict_runner 완료: 마감 {} · 판정 대상 {} · 판정 {} · 실패 {}", closed, judging.size(), judged, failed);
    }
}
```

- [ ] **Step 8: 통과 확인**

Run: `./gradlew compileJava compileTestJava :app:test --tests "kr.trendstage.judge.*"`
Expected: PASS (`SchemaV28Test` 8 + `JudgePipelineTest` 7 + `VerdictRunnerRetryTest` 1).

**문제 해결:** `VerdictEvidence` 역직렬화는 Task 5에서 처음 쓰인다. 여기서 JSON이 이상하면 `SELECT evidence_json FROM verdicts ...`로 한 행을 찍어 `params`·`signal.entries[].submittedAt`(ISO 문자열이어야 함)을 확인한다 — Spring Boot 기본 `ObjectMapper`는 `WRITE_DATES_AS_TIMESTAMPS`가 꺼져 있다.

- [ ] **Step 9: 커밋**

```bash
git add backend/settings.gradle.kts backend/judge/build.gradle.kts backend/judge/src/main/java/kr/trendstage/judge/JudgeService.java backend/judge/src/main/java/kr/trendstage/judge/VerdictEvidence.java backend/judge/src/main/java/kr/trendstage/judge/ParamsSnapshot.java backend/judge/src/main/java/kr/trendstage/judge/JudgeRejectedException.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/TrendItemRepository.java backend/scheduler/build.gradle.kts backend/app/build.gradle.kts backend/scheduler/src/main/java/kr/trendstage/scheduler/VerdictRunner.java backend/app/src/test/java/kr/trendstage/judge/JudgePipelineTest.java backend/app/src/test/java/kr/trendstage/judge/VerdictRunnerRetryTest.java
git commit -m "feat(judge): JudgeService 신설 — 배치 판정을 항목 단위 트랜잭션으로, JUDGING 전이·관측 마감·판정 근거 동결

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: 재판정·항목 VOID를 `JudgeService`로

**Files:**
- Modify: `backend/judge/src/main/java/kr/trendstage/judge/JudgeService.java`
- Create: `backend/judge/src/main/java/kr/trendstage/judge/JudgeConflictException.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/ScoreLedgerRepository.java`
- Modify: `backend/api-admin/build.gradle.kts`
- Rewrite: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/verdict/VerdictAdminService.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/MergeQueueController.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminApiExceptionHandler.java`
- Modify: `backend/merge/src/main/java/kr/trendstage/merge/MergeService.java` (`voidTrendItem` 삭제)
- Modify: `backend/app/src/test/java/kr/trendstage/support/Fixtures.java`
- Test: `backend/app/src/test/java/kr/trendstage/judge/RejudgeAndVoidTest.java`

- [ ] **Step 1: 헬퍼 추가**

`Fixtures.java`에 추가:

```java
    public UUID admin() {
        return jdbc.queryForObject("INSERT INTO admin_accounts (login_id, display_name, role, password_hash) "
                + "VALUES (?, '테스트 관리자', 'ADMIN', 'x') RETURNING id", UUID.class, "a_" + rand());
    }

    /** 적용된 파라미터 드래프트(가장 최근 APPLIED가 현재값). 테스트 끝에 반드시 {@link #deleteDraft}로 지운다 — 공유 DB. */
    public UUID appliedDraft(String payloadJson) {
        return jdbc.queryForObject("INSERT INTO parameter_drafts (author_id, status, payload, applied_at) "
                + "VALUES (?, 'APPLIED', ?::jsonb, now()) RETURNING id", UUID.class, admin(), payloadJson);
    }

    public void deleteDraft(UUID draftId) {
        jdbc.update("DELETE FROM parameter_drafts WHERE id = ?", draftId);
    }
```

- [ ] **Step 2: 실패하는 테스트 작성**

`RejudgeAndVoidTest.java`:

```java
package kr.trendstage.judge;

import kr.trendstage.domain.verdict.VerdictResult;
import kr.trendstage.persistence.entity.Verdict;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 스펙 §7.2 8~10: 재판정 차액(제보 단위·원 판정 기준), 동결 파라미터(J2), 항목 VOID. */
class RejudgeAndVoidTest extends AbstractIntegrationTest {

    private static final Instant FIRST_SEEN = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant JUDGED_AT = FIRST_SEEN.plus(Duration.ofDays(15));

    @Autowired JudgeService judge;

    @Test
    void rejudgeWritesPerSubmissionDiffAndSecondRejudgeAddsNothing() {   // #8
        UUID item = fx.item(FIRST_SEEN);
        UUID seedUser = fx.user();
        fx.seedSubmission(seedUser, item, FIRST_SEEN);
        List<UUID> users = new ArrayList<>();
        List<UUID> subs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID u = fx.user();
            users.add(u);
            subs.add(fx.submission(u, item, 30, FIRST_SEEN.plusSeconds(10 + i)));
        }
        judgeAfterDeadline(item);   // 실유저 4명 → HIT L1

        // 카르텔로 드러난 2건 VOID → 2명 → MISS
        fx.voidSubmission(subs.get(2), JUDGED_AT.plusSeconds(10));
        fx.voidSubmission(subs.get(3), JUDGED_AT.plusSeconds(10));
        judge.rejudge(item, "카르텔", JUDGED_AT.plus(Duration.ofDays(1)));

        assertThat(fx.ledgerSum(users.get(0), item)).isEqualByComparingTo("-15");   // 36 − 51
        assertThat(fx.ledgerSum(users.get(1), item)).isEqualByComparingTo("-15");   // 21.6 − 36.6
        assertThat(fx.ledgerSum(users.get(2), item)).isEqualByComparingTo("0");
        assertThat(fx.ledgerSum(users.get(3), item)).isEqualByComparingTo("0");
        assertThat(fx.ledgerSum(seedUser, item)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM score_ledger WHERE user_id = ?", Integer.class, seedUser)).isZero();
        // ADJ는 원 판정의 감쇠 기준을 쓴다
        assertThat(jdbc.queryForObject("SELECT count(*) FROM score_ledger l JOIN verdicts v ON v.id = l.verdict_id "
                + "WHERE v.trend_item_id = ? AND l.kind = 'ADJ' AND (l.decay_anchor_at <> ? OR l.halflife_days <> 90)",
                Integer.class, item, Timestamp.from(JUDGED_AT))).isZero();

        int rowsAfterFirst = fx.ledgerRows(item);
        judge.rejudge(item, "재확인", JUDGED_AT.plus(Duration.ofDays(2)));
        assertThat(fx.ledgerRows(item)).isEqualTo(rowsAfterFirst);   // 차액 0 — 두 번째 재판정 이중 반영 회귀
        assertThat(fx.ledgerSum(users.get(0), item)).isEqualByComparingTo("-15");
    }

    @Test
    void rejudgeUsesParametersFrozenAtOriginalJudgment() {   // #9, J2
        UUID item = fx.item(FIRST_SEEN);
        for (int i = 0; i < 3; i++) fx.submission(fx.user(), item, 30, FIRST_SEEN.plusSeconds(i));
        judgeAfterDeadline(item);   // 3명 → T = 0.15 → MISS

        UUID draft = fx.appliedDraft("{\"submitterTarget\":20,\"hitThreshold\":0.10}");   // 지금 적용값이면 HIT
        try {
            Verdict next = judge.rejudge(item, "입력 변화 없음", JUDGED_AT.plus(Duration.ofDays(1)));
            assertThat(next.getResult()).isEqualTo(VerdictResult.MISS);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM score_ledger l JOIN verdicts v ON v.id = l.verdict_id "
                    + "WHERE v.trend_item_id = ? AND l.kind = 'ADJ'", Integer.class, item)).isZero();
        } finally {
            fx.deleteDraft(draft);
        }
    }

    @Test
    void voidingJudgedItemZeroesLedgerAndVoidsSubmissions() {   // #10
        UUID item = fx.item(FIRST_SEEN);
        List<UUID> users = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID u = fx.user();
            users.add(u);
            fx.submission(u, item, 30, FIRST_SEEN.plusSeconds(i));
        }
        judgeAfterDeadline(item);
        Instant voidAt = JUDGED_AT.plus(Duration.ofDays(1));

        assertThat(judge.voidItem(item, "허위 제보", voidAt)).isPresent();

        users.forEach(u -> assertThat(fx.ledgerSum(u, item)).isEqualByComparingTo("0"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM submissions WHERE trend_item_id = ? "
                + "AND (result <> 'VOID' OR voided_at <> ?)", Integer.class, item, Timestamp.from(voidAt))).isZero();
        assertThat(fx.itemState(item)).isEqualTo("VOID");
        assertThatThrownBy(() -> judge.voidItem(item, "다시", voidAt)).isInstanceOf(JudgeConflictException.class);
        assertThatThrownBy(() -> judge.rejudge(item, "다시", voidAt)).isInstanceOf(JudgeConflictException.class);
    }

    @Test
    void voidingPendingItemOnlyVoidsSubmissions() {   // ADM-100 큐 VOID 경로
        UUID item = fx.item(FIRST_SEEN);
        UUID s = fx.submission(fx.user(), item, 30, FIRST_SEEN.plusSeconds(1));

        assertThat(judge.voidItem(item, null, FIRST_SEEN.plus(Duration.ofDays(1)))).isEmpty();

        assertThat(fx.itemState(item)).isEqualTo("VOID");
        assertThat(fx.submissionResult(s)).isEqualTo("VOID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM verdicts WHERE trend_item_id = ?", Integer.class, item)).isZero();
    }

    private void judgeAfterDeadline(UUID item) {
        judge.closeDue(JUDGED_AT);
        judge.judge(item, JUDGED_AT);
    }
}
```

(점수 확인: 원 판정 HIT L1, m = 0.2 → 1위 30×1.0×1.2 = 36, 2위 30×0.6×1.2 = 21.6, 3위 14.4, 4위 7.2. 재판정 MISS −15 → 1위 ADJ −51, 2위 ADJ −36.6, 3·4위 ADJ −14.4·−7.2.)

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :app:test --tests kr.trendstage.judge.RejudgeAndVoidTest`
Expected: 컴파일 실패 — `JudgeService.rejudge/voidItem`, `JudgeConflictException` 없음.

- [ ] **Step 4: 구현**

`JudgeConflictException.java`:

```java
package kr.trendstage.judge;

/** 상태 충돌(이미 VOID·병합된 항목, 재판정 체인 경합). 콘솔 API는 409로 매핑한다. */
public class JudgeConflictException extends RuntimeException {
    public JudgeConflictException(String message) {
        super(message);
    }
}
```

`ScoreLedgerRepository.java`에 추가(import `java.util.Collection`):

```java
    /** 재판정·VOID 차액 계산용 — 항목 판정 체인에 귀속된 원장(원 판정 행 + 이전 재판정 ADJ). */
    List<ScoreLedgerEntry> findByVerdictIdIn(Collection<UUID> verdictIds);
```

`JudgeService.java`에 추가(import `org.springframework.dao.DataIntegrityViolationException`, `java.util.HashMap`, `java.util.HashSet`, `java.util.Optional`, `java.util.Set`):

```java
    /**
     * 재판정 — 원 판정 때 동결한 파라미터로 다시 계산해 supersede 판정을 쌓고, 원장은 제보 단위 차액만 ADJ로 남긴다(J2).
     * 그사이 VOID된 제보가 빠지는 것이 재판정의 실질이다. 유효 제보가 0건이 되면 VOID 판정 → 항목 VOID.
     */
    @Transactional
    public Verdict rejudge(UUID itemId, String reason, Instant now) {
        TrendItem item = lock(itemId);
        Verdict current = verdicts.findCurrentByTrendItemId(itemId)
                .orElseThrow(() -> new JudgeRejectedException("판정 이력이 없는 항목입니다"));
        if (current.getResult() == VerdictResult.VOID) {
            throw new JudgeConflictException("이미 VOID 처리된 항목은 다시 판정할 수 없습니다");
        }
        Chain chain = chainOf(itemId);
        Inputs in = collect(item, deadlineOf(item));
        VerdictPlan plan = VerdictComputation.run(in.signal(), in.refs(), chain.params());

        Verdict next = supersede(new Verdict(itemId, plan.result(), plan.reach(), scoreT(plan), now,
                evidence(plan, in, chain.params(), current.getId(), reason), current.getId()));
        settle(chain, next, plan.ledgerLines(), "재판정 · " + reason);
        for (Submission s : in.submissions()) {
            s.markResult(toSubmissionResult(plan.result()), now);
        }
        if (plan.result() == VerdictResult.VOID) {
            item.transitionTo(TrendState.VOID);
        }
        return next;
    }

    /**
     * 항목 VOID(P5 "항목이 VOID됨") — ADM-100 큐 VOID와 ADM-200 VOID의 공통 경로.
     * 비VOID 제보 전부 VOID(voided_at = 제보권 반환 시점, J4). 판정이 있었으면 VOID 판정을 쌓고 원장을 제보 단위로 전액 상쇄한다.
     *
     * @return 새 VOID 판정(판정 전 항목이면 비어 있음)
     */
    @Transactional
    public Optional<Verdict> voidItem(UUID itemId, String reason, Instant now) {
        TrendItem item = lock(itemId);
        if (item.getState() == TrendState.MERGED || item.getState() == TrendState.VOID) {
            throw new JudgeConflictException("이미 병합됐거나 VOID된 항목입니다");
        }
        for (Submission s : submissions.findByTrendItemIdAndResultNot(itemId, SubmissionResult.VOID)) {
            s.voidOut(now);
        }
        Optional<Verdict> current = verdicts.findCurrentByTrendItemId(itemId);
        Optional<Verdict> voided = Optional.empty();
        if (current.isPresent()) {
            Chain chain = chainOf(itemId);
            Verdict next = supersede(new Verdict(itemId, VerdictResult.VOID, null, null, now,
                    write(new VerdictEvidence("VOID", null, null, null, null, null, 0, 0, Map.of(),
                            current.get().getId(), reason)),
                    current.get().getId()));
            settle(chain, next, List.of(), "VOID · " + (reason == null ? "" : reason));
            voided = Optional.of(next);
        }
        item.transitionTo(TrendState.VOID);
        return voided;
    }

    /** 판정 체인 — 원본의 파라미터·판정 시각이 재판정·VOID 차액의 기준이다(J1·J2). */
    record Chain(List<UUID> verdictIds, ParameterSet params, Instant anchor) {}

    private Chain chainOf(UUID itemId) {
        List<Verdict> all = verdicts.findByTrendItemIdOrderByCreatedAtAsc(itemId);
        Verdict original = all.stream().filter(v -> v.getSupersedes() == null).findFirst()
                .orElseThrow(() -> new IllegalStateException("원본 판정이 없습니다: " + itemId));
        ParamsSnapshot frozen = read(original).params();
        if (frozen == null) {
            throw new IllegalStateException("원본 판정 근거에 파라미터가 없습니다: " + original.getId());
        }
        return new Chain(all.stream().map(Verdict::getId).toList(), frozen.toParameterSet(), original.getJudgedAt());
    }

    /** 체인 원장을 제보 단위로 합산해 새 라인과의 차액만 ADJ로 남긴다 — 원 판정과 같은 감쇠 기준(J1). */
    private void settle(Chain chain, Verdict next, List<LedgerLine> lines, String reason) {
        Map<UUID, BigDecimal> before = new HashMap<>();
        Map<UUID, UUID> owner = new HashMap<>();
        for (ScoreLedgerEntry e : ledger.findByVerdictIdIn(chain.verdictIds())) {
            if (e.getSubmissionId() == null) continue;
            before.merge(e.getSubmissionId(), e.getDelta(), BigDecimal::add);
            owner.put(e.getSubmissionId(), e.getUserId());
        }
        Map<UUID, BigDecimal> after = new HashMap<>();
        for (LedgerLine line : lines) {
            after.put(line.submissionId(), amount(line.delta()));
            owner.put(line.submissionId(), line.userId());
        }
        Set<UUID> touched = new HashSet<>(before.keySet());
        touched.addAll(after.keySet());
        for (UUID submissionId : touched) {
            BigDecimal diff = after.getOrDefault(submissionId, BigDecimal.ZERO)
                    .subtract(before.getOrDefault(submissionId, BigDecimal.ZERO));
            if (diff.signum() != 0) {
                ledger.save(ScoreLedgerEntry.verdictAdjustment(owner.get(submissionId), submissionId, next.getId(),
                        diff, reason, chain.params().halflifeDays, chain.anchor()));
            }
        }
    }

    /** 체인 분기는 DB(verdict_superseded_once)가 막는다 — 행 잠금 덕에 거의 없지만 마지막 방어선을 409로 옮긴다. */
    private Verdict supersede(Verdict next) {
        try {
            return verdicts.saveAndFlush(next);
        } catch (DataIntegrityViolationException e) {
            throw new JudgeConflictException("다른 요청이 먼저 이 판정을 대체했습니다 — 새로고침 후 다시 시도하세요");
        }
    }

    VerdictEvidence read(Verdict v) {
        try {
            return objectMapper.readValue(v.getEvidenceJson(), VerdictEvidence.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("판정 근거 파싱 실패: " + v.getId(), e);
        }
    }
```

`api-admin/build.gradle.kts`에 `implementation(project(":judge"))`를 추가(`:merge` 다음).

`VerdictAdminService.java` 전체 교체:

```java
package kr.trendstage.apiadmin.verdict;

import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.judge.JudgeService;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.entity.Verdict;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.VerdictRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * ADM-200. R1: 관리자는 판정 결과를 직접 바꾸지 못한다 — VOID·재판정은 JudgeService가 엔진을 다시 돌려
 * supersede 판정을 쌓을 뿐이다. 재판정은 원 판정 때 동결한 파라미터를 쓴다(J2). 감사 기록은 여기서 남긴다.
 */
@Service
public class VerdictAdminService {

    private static final int JUDGE_WINDOW_DAYS = 14;
    private static final int MAX_GRACE_DAYS = 7;

    private final TrendItemRepository trendItems;
    private final VerdictRepository verdicts;
    private final JudgeService judgeService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public VerdictAdminService(TrendItemRepository trendItems, VerdictRepository verdicts, JudgeService judgeService,
                               AuditLogService auditLogService, Clock clock) {
        this.trendItems = trendItems;
        this.verdicts = verdicts;
        this.judgeService = judgeService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Transactional
    public void voidVerdict(UUID trendItemId, UUID actorId, AdminRole actorRole, String reason) {
        requireReason(reason);
        Verdict previous = verdicts.findCurrentByTrendItemId(trendItemId)
                .orElseThrow(() -> new AdminValidationException("판정 이력이 없는 항목입니다"));
        Verdict voided = judgeService.voidItem(trendItemId, reason, clock.instant())
                .orElseThrow(() -> new IllegalStateException("VOID 판정이 기록되지 않았습니다: " + trendItemId));
        auditLogService.record(actorId, actorRole, "VERDICT_VOID", "TREND_ITEM", trendItemId, Map.of(
                "reason", reason,
                "previousVerdictId", previous.getId().toString(),
                "newVerdictId", voided.getId().toString()));
    }

    @Transactional
    public void requestRejudge(UUID trendItemId, UUID actorId, AdminRole actorRole, String reason) {
        requireReason(reason);
        Verdict previous = verdicts.findCurrentByTrendItemId(trendItemId)
                .orElseThrow(() -> new AdminValidationException("판정 이력이 없는 항목입니다"));
        Verdict next = judgeService.rejudge(trendItemId, reason, clock.instant());
        auditLogService.record(actorId, actorRole, "VERDICT_REJUDGE", "TREND_ITEM", trendItemId, Map.of(
                "reason", reason,
                "previousVerdictId", previous.getId().toString(),
                "newVerdictId", next.getId().toString(),
                "newResult", next.getResult().name()));
    }

    @Transactional
    public void extendGrace(UUID trendItemId, int days, UUID actorId, AdminRole actorRole, String reason) {
        if (days < 1 || days > MAX_GRACE_DAYS) {
            throw new AdminValidationException("연장 일수는 1~%d일 사이여야 합니다".formatted(MAX_GRACE_DAYS));
        }
        if (verdicts.existsByTrendItemIdAndSupersedesIsNull(trendItemId)) {
            throw new AdminValidationException("이미 판정된 항목은 유예 연장 대상이 아닙니다");
        }
        TrendItem item = trendItems.findById(trendItemId)
                .orElseThrow(() -> new AdminValidationException("존재하지 않는 항목입니다"));

        Instant originalDeadline = item.getFirstSeenAt().plus(Duration.ofDays(JUDGE_WINDOW_DAYS));
        Instant currentDeadline = item.getJudgmentDeadlineOverride() != null
                ? item.getJudgmentDeadlineOverride() : originalDeadline;
        Instant ceiling = originalDeadline.plus(Duration.ofDays(MAX_GRACE_DAYS));
        Instant requested = currentDeadline.plus(Duration.ofDays(days));
        Instant capped = requested.isAfter(ceiling) ? ceiling : requested;

        if (!capped.isAfter(currentDeadline)) {
            throw new AdminValidationException("이미 최대 유예 기간(D+%d)에 도달했습니다".formatted(JUDGE_WINDOW_DAYS + MAX_GRACE_DAYS));
        }
        item.extendJudgmentDeadline(capped);
        // 마감이 지나 JUDGING으로 닫혔던 항목도 새 마감이 미래면 다시 관측 중으로 — 그동안 제보를 받는다(J6)
        if (item.getState() == TrendState.JUDGING && capped.isAfter(clock.instant())) {
            item.transitionTo(TrendState.PENDING);
        }

        auditLogService.record(actorId, actorRole, "VERDICT_GRACE_EXTEND", "TREND_ITEM", trendItemId, Map.of(
                "reason", reason == null ? "" : reason,
                "newDeadline", capped.toString()
        ));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AdminValidationException("사유는 필수입니다");
        }
    }
}
```

(`extendGrace`는 기존 본문에 JUDGING → PENDING 복귀 세 줄만 더한 것이다.)

`MergeQueueController.java`: 생성자에 `JudgeService judgeService`, `AuditLogService auditLogService`를 추가하고 `/void`를 교체:

```java
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Transactional
    public void voidCandidate(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest req,
                               @AuthenticationPrincipal AdminPrincipal actor) {
        MergeQueueEntry entry = requirePending(id);
        String reason = req == null ? null : req.reason();
        // 항목 VOID는 판정 사건이다(P5) — 판정된 항목이면 원장 상쇄까지 JudgeService가 한다
        judgeService.voidItem(entry.getNewTrendItemId(), reason, clock.instant());
        auditLogService.record(actor.id(), actor.role(), "MERGE_VOID", "TREND_ITEM", entry.getNewTrendItemId(),
                Map.of("reason", reason == null ? "" : reason));
        entry.resolve(MergeQueueStatus.VOIDED, actor.id(), clock.instant());
    }
```

`MergeService.java`: `voidTrendItem` 메서드를 삭제한다(사용처 없음 확인: `grep -rn voidTrendItem backend`).

`AdminApiExceptionHandler.java`에 추가(import `kr.trendstage.judge.JudgeConflictException`, `kr.trendstage.judge.JudgeRejectedException`):

```java
    @ExceptionHandler(JudgeRejectedException.class)
    public ResponseEntity<Map<String, Object>> handle(JudgeRejectedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem(422, e.getMessage()));
    }

    @ExceptionHandler(JudgeConflictException.class)
    public ResponseEntity<Map<String, Object>> handle(JudgeConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(409, e.getMessage()));
    }
```

(기존 핸들러의 반환 타입·`problem` 헬퍼 시그니처에 맞춘다.)

- [ ] **Step 5: 통과 확인**

Run: `./gradlew compileJava compileTestJava :app:test`
Expected: PASS (전체 `app` 통합 테스트).

- [ ] **Step 6: 커밋**

```bash
git add backend/judge/src/main/java/kr/trendstage/judge/JudgeService.java backend/judge/src/main/java/kr/trendstage/judge/JudgeConflictException.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/ScoreLedgerRepository.java backend/api-admin/build.gradle.kts backend/api-admin/src/main/java/kr/trendstage/apiadmin/verdict/VerdictAdminService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/MergeQueueController.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminApiExceptionHandler.java backend/merge/src/main/java/kr/trendstage/merge/MergeService.java backend/app/src/test/java/kr/trendstage/support/Fixtures.java backend/app/src/test/java/kr/trendstage/judge/RejudgeAndVoidTest.java
git commit -m "feat(judge): 재판정·항목 VOID를 JudgeService로 — 원 판정 파라미터, 제보 단위 차액, 이중 반영·시딩 ADJ 해소

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: 미리보기·시뮬레이션을 새 신호로, 옛 경로 삭제

**Files:**
- Modify: `backend/judge/src/main/java/kr/trendstage/judge/JudgeService.java` (`preview`)
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/trend/TrendItemAdminService.java`
- Modify: `backend/domain-core/src/main/java/kr/trendstage/domain/params/VerdictSnapshot.java`, `ParamSimulation.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/params/ParamStudioService.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/seed/AdminSeedService.java`
- Modify: `backend/domain-core/src/main/java/kr/trendstage/domain/verdict/VerdictEngine.java`, `score/VerdictComputation.java` (deprecated 삭제)
- Delete: `backend/domain-core/src/main/java/kr/trendstage/domain/verdict/SubmissionSignal.java`
- Test: `ParamSimulationTest.java`, `backend/app/src/test/java/kr/trendstage/judge/SimulationFromEvidenceTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`ParamSimulationTest.java`: `new VerdictSnapshot(result, reach, n, k)`를 `new VerdictSnapshot(result, reach, signalOf(n))`로 바꾸고, Task 3의 `EngineGoldenTest`와 같은 `signalOf` 헬퍼를 추가한다.

`SimulationFromEvidenceTest.java`:

```java
package kr.trendstage.judge;

import kr.trendstage.apiadmin.params.ParamStudioService;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 판정 근거에 동결한 신호로 시뮬레이션이 돈다(스펙 §2.6). 공유 DB라 개수는 보지 않는다. */
class SimulationFromEvidenceTest extends AbstractIntegrationTest {

    @Autowired JudgeService judge;
    @Autowired ParamStudioService studio;

    @Test
    void simulationReadsSignalFromEvidence() {
        Instant firstSeen = Instant.parse("2026-09-01T00:00:00Z");
        Instant judgedAt = firstSeen.plus(Duration.ofDays(15));
        UUID item = fx.item(firstSeen);
        for (int i = 0; i < 4; i++) fx.submission(fx.user(), item, 30, firstSeen.plusSeconds(i));
        judge.closeDue(judgedAt);
        judge.judge(item, judgedAt);
        clock.set(judgedAt.plus(Duration.ofDays(1)));

        String simResult = studio.simulate(fx.admin(), AdminRole.ADMIN).getSimResult();

        assertThat(simResult).contains("\"total\":");
    }

    @Test
    void previewCountsSubmittersWithoutSeeds() {
        UUID item = fx.item(Instant.parse("2026-09-10T00:00:00Z"));
        fx.seedSubmission(fx.user(), item, Instant.parse("2026-09-10T00:00:00Z"));
        fx.submission(fx.user(), item, 30, Instant.parse("2026-09-10T01:00:00Z"));

        JudgeService.Preview preview = judge.preview(item);

        assertThat(preview.signal().distinctSubmitters()).isEqualTo(1);
        assertThat(preview.signal().validCount()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :domain-core:test :app:test --tests kr.trendstage.judge.SimulationFromEvidenceTest`
Expected: 컴파일 실패 — `VerdictSnapshot(result, reach, TrendSignal)`, `JudgeService.preview/Preview` 없음.

- [ ] **Step 3: 구현**

`JudgeService.java`에 추가:

```java
    /** ADM-111 예상 판정 — 현재 파라미터와 지금까지의 신호로 계산만 한다(저장 없음). */
    @Transactional(readOnly = true)
    public Preview preview(UUID itemId) {
        TrendItem item = trendItems.findById(itemId)
                .orElseThrow(() -> new JudgeRejectedException("존재하지 않는 항목입니다"));
        Inputs in = collect(item, deadlineOf(item));
        return new Preview(in.signal(), VerdictComputation.run(in.signal(), in.refs(), params.resolve()));
    }

    public record Preview(TrendSignal signal, VerdictPlan plan) {}
```

`VerdictSnapshot.java`:

```java
/** 과거 판정 1건의 재평가 입력 — 판정 근거(evidence_json)에 동결된 신호. */
public record VerdictSnapshot(VerdictResult result, ReachLevel reach, TrendSignal signal) {}
```

`ParamSimulation.java`: `SubmissionSignal sig = new SubmissionSignal(...)`와 그 줄을 지우고 `VerdictOutcome after = VerdictEngine.evaluate(v.signal(), p);`로 바꾼다.

`ParamStudioService.toSnapshot`을 교체(import `kr.trendstage.judge.VerdictEvidence`):

```java
    private VerdictSnapshot toSnapshot(Verdict v) {
        try {
            VerdictEvidence ev = objectMapper.readValue(v.getEvidenceJson(), VerdictEvidence.class);
            return new VerdictSnapshot(v.getResult(), v.getReachLevel(), ev.signal());
        } catch (Exception e) {
            throw new IllegalStateException("evidence_json 파싱 실패: verdict=" + v.getId(), e);
        }
    }
```

`TrendItemAdminService.java`:
- 생성자에 `JudgeService judgeService`를 추가한다.
- `detail()`에서 `refs`·`distinctSubmitters`·`distinctPlatforms`·`signal`·`preview`를 만드는 블록을 지우고 아래로 교체:

```java
        JudgeService.Preview preview = judgeService.preview(trendItemId);
        int distinctSubmitters = preview.signal().distinctSubmitters();   // 시딩 제외 — 판정과 같은 기준
        int distinctPlatforms = preview.signal().distinctPlatforms();
        Verdict current = verdicts.findCurrentByTrendItemId(trendItemId).orElse(null);
        VerdictPlan plan = current == null ? preview.plan() : null;
```

- 이후 `preview.result()`·`preview.reach()`·`preview.t()` 참조는 `plan.…`으로, `(int) distinctSubmitters` 캐스트는 제거한다.
- `SubmissionRow`의 `int orderRank`를 `Integer orderRank`(시딩은 null — 순위에서 빠진다, J3)로 바꾸고, 행을 만들 때 `rankById.getOrDefault(s.getId(), Integer.MAX_VALUE)` → `rankById.get(s.getId())`. 정렬은 `Comparator.comparingInt(s -> rankById.getOrDefault(s.getId(), Integer.MAX_VALUE))`를 유지(시딩은 뒤로).
- 쓰지 않게 된 import(`ParameterSet`, `SubmissionRef`, `VerdictComputation`, `SubmissionSignal`, `Objects`)를 지운다.

`AdminSeedService.java`: 생성자에 `CurrentParameterSetResolver currentParams`(`kr.trendstage.persistence.params`)를 추가하고 `listAccuracy()`의 `ParameterSet p = ParameterSet.defaults();` → `ParameterSet p = currentParams.resolve();`.

옛 경로 삭제:
- `VerdictComputation.run(SubmissionSignal, boolean, …)` 삭제.
- `VerdictEngine`의 `computeT(SubmissionSignal, …)`·`evaluate(SubmissionSignal, …)` 삭제.
- `SubmissionSignal.java` 파일 삭제(`git rm`).
- 확인: `grep -rnE "SubmissionSignal|voidByRule" backend` → 결과 없음. `grep -rn "ParameterSet.defaults()" backend/*/src/main` → `ParameterSet.java`, `CurrentParameterSetResolver.java`, `ParameterDraft.java`, `ParamStudioService.java`(`defaultPayloadJson`)만, 그리고 Task 8에서 정리할 `MeService.java`가 남는다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew compileJava compileTestJava :domain-core:test :app:test`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git rm backend/domain-core/src/main/java/kr/trendstage/domain/verdict/SubmissionSignal.java
git add backend/judge/src/main/java/kr/trendstage/judge/JudgeService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/trend/TrendItemAdminService.java backend/domain-core/src/main/java/kr/trendstage/domain/params/VerdictSnapshot.java backend/domain-core/src/main/java/kr/trendstage/domain/params/ParamSimulation.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/params/ParamStudioService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/seed/AdminSeedService.java backend/domain-core/src/main/java/kr/trendstage/domain/verdict/VerdictEngine.java backend/domain-core/src/main/java/kr/trendstage/domain/score/VerdictComputation.java backend/domain-core/src/test/java/kr/trendstage/domain/ParamSimulationTest.java backend/app/src/test/java/kr/trendstage/judge/SimulationFromEvidenceTest.java
git commit -m "refactor(judge): ADM-111 미리보기·ADM-600 시뮬레이션을 판정 신호로, voidByRule·SubmissionSignal 삭제

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: 제보권 집행·관측 마감 후 제보 차단

**Files:**
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/SubmissionRepository.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/UserRepository.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/Submission.java` (생성 시각 받는 생성자)
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/service/QuotaService.java`
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/QuotaExhaustedException.java`
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/ItemClosedException.java`
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/service/SubmissionService.java`
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/service/MeService.java` (`summary`)
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/ApiExceptionHandler.java`
- Test: `backend/app/src/test/java/kr/trendstage/submission/SubmissionQuotaIntegrationTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.trendstage.submission;

import kr.trendstage.apipublic.service.QuotaService;
import kr.trendstage.apipublic.service.SubmissionService;
import kr.trendstage.apipublic.web.DuplicateSubmissionException;
import kr.trendstage.apipublic.web.ItemClosedException;
import kr.trendstage.apipublic.web.QuotaExhaustedException;
import kr.trendstage.apipublic.web.SubmissionCreateRequest;
import kr.trendstage.apipublic.web.SubmissionMineResponse;
import kr.trendstage.judge.JudgeService;
import kr.trendstage.persistence.type.TrendCategory;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스펙 §7.2 6·12·13번 — 제보권(J4)과 관측 마감(J6). */
class SubmissionQuotaIntegrationTest extends AbstractIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Instant MONDAY = ZonedDateTime.of(2026, 9, 21, 10, 0, 0, 0, KST).toInstant();

    @Autowired SubmissionService service;
    @Autowired QuotaService quota;
    @Autowired JudgeService judge;

    @Test
    void l0GetsTwoPerWeekRefundOnVoidAndRefillOnMonday() {   // #12
        clock.set(MONDAY);
        UUID u = fx.user();
        SubmissionMineResponse first = service.create(u, req(unique()));
        service.create(u, req(unique()));
        assertThatThrownBy(() -> service.create(u, req(unique()))).isInstanceOf(QuotaExhaustedException.class);

        fx.voidSubmission(first.id(), MONDAY.plusSeconds(60));   // 이번 주 VOID → 한 장 반환
        service.create(u, req(unique()));
        assertThatThrownBy(() -> service.create(u, req(unique()))).isInstanceOf(QuotaExhaustedException.class);

        clock.set(ZonedDateTime.of(2026, 9, 28, 0, 0, 0, 0, KST).toInstant());   // 다음 월요일 00:00 KST — 리필
        service.create(u, req(unique()));
        assertThat(quota.of(u, clock.instant()).used()).isEqualTo(1);
    }

    @Test
    void duplicatesAndSeedsDoNotConsumeQuota() {
        clock.set(MONDAY);
        UUID u = fx.user();
        String name = unique();
        service.create(u, req(name));
        assertThatThrownBy(() -> service.create(u, req(name))).isInstanceOf(DuplicateSubmissionException.class);
        fx.seedSubmission(u, fx.item(MONDAY), MONDAY);

        assertThat(quota.of(u, MONDAY).used()).isEqualTo(1);
    }

    @Test
    void concurrentSubmissionsCannotExceedLimit() throws Exception {   // #13
        clock.set(MONDAY);
        UUID u = fx.user();
        service.create(u, req(unique()));   // 남은 제보권 1

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String name = unique();
            results.add(pool.submit(() -> {
                go.await();
                try {
                    service.create(u, req(name));
                    return true;
                } catch (QuotaExhaustedException e) {
                    return false;
                }
            }));
        }
        go.countDown();
        int ok = 0;
        for (Future<Boolean> f : results) if (f.get(30, TimeUnit.SECONDS)) ok++;
        pool.shutdown();

        assertThat(ok).isEqualTo(1);
    }

    @Test
    void closedItemsRejectWithoutCharge() {   // #6(제보 쪽)
        clock.set(MONDAY);
        String name = unique();
        SubmissionMineResponse opener = service.create(fx.user(), req(name));   // first_seen = MONDAY, 마감 = +14일

        clock.set(MONDAY.plus(Duration.ofDays(14)).minusSeconds(1));
        service.create(fx.user(), req(name));   // 마감 직전은 받는다

        clock.set(MONDAY.plus(Duration.ofDays(14)));
        UUID late = fx.user();
        assertThatThrownBy(() -> service.create(late, req(name))).isInstanceOf(ItemClosedException.class);
        assertThat(quota.of(late, clock.instant()).used()).isZero();

        UUID item = jdbc.queryForObject("SELECT trend_item_id FROM submissions WHERE id = ?", UUID.class, opener.id());
        judge.closeDue(clock.instant());
        judge.judge(item, clock.instant());
        assertThatThrownBy(() -> service.create(fx.user(), req(name))).isInstanceOf(ItemClosedException.class);
    }

    @Test
    void quotaExhaustedIsProblem422WithType() throws Exception {
        clock.set(MONDAY);
        UUID u = fx.user();
        service.create(u, req(unique()));
        service.create(u, req(unique()));

        mvc.perform(post("/v1/submissions")
                        .with(authentication(new UsernamePasswordAuthenticationToken(u, null, List.of())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + unique() + "\",\"category\":\"MEME\",\"platform\":\"X\","
                                + "\"evidenceUrl\":\"https://example.com\",\"confidence\":30,\"disclosure\":false,\"oneLine\":\"설명\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("quota-exhausted"));
    }

    private static SubmissionCreateRequest req(String name) {
        return new SubmissionCreateRequest(name, TrendCategory.MEME, "X", "https://example.com", 30, false, "설명");
    }

    private static String unique() {
        return "제보" + UUID.randomUUID().toString().substring(0, 8);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:test --tests kr.trendstage.submission.SubmissionQuotaIntegrationTest`
Expected: 컴파일 실패 — `QuotaService`, `QuotaExhaustedException`, `ItemClosedException` 없음.

- [ ] **Step 3: 영속 계층**

`SubmissionRepository.java`: MeService만 쓰던 `countByUserIdAndCreatedAtAfterAndResultNot`을 지우고 추가:

```java
    /** 제보권: 이번 주에 낸 제보 수(시딩 제외, VOID 여부 무관 — J4). */
    long countByUserIdAndSeedFalseAndCreatedAtGreaterThanEqual(UUID userId, Instant since);

    /** 제보권: 이번 주에 VOID로 반환된 제보 수(시딩 제외, 지난주에 낸 것 포함 — J4). */
    long countByUserIdAndSeedFalseAndVoidedAtGreaterThanEqual(UUID userId, Instant since);
```

`UserRepository.java`에 추가(import는 Task 4의 `TrendItemRepository`와 같다):

```java
    /** 같은 유저의 동시 제보를 직렬화 — 제보권 확인과 저장 사이에 다른 제보가 끼지 못하게(J4). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserAccount u where u.id = :id")
    Optional<UserAccount> findByIdForUpdate(@Param("id") UUID id);
```

`Submission.java`에 생성자 추가:

```java
    /** 생성 시각을 명시(Clock). created_at은 order_rank 기준이라 이후 바뀌지 않는다. */
    public Submission(UUID userId, UUID trendItemId, String rawInput, String normalizedKey,
                      short confidence, String sourcePlatform, String evidenceUrl, String oneLine,
                      boolean disclosure, boolean seed, Instant createdAt) {
        this(userId, trendItemId, rawInput, normalizedKey, confidence, sourcePlatform, evidenceUrl, oneLine,
                disclosure, seed);
        this.createdAt = createdAt;
    }
```

- [ ] **Step 4: 서비스·예외**

`QuotaExhaustedException.java`:

```java
package kr.trendstage.apipublic.web;

/** 이번 주 제보권 소진(422, Problem.type = quota-exhausted) — J4. */
public class QuotaExhaustedException extends RuntimeException {
    public QuotaExhaustedException(String message) {
        super(message);
    }
}
```

`ItemClosedException.java`:

```java
package kr.trendstage.apipublic.web;

/** 관측이 끝난 항목(422, Problem.type = item-closed) — 마감 이후·JUDGING·RESOLVED·VOID·MERGED(J6). */
public class ItemClosedException extends RuntimeException {
    public ItemClosedException(String message) {
        super(message);
    }
}
```

`QuotaService.java`:

```java
package kr.trendstage.apipublic.service;

import kr.trendstage.domain.grade.Grade;
import kr.trendstage.domain.grade.SubmissionQuota;
import kr.trendstage.persistence.entity.UserGrade;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.UserGradeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 제보권(01 §3.3, J4). 저장 카운터 없이 제보 행에서 파생한다 — 이번 주 사용 = 이번 주 제출 − 이번 주 VOID 반환.
 * 리필은 주 경계(월 00:00 KST) 자체, 한도의 등급은 주간 스냅샷(J5).
 * 집행(SubmissionService)과 표시(MeService.summary)가 이 한 곳을 쓴다.
 */
@Service
public class QuotaService {

    private final SubmissionRepository submissions;
    private final UserGradeRepository grades;

    public QuotaService(SubmissionRepository submissions, UserGradeRepository grades) {
        this.submissions = submissions;
        this.grades = grades;
    }

    public record Quota(Grade grade, int used, int max, int remaining) {}

    @Transactional(readOnly = true)
    public Quota of(UUID userId, Instant now) {
        Instant weekStart = SubmissionQuota.weekStart(now);
        int submitted = (int) submissions.countByUserIdAndSeedFalseAndCreatedAtGreaterThanEqual(userId, weekStart);
        int refunded = (int) submissions.countByUserIdAndSeedFalseAndVoidedAtGreaterThanEqual(userId, weekStart);
        Grade grade = grades.findTopByUserIdOrderByComputedAtDesc(userId).map(UserGrade::getGrade).orElse(Grade.L0);
        int used = SubmissionQuota.used(submitted, refunded);
        return new Quota(grade, used, SubmissionQuota.weeklyLimit(grade), SubmissionQuota.remaining(grade, used));
    }
}
```

`SubmissionService.java`:
- 클래스 주석의 "제보권(quota) 차감은 QuotaService 도입 후 처리(TODO …)" → "제보권은 QuotaService가 이번 주 제보 행에서 계산해 집행한다(J4). 관측이 끝난 항목에는 제보를 받지 않는다(J6)."
- 생성자에 `UserRepository users`, `QuotaService quotaService`, `Clock clock`을 추가한다.
- `create`를 교체:

```java
    @Transactional
    public SubmissionMineResponse create(UUID userId, SubmissionCreateRequest req) {
        if (!VALID_CONFIDENCE.contains(req.confidence())) {
            throw new SubmissionValidationException("confidence는 10/30/50 중 하나여야 합니다");
        }
        String normalized = NameNormalizer.normalize(req.name());
        Instant now = clock.instant();

        users.findByIdForUpdate(userId).orElseThrow(() -> new IllegalStateException("유저가 없습니다: " + userId));

        TrendItem item = trends.findByNormalizedKey(normalized).orElse(null);
        if (item != null) {
            requireOpen(item, now);
            boolean dup = submissions.existsByTrendItemIdAndUserIdAndResultNot(
                    item.getId(), userId, SubmissionResult.VOID);
            if (dup) {
                long rank = submissions.countByTrendItemIdAndResultNot(item.getId(), SubmissionResult.VOID);
                throw new DuplicateSubmissionException((item.getId()), (int) rank,
                        "이미 제보한 항목입니다 — 동의로 전환할 수 있습니다");
            }
        }

        QuotaService.Quota quota = quotaService.of(userId, now);
        if (quota.remaining() == 0) {
            throw new QuotaExhaustedException("이번 주 제보권 %d/%d장을 모두 썼습니다 · 월요일 00:00에 다시 채워집니다"
                    .formatted(quota.used(), quota.max()));
        }

        if (item == null) {
            item = trends.save(new TrendItem(req.name(), normalized, req.category(), now));
            item.transitionTo(TrendState.PENDING);
        }
        Submission sub = submissions.save(new Submission(
                userId, item.getId(), req.name(), normalized,
                req.confidence().shortValue(), req.platform(), req.evidenceUrl(), req.oneLine(),
                req.disclosure(), false, now));
        return toResponse(sub, item);
    }

    /** 관측 창(기본 D+14, 유예 연장 반영)이 열려 있는 PENDING 항목만 제보를 받는다(J6). 거부해도 제보권은 쓰지 않는다. */
    private static void requireOpen(TrendItem item, Instant now) {
        if (item.getState() == TrendState.MERGED) {
            // SP2가 병합 승자로 합류시키기 전까지는 막는다 — 죽은 클러스터에 붙어 영원히 PENDING이 되는 것보다 낫다
            throw new ItemClosedException("다른 항목으로 병합된 트렌드입니다 — 검색에서 대표 항목을 찾아 동의해 주세요");
        }
        Instant deadline = DeadlineWindow.effectiveDeadline(item.getFirstSeenAt(), item.getJudgmentDeadlineOverride());
        if (item.getState() != TrendState.PENDING || !now.isBefore(deadline)) {
            throw new ItemClosedException("관측이 끝난 트렌드입니다 — 판정이 끝났거나 진행 중이라 새 제보를 받지 않습니다");
        }
    }
```

- `toResponse`의 `Instant.now()`는 `clock.instant()`로, 마감 계산은 `DeadlineWindow.effectiveDeadline(item.getFirstSeenAt(), item.getJudgmentDeadlineOverride())`로 바꾼다(`toResponse`를 인스턴스 메서드로 유지).

`MeService.java`:
- 생성자에 `QuotaService quotaService`를 추가한다.
- `summary()`의 `quotaMax`·`weekStart`·`quotaUsed` 계산을 지우고:

```java
        QuotaService.Quota quota = quotaService.of(userId, clock.instant());
```

  반환의 `quotaUsed, quotaMax` → `quota.used(), quota.max()`.
- 쓰지 않게 된 `KST`·`DayOfWeek`·`TemporalAdjusters`·`SubmissionQuota` import와 상수를 지운다.

`ApiExceptionHandler.java`:

```java
    @ExceptionHandler(QuotaExhaustedException.class)
    public ResponseEntity<Map<String, Object>> handle(QuotaExhaustedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem("quota-exhausted", 422, e.getMessage()));
    }

    @ExceptionHandler(ItemClosedException.class)
    public ResponseEntity<Map<String, Object>> handle(ItemClosedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem("item-closed", 422, e.getMessage()));
    }
```

기존 `problem(int, String)`은 `problem("about:blank", status, detail)`을 부르게 하고 3인자 판을 추가:

```java
    private Map<String, Object> problem(int status, String detail) {
        return problem("about:blank", status, detail);
    }

    /** RFC 9457. type으로 앱이 오류 종류를 구분한다(quota-exhausted·item-closed). */
    private Map<String, Object> problem(String type, int status, String detail) {
        return Map.of("type", type, "status", status, "detail", detail == null ? "" : detail);
    }
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew compileJava compileTestJava :app:test`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add backend/persistence/src/main/java/kr/trendstage/persistence/repo/SubmissionRepository.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/UserRepository.java backend/persistence/src/main/java/kr/trendstage/persistence/entity/Submission.java backend/api-public/src/main/java/kr/trendstage/apipublic/service/QuotaService.java backend/api-public/src/main/java/kr/trendstage/apipublic/web/QuotaExhaustedException.java backend/api-public/src/main/java/kr/trendstage/apipublic/web/ItemClosedException.java backend/api-public/src/main/java/kr/trendstage/apipublic/service/SubmissionService.java backend/api-public/src/main/java/kr/trendstage/apipublic/service/MeService.java backend/api-public/src/main/java/kr/trendstage/apipublic/web/ApiExceptionHandler.java backend/app/src/test/java/kr/trendstage/submission/SubmissionQuotaIntegrationTest.java
git commit -m "feat(api-public): 제보권 집행(주간 파생·VOID 반환·유저 행 잠금), 관측 마감 후 제보 차단

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 8: 등급 — 주간 스냅샷이 공식 등급, TI 180일, 강등 없음

**Files:**
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/SubmissionRepository.java`
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/grade/GradeInputsReader.java`
- Rewrite: `backend/scheduler/src/main/java/kr/trendstage/scheduler/GradeRecalcJob.java`
- Delete: `backend/scheduler/src/main/java/kr/trendstage/scheduler/ParameterSetProvider.java`
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/service/MeService.java` (`grade`)
- Modify: `infra/docker-compose.yml`, `infra/.env.example`
- Modify: `backend/app/src/test/java/kr/trendstage/support/Fixtures.java`
- Test: `backend/app/src/test/java/kr/trendstage/grade/GradeRecalcTest.java`

- [ ] **Step 1: 헬퍼 추가**

`Fixtures.java`에 추가:

```java
    /** 이미 판정된 제보(항목 포함, 항목은 RESOLVED — 다른 테스트의 배치가 다시 판정하지 않게). TI·판정완료 건수 테스트용. */
    public UUID judgedSubmission(UUID userId, String result, Instant resolvedAt) {
        UUID item = item(resolvedAt.minus(java.time.Duration.ofDays(15)));
        UUID s = submission(userId, item, 30, resolvedAt.minus(java.time.Duration.ofDays(14)));
        jdbc.update("UPDATE submissions SET result = ?::submission_result, resolved_at = ? WHERE id = ?",
                result, Timestamp.from(resolvedAt), s);
        jdbc.update("UPDATE trend_items SET state = 'RESOLVED' WHERE id = ?", item);
        return s;
    }

    /** 판정과 무관한 원장 한 행(AS 테스트용). */
    public void ledgerRow(UUID userId, double delta, int halflifeDays, Instant anchor) {
        jdbc.update("INSERT INTO score_ledger (user_id, kind, delta, reason, halflife_days, decay_anchor_at) "
                + "VALUES (?, 'ADJ', ?, '테스트', ?, ?)", userId, delta, halflifeDays, Timestamp.from(anchor));
    }

    public void gradeSnapshot(UUID userId, String grade, Instant computedAt) {
        jdbc.update("INSERT INTO user_grades (user_id, grade, trust_index, active_score, judged_count, computed_at) "
                + "VALUES (?, ?::grade_level, 0.5, 40, 6, ?)", userId, grade, Timestamp.from(computedAt));
    }
```

- [ ] **Step 2: 실패하는 테스트 작성**

```java
package kr.trendstage.grade;

import kr.trendstage.apipublic.service.MeService;
import kr.trendstage.apipublic.service.QuotaService;
import kr.trendstage.scheduler.GradeRecalcJob;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 스펙 §7.2 14~16: TI 180일 창, 공식 등급 = 주간 스냅샷(J5), 강등 없음(J8). */
class GradeRecalcTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");

    @Autowired GradeRecalcJob job;
    @Autowired MeService me;
    @Autowired QuotaService quota;

    @Test
    void trustIndexCountsOnlyLast180Days() {   // #14
        clock.set(NOW);
        UUID u = fx.user();
        for (int i = 0; i < 5; i++) fx.judgedSubmission(u, "MISS", NOW.minus(Duration.ofDays(200)));
        for (int i = 0; i < 5; i++) fx.judgedSubmission(u, "HIT", NOW.minus(Duration.ofDays(10)));

        runGradeRecalc();

        assertThat(latest(u, "trust_index", BigDecimal.class)).isEqualByComparingTo("0.700");   // (5+2)/(5+0+5)
        assertThat(latest(u, "judged_count", Integer.class)).isEqualTo(10);                    // 판정완료는 전 기간
    }

    @Test
    void snapshotIsTheOfficialGradeForMeAndQuota() {   // #15
        clock.set(NOW);
        UUID u = fx.user();
        for (int i = 0; i < 5; i++) fx.judgedSubmission(u, "HIT", NOW.minus(Duration.ofDays(1)));
        fx.ledgerRow(u, 40, 90, NOW);   // AS 40

        assertThat(me.grade(u).grade()).isEqualTo("L0");          // 요건은 채웠지만 스냅샷 전
        assertThat(me.grade(u).note()).contains("월요일");
        assertThat(quota.of(u, NOW).max()).isEqualTo(2);

        runGradeRecalc();

        assertThat(me.grade(u).grade()).isEqualTo("L1");
        assertThat(quota.of(u, NOW).max()).isEqualTo(3);
    }

    @Test
    void recalcNeverLowersGradeBeforeDemotionRules() {   // #16
        clock.set(NOW);
        UUID u = fx.user();
        fx.gradeSnapshot(u, "L1", NOW.minus(Duration.ofDays(7)));

        runGradeRecalc();   // 지금 값으로는 L0

        assertThat(latest(u, "grade::text", String.class)).isEqualTo("L1");
        assertThat(latest(u, "trust_index", BigDecimal.class)).isEqualByComparingTo("0.400");
    }

    private void runGradeRecalc() {
        releaseBatchLock("grade_recalc");
        job.run();
    }

    private <T> T latest(UUID userId, String column, Class<T> type) {
        return jdbc.queryForObject("SELECT " + column + " FROM user_grade_current WHERE user_id = ?", type, userId);
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :app:test --tests kr.trendstage.grade.GradeRecalcTest`
Expected: FAIL — TI가 전 기간(0.467), `/v1/me` 등급이 실시간(재계산 전 L1), 스냅샷 L1이 L0으로 내려감.

- [ ] **Step 4: 구현**

`SubmissionRepository.java`에 추가:

```java
    /** TI 창(최근 180일) — 처음 판정 시각 기준, 시딩 제외. */
    long countByUserIdAndResultAndSeedFalseAndResolvedAtGreaterThanEqual(UUID userId, SubmissionResult result, Instant since);

    /** 판정완료 건수(전 기간), 시딩 제외. */
    long countByUserIdAndResultAndSeedFalse(UUID userId, SubmissionResult result);
```

`GradeInputsReader.java`:

```java
package kr.trendstage.persistence.grade;

import kr.trendstage.domain.score.ActiveScore;
import kr.trendstage.persistence.repo.ScoreLedgerRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.type.SubmissionResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 등급 계산 입력. grade_recalc(스냅샷)와 /v1/me(진행 상황)가 같은 값을 보도록 한 곳에서 읽는다(J5) —
 * 두 모듈이 공통으로 의존하는 persistence에 둔다(CurrentParameterSetResolver와 같은 이유).
 * TI는 최근 180일 판정분(01 §5.2), 판정완료 건수는 전 기간, AS는 행별 감쇠(J1). 시딩 제보는 제외.
 */
@Component
public class GradeInputsReader {

    public static final int TI_WINDOW_DAYS = 180;

    private final SubmissionRepository submissions;
    private final ScoreLedgerRepository ledger;

    public GradeInputsReader(SubmissionRepository submissions, ScoreLedgerRepository ledger) {
        this.submissions = submissions;
        this.ledger = ledger;
    }

    public record GradeInputs(int judgedCount, int hitInWindow, int missInWindow, double activeScore) {}

    @Transactional(readOnly = true)
    public GradeInputs read(UUID userId, Instant now) {
        Instant since = now.minus(Duration.ofDays(TI_WINDOW_DAYS));
        int hit = (int) submissions.countByUserIdAndResultAndSeedFalseAndResolvedAtGreaterThanEqual(
                userId, SubmissionResult.HIT, since);
        int miss = (int) submissions.countByUserIdAndResultAndSeedFalseAndResolvedAtGreaterThanEqual(
                userId, SubmissionResult.MISS, since);
        int judged = (int) (submissions.countByUserIdAndResultAndSeedFalse(userId, SubmissionResult.HIT)
                + submissions.countByUserIdAndResultAndSeedFalse(userId, SubmissionResult.MISS));
        List<ActiveScore.Aged> aged = ledger.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(e -> new ActiveScore.Aged(e.getDelta().doubleValue(),
                        Math.max(0, Duration.between(e.getDecayAnchorAt(), now).toDays()), e.getHalflifeDays()))
                .toList();
        return new GradeInputs(judged, hit, miss, ActiveScore.compute(aged));
    }
}
```

`GradeRecalcJob.java` 전체 교체:

```java
package kr.trendstage.scheduler;

import kr.trendstage.domain.grade.Grade;
import kr.trendstage.domain.grade.GradePolicy;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.TrustIndex;
import kr.trendstage.persistence.entity.UserAccount;
import kr.trendstage.persistence.entity.UserGrade;
import kr.trendstage.persistence.grade.GradeInputsReader;
import kr.trendstage.persistence.params.CurrentParameterSetResolver;
import kr.trendstage.persistence.repo.UserGradeRepository;
import kr.trendstage.persistence.repo.UserRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * 주간 등급 재계산 — 공식 등급은 이 스냅샷이다(J5). 등급은 활동량이 아니라 적중률 주축(AS AND TI, R3).
 * 스냅샷을 append할 뿐(user_grades 불변). 강등 규칙(01 §6.1, Phase 2) 전까지 등급을 내리지 않는다(J8).
 * 제보권 리필은 주 경계 자체라 여기서 할 일이 없다(J4).
 */
@Component
public class GradeRecalcJob {

    private static final Logger log = LoggerFactory.getLogger(GradeRecalcJob.class);

    private final UserRepository users;
    private final UserGradeRepository grades;
    private final GradeInputsReader inputs;
    private final CurrentParameterSetResolver params;
    private final Clock clock;

    public GradeRecalcJob(UserRepository users, UserGradeRepository grades, GradeInputsReader inputs,
                          CurrentParameterSetResolver params, Clock clock) {
        this.users = users;
        this.grades = grades;
        this.inputs = inputs;
        this.params = params;
        this.clock = clock;
    }

    /** 주 1회 월요일 00:00 KST — 제보권 주 경계와 같은 순간. 타임존을 코드에 명시해 서버 타임존과 무관하게 한다. */
    @Scheduled(cron = "${jobs.grade-recalc.cron:0 0 0 * * MON}", zone = "${jobs.grade-recalc.zone:Asia/Seoul}")
    @SchedulerLock(name = "grade_recalc", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void run() {
        ParameterSet p = params.resolve();
        Instant now = clock.instant();
        int n = 0;
        for (UserAccount u : users.findAll()) {
            recalcOne(u.getId(), p, now);
            n++;
        }
        log.info("grade_recalc 완료: 유저 {}", n);
    }

    private void recalcOne(UUID userId, ParameterSet p, Instant now) {
        GradeInputsReader.GradeInputs in = inputs.read(userId, now);
        double ti = TrustIndex.compute(in.hitInWindow(), in.missInWindow(), p);
        Grade evaluated = GradePolicy.evaluate(in.judgedCount(), ti, in.activeScore()).current();
        Grade previous = grades.findTopByUserIdOrderByComputedAtDesc(userId).map(UserGrade::getGrade).orElse(Grade.L0);
        Grade grade = evaluated.compareTo(previous) >= 0 ? evaluated : previous;   // J8 — 강등 규칙 전까지 유지

        grades.save(new UserGrade(userId, grade,
                BigDecimal.valueOf(ti).setScale(3, RoundingMode.HALF_UP),
                BigDecimal.valueOf(in.activeScore()).setScale(4, RoundingMode.HALF_UP),
                in.judgedCount()));
    }
}
```

`ParameterSetProvider.java`를 삭제한다(`git rm`, 사용처가 없어짐 — `grep -rn ParameterSetProvider backend`로 확인).

`MeService.java`:
- 클래스 주석을 교체: "등급·원장·요약·설정 조회. 공식 등급은 주간 스냅샷이다(J5) — 제보권 한도와 같은 값. 다음 등급까지의 진행 상황만 지금 값으로 계산한다(스냅샷이 최대 일주일 묵는 대신 주중 등락과 화면상 강등이 없다)."
- 생성자에 `UserGradeRepository grades`, `GradeInputsReader gradeInputs`, `CurrentParameterSetResolver currentParams`를 추가한다.
- `grade()`를 교체:

```java
    @Transactional(readOnly = true)
    public GradeStatusResponse grade(UUID userId) {
        Instant now = clock.instant();
        GradeInputsReader.GradeInputs in = gradeInputs.read(userId, now);
        double ti = TrustIndex.compute(in.hitInWindow(), in.missInWindow(), currentParams.resolve());
        Grade current = grades.findTopByUserIdOrderByComputedAtDesc(userId).map(UserGrade::getGrade).orElse(Grade.L0);
        Grade next = GradePolicy.nextOf(current);
        List<GradeRequirement> reqs = current == next
                ? List.of() : GradePolicy.progressToward(next, in.judgedCount(), ti, in.activeScore());

        String note = null;
        if (current == next) {
            note = "최고 등급입니다";
        } else if (!reqs.isEmpty() && reqs.stream().allMatch(GradeRequirement::met)) {
            note = "요건을 모두 채웠습니다 — 월요일 00:00 등급 재계산 때 반영됩니다";
        }

        return new GradeStatusResponse(
                current.name(), GRADE_NAMES.get(current), ti, in.activeScore(), in.judgedCount(),
                GRADE_NAMES.get(next),
                reqs.stream().map(r -> new GradeRequirementResponse(
                        r.kind(), r.label(), r.current(), r.required(), r.met(), r.basis())).toList(),
                note);
    }
```

- 쓰지 않게 된 `computeGradeStatus`·`judgedCount`·`hitCount`·`missCount`·`activeScore`와 관련 import(`ParameterSet`, `ActiveScore`, `GradeStatus`, `ScoreLedgerEntry` 등)를 지운다. `ledger`·`submissions`는 `ledger()`·`wordFor()`가 계속 쓴다.

`infra/docker-compose.yml`의 backend `environment`에서 아래 세 줄(주석 두 줄 + 값)을 삭제한다:

```yaml
      # 컨테이너 JVM은 UTC다. 일 배치 기본값(17:00·18:00 UTC = 02:00·03:00 KST)은 그대로 맞지만
      # 주간 grade_recalc 기본값(월 00:00)은 UTC 기준이 돼 버리므로 월 00:00 KST(= 일 15:00 UTC)로 맞춘다.
      JOBS_GRADE_RECALC_CRON: "${JOBS_GRADE_RECALC_CRON:-0 0 15 * * SUN}"
```

(남겨 두면 코드가 이제 KST로 해석하므로 일요일 15:00에 돈다.)

`infra/.env.example`의 "배치 스케줄" 절을 교체:

```bash
# ── 배치 스케줄 ─────────────────────────────────────────────────────────
# cluster_merge 02:00 KST, verdict_runner 03:00 KST, grade_recalc 월 00:00 KST — 모두 코드 기본값.
# grade_recalc는 코드에 KST가 명시돼 있어 보정이 필요 없다.
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew compileJava compileTestJava :app:test`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git rm backend/scheduler/src/main/java/kr/trendstage/scheduler/ParameterSetProvider.java
git add backend/persistence/src/main/java/kr/trendstage/persistence/repo/SubmissionRepository.java backend/persistence/src/main/java/kr/trendstage/persistence/grade/GradeInputsReader.java backend/scheduler/src/main/java/kr/trendstage/scheduler/GradeRecalcJob.java backend/api-public/src/main/java/kr/trendstage/apipublic/service/MeService.java infra/docker-compose.yml infra/.env.example backend/app/src/test/java/kr/trendstage/support/Fixtures.java backend/app/src/test/java/kr/trendstage/grade/GradeRecalcTest.java
git commit -m "feat(grade): 공식 등급 = 주간 스냅샷(KST 명시), TI 180일 창, 강등 규칙 전까지 등급 유지

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 9: API 계약 (`openapi.yaml`)

**Files:**
- Modify: `backend/api-spec/openapi.yaml`

- [ ] **Step 1: `POST /v1/submissions`의 `'422'` 응답 교체**

```yaml
        '422':
          description: >
            제보를 받을 수 없음 — Problem.type으로 구분한다.
            quota-exhausted: 이번 주 제보권 소진(월 00:00 KST 리필, 제출 − VOID 반환 기준).
            item-closed: 관측이 끝난 항목(마감 이후·판정 중·판정 완료·VOID·병합됨). 제보권은 쓰지 않는다.
            about:blank: 필드 값 오류(확신도 등).
          content: { application/json: { schema: { $ref: '#/components/schemas/Problem' } } }
```

- [ ] **Step 2: 요약·등급 설명 보강**

- `/v1/me/summary`의 `description`(없으면 `summary` 아래에 추가): "quotaUsed = 이번 주(월 00:00 KST~) 낸 제보 − 이번 주 VOID로 반환된 제보. quotaMax는 주간 스냅샷 등급의 한도."
- `/v1/me/grade`에 `description` 추가: "grade는 주간 스냅샷(월 00:00 KST 재계산)이다. requirements는 다음 등급까지를 지금 값(TI 최근 180일·AS 행별 감쇠)으로 계산한다. 요건을 모두 채웠으면 note로 반영 시점을 알린다."
- `components.schemas.Problem.properties.type`에 `description: "about:blank | quota-exhausted | item-closed"`.

- [ ] **Step 3: 커밋**

```bash
git add backend/api-spec/openapi.yaml
git commit -m "docs(api-spec): 제보 422 type(quota-exhausted·item-closed), 제보권·등급 산정 기준

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 10: 프론트 — 앱 제보권 표시, 콘솔 재판정 안내·시딩 순위

**Files:**
- Modify: `app/src/screens/SubmitScreen.tsx`
- Modify: `admin/src/screens/VerdictScreen.tsx`
- Modify: `admin/src/screens/TrendDetailScreen.tsx`
- Modify: `admin/src/api/types.ts`, `admin/src/fixtures.ts`

- [ ] **Step 1: 앱 `SubmitScreen`**

`useMeSummary`를 import하고 `NewSubmission()`에서:

```tsx
  const summary = useMeSummary();
  const remaining = summary.data ? Math.max(0, summary.data.quotaMax - summary.data.quotaUsed) : null;
  const exhausted = remaining === 0;

  const ready = name.trim() && cat && plat && url.trim().length > 3 && oneLine.trim() && !exhausted;
```

오류 문구(`submit.isError`) 위에:

```tsx
      {remaining !== null && (
        <Muted style={exhausted ? { color: C.fading } : undefined}>
          {exhausted ? "이번 주 제보권을 모두 썼어요 · 월요일 00:00에 다시 채워져요" : `이번 주 제보권 ${remaining}장 남음`}
        </Muted>
      )}
```

버튼 문구의 삼항식을 `submit.isPending ? "제보 중…" : exhausted ? "이번 주 제보권 소진" : ready ? … : …`로 바꾼다. 422 응답은 서버 `detail`이 이미 사람이 읽는 문장이라 기존 `submit.error.message` 표시를 그대로 쓴다.

- [ ] **Step 2: 콘솔 재판정 안내 (`VerdictScreen.tsx`)**

다이얼로그에서 `{dialog.kind === "grace" && (…)}` 블록 바로 앞에:

```tsx
            {dialog.kind === "rejudge" && (
              <p style={{ margin: "10px 0 0", color: C.sub, fontSize: 12.5, lineHeight: 1.5 }}>
                원 판정 때의 파라미터로, 그사이 VOID된 제보를 뺀 지금의 제보를 다시 계산합니다.
                새 파라미터를 과거 판정에 소급하지 않습니다.
              </p>
            )}
```

- [ ] **Step 3: 시딩 순위 표시**

`admin/src/api/types.ts`의 제보 행 타입(`submissionId: string; userHandle: string; orderRank: number;`)에서 `orderRank: number | null;`로 바꾼다. `TrendDetailScreen.tsx`의 `{s.orderRank}` → `{s.orderRank ?? "시딩"}`. `fixtures.ts`는 타입이 넓어졌을 뿐이라 수정 불필요(컴파일 확인).

- [ ] **Step 4: 빌드 확인**

- 콘솔: `npm run build --prefix admin` → 성공.
- 앱: `cd app && npx tsc --noEmit` → 오류 없음.
- Node가 없는 이 PC에서는:
  ```bash
  MSYS_NO_PATHCONV=1 docker run --rm -v "$(cygpath -w "$PWD"):/src:ro" node:22-alpine sh -c 'cp -r /src/admin /w && cd /w && npm ci && npm run build'
  ```
  ```bash
  MSYS_NO_PATHCONV=1 docker run --rm -v "$(cygpath -w "$PWD"):/src:ro" node:22-alpine sh -c 'cp -r /src/app /w && cd /w && npm ci && npx tsc --noEmit'
  ```

- [ ] **Step 5: 커밋**

```bash
git add app/src/screens/SubmitScreen.tsx admin/src/screens/VerdictScreen.tsx admin/src/screens/TrendDetailScreen.tsx admin/src/api/types.ts
git commit -m "feat(app,admin): 남은 제보권 표시·소진 시 제출 막기, 재판정 기준 안내, 시딩 순위 표시

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 11: 문서

**Files:** `CLAUDE.md`, `01-system-design.md`, `02-admin-console.md`, `03-merge-clustering.md`, `04-development-plan.md`, `05-screen-endpoint-map.md`, `README.md`

원칙: 코드에 실재하게 된 것은 "미구현(SP1)" 표기를 지우고 현재형으로 쓴다. 과잉 표기(없는 것을 있다고 쓰기)는 결함으로 취급한다(상위 스펙 §4).

- [ ] **Step 1: CLAUDE.md**
  - 점수 공식 절: "(현행 코드는 `ScoreEngine`이 Δ에 감쇠를 곱해 기록하고 `ActiveScore`가 다시 곱하는 **이중 적용** 상태다. SP1에서 원값 기록으로 전환.)" 삭제. 경고 블록의 O11 문장을 "원장 행마다 `halflife_days`·`decay_anchor_at`을 기록하고 AS는 행별 값으로 감쇠한다(O11 = (b), 2026-09-20 결정) — 반감기를 바꿔도 과거 AS는 그대로다."로.
  - R5: "시딩도 제외해야 하지만 미구현(SP1) …" → "VOID·시딩(`is_seed=true`) 제보는 신호에서 제외한다(T와 선점 순위 모두)."
  - "⚠ order_rank" SQL에 `AND NOT is_seed` 추가.
  - "⚠ 같은 유저의 중복 제보는 VOID 처리": 제보권 반환 → "`voided_at`이 속한 주에 제보권 한 장이 반환된다(제보 행에서 파생, `QuotaService`)."
  - 배치 표: `verdict_runner` → "마감 지난 항목 JUDGING → `JudgeService` 항목별 판정", `grade_recalc` → "월 00:00 **KST**(코드에 zone 명시). 공식 등급 스냅샷, TI 180일, 강등 규칙 전까지 등급 유지(J8). 제보권 리필은 주 경계 자체". "TI·판정완료 건수가 … 고정돼" 문장 삭제.
  - Phase 1: "판정 신호(T)에서도 제외해야 하지만 **미구현(SP1)**" → 구현, "선행 조건: 제보권 …(SP1)" → 충족.
  - 열린 결정 O11 → "결정됨(b) — 원장 행별 반감기".
  - 코드 규약: "`score_ledger(verdict_id, submission_id)` UNIQUE(**SP1에서 신설**)" → "(V28)". "`JudgeService` 하나로 모은다(SP1에서 신설)" → "(`judge` 모듈)".
- [ ] **Step 2: 01** — §2.2(JUDGING = 관측 마감 후 판정 대기, 실제로 설정됨), §3.3(집행·반환·리필 방식 J4, 마감 후 제보 불가 J6, 공식 등급 = 스냅샷 J5), §4.1·4.3·4.4·5.1~5.3의 "미구현(SP1)"·현행 서술 갱신, §4.3에 재판정 = 원 판정 파라미터(J2), §6 note(공식 등급 = 주간 스냅샷, 강등 규칙 전까지 유지 J8).
- [ ] **Step 3: 02** — ADM-200 재판정 = 원 판정 파라미터·제보 단위 ADJ(J2), VOID = 항목 VOID 사건(원장 상쇄 포함). ADM-100 VOID도 같은 경로(판정된 항목이면 원장 상쇄). ADM-111 시딩 행 순위 "시딩".
- [ ] **Step 4: 03** — 선점 순위 시딩 제외(J3). 같은 유저 중복 VOID의 제보권 반환 = 구현.
- [ ] **Step 5: 04** — 모듈 트리에 `judge`(JudgeService, scheduler·api-admin 공용), §5.1 스키마 V28, §5.2 원장 원값·행별 감쇠, §6 배치 표, §7.1 `POST /v1/submissions` 제보권 집행 = 구현, §7.2 재판정 서술을 J2로("JudgeService가 원 판정 때 동결한 파라미터로 재실행").
- [ ] **Step 6: 05** — `POST /v1/submissions` 422 type, 구현 상태 열 갱신.
- [ ] **Step 7: README** — "현재 구현 상태" 표에서 판정 배치·제보권·등급 관련 행을 SP1 결과로(판정 저장·시딩 제외·제보권 집행·TI 180일). Docker 절의 배치 시각 문구는 유지.
- [ ] **Step 8: 확인**

```bash
grep -rnE "미구현\(SP1\)|SP1에서 (신설|전환|수정)|이중 적용 상태|voidByRule" CLAUDE.md 0[1-5]-*.md README.md
```

Expected: 결과 없음(남는 것이 있으면 사실과 대조해 고친다).

- [ ] **Step 9: 커밋**

```bash
git add CLAUDE.md 01-system-design.md 02-admin-console.md 03-merge-clustering.md 04-development-plan.md 05-screen-endpoint-map.md README.md
git commit -m "docs: SP1 반영 — JudgeService, 시딩 제외, 제보권, 원장 원값·행별 감쇠, 공식 등급 스냅샷

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 12: 최종 검증·PR

- [ ] **Step 1: 전체 백엔드 테스트** — `./gradlew test` (이 PC는 전제의 컨테이너 명령에 `test`). Expected: 전부 PASS. 테스트 수를 기록한다(SP1 전 64개).
- [ ] **Step 2: 정적 확인**

```bash
grep -rnE "SubmissionSignal|voidByRule|ParameterSetProvider|elapsedDays" backend --include=*.java
grep -rn "ParameterSet.defaults()" backend --include=*.java | grep -v /test/
```

Expected: 첫 줄 결과 없음. 둘째 줄은 `ParameterSet.java`, `CurrentParameterSetResolver.java`, `ParameterDraft.java`, `ParamStudioService.java`(`defaultPayloadJson`)만.

- [ ] **Step 3: 프론트 빌드** — Task 10 Step 4.
- [ ] **Step 4: Docker 기동 확인**(스펙 §9)
  1. `docker compose -f infra/docker-compose.yml down` → `docker volume rm infra_trd-db` → `docker compose -f infra/docker-compose.yml up -d --build`. backend가 healthy, 로그에 `Successfully applied 28 migrations`.
  2. V28 가드: `docker compose ... stop backend` → `docker exec trd-db psql -U postgres -d trd -c "DELETE FROM flyway_schema_history WHERE version = '28'"` 후 옛 판정 행을 하나 넣고(`INSERT INTO trend_items …`, `INSERT INTO verdicts …`) `up -d backend` → 로그에 `SP1(V28): 기존 판정·원장이 남아 있습니다`와 함께 기동 실패. 확인 뒤 다시 `down` + `volume rm`으로 되돌린다.
  3. 앱 서버를 계속 쓸 거면 빈 DB로 다시 올린다.
- [ ] **Step 5: 스펙 체크** — 스펙 §9 완료 기준 네 줄을 하나씩 확인해 PR 본문에 적는다.
- [ ] **Step 6: push·PR** (`main`은 소유자만 병합 — PR까지만)

```bash
git -c credential.helper= -c 'credential.helper=!"/c/Program Files/GitHub CLI/gh.exe" auth git-credential' push -u origin sp1-verdict-pipeline
```

```bash
"/c/Program Files/GitHub CLI/gh.exe" pr create --repo KangGuYong/TRD --base main --head sp1-verdict-pipeline --title "SP1: 판정 파이프라인 정합성 — JudgeService·시딩 제외·VOID 사건화·제보권·원장 원값" --body-file <본문 파일>
```

PR 본문: 요약(스펙 §0 표 10항목이 어떻게 해결됐는지), 결정 J1~J8, 검증(테스트 수·케이스 번호, 프론트 빌드, Docker 기동·V28 가드), 로컬 환경 안내(**DB 초기화 필수**, `JOBS_GRADE_RECALC_CRON` 보정 제거), 범위 밖(스펙 §0 비범위·§10), 끝에 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
