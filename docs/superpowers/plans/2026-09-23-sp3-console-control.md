# SP3 콘솔 통제·SLA Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 콘솔의 통제를 서버가 하게 만든다 — 100점 초과 원장 변동·계정 생성·역할 변경은 요청자 + 승인자 1명의 승인을 거치고, 유저 원장(ADM-311)이 실데이터가 되고, `sla_watch`가 신고 4h·병합 24h·90일 미접속을 처리한다.

**Architecture:** 승인은 `api-admin`의 `ApprovalGate`(요청 생성)·`ApprovalService`(승인 1회 → 실행기 실행)·`ApprovalExecutor` 구현 6종이 담당한다. 판정 모듈은 승인을 모른다 — `JudgeService.rejudge/voidItem`이 `AdjustmentPolicy`(한도 또는 승인 정보)를 받아, 한도를 넘으면 아무것도 쓰지 않고 `JudgeOutcome.NeedsApproval`을 돌려준다. `sla_watch`는 scheduler 모듈의 `SlaWatchJob`(순회) + `SlaWatchService`(트랜잭션 메서드 3개). 스키마 변경은 V30 하나.

**Tech Stack:** Java 17, Spring Boot 3.3.5(Spring Security 6, Data JPA/Hibernate 6), Flyway, PostgreSQL 16 + pgvector, ShedLock 5.13, Testcontainers 1.21, JUnit 5, AssertJ, MockMvc; React 18 + Vite + TanStack Query(관리자 콘솔).

**Spec:** `docs/superpowers/specs/2026-09-23-sp3-console-control-design.md` (결정 K1~K12)

## Global Constraints

- 브랜치 `sp3-console-control`. 시작 전 `git branch --show-current`로 확인하고 다른 브랜치로 바꾸지 않는다. `main`은 소유자만 병합한다 — PR까지만.
- **`git add -A`·`git add .` 금지.** 파일명을 지정한다. `backend/app/.env`(있다면)는 읽지도 고치지도 않는다.
- V1~V29는 수정하지 않는다. SP3의 스키마 변경은 `V30__sp3_console_control.sql` 하나.
- 커밋 메시지 끝에 반드시: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
- 결정 K1~K12를 구현 중에 바꾸지 않는다. 계획대로 되지 않으면 멈추고 보고한다.
- 승인 한도: **`|원장 변동| 합 > 100`이면 승인 대상, 정확히 100은 통과**(K3). 상수 `ApprovalGate.THRESHOLD = new BigDecimal("100")`.
- 승인권 유예 **7일**(`AdminAccount.APPROVER_GRACE = Duration.ofDays(7)`), 신고 SLA **4시간**, 병합 SLA **24시간**, 관리자 미접속 **90일**, 관측 마감 상한 **최초 제보 + 21일**(`DeadlineWindow.MAX_DEADLINE_DAYS`).
- 시각은 UTC 저장·주입 `Clock` 사용(`Instant.now()` 금지), 화면 표시는 KST `yyyy-MM-dd HH:mm`.
- 409·422 응답 본문은 `{type, status, detail}`(`Problems.of`). 새 type: `approval-pending`, `void-needs-approval`, `session-revoked`(401).
- 감사 로그 액션 이름(정확히): `APPROVAL_REQUEST`, `APPROVAL_APPROVE`, `APPROVAL_EXECUTE`, `APPROVAL_REJECT`, `VERDICT_VOID`, `VERDICT_REJUDGE`, `LEDGER_ADJ`, `ACCOUNT_CREATE`, `ACCOUNT_CREATE_BOOTSTRAP`, `ACCOUNT_PASSWORD_CHANGE`, `SLA_AUTO_HIDE`, `SLA_GRACE_EXTEND`, `SLA_ADMIN_DISABLE`.
- 같은 빈의 `@Transactional` 메서드를 그 빈 안에서 호출하지 않는다(self-invocation, CLAUDE.md).
- **테스트 실행.** Docker가 실행 중이어야 한다(`docker info`). JDK가 없는 이 PC에서는 저장소 루트에서(`<태스크>` 자리에 Gradle 인자, `--tests` 필터는 큰따옴표):
  ```bash
  MSYS_NO_PATHCONV=1 docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$(cygpath -w "$PWD/backend"):/src:ro" -v trd-gradle-test-cache:/root/.gradle -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal eclipse-temurin:17-jdk bash -c 'cp -r /src /workspace && cd /workspace && sed -i "s/\r$//" gradlew && ./gradlew --no-daemon <태스크>'
  ```
  JDK가 있으면 `backend/`에서 `./gradlew <태스크>`.
- **공유 테스트 DB.** 통합 테스트 클래스는 컨테이너 하나를 공유한다(`AbstractIntegrationTest`). 이름·키는 랜덤, 단언은 자기 데이터로만. 배치를 부르기 전 `releaseBatchLock(name)`. **`sla_watch` 테스트는 시계를 실제 현재 시각 + 90일 이후로 옮기지 않는다** — 다른 테스트가 만든 관리자까지 90일 미접속으로 잡힌다.
- 관리자 요청 테스트는 `asAdmin(accountId, role)`(Task 1)을 쓴다. **계정의 DB 역할과 같은 역할을 넘긴다** — Task 6 이후 세션 재검증이 다르면 401을 낸다.
- 각 태스크 끝에 `compileJava compileTestJava`와 그 태스크의 테스트가 통과해야 한다. Task 12에서 전체 `test`.

## Review Focus

사용자가 실제로 만날 가능성이 높은데 스펙의 완료 기준 테스트가 직접 누르지 않는 입력 다섯 가지. 각 줄의 테스트는 해당 태스크에 들어 있다.

1. **역할 변경 요청이 승인되기 전에 대상의 역할이 이미 바뀜**(두 요청이 겹침) → 두 번째 승인은 409, 요청은 PENDING으로 남는다(Task 5 `roleChangeApprovalConflictsWhenRoleAlreadyChanged`).
2. **수동 ADJ 금액이 0·10000 초과·소수 5자리 이상** → 0과 10000 초과는 422, 소수는 4자리로 반올림돼 기록(Task 4 `manualAdjustmentValidatesAmount`).
3. **차액 합계가 정확히 100** → 승인 없이 반영(Task 3 `AdjustmentPolicyTest`).
4. **감사 로그 필터에 잘못된 날짜** → 500이 아니라 422(Task 7 `badDateFilterIs422`).
5. **이미 사람이 숨긴(`TEMP_HIDDEN`)·영구 비공개된 항목의 OPEN 신고가 4h를 넘김** → visibility를 바꾸지 않고 `auto_hidden_at`만 기록(Task 8 `autoHideLeavesNonPublicItemsAlone`).

---

## 파일 구조

| 파일 | 책임 | 태스크 |
|---|---|---|
| `backend/persistence/src/main/resources/db/migration/V30__sp3_console_control.sql` | 승인권 유예·활성 시각, 자동 숨김 시각, ADJ 승인자, 항목당 대기 1건, PARTIAL 정리 | 1 |
| `backend/persistence/.../entity/{AdminAccount,Report,ScoreLedgerEntry,ApprovalRequest}.java` | 새 컬럼 매핑, 계정 활성·역할·승인 자격 | 1, 2 |
| `backend/persistence/.../repo/{AdminAccountRepository,ApprovalRequestRepository,ReportRepository,MergeQueueRepository,UserRepository,AdminAuditLogRepository}.java` | 조회 추가 | 2, 4, 7, 8 |
| `backend/api-admin/.../approval/{ActionType,ApprovalGate,ApprovalService,ApprovalExecutor,ParamApplyExecutor}.java` | 승인 요청·승인 1회·실행기 계약 | 2 |
| `backend/api-admin/.../web/{AdminConflictException,AdminNotFoundException,AdminApiExceptionHandler,ApprovalController}.java` | 409·404 매핑, 승인 응답 | 2, 4 |
| `backend/judge/.../{AdjustmentPolicy,JudgeOutcome,JudgeService}.java` | 한도·결과값, 차액 계산 분리 | 3 |
| `backend/api-admin/.../verdict/{VerdictAdminService,VerdictController,VerdictRejudgeExecutor,ItemVoidExecutor}.java` | ADM-200 게이트, 판정 실행기 | 3 |
| `backend/api-admin/.../merge/MergeDecisionService.java` | ADM-100 VOID 한도(K8) | 3 |
| `backend/api-admin/.../user/{AdminUserService,LedgerAdjustService,LedgerAdjustExecutor}.java`, `web/AdminUserController.java` | ADM-311 | 4 |
| `backend/api-admin/.../account/{AdminAccountManagementService,AccountCreateExecutor,AccountRoleChangeExecutor}.java`, `auth/AccountPendingException.java`, `web/{AdminAccountController,AdminAuthController}.java` | 계정 통제 | 5 |
| `backend/api-admin/.../config/{AdminSessionRevalidationFilter,AdminSecurityConfig}.java` | 세션 재검증(K7) | 6 |
| `backend/api-admin/.../web/{ReportAdminController,ParamStudioController,AuditLogController}.java` | 역할 정합, 감사 로그 필터 | 7 |
| `backend/domain-core/.../verdict/DeadlineWindow.java` | `slaExtended`·`ceilingReached` | 8 |
| `backend/scheduler/.../{SlaWatchJob,SlaWatchService}.java` | `sla_watch` | 8 |
| `backend/api-admin/.../report/ReportAdminService.java`, `queues/QueueSummaryService.java` | OPEN에서 복원, 알림 문구 | 8 |
| `backend/app/src/test/java/kr/trendstage/support/{Fixtures,AbstractIntegrationTest}.java` | 관리자·신고 픽스처, `asAdmin` | 1, 4, 8 |
| `backend/app/src/test/java/kr/trendstage/{admin,approval,sla}/*Test.java` | 통합 테스트 | 1~8 |
| `backend/api-spec/openapi.yaml` | 계약 | 9 |
| `admin/src/**` | 콘솔 | 10 |
| `CLAUDE.md`, `02`·`04`·`05`, `README.md`, `infra/.env.example` | 문서 | 11 |

---

### Task 1: V30 스키마 + 엔티티 매핑 + 테스트 기반

**Files:**
- Create: `backend/persistence/src/main/resources/db/migration/V30__sp3_console_control.sql`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/AdminAccount.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/Report.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/ScoreLedgerEntry.java`
- Modify: `backend/judge/src/main/java/kr/trendstage/judge/JudgeService.java` (`verdictAdjustment` 호출 인자만)
- Modify: `backend/app/src/test/java/kr/trendstage/support/Fixtures.java`
- Modify: `backend/app/src/test/java/kr/trendstage/support/AbstractIntegrationTest.java`
- Test: `backend/app/src/test/java/kr/trendstage/admin/SchemaV30Test.java`

**Interfaces:**
- Produces:
  - `AdminAccount.APPROVER_GRACE: Duration` (7일)
  - `AdminAccount.createdByConsole(String loginId, String displayName, AdminRole role, String passwordHash, Instant now, boolean activate): AdminAccount`
  - `AdminAccount#activate(Instant)`, `#changeRole(AdminRole, Instant)`, `#changePasswordHash(String)`, `#isActive(): boolean`, `#canApproveAt(Instant): boolean`, `#getApproverSince()`, `#getActivatedAt()`
  - `Report#markAutoHidden(Instant)`, `#getAutoHiddenAt()`
  - `ScoreLedgerEntry.verdictAdjustment(UUID userId, UUID submissionId, UUID verdictId, BigDecimal delta, String reason, int halflifeDays, Instant decayAnchorAt, UUID approvalId, UUID approvedByAdminId)`
  - `ScoreLedgerEntry.adjustment(UUID userId, BigDecimal delta, String reason, UUID approvalId, UUID approvedByAdminId, int halflifeDays, Instant decayAnchorAt)`
  - `ScoreLedgerEntry#getApprovalId()`, `#getApprovedByAdminId()`
  - Fixtures: `admin()`, `admin(String role)`, `adminInGrace(Instant until)`, `pendingAdmin(String role)`, `report(UUID itemId, String status, Instant createdAt)`, `visibility(UUID itemId)`, `setVisibility(UUID itemId, String v)`
  - `AbstractIntegrationTest#asAdmin(UUID accountId, AdminRole role): RequestPostProcessor`

- [ ] **Step 1: 테스트 기반(Fixtures·asAdmin)을 먼저 고친다**

`Fixtures.java`의 `admin()`을 아래로 교체하고 메서드들을 추가한다(`admin()`은 V30 뒤 `approver_since NOT NULL` 때문에 그대로 두면 모든 테스트가 깨진다):

```java
    /** 승인권이 있는(유예가 끝난) 활성 ADMIN. */
    public UUID admin() {
        return admin("ADMIN");
    }

    /** 승인권 유예가 끝난 활성 계정. role = REVIEWER|OPERATOR|ADMIN|AUDITOR. */
    public UUID admin(String role) {
        return jdbc.queryForObject("INSERT INTO admin_accounts (login_id, display_name, role, password_hash, "
                + "approver_since, activated_at) VALUES (?, '테스트 관리자', ?::admin_role, 'x', "
                + "TIMESTAMPTZ '2000-01-01 00:00:00+00', now()) RETURNING id", UUID.class, "a_" + rand(), role);
    }

    /** 승인권 유예 중인 활성 ADMIN — until까지 승인할 수 없다. */
    public UUID adminInGrace(Instant until) {
        return jdbc.queryForObject("INSERT INTO admin_accounts (login_id, display_name, role, password_hash, "
                + "approver_since, activated_at) VALUES (?, '유예 관리자', 'ADMIN', 'x', ?, now()) RETURNING id",
                UUID.class, "g_" + rand(), Timestamp.from(until));
    }

    /** 승인 대기(activated_at NULL) 계정. */
    public UUID pendingAdmin(String role) {
        return jdbc.queryForObject("INSERT INTO admin_accounts (login_id, display_name, role, password_hash, "
                + "approver_since) VALUES (?, '대기 관리자', ?::admin_role, 'x', now()) RETURNING id",
                UUID.class, "p_" + rand(), role);
    }

    /** 신고 한 건(신고자는 새 유저). status = OPEN|EXPLAINING|DECIDED. */
    public UUID report(UUID itemId, String status, Instant createdAt) {
        return jdbc.queryForObject("INSERT INTO reports (trend_item_id, reporter_id, reason, status, created_at) "
                + "VALUES (?, ?, 'OTHER', ?::report_status, ?) RETURNING id",
                UUID.class, itemId, user(), status, Timestamp.from(createdAt));
    }

    public String visibility(UUID itemId) {
        return jdbc.queryForObject("SELECT visibility::text FROM trend_items WHERE id = ?", String.class, itemId);
    }

    public void setVisibility(UUID itemId, String visibility) {
        jdbc.update("UPDATE trend_items SET visibility = ?::trend_visibility WHERE id = ?", visibility, itemId);
    }
```

`AbstractIntegrationTest.java`에 헬퍼를 추가한다(import: `jakarta.servlet.http.Cookie`, `kr.trendstage.apiadmin.auth.AdminPrincipal`, `kr.trendstage.persistence.type.AdminRole`, `org.springframework.security.authentication.UsernamePasswordAuthenticationToken`, `org.springframework.security.core.authority.SimpleGrantedAuthority`, `org.springframework.test.web.servlet.request.RequestPostProcessor`, `java.util.List`, `java.util.UUID`, static `...SecurityMockMvcRequestPostProcessors.authentication`, static `...MockMvcRequestBuilders.get`):

```java
    /**
     * 관리자 세션 + CSRF(쿠키-헤더 이중 제출). role은 계정의 DB 역할과 같아야 한다 —
     * 세션 재검증(SP3 K7)이 다르면 401을 낸다.
     */
    protected RequestPostProcessor asAdmin(UUID accountId, AdminRole role) throws Exception {
        Cookie csrf = mvc.perform(get("/admin/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        AdminPrincipal principal = new AdminPrincipal(accountId, "t_" + accountId, "테스터", role);
        RequestPostProcessor auth = authentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
        return request -> {
            auth.postProcessRequest(request);
            request.setCookies(csrf);
            request.addHeader("X-XSRF-TOKEN", csrf.getValue());
            return request;
        };
    }
```

- [ ] **Step 2: 실패하는 테스트 작성**

`SchemaV30Test.java`:

```java
package kr.trendstage.admin;

import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V30 제약과 새 컬럼 매핑. */
class SchemaV30Test extends AbstractIntegrationTest {

    @Autowired AdminAccountRepository accounts;

    @Test
    void approverSinceIsRequired() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO admin_accounts (login_id, display_name, role, password_hash) "
                + "VALUES (?, 'x', 'ADMIN', 'x')", "n_" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ledgerApprovalAndApproverComeTogether() {
        UUID user = fx.user();
        UUID admin = fx.admin();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO score_ledger (user_id, kind, delta, reason, halflife_days, "
                + "decay_anchor_at, approved_by_admin_id) VALUES (?, 'ADJ', 1, '사유', 90, now(), ?)", user, admin))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onePendingVerdictChangePerItem() {
        UUID item = fx.item(Instant.parse("2026-08-01T00:00:00Z"));
        UUID requester = fx.admin();
        insertApproval("VERDICT_REJUDGE", item, requester, "PENDING");
        assertThatThrownBy(() -> insertApproval("ITEM_VOID", item, requester, "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 끝난 요청은 막지 않는다
        insertApproval("ITEM_VOID", UUID.randomUUID(), requester, "PENDING");
        UUID other = fx.item(Instant.parse("2026-08-01T00:00:00Z"));
        insertApproval("VERDICT_REJUDGE", other, requester, "EXECUTED");
        insertApproval("VERDICT_REJUDGE", other, requester, "PENDING");
    }

    @Test
    void adminAccountMapsNewColumns() {
        UUID pending = fx.pendingAdmin("OPERATOR");
        AdminAccount a = accounts.findById(pending).orElseThrow();
        assertThat(a.getActivatedAt()).isNull();
        assertThat(a.isActive()).isFalse();
        assertThat(a.getApproverSince()).isNotNull();

        AdminAccount eligible = accounts.findById(fx.admin()).orElseThrow();
        assertThat(eligible.canApproveAt(Instant.now())).isTrue();
        AdminAccount grace = accounts.findById(fx.adminInGrace(Instant.now().plusSeconds(3600))).orElseThrow();
        assertThat(grace.canApproveAt(Instant.now())).isFalse();
    }

    @Test
    void reportMapsAutoHiddenAt() {
        UUID report = fx.report(fx.item(Instant.parse("2026-08-01T00:00:00Z")), "OPEN", Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(jdbc.queryForObject("SELECT auto_hidden_at FROM reports WHERE id = ?", Instant.class, report)).isNull();
    }

    private void insertApproval(String type, UUID target, UUID requester, String status) {
        jdbc.update("INSERT INTO approval_requests (action_type, target_ref, requested_by, status) "
                + "VALUES (?, ?, ?, ?::approval_status)", type, target, requester, status);
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `:app:test --tests "kr.trendstage.admin.SchemaV30Test"`
Expected: 컴파일 실패(`getActivatedAt`, `isActive` 등 없음).

- [ ] **Step 4: 마이그레이션 작성**

`V30__sp3_console_control.sql`:

```sql
-- V30 · SP3 콘솔 통제·SLA (docs/superpowers/specs/2026-09-23-sp3-console-control-design.md)

-- 1) 계정 통제(K2·K6). 기존 계정은 승인권·활성 모두 생성 시각부터 — 규칙 도입 전 계정을 잠그지 않는다.
ALTER TABLE admin_accounts
    ADD COLUMN approver_since TIMESTAMPTZ,
    ADD COLUMN activated_at   TIMESTAMPTZ;
UPDATE admin_accounts SET approver_since = created_at, activated_at = created_at;
ALTER TABLE admin_accounts ALTER COLUMN approver_since SET NOT NULL;
COMMENT ON COLUMN admin_accounts.approver_since IS '이 시각부터 승인권이 있다. 생성·ADMIN 승격 시 +7일(P8). 부트스트랩 계정은 생성 시각.';
COMMENT ON COLUMN admin_accounts.activated_at IS 'NULL = 계정 생성 승인 대기(로그인 불가). 승인되거나 부트스트랩 예외로 만들면 채워진다.';

-- 2) 신고 자동 임시 비공개 기록(K9) — 있으면 sla_watch가 다시 처리하지 않는다
ALTER TABLE reports ADD COLUMN auto_hidden_at TIMESTAMPTZ;
COMMENT ON COLUMN reports.auto_hidden_at IS 'sla_watch가 4시간 SLA 초과로 이 신고를 자동 처리한 시각. 신고 상태는 OPEN 그대로 — 결정은 사람이 한다(R4).';

-- 3) 승인된 ADJ의 승인자(관리자). 옛 approved_by는 users FK라 관리자를 가리킬 수 없는 잔재 — 쓰지 않는다
ALTER TABLE score_ledger
    ADD COLUMN approved_by_admin_id UUID REFERENCES admin_accounts(id),
    ADD CONSTRAINT ledger_approval_pair CHECK ((approval_id IS NULL) = (approved_by_admin_id IS NULL));
COMMENT ON COLUMN score_ledger.approved_by_admin_id IS '2인 승인을 거친 ADJ의 승인 관리자. approval_id와 함께만 존재.';
COMMENT ON COLUMN score_ledger.approved_by IS '사용하지 않음(users FK라 관리자를 가리킬 수 없음). 승인자는 approved_by_admin_id.';

-- 4) 승인 1명 체계(K1): 남은 1/2 요청은 처음부터 다시 승인받는다
UPDATE approval_requests SET status = 'PENDING', approver_1 = NULL WHERE status = 'PARTIAL';

-- 5) 항목당 대기 중인 재판정·VOID 요청 하나(K5)
CREATE UNIQUE INDEX approval_one_pending_verdict_change
    ON approval_requests (target_ref)
    WHERE status = 'PENDING' AND action_type IN ('VERDICT_REJUDGE', 'ITEM_VOID');
```

- [ ] **Step 5: 엔티티 수정**

`AdminAccount.java` — 필드(`createdAt` 위)와 상수·메서드 추가, 기존 생성자 끝에 두 줄 추가(import `java.time.Duration`, `kr.trendstage.persistence.type.AdminRole`는 이미 있음):

```java
    /** 신규 계정·ADMIN 승격 후 승인권이 생기기까지의 유예(P8, SP3 K2). */
    public static final Duration APPROVER_GRACE = Duration.ofDays(7);

    @Column(name = "approver_since", nullable = false)
    private Instant approverSince;

    /** NULL = 계정 생성 승인 대기 — 로그인 불가(SP3 §5). */
    @Column(name = "activated_at")
    private Instant activatedAt;
```

기존 생성자 본문 끝:

```java
        // 부트스트랩·시딩 계정: 만들자마자 활성, 승인권 유예 없음(K6). 콘솔 생성은 createdByConsole을 쓴다.
        this.approverSince = this.createdAt;
        this.activatedAt = this.createdAt;
```

메서드:

```java
    /** 콘솔에서 만드는 계정 — 승인권은 now + 7일부터. activate=false면 승인 대기(로그인 불가). */
    public static AdminAccount createdByConsole(String loginId, String displayName, AdminRole role, String passwordHash,
                                                Instant now, boolean activate) {
        AdminAccount a = new AdminAccount(loginId, displayName, role, passwordHash);
        a.createdAt = now;
        a.approverSince = now.plus(APPROVER_GRACE);
        a.activatedAt = activate ? now : null;
        return a;
    }

    /** 계정 생성 승인(ACCOUNT_CREATE). 이미 활성이면 그대로. */
    public void activate(Instant at) {
        if (this.activatedAt == null) this.activatedAt = at;
    }

    /** 역할 변경(ACCOUNT_ROLE_CHANGE). ADMIN으로 승격되면 승인권은 now + 7일부터(P8). */
    public void changeRole(AdminRole next, Instant now) {
        if (next == AdminRole.ADMIN && this.role != AdminRole.ADMIN) {
            this.approverSince = now.plus(APPROVER_GRACE);
        }
        this.role = next;
    }

    public void changePasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    /** 로그인·세션 유지 가능: 비활성화되지 않았고 승인 대기도 아님. */
    public boolean isActive() { return disabledAt == null && activatedAt != null; }

    /** 2인 승인의 승인자 자격(K2) — 요청자와 다른지는 호출 측이 본다. */
    public boolean canApproveAt(Instant now) {
        return role == AdminRole.ADMIN && isActive() && !now.isBefore(approverSince);
    }

    public Instant getApproverSince() { return approverSince; }
    public Instant getActivatedAt() { return activatedAt; }
```

`Report.java` — 필드(`createdAt` 위)와 메서드:

```java
    /** sla_watch가 4h SLA 초과로 자동 처리한 시각(SP3 K9). 상태는 OPEN 그대로. */
    @Column(name = "auto_hidden_at")
    private Instant autoHiddenAt;

    public void markAutoHidden(Instant at) { this.autoHiddenAt = at; }
    public Instant getAutoHiddenAt() { return autoHiddenAt; }
```

`ScoreLedgerEntry.java` — 필드(`approvalId` 다음):

```java
    /** 승인된 ADJ의 승인 관리자(SP3). approval_id와 함께만 존재(DB CHECK ledger_approval_pair). */
    @Column(name = "approved_by_admin_id")
    private UUID approvedByAdminId;
```

`verdictAdjustment`·`adjustment`를 교체하고 getter 추가:

```java
    /**
     * 재판정·항목 VOID의 제보 단위 차액(ADJ). 원 판정과 같은 감쇠 기준이어야 AS에서 정확히 상쇄된다.
     * 승인을 거쳐 실행됐으면 approvalId·approvedByAdminId를 채운다(둘 다 null이거나 둘 다 값).
     */
    public static ScoreLedgerEntry verdictAdjustment(UUID userId, UUID submissionId, UUID verdictId, BigDecimal delta,
                                                     String reason, int halflifeDays, Instant decayAnchorAt,
                                                     UUID approvalId, UUID approvedByAdminId) {
        ScoreLedgerEntry e = ofVerdict(userId, submissionId, verdictId, LedgerKind.ADJ, delta, reason, halflifeDays, decayAnchorAt);
        e.approvalId = approvalId;
        e.approvedByAdminId = approvedByAdminId;
        return e;
    }

    /** 수동 상쇄 원장(ADJ, ADM-311). 사유 필수. 승인을 거쳤으면 승인 정보, 단독 기록이면 둘 다 null. */
    public static ScoreLedgerEntry adjustment(UUID userId, BigDecimal delta, String reason,
                                              UUID approvalId, UUID approvedByAdminId, int halflifeDays, Instant decayAnchorAt) {
        ScoreLedgerEntry e = new ScoreLedgerEntry();
        e.userId = userId; e.kind = LedgerKind.ADJ; e.delta = delta; e.reason = reason;
        e.approvalId = approvalId; e.approvedByAdminId = approvedByAdminId;
        e.halflifeDays = halflifeDays; e.decayAnchorAt = decayAnchorAt;
        return e;
    }

    public UUID getApprovalId() { return approvalId; }
    public UUID getApprovedByAdminId() { return approvedByAdminId; }
```

`adjustment`의 옛 호출부 확인: `grep -rn "ScoreLedgerEntry.adjustment(" backend --include=*.java` — 없어야 정상(있으면 새 인자 순서로 고친다).

`JudgeService.settle`의 `ScoreLedgerEntry.verdictAdjustment(...)` 호출 끝에 `, null, null`을 붙인다(Task 3에서 정책 값으로 바뀐다).

- [ ] **Step 6: 통과 확인**

Run: `compileJava compileTestJava :app:test --tests "kr.trendstage.admin.SchemaV30Test" --tests "kr.trendstage.ContextSmokeTest" --tests "kr.trendstage.admin.AdminAccountBootstrapTest"`
Expected: PASS. `ddl-auto: validate`가 새 컬럼 매핑을 검증한다.

- [ ] **Step 7: 커밋**

```bash
git add backend/persistence/src/main/resources/db/migration/V30__sp3_console_control.sql backend/persistence/src/main/java/kr/trendstage/persistence/entity/AdminAccount.java backend/persistence/src/main/java/kr/trendstage/persistence/entity/Report.java backend/persistence/src/main/java/kr/trendstage/persistence/entity/ScoreLedgerEntry.java backend/judge/src/main/java/kr/trendstage/judge/JudgeService.java backend/app/src/test/java/kr/trendstage/support/Fixtures.java backend/app/src/test/java/kr/trendstage/support/AbstractIntegrationTest.java backend/app/src/test/java/kr/trendstage/admin/SchemaV30Test.java
git commit -m "feat(persistence): V30 승인권 유예·계정 활성, 신고 자동 숨김 시각, ADJ 승인자, 항목당 대기 요청 1건

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 승인 게이트 — 요청자 + 승인자 1명

**Files:**
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/approval/ActionType.java`
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/approval/ApprovalGate.java`
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminConflictException.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/approval/{ApprovalExecutor,ApprovalService,ParamApplyExecutor}.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/ApprovalRequest.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/{ApprovalRequestRepository,AdminAccountRepository}.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/{ApprovalController,AdminApiExceptionHandler}.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/params/ParamStudioService.java`
- Modify: `backend/app/src/test/java/kr/trendstage/support/Fixtures.java`
- Test: `backend/app/src/test/java/kr/trendstage/approval/ApprovalFlowTest.java`

**Interfaces:**
- Consumes: `AdminAccount#canApproveAt`, `#isActive`, `#getApproverSince` (Task 1)
- Produces:
  - `enum ActionType { PARAM_APPLY, VERDICT_REJUDGE, ITEM_VOID, LEDGER_ADJ, ACCOUNT_CREATE, ACCOUNT_ROLE_CHANGE }`
  - `ApprovalGate.THRESHOLD: BigDecimal` (100)
  - `ApprovalGate#exceeds(BigDecimal amount): boolean` — `amount.abs() > 100`
  - `ApprovalGate#request(ActionType type, UUID targetRef, Map<String, String> payload, UUID requesterId, AdminRole requesterRole): ApprovalRequest` — 부분 UNIQUE 위반이면 `AdminConflictException("approval-pending", …)`
  - `ApprovalGate#hasPendingVerdictChange(UUID itemId): boolean`
  - `ApprovalGate#eligibleApproverCount(UUID excludingAccountId, Instant now): long`
  - `ApprovalGate#payload(ApprovalRequest): Map<String, String>` — payload JSON 파싱
  - `AdminConflictException(String type, String message)` → 409 `{type}`
  - `interface ApprovalExecutor { String actionType(); Map<String, String> execute(ApprovalRequest request); default void onReject(ApprovalRequest r) {} default String describe(ApprovalRequest r) { return actionType(); } }`
  - `ApprovalService#describe(ApprovalRequest): String`
  - `ApprovalRequest#approve(UUID approverId, Instant now)` (1회 승인)
  - Fixtures: `paramDraftInReview(UUID requesterId): UUID[] {draftId, approvalId}`

- [ ] **Step 1: 픽스처 추가**

`Fixtures.java`:

```java
    /** 승인 대기 중인 파라미터 드래프트(값은 기본값과 같다 — 적용돼도 판정에 영향 없음). {draftId, approvalId}. 끝나면 deleteDraft. */
    public UUID[] paramDraftInReview(UUID requesterId) {
        UUID draft = jdbc.queryForObject("INSERT INTO parameter_drafts (author_id, status, payload, sim_result) VALUES "
                + "(?, 'REVIEW', '{\"submitterTarget\":20,\"hitThreshold\":0.2}'::jsonb, '{}'::jsonb) RETURNING id",
                UUID.class, requesterId);
        UUID approval = jdbc.queryForObject("INSERT INTO approval_requests (action_type, target_ref, requested_by, payload) "
                + "VALUES ('PARAM_APPLY', ?, ?, '{\"reason\":\"테스트\"}'::jsonb) RETURNING id", UUID.class, draft, requesterId);
        jdbc.update("UPDATE parameter_drafts SET approval_id = ? WHERE id = ?", approval, draft);
        return new UUID[]{draft, approval};
    }

    public String approvalStatus(UUID approvalId) {
        return jdbc.queryForObject("SELECT status::text FROM approval_requests WHERE id = ?", String.class, approvalId);
    }
```

- [ ] **Step 2: 실패하는 테스트 작성**

`ApprovalFlowTest.java`:

```java
package kr.trendstage.approval;

import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스펙 §10 #9·#10: 요청자 + 승인자 1명(K1), 승인자 자격(K2). */
class ApprovalFlowTest extends AbstractIntegrationTest {

    @Test
    void paramApplyExecutesOnSingleApproval() throws Exception {
        UUID requester = fx.admin("OPERATOR");
        UUID approver = fx.admin();
        UUID[] ids = fx.paramDraftInReview(requester);
        try {
            mvc.perform(post("/admin/approvals/{id}/approve", ids[1]).with(asAdmin(approver, AdminRole.ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("EXECUTED"))
                    .andExpect(jsonPath("$.approvals").value(1))
                    .andExpect(jsonPath("$.requiredApprovals").value(1));
            assertThat(jdbc.queryForObject("SELECT status::text FROM parameter_drafts WHERE id = ?", String.class, ids[0]))
                    .isEqualTo("APPLIED");
            assertThat(fx.auditCount("APPROVAL_EXECUTE", ids[0])).isEqualTo(1);
        } finally {
            fx.deleteDraft(ids[0]);
        }
    }

    @Test
    void requesterCannotApproveOwnRequest() throws Exception {
        UUID requester = fx.admin();
        UUID[] ids = fx.paramDraftInReview(requester);
        try {
            mvc.perform(post("/admin/approvals/{id}/approve", ids[1]).with(asAdmin(requester, AdminRole.ADMIN)))
                    .andExpect(status().isConflict());
            assertThat(fx.approvalStatus(ids[1])).isEqualTo("PENDING");
        } finally {
            fx.deleteDraft(ids[0]);
        }
    }

    @Test
    void adminInGraceCannotApprove() throws Exception {
        UUID requester = fx.admin("OPERATOR");
        UUID grace = fx.adminInGrace(clock.instant().plus(Duration.ofDays(3)));
        UUID[] ids = fx.paramDraftInReview(requester);
        try {
            mvc.perform(post("/admin/approvals/{id}/approve", ids[1]).with(asAdmin(grace, AdminRole.ADMIN)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value(containsString("부터")));
            assertThat(fx.approvalStatus(ids[1])).isEqualTo("PENDING");
        } finally {
            fx.deleteDraft(ids[0]);
        }
    }

    @Test
    void operatorCannotApprove() throws Exception {
        UUID requester = fx.admin("OPERATOR");
        UUID operator = fx.admin("OPERATOR");
        UUID[] ids = fx.paramDraftInReview(requester);
        try {
            mvc.perform(post("/admin/approvals/{id}/approve", ids[1]).with(asAdmin(operator, AdminRole.OPERATOR)))
                    .andExpect(status().isForbidden());
        } finally {
            fx.deleteDraft(ids[0]);
        }
    }

    @Test
    void pendingListShowsSummary() throws Exception {
        UUID requester = fx.admin("OPERATOR");
        UUID[] ids = fx.paramDraftInReview(requester);
        try {
            mvc.perform(get("/admin/approvals").with(asAdmin(fx.admin(), AdminRole.ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.id == '" + ids[1] + "')].summary").value(org.hamcrest.Matchers.hasItem(startsWith("파라미터 적용"))));
        } finally {
            fx.deleteDraft(ids[0]);
        }
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `:app:test --tests "kr.trendstage.approval.ApprovalFlowTest"`
Expected: FAIL — `approve`가 `PARTIAL`에서 멈춤(`status` = PARTIAL), `requiredApprovals`·`summary` 필드 없음.

- [ ] **Step 4: 저장소·엔티티**

`ApprovalRequest.java`: `approveFirst`·`approveSecond`를 지우고 추가:

```java
    /** 승인(SP3 K1 — 요청자 + 승인자 1명). 자격 검사는 ApprovalService가 먼저 한다. */
    public void approve(UUID approverId, Instant now) {
        this.approver1 = approverId;
        this.status = ApprovalStatus.APPROVED;
        this.resolvedAt = now;
    }
```

`ApprovalRequestRepository.java`에 추가(import `java.util.Collection`):

```java
    /** 항목에 대기 중인 재판정·VOID 요청이 있는지(K5). */
    boolean existsByTargetRefAndStatusAndActionTypeIn(UUID targetRef, ApprovalStatus status, Collection<String> actionTypes);
```

주석 "PENDING/PARTIAL만"을 "PENDING만(SP3부터 PARTIAL은 쓰지 않는다)"으로 고친다.

`AdminAccountRepository.java`에 추가(import `java.time.Instant`, `java.util.UUID`):

```java
    /** 승인 자격자 수(K2) — 활성 ADMIN, 유예 경과, 특정 계정 제외. 부트스트랩 예외 판정(K6)에 쓴다. */
    @Query("SELECT count(a) FROM AdminAccount a WHERE a.role = kr.trendstage.persistence.type.AdminRole.ADMIN "
            + "AND a.disabledAt IS NULL AND a.activatedAt IS NOT NULL AND a.approverSince <= :now AND a.id <> :excluding")
    long countEligibleApprovers(@Param("excluding") UUID excluding, @Param("now") Instant now);
```

- [ ] **Step 5: 예외·ActionType·ApprovalGate**

`AdminConflictException.java`:

```java
package kr.trendstage.apiadmin.web;

/** 409 + type. 콘솔이 type으로 안내를 나눈다(approval-pending, void-needs-approval 등). */
public class AdminConflictException extends RuntimeException {
    private final String type;

    public AdminConflictException(String type, String message) {
        super(message);
        this.type = type;
    }

    public String type() { return type; }
}
```

`AdminApiExceptionHandler.java`에 추가:

```java
    @ExceptionHandler(AdminConflictException.class)
    public ResponseEntity<Map<String, Object>> handle(AdminConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Problems.of(409, e.type(), e.getMessage()));
    }
```

`ActionType.java`:

```java
package kr.trendstage.apiadmin.approval;

/** approval_requests.action_type 값. DB 컬럼은 VARCHAR(40) — name()이 그대로 저장된다. */
public enum ActionType {
    PARAM_APPLY, VERDICT_REJUDGE, ITEM_VOID, LEDGER_ADJ, ACCOUNT_CREATE, ACCOUNT_ROLE_CHANGE
}
```

`ApprovalGate.java`:

```java
package kr.trendstage.apiadmin.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.trendstage.apiadmin.web.AdminConflictException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.repo.ApprovalRequestRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.ApprovalStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 2인 승인의 입구(P7, SP3 §2). 원장을 100점 넘게 움직이는 관리자 작업·계정 생성·역할 변경은 여기서 승인 요청이 된다.
 * 실행은 ApprovalService가 승인 1회 뒤 해당 ApprovalExecutor에 맡긴다. 호출 측 트랜잭션 안에서 쓴다.
 */
@Service
public class ApprovalGate {

    /** 원장 변동 절댓값 합이 이 값을 넘으면 승인 대상(K3). 정확히 100은 통과. */
    public static final BigDecimal THRESHOLD = new BigDecimal("100");

    static final List<String> VERDICT_CHANGE_TYPES = List.of(ActionType.VERDICT_REJUDGE.name(), ActionType.ITEM_VOID.name());

    private final ApprovalRequestRepository approvals;
    private final AdminAccountRepository accounts;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public ApprovalGate(ApprovalRequestRepository approvals, AdminAccountRepository accounts,
                        AuditLogService auditLogService, ObjectMapper objectMapper) {
        this.approvals = approvals;
        this.accounts = accounts;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    public boolean exceeds(BigDecimal amount) {
        return amount.abs().compareTo(THRESHOLD) > 0;
    }

    public boolean hasPendingVerdictChange(UUID itemId) {
        return approvals.existsByTargetRefAndStatusAndActionTypeIn(itemId, ApprovalStatus.PENDING, VERDICT_CHANGE_TYPES);
    }

    public long eligibleApproverCount(UUID excludingAccountId, Instant now) {
        return accounts.countEligibleApprovers(excludingAccountId, now);
    }

    /** 승인 요청 생성. 같은 항목에 대기 중인 재판정·VOID가 있으면 DB 부분 UNIQUE가 막고 409(K5). */
    public ApprovalRequest request(ActionType type, UUID targetRef, Map<String, String> payload,
                                   UUID requesterId, AdminRole requesterRole) {
        ApprovalRequest saved;
        try {
            saved = approvals.saveAndFlush(new ApprovalRequest(type.name(), targetRef, requesterId, write(payload)));
        } catch (DataIntegrityViolationException e) {
            throw new AdminConflictException("approval-pending", "이 항목에 대기 중인 승인 요청이 있습니다 — 승인·반려 후 다시 시도하세요");
        }
        Map<String, Object> detail = new HashMap<>(payload);
        detail.put("approvalRequestId", saved.getId().toString());
        auditLogService.record(requesterId, requesterRole, "APPROVAL_REQUEST", type.name(), targetRef, detail);
        return saved;
    }

    public Map<String, String> payload(ApprovalRequest request) {
        try {
            return objectMapper.readValue(request.getPayload(), new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("승인 요청 payload 파싱 실패: " + request.getId(), e);
        }
    }

    private String write(Map<String, String> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("승인 요청 payload 직렬화 실패", e);
        }
    }
}
```

- [ ] **Step 6: 실행기 계약·ApprovalService**

`ApprovalExecutor.java` 전체 교체:

```java
package kr.trendstage.apiadmin.approval;

import kr.trendstage.persistence.entity.ApprovalRequest;

import java.util.Map;

/**
 * approval_requests.action_type별 실행 로직. 구현 @Component를 추가하면 ApprovalService가 자동 등록한다.
 */
public interface ApprovalExecutor {
    /** approval_requests.action_type과 정확히 일치(ActionType.name()). */
    String actionType();

    /**
     * 승인 시 호출. 반환값은 APPROVAL_EXECUTE 감사 로그 detail에 더해진다(예상·실제 차액 등).
     * 예외를 던지면 승인 트랜잭션 전체가 롤백된다 — 요청은 PENDING으로 남아 재시도·반려할 수 있다.
     */
    Map<String, String> execute(ApprovalRequest request);

    /** 반려 시 대상 리소스 원복. 기본은 없음. */
    default void onReject(ApprovalRequest request) {}

    /** 승인 화면 요약 한 줄. */
    default String describe(ApprovalRequest request) { return actionType(); }
}
```

`ParamApplyExecutor.java`: `execute`의 반환형을 `Map<String, String>`으로 바꾸고 끝에 `return Map.of("draftId", draft.getId().toString());`, 그리고 추가:

```java
    @Override
    public String describe(ApprovalRequest request) {
        return "파라미터 적용 · 드래프트 " + request.getTargetRef().toString().substring(0, 8);
    }
```

`ApprovalService.java` 전체 교체:

```java
package kr.trendstage.apiadmin.approval;

import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.repo.ApprovalRequestRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.ApprovalStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 2인 승인 = 요청자 + 승인자 1명(SP3 K1). 승인 1회로 실행기까지 한 트랜잭션에서 끝난다(PENDING → EXECUTED).
 * 승인자 자격(K2): 활성 ADMIN · 요청자 아님 · 승인권 유예(approver_since) 경과.
 */
@Service
public class ApprovalService {

    private static final DateTimeFormatter KST = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final ApprovalRequestRepository approvals;
    private final AdminAccountRepository accounts;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final Map<String, ApprovalExecutor> executors;

    public ApprovalService(ApprovalRequestRepository approvals, AdminAccountRepository accounts,
                           List<ApprovalExecutor> executorBeans, AuditLogService auditLogService, Clock clock) {
        this.approvals = approvals;
        this.accounts = accounts;
        this.auditLogService = auditLogService;
        this.clock = clock;
        this.executors = executorBeans.stream().collect(Collectors.toMap(ApprovalExecutor::actionType, e -> e));
    }

    public List<ApprovalRequest> listPending() {
        return approvals.findByStatusInOrderByCreatedAtAsc(List.of(ApprovalStatus.PENDING));
    }

    public String describe(ApprovalRequest request) {
        ApprovalExecutor executor = executors.get(request.getActionType());
        return executor == null ? request.getActionType() : executor.describe(request);
    }

    @Transactional
    public ApprovalRequest approve(UUID actorId, AdminRole actorRole, UUID id) {
        ApprovalRequest req = requirePending(id);
        if (req.getRequestedBy().equals(actorId)) {
            throw new ApprovalConflictException("요청자 본인은 승인할 수 없습니다");
        }
        AdminAccount approver = accounts.findById(actorId)
                .orElseThrow(() -> new ApprovalConflictException("승인자 계정을 찾을 수 없습니다"));
        Instant now = clock.instant();
        if (!approver.canApproveAt(now)) {
            boolean inGrace = approver.getRole() == AdminRole.ADMIN && approver.isActive();
            throw new ApprovalConflictException(inGrace
                    ? "승인권은 %s부터 생깁니다".formatted(KST.format(approver.getApproverSince()))
                    : "승인 권한이 없는 계정입니다");
        }

        req.approve(actorId, now);
        Map<String, String> executed = executorFor(req.getActionType()).execute(req);
        req.markExecuted();

        auditLogService.record(actorId, actorRole, "APPROVAL_APPROVE", req.getActionType(), req.getTargetRef(),
                Map.of("approvalRequestId", id.toString()));
        Map<String, Object> detail = new HashMap<>(executed);
        detail.put("approvalRequestId", id.toString());
        auditLogService.record(actorId, actorRole, "APPROVAL_EXECUTE", req.getActionType(), req.getTargetRef(), detail);
        return req;
    }

    @Transactional
    public ApprovalRequest reject(UUID actorId, AdminRole actorRole, UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AdminValidationException("반려 사유는 필수입니다");
        }
        ApprovalRequest req = requirePending(id);
        req.reject(clock.instant());
        ApprovalExecutor executor = executors.get(req.getActionType());
        if (executor != null) executor.onReject(req);
        auditLogService.record(actorId, actorRole, "APPROVAL_REJECT", req.getActionType(), req.getTargetRef(),
                Map.of("approvalRequestId", id.toString(), "reason", reason));
        return req;
    }

    private ApprovalRequest requirePending(UUID id) {
        ApprovalRequest req = approvals.findByIdForUpdate(id)
                .orElseThrow(() -> new AdminValidationException("존재하지 않는 승인 요청입니다"));
        if (req.getStatus() != ApprovalStatus.PENDING) {
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

- [ ] **Step 7: 응답·파라미터 요청 경로**

`ApprovalController.java`: 응답 레코드에 `int requiredApprovals, String summary`를 더하고(`approvals` 다음), `toResponse`를 고친다:

```java
    public record ApprovalRequestResponse(String id, String actionType, String targetRef,
                                          String requestedBy, String requestedByName, int approvals, int requiredApprovals,
                                          String status, String reason, String summary, String createdAt, String resolvedAt) {}
```

```java
        int approvalCount = req.getApprover1() != null ? 1 : 0;
        return new ApprovalRequestResponse(
                req.getId().toString(), req.getActionType(), req.getTargetRef().toString(),
                req.getRequestedBy().toString(), requestedByName, approvalCount, 1, req.getStatus().name(),
                extractReason(req.getPayload()), service.describe(req),
                DISPLAY_FORMAT.format(req.getCreatedAt()),
                req.getResolvedAt() == null ? null : DISPLAY_FORMAT.format(req.getResolvedAt()));
```

클래스 주석을 "ADM-620 승인 대기함. 2인 승인 = 요청자 + 승인자 1명(SP3 K1). 확정은 ADMIN, 조회는 ADMIN/AUDITOR."로.

`ParamStudioService.requestApproval`: `approvals.save(new ApprovalRequest("PARAM_APPLY", …))`와 뒤의 `PARAM_APPROVAL_REQUEST` 감사 기록을 아래로 바꾼다(생성자에 `ApprovalGate gate` 추가, 쓰지 않게 된 `ApprovalRequestRepository` 필드·import 삭제):

```java
        ApprovalRequest approval = gate.request(ActionType.PARAM_APPLY, draft.getId(),
                Map.of("reason", reason), actorId, actorRole);
        draft.moveToReview(approval.getId());
        return draft;
```

- [ ] **Step 8: 통과 확인**

Run: `compileJava compileTestJava :app:test --tests "kr.trendstage.approval.*" --tests "kr.trendstage.ContextSmokeTest"`
Expected: PASS (5 tests + smoke).

- [ ] **Step 9: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/approval/ActionType.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/approval/ApprovalGate.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/approval/ApprovalExecutor.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/approval/ApprovalService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/approval/ParamApplyExecutor.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminConflictException.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminApiExceptionHandler.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/ApprovalController.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/params/ParamStudioService.java backend/persistence/src/main/java/kr/trendstage/persistence/entity/ApprovalRequest.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/ApprovalRequestRepository.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/AdminAccountRepository.java backend/app/src/test/java/kr/trendstage/support/Fixtures.java backend/app/src/test/java/kr/trendstage/approval/ApprovalFlowTest.java
git commit -m "feat(api-admin): 2인 승인을 요청자+승인자 1명으로, 승인권 유예 검사, ApprovalGate 신설

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 판정 경로 게이트 — 재판정·항목 VOID 100점 초과는 승인 대기

**Files:**
- Create: `backend/judge/src/main/java/kr/trendstage/judge/AdjustmentPolicy.java`
- Create: `backend/judge/src/main/java/kr/trendstage/judge/JudgeOutcome.java`
- Modify: `backend/judge/src/main/java/kr/trendstage/judge/JudgeService.java`
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/verdict/VerdictRejudgeExecutor.java`
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/verdict/ItemVoidExecutor.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/verdict/{VerdictAdminService,VerdictController}.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/merge/MergeDecisionService.java`
- Modify: `backend/app/src/test/java/kr/trendstage/judge/RejudgeAndVoidTest.java`, `backend/app/src/test/java/kr/trendstage/merge/MergeConcurrencyTest.java`
- Test: `backend/app/src/test/java/kr/trendstage/approval/AdjustmentPolicyTest.java`
- Test: `backend/app/src/test/java/kr/trendstage/approval/VerdictApprovalTest.java`

**Interfaces:**
- Consumes: `ApprovalGate` (`THRESHOLD`, `request`, `hasPendingVerdictChange`, `payload`), `ActionType`, `AdminConflictException`, `ApprovalExecutor` (Task 2); `ScoreLedgerEntry.verdictAdjustment(..., approvalId, approvedByAdminId)` (Task 1)
- Produces:
  - `record AdjustmentPolicy(BigDecimal limit, UUID approvalId, UUID approvedBy)` with `static limitedTo(BigDecimal)`, `static approved(UUID approvalId, UUID approvedBy)`, `boolean exceededBy(BigDecimal total)`
  - `sealed interface JudgeOutcome { record Applied(Optional<Verdict> verdict, BigDecimal adjTotal); record NeedsApproval(BigDecimal adjTotal, VerdictResult expectedResult); }`
  - `JudgeService#rejudge(UUID itemId, String reason, Instant now, AdjustmentPolicy policy): JudgeOutcome`
  - `JudgeService#voidItem(UUID itemId, String reason, Instant now, AdjustmentPolicy policy): JudgeOutcome`
  - `record VerdictActionResult(String status, String verdictId, String approvalRequestId, BigDecimal adjTotal)` — status `APPLIED` | `PENDING_APPROVAL`
  - `VerdictAdminService#voidVerdict(UUID itemId, UUID actorId, AdminRole role, String reason): VerdictActionResult`, `#requestRejudge(...)`: 같은 반환형

- [ ] **Step 1: 실패하는 테스트 작성**

`AdjustmentPolicyTest.java`(컨테이너 없음):

```java
package kr.trendstage.approval;

import kr.trendstage.judge.AdjustmentPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** K3 경계 — 정확히 100은 통과, 그보다 크면 승인. 승인 실행은 한도가 없다. */
class AdjustmentPolicyTest {

    @Test
    void exactly100PassesAndAboveNeedsApproval() {
        AdjustmentPolicy p = AdjustmentPolicy.limitedTo(new BigDecimal("100"));
        assertThat(p.exceededBy(new BigDecimal("100.0000"))).isFalse();
        assertThat(p.exceededBy(new BigDecimal("100.0001"))).isTrue();
        assertThat(p.exceededBy(BigDecimal.ZERO)).isFalse();
    }

    @Test
    void approvedExecutionHasNoLimit() {
        AdjustmentPolicy p = AdjustmentPolicy.approved(UUID.randomUUID(), UUID.randomUUID());
        assertThat(p.exceededBy(new BigDecimal("99999"))).isFalse();
    }
}
```

`VerdictApprovalTest.java`:

```java
package kr.trendstage.approval;

import kr.trendstage.apiadmin.approval.ActionType;
import kr.trendstage.apiadmin.approval.ApprovalGate;
import kr.trendstage.apiadmin.web.AdminConflictException;
import kr.trendstage.judge.JudgeService;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스펙 §10 #3~#8. 판정 점수(HIT L1, m = 0.2):
 *   확신도 50 — 1~4위 60 / 36 / 24 / 12. 3·4위 VOID 후 재판정 → 2명 MISS(−25): 차액 85+61+24+12 = 182
 *   확신도 10 — 12 / 7.2 / 4.8 / 2.4.   3·4위 VOID 후 재판정 → MISS(−5):   차액 17+12.2+4.8+2.4 = 36.4
 */
class VerdictApprovalTest extends AbstractIntegrationTest {

    private static final Instant FIRST_SEEN = Instant.parse("2026-07-10T00:00:00Z");
    private static final Instant JUDGED_AT = FIRST_SEEN.plus(Duration.ofDays(15));

    @Autowired JudgeService judge;
    @Autowired ApprovalGate gate;
    @Autowired TransactionTemplate tx;

    private UUID operator;
    private UUID approver;

    @BeforeEach
    void setUp() {
        operator = fx.admin("OPERATOR");
        approver = fx.admin();
        clock.set(JUDGED_AT.plus(Duration.ofDays(1)));
    }

    @Test
    void rejudgeOver100WaitsForApprovalThenApplies() throws Exception {   // #3
        List<UUID> subs = new ArrayList<>();
        UUID item = judgedHit(50, subs);
        fx.voidSubmission(subs.get(2), JUDGED_AT.plusSeconds(10));
        fx.voidSubmission(subs.get(3), JUDGED_AT.plusSeconds(10));
        int rowsBefore = fx.ledgerRows(item);

        String body = mvc.perform(post("/admin/verdicts/{id}/rejudge", item).with(asAdmin(operator, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"카르텔\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn().getResponse().getContentAsString();
        UUID approvalId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.approvalRequestId"));
        assertThat(fx.ledgerRows(item)).isEqualTo(rowsBefore);
        assertThat(verdictCount(item)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload->>'expectedAdjTotal' FROM approval_requests WHERE id = ?",
                String.class, approvalId)).isEqualTo("182.0000");

        approve(approvalId);

        assertThat(verdictCount(item)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM score_ledger l JOIN verdicts v ON v.id = l.verdict_id "
                + "WHERE v.trend_item_id = ? AND l.kind = 'ADJ' AND l.approval_id = ? AND l.approved_by_admin_id = ?",
                Integer.class, item, approvalId, approver)).isEqualTo(4);
        assertThat(lastExecuteDetail(item, "adjTotal")).isEqualTo("182.0000");
    }

    @Test
    void rejudgeUnder100AppliesImmediately() throws Exception {   // #4
        List<UUID> subs = new ArrayList<>();
        UUID item = judgedHit(10, subs);
        fx.voidSubmission(subs.get(2), JUDGED_AT.plusSeconds(10));
        fx.voidSubmission(subs.get(3), JUDGED_AT.plusSeconds(10));

        mvc.perform(post("/admin/verdicts/{id}/rejudge", item).with(asAdmin(operator, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"카르텔\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.adjTotal").value(36.4));
        assertThat(verdictCount(item)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM score_ledger l JOIN verdicts v ON v.id = l.verdict_id "
                + "WHERE v.trend_item_id = ? AND l.kind = 'ADJ' AND l.approval_id IS NULL", Integer.class, item)).isEqualTo(4);
    }

    @Test
    void pendingRequestBlocksAnyVerdictChangeOnItem() throws Exception {   // #5
        List<UUID> subs = new ArrayList<>();
        UUID item = judgedHit(50, subs);
        mvc.perform(post("/admin/verdicts/{id}/void", item).with(asAdmin(operator, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"허위\"}"))
                .andExpect(status().isAccepted());

        mvc.perform(post("/admin/verdicts/{id}/rejudge", item).with(asAdmin(operator, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"다시\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("approval-pending"));

        // 같은 항목에 요청 두 개는 DB가 막는다(동시 요청 경합의 마지막 방어선)
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> gate.request(ActionType.VERDICT_REJUDGE, item,
                Map.of("reason", "경합"), operator, AdminRole.OPERATOR)))
                .isInstanceOf(AdminConflictException.class);
    }

    @Test
    void voidOver100WaitsForApprovalThenZeroesLedger() throws Exception {   // #6
        List<UUID> subs = new ArrayList<>();
        List<UUID> users = new ArrayList<>();
        UUID item = judgedHit(50, subs, users);
        String body = mvc.perform(post("/admin/verdicts/{id}/void", item).with(asAdmin(operator, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"허위 제보\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(fx.itemState(item)).isEqualTo("RESOLVED");

        approve(UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.approvalRequestId")));

        assertThat(fx.itemState(item)).isEqualTo("VOID");
        users.forEach(u -> assertThat(fx.ledgerSum(u, item)).isEqualByComparingTo("0"));
        subs.forEach(s -> assertThat(fx.submissionResult(s)).isEqualTo("VOID"));
    }

    @Test
    void mergeQueueVoidOverLimitIsRejected() throws Exception {   // #7
        List<UUID> subs = new ArrayList<>();
        UUID item = judgedHit(50, subs);
        UUID other = fx.item(FIRST_SEEN.plus(Duration.ofDays(20)));
        UUID queueId = fx.mergeQueueEntry(item, other, 0.80);

        mvc.perform(post("/admin/merge-queue/{id}/void", queueId).with(asAdmin(operator, AdminRole.OPERATOR))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"허위\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("void-needs-approval"));
        assertThat(fx.queueStatus(queueId)).isEqualTo("PENDING");
        assertThat(fx.itemState(item)).isEqualTo("RESOLVED");
    }

    @Test
    void approvalRecomputesWithInputsAtApprovalTime() throws Exception {   // #8
        List<UUID> subs = new ArrayList<>();
        UUID item = judgedHit(50, subs);
        fx.voidSubmission(subs.get(2), JUDGED_AT.plusSeconds(10));
        fx.voidSubmission(subs.get(3), JUDGED_AT.plusSeconds(10));
        String body = mvc.perform(post("/admin/verdicts/{id}/rejudge", item).with(asAdmin(operator, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"카르텔\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        fx.voidSubmission(subs.get(1), JUDGED_AT.plusSeconds(20));   // 승인 대기 중 한 건 더 VOID

        approve(UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.approvalRequestId")));

        assertThat(lastExecuteDetail(item, "expectedAdjTotal")).isEqualTo("182.0000");
        assertThat(lastExecuteDetail(item, "adjTotal")).isEqualTo("157.0000");   // 85 + 36 + 24 + 12
    }

    private UUID judgedHit(int confidence, List<UUID> subsOut) {
        return judgedHit(confidence, subsOut, new ArrayList<>());
    }

    /** 실유저 4명(선점 1~4위)이 같은 확신도로 제보한 항목을 HIT L1로 판정한다. */
    private UUID judgedHit(int confidence, List<UUID> subsOut, List<UUID> usersOut) {
        UUID item = fx.item(FIRST_SEEN);
        for (int i = 0; i < 4; i++) {
            UUID u = fx.user();
            usersOut.add(u);
            subsOut.add(fx.submission(u, item, confidence, FIRST_SEEN.plusSeconds(1 + i)));
        }
        judge.closeDue(JUDGED_AT);
        judge.judge(item, JUDGED_AT);
        assertThat(jdbc.queryForObject("SELECT result::text FROM verdicts WHERE trend_item_id = ?", String.class, item))
                .isEqualTo("HIT");
        return item;
    }

    private void approve(UUID approvalId) throws Exception {
        mvc.perform(post("/admin/approvals/{id}/approve", approvalId).with(asAdmin(approver, AdminRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));
    }

    private int verdictCount(UUID item) {
        return jdbc.queryForObject("SELECT count(*) FROM verdicts WHERE trend_item_id = ?", Integer.class, item);
    }

    private String lastExecuteDetail(UUID item, String key) {
        return jdbc.queryForObject("SELECT detail->>? FROM admin_audit_log WHERE action = 'APPROVAL_EXECUTE' "
                + "AND target_id = ? ORDER BY id DESC LIMIT 1", String.class, key, item);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `:app:test --tests "kr.trendstage.approval.*"`
Expected: 컴파일 실패 — `kr.trendstage.judge.AdjustmentPolicy` 없음.

- [ ] **Step 3: judge 모듈**

`AdjustmentPolicy.java`:

```java
package kr.trendstage.judge;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * 재판정·항목 VOID가 만드는 원장 변동(ADJ)을 어떻게 다룰지(SP3 §3.1). 판정 모듈은 승인을 모른다 —
 * 한도를 넘는지만 알려 주고, 승인 실행이면 ADJ 행에 승인 정보를 남긴다.
 *
 * @param limit      차액 절댓값 합의 한도. null = 한도 없음(승인된 실행)
 * @param approvalId 승인 요청 id(승인 실행일 때만)
 * @param approvedBy 승인 관리자 id(승인 실행일 때만)
 */
public record AdjustmentPolicy(BigDecimal limit, UUID approvalId, UUID approvedBy) {

    public static AdjustmentPolicy limitedTo(BigDecimal limit) {
        return new AdjustmentPolicy(Objects.requireNonNull(limit), null, null);
    }

    public static AdjustmentPolicy approved(UUID approvalId, UUID approvedBy) {
        return new AdjustmentPolicy(null, Objects.requireNonNull(approvalId), Objects.requireNonNull(approvedBy));
    }

    /** 한도를 넘는가 — 정확히 한도와 같으면 통과(K3). */
    public boolean exceededBy(BigDecimal total) {
        return limit != null && total.compareTo(limit) > 0;
    }
}
```

`JudgeOutcome.java`:

```java
package kr.trendstage.judge;

import kr.trendstage.domain.verdict.VerdictResult;
import kr.trendstage.persistence.entity.Verdict;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 재판정·항목 VOID의 결과. 예외가 아니라 값으로 돌려준다 — 예외가 @Transactional 프록시를 지나면
 * 호출 측 트랜잭션이 rollback-only가 되어 승인 요청을 저장할 수 없다(SP3 §3.1).
 */
public sealed interface JudgeOutcome {

    /** 반영됨. verdict는 새 판정(판정 전 항목의 VOID면 비어 있음). adjTotal = 기록한 ADJ 절댓값 합. */
    record Applied(Optional<Verdict> verdict, BigDecimal adjTotal) implements JudgeOutcome {}

    /** 한도 초과 — 아무것도 쓰지 않았다. */
    record NeedsApproval(BigDecimal adjTotal, VerdictResult expectedResult) implements JudgeOutcome {}
}
```

`JudgeService.java`:
- `settle`을 지우고 아래 셋으로 바꾼다(import `java.util.TreeMap`, `java.util.LinkedHashMap`):

```java
    /** 제보 단위 차액 한 줄. */
    record Diff(UUID userId, BigDecimal amount) {}

    /** 체인 원장을 제보 단위로 합산해 새 라인과의 차액을 계산한다(쓰기 없음). 0인 제보는 빠진다. */
    private Map<UUID, Diff> diffs(Chain chain, List<LedgerLine> lines) {
        Map<UUID, BigDecimal> before = new TreeMap<>();
        Map<UUID, UUID> owner = new TreeMap<>();
        for (ScoreLedgerEntry e : ledger.findByVerdictIdIn(chain.verdictIds())) {
            if (e.getSubmissionId() == null) continue;
            before.merge(e.getSubmissionId(), e.getDelta(), BigDecimal::add);
            owner.put(e.getSubmissionId(), e.getUserId());
        }
        Map<UUID, BigDecimal> after = new TreeMap<>();
        for (LedgerLine line : lines) {
            after.put(line.submissionId(), amount(line.delta()));
            owner.put(line.submissionId(), line.userId());
        }
        Set<UUID> touched = new java.util.TreeSet<>(before.keySet());
        touched.addAll(after.keySet());
        Map<UUID, Diff> out = new LinkedHashMap<>();
        for (UUID submissionId : touched) {
            BigDecimal diff = after.getOrDefault(submissionId, BigDecimal.ZERO)
                    .subtract(before.getOrDefault(submissionId, BigDecimal.ZERO));
            if (diff.signum() != 0) out.put(submissionId, new Diff(owner.get(submissionId), diff));
        }
        return out;
    }

    /** 차액 절댓값 합(K3) — 원장 자릿수(4). */
    static BigDecimal total(Map<UUID, Diff> diffs) {
        return diffs.values().stream().map(d -> d.amount().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(4, RoundingMode.HALF_UP);
    }

    /** 차액을 ADJ로 남긴다 — 원 판정과 같은 감쇠 기준(J1). 승인 실행이면 승인 정보를 채운다. */
    private void writeAdjustments(Map<UUID, Diff> diffs, Chain chain, Verdict next, String reason, AdjustmentPolicy policy) {
        diffs.forEach((submissionId, d) -> ledger.save(ScoreLedgerEntry.verdictAdjustment(d.userId(), submissionId,
                next.getId(), d.amount(), reason, chain.params().halflifeDays, chain.anchor(),
                policy.approvalId(), policy.approvedBy())));
    }
```

- `rejudge`·`voidItem`을 교체한다:

```java
    /**
     * 재판정 — 원 판정 때 동결한 파라미터로 다시 계산해 supersede 판정을 쌓고, 원장은 제보 단위 차액만 ADJ로 남긴다(J2).
     * 차액 절댓값 합이 policy 한도를 넘으면 아무것도 쓰지 않고 NeedsApproval(SP3 K4).
     */
    @Transactional
    public JudgeOutcome rejudge(UUID itemId, String reason, Instant now, AdjustmentPolicy policy) {
        TrendItem item = lock(itemId);
        Verdict current = verdicts.findCurrentByTrendItemId(itemId)
                .orElseThrow(() -> new JudgeRejectedException("판정 이력이 없는 항목입니다"));
        if (current.getResult() == VerdictResult.VOID) {
            throw new JudgeConflictException("이미 VOID 처리된 항목은 다시 판정할 수 없습니다");
        }
        Chain chain = chainOf(itemId);
        Inputs in = collect(item, deadlineOf(item));
        VerdictPlan plan = VerdictComputation.run(in.signal(), in.refs(), chain.params());
        Map<UUID, Diff> diffs = diffs(chain, plan.ledgerLines());
        BigDecimal total = total(diffs);
        if (policy.exceededBy(total)) {
            return new JudgeOutcome.NeedsApproval(total, plan.result());
        }

        Verdict next = supersede(new Verdict(itemId, plan.result(), plan.reach(), scoreT(plan), now,
                evidence(plan, in, chain.params(), current.getId(), reason), current.getId()));
        writeAdjustments(diffs, chain, next, "재판정 · " + reason, policy);
        for (Submission s : in.submissions()) {
            s.markResult(toSubmissionResult(plan.result()), now);
        }
        if (plan.result() == VerdictResult.VOID) {
            item.transitionTo(TrendState.VOID);
        }
        return new JudgeOutcome.Applied(Optional.of(next), total);
    }

    /**
     * 항목 VOID(P5) — ADM-100 큐 VOID와 ADM-200 VOID의 공통 경로. 판정된 항목이면 원장을 제보 단위로 전액 상쇄하는데,
     * 그 합이 policy 한도를 넘으면 아무것도 쓰지 않고 NeedsApproval. 판정 전 항목은 변동 0이라 항상 반영.
     */
    @Transactional
    public JudgeOutcome voidItem(UUID itemId, String reason, Instant now, AdjustmentPolicy policy) {
        TrendItem item = lock(itemId);
        if (item.getState() == TrendState.MERGED || item.getState() == TrendState.VOID) {
            throw new JudgeConflictException("이미 병합됐거나 VOID된 항목입니다");
        }
        Optional<Verdict> current = verdicts.findCurrentByTrendItemId(itemId);
        Chain chain = current.isPresent() ? chainOf(itemId) : null;
        Map<UUID, Diff> diffs = chain == null ? Map.of() : diffs(chain, List.of());
        BigDecimal total = total(diffs);
        if (policy.exceededBy(total)) {
            return new JudgeOutcome.NeedsApproval(total, VerdictResult.VOID);
        }

        for (Submission s : submissions.findByTrendItemIdAndResultNot(itemId, SubmissionResult.VOID)) {
            s.voidOut(now);
        }
        Optional<Verdict> voided = Optional.empty();
        if (current.isPresent()) {
            Verdict next = supersede(new Verdict(itemId, VerdictResult.VOID, null, null, now,
                    write(new VerdictEvidence("VOID", null, null, null, null, null, 0, 0, Map.of(),
                            current.get().getId(), reason)),
                    current.get().getId()));
            writeAdjustments(diffs, chain, next, "VOID · " + (reason == null ? "" : reason), policy);
            voided = Optional.of(next);
        }
        item.transitionTo(TrendState.VOID);
        return new JudgeOutcome.Applied(voided, total);
    }
```

- 쓰지 않게 된 `HashMap`·`HashSet` import가 있으면 지운다.

- [ ] **Step 4: 기존 테스트 호출부를 새 시그니처로**

`RejudgeAndVoidTest.java`에 상수와 헬퍼를 추가한다(import `kr.trendstage.judge.AdjustmentPolicy`, `kr.trendstage.judge.JudgeOutcome`, `java.math.BigDecimal`, `java.util.Optional`):

```java
    private static final AdjustmentPolicy NO_LIMIT = AdjustmentPolicy.limitedTo(new BigDecimal("999999"));

    private static Optional<Verdict> applied(JudgeOutcome o) {
        return ((JudgeOutcome.Applied) o).verdict();
    }
```

그리고 호출을 바꾼다:
- `judge.rejudge(item, "카르텔", JUDGED_AT.plus(Duration.ofDays(1)));` → `judge.rejudge(item, "카르텔", JUDGED_AT.plus(Duration.ofDays(1)), NO_LIMIT);` (재확인 호출도 같은 방식)
- `Verdict next = judge.rejudge(item, "입력 변화 없음", JUDGED_AT.plus(Duration.ofDays(1)));` → `Verdict next = applied(judge.rejudge(item, "입력 변화 없음", JUDGED_AT.plus(Duration.ofDays(1)), NO_LIMIT)).orElseThrow();`
- `assertThat(judge.voidItem(item, "허위 제보", voidAt)).isPresent();` → `assertThat(applied(judge.voidItem(item, "허위 제보", voidAt, NO_LIMIT))).isPresent();`
- 예외 단언 두 줄: 마지막 인자로 `, NO_LIMIT` 추가
- `assertThat(judge.voidItem(item, null, FIRST_SEEN.plus(Duration.ofDays(1)))).isEmpty();` → `assertThat(applied(judge.voidItem(item, null, FIRST_SEEN.plus(Duration.ofDays(1)), NO_LIMIT))).isEmpty();`

`MergeConcurrencyTest.java` 58행: `judge.voidItem(l, "VOID", now)` → `judge.voidItem(l, "VOID", now, AdjustmentPolicy.limitedTo(new BigDecimal("999999")))` (import 두 개).

- [ ] **Step 5: ADM-200 경로**

`VerdictAdminService.java` — 생성자에 `ApprovalGate gate` 추가, 결과 레코드 추가, `voidVerdict`·`requestRejudge` 교체(import `kr.trendstage.apiadmin.approval.*`, `kr.trendstage.apiadmin.web.AdminConflictException`, `kr.trendstage.judge.AdjustmentPolicy`, `kr.trendstage.judge.JudgeOutcome`, `java.math.BigDecimal`):

```java
    /** ADM-200 VOID·재판정 결과. status = APPLIED(200) | PENDING_APPROVAL(202). */
    public record VerdictActionResult(String status, String verdictId, String approvalRequestId, BigDecimal adjTotal) {
        public boolean pending() { return "PENDING_APPROVAL".equals(status); }
    }

    @Transactional
    public VerdictActionResult voidVerdict(UUID trendItemId, UUID actorId, AdminRole actorRole, String reason) {
        requireReason(reason);
        Verdict previous = lockAndRequireJudged(trendItemId);
        JudgeOutcome outcome = judgeService.voidItem(trendItemId, reason, clock.instant(),
                AdjustmentPolicy.limitedTo(ApprovalGate.THRESHOLD));
        if (outcome instanceof JudgeOutcome.NeedsApproval n) {
            return pending(ActionType.ITEM_VOID, trendItemId, reason, n, actorId, actorRole);
        }
        JudgeOutcome.Applied a = (JudgeOutcome.Applied) outcome;
        Verdict voided = a.verdict().orElseThrow(() -> new IllegalStateException("VOID 판정이 기록되지 않았습니다: " + trendItemId));
        auditLogService.record(actorId, actorRole, "VERDICT_VOID", "TREND_ITEM", trendItemId, Map.of(
                "reason", reason,
                "previousVerdictId", previous.getId().toString(),
                "newVerdictId", voided.getId().toString(),
                "adjTotal", a.adjTotal().toPlainString()));
        return new VerdictActionResult("APPLIED", voided.getId().toString(), null, a.adjTotal());
    }

    @Transactional
    public VerdictActionResult requestRejudge(UUID trendItemId, UUID actorId, AdminRole actorRole, String reason) {
        requireReason(reason);
        Verdict previous = lockAndRequireJudged(trendItemId);
        JudgeOutcome outcome = judgeService.rejudge(trendItemId, reason, clock.instant(),
                AdjustmentPolicy.limitedTo(ApprovalGate.THRESHOLD));
        if (outcome instanceof JudgeOutcome.NeedsApproval n) {
            return pending(ActionType.VERDICT_REJUDGE, trendItemId, reason, n, actorId, actorRole);
        }
        JudgeOutcome.Applied a = (JudgeOutcome.Applied) outcome;
        Verdict next = a.verdict().orElseThrow();
        auditLogService.record(actorId, actorRole, "VERDICT_REJUDGE", "TREND_ITEM", trendItemId, Map.of(
                "reason", reason,
                "previousVerdictId", previous.getId().toString(),
                "newVerdictId", next.getId().toString(),
                "newResult", next.getResult().name(),
                "adjTotal", a.adjTotal().toPlainString()));
        return new VerdictActionResult("APPLIED", next.getId().toString(), null, a.adjTotal());
    }

    /** 항목 행 잠금 → 대기 요청 확인(K5) → 현행 판정. 잠금 아래에서 확인해야 요청 생성과 직접 반영이 엇갈리지 않는다. */
    private Verdict lockAndRequireJudged(UUID trendItemId) {
        trendItems.findByIdForUpdate(trendItemId)
                .orElseThrow(() -> new AdminValidationException("존재하지 않는 항목입니다"));
        if (gate.hasPendingVerdictChange(trendItemId)) {
            throw new AdminConflictException("approval-pending", "이 항목에 대기 중인 승인 요청이 있습니다 — 승인·반려 후 다시 시도하세요");
        }
        return verdicts.findCurrentByTrendItemId(trendItemId)
                .orElseThrow(() -> new AdminValidationException("판정 이력이 없는 항목입니다"));
    }

    private VerdictActionResult pending(ActionType type, UUID trendItemId, String reason, JudgeOutcome.NeedsApproval n,
                                        UUID actorId, AdminRole actorRole) {
        ApprovalRequest r = gate.request(type, trendItemId, Map.of(
                "reason", reason,
                "expectedAdjTotal", n.adjTotal().toPlainString(),
                "expectedResult", n.expectedResult().name()), actorId, actorRole);
        return new VerdictActionResult("PENDING_APPROVAL", null, r.getId().toString(), n.adjTotal());
    }
```

(`ApprovalRequest` import: `kr.trendstage.persistence.entity.ApprovalRequest`.)

`VerdictController.java` — 두 엔드포인트를 교체(import `org.springframework.http.ResponseEntity`, `org.springframework.http.HttpStatus`):

```java
    @PostMapping("/{trendItemId}/void")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ResponseEntity<VerdictAdminService.VerdictActionResult> voidVerdict(@PathVariable UUID trendItemId,
            @RequestBody ReasonRequest req, @AuthenticationPrincipal AdminPrincipal actor) {
        return respond(verdictAdminService.voidVerdict(trendItemId, actor.id(), actor.role(), req.reason()));
    }

    @PostMapping("/{trendItemId}/rejudge")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ResponseEntity<VerdictAdminService.VerdictActionResult> rejudge(@PathVariable UUID trendItemId,
            @RequestBody ReasonRequest req, @AuthenticationPrincipal AdminPrincipal actor) {
        return respond(verdictAdminService.requestRejudge(trendItemId, actor.id(), actor.role(), req.reason()));
    }

    private static ResponseEntity<VerdictAdminService.VerdictActionResult> respond(VerdictAdminService.VerdictActionResult r) {
        return ResponseEntity.status(r.pending() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(r);
    }
```

- [ ] **Step 6: 판정 실행기 두 개**

`VerdictRejudgeExecutor.java`:

```java
package kr.trendstage.apiadmin.verdict;

import kr.trendstage.apiadmin.approval.ActionType;
import kr.trendstage.apiadmin.approval.ApprovalExecutor;
import kr.trendstage.apiadmin.approval.ApprovalGate;
import kr.trendstage.judge.AdjustmentPolicy;
import kr.trendstage.judge.JudgeOutcome;
import kr.trendstage.judge.JudgeService;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.repo.TrendItemRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;

/** VERDICT_REJUDGE 승인 실행 — 그 시점 입력으로 다시 계산해 한도 없이 반영하고 예상·실제 차액을 남긴다(K4). */
@Component
public class VerdictRejudgeExecutor implements ApprovalExecutor {

    private final JudgeService judgeService;
    private final ApprovalGate gate;
    private final TrendItemRepository trendItems;
    private final Clock clock;

    public VerdictRejudgeExecutor(JudgeService judgeService, ApprovalGate gate, TrendItemRepository trendItems, Clock clock) {
        this.judgeService = judgeService;
        this.gate = gate;
        this.trendItems = trendItems;
        this.clock = clock;
    }

    @Override public String actionType() { return ActionType.VERDICT_REJUDGE.name(); }

    @Override
    public Map<String, String> execute(ApprovalRequest request) {
        Map<String, String> p = gate.payload(request);
        JudgeOutcome.Applied a = (JudgeOutcome.Applied) judgeService.rejudge(request.getTargetRef(), p.get("reason"),
                clock.instant(), AdjustmentPolicy.approved(request.getId(), request.getApprover1()));
        return Map.of(
                "expectedAdjTotal", p.getOrDefault("expectedAdjTotal", ""),
                "adjTotal", a.adjTotal().toPlainString(),
                "newVerdictId", a.verdict().map(v -> v.getId().toString()).orElse(""),
                "newResult", a.verdict().map(v -> v.getResult().name()).orElse(""));
    }

    @Override
    public String describe(ApprovalRequest request) {
        Map<String, String> p = gate.payload(request);
        String name = trendItems.findById(request.getTargetRef()).map(i -> i.getCanonicalName()).orElse("(삭제된 항목)");
        return "재판정 · %s · 예상 차액 %s점 · 사유: %s".formatted(name, p.get("expectedAdjTotal"), p.get("reason"));
    }
}
```

`ItemVoidExecutor.java`: 같은 구조로 `actionType()` = `ActionType.ITEM_VOID.name()`, `execute`는 `judgeService.voidItem(...)`을 부르고 `newResult`를 빼고 같은 키를 돌려준다. `describe`는 `"판정 VOID · %s · 예상 차액 %s점 · 사유: %s"`.

```java
    @Override
    public Map<String, String> execute(ApprovalRequest request) {
        Map<String, String> p = gate.payload(request);
        JudgeOutcome.Applied a = (JudgeOutcome.Applied) judgeService.voidItem(request.getTargetRef(), p.get("reason"),
                clock.instant(), AdjustmentPolicy.approved(request.getId(), request.getApprover1()));
        return Map.of(
                "expectedAdjTotal", p.getOrDefault("expectedAdjTotal", ""),
                "adjTotal", a.adjTotal().toPlainString(),
                "newVerdictId", a.verdict().map(v -> v.getId().toString()).orElse(""));
    }
```

- [ ] **Step 7: ADM-100 큐 VOID(K8)**

`MergeDecisionService.java` — 생성자에 `ApprovalGate gate` 추가, `case VOID ->` 블록의 `judgeService.voidItem(newId, reason, now);`를 아래로(import `kr.trendstage.apiadmin.approval.ApprovalGate`, `kr.trendstage.apiadmin.web.AdminConflictException`, `kr.trendstage.judge.AdjustmentPolicy`, `kr.trendstage.judge.JudgeOutcome`):

```java
                if (gate.hasPendingVerdictChange(newId)) {
                    throw new AdminConflictException("approval-pending", "이 항목에 대기 중인 승인 요청이 있습니다 — 승인·반려 후 다시 시도하세요");
                }
                JudgeOutcome outcome = judgeService.voidItem(newId, reason, now, AdjustmentPolicy.limitedTo(ApprovalGate.THRESHOLD));
                if (outcome instanceof JudgeOutcome.NeedsApproval n) {
                    // 큐 결정은 멱등키로 즉시 확정되는 흐름이라 승인 대기를 끼우지 않는다(K8) — 큐 항목은 PENDING으로 남는다
                    throw new AdminConflictException("void-needs-approval",
                            "판정된 항목이라 차액 %s점 — ADM-200 판정 관리에서 VOID하세요(2인 승인)"
                                    .formatted(n.adjTotal().stripTrailingZeros().toPlainString()));
                }
```

- [ ] **Step 8: 통과 확인**

Run: `compileJava compileTestJava :app:test --tests "kr.trendstage.approval.*" --tests "kr.trendstage.judge.*" --tests "kr.trendstage.merge.*"`
Expected: PASS. (기존 judge·merge 테스트는 새 시그니처로만 바뀌었다.)

- [ ] **Step 9: 커밋**

```bash
git add backend/judge/src/main/java/kr/trendstage/judge/AdjustmentPolicy.java backend/judge/src/main/java/kr/trendstage/judge/JudgeOutcome.java backend/judge/src/main/java/kr/trendstage/judge/JudgeService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/verdict/VerdictRejudgeExecutor.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/verdict/ItemVoidExecutor.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/verdict/VerdictAdminService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/verdict/VerdictController.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/merge/MergeDecisionService.java backend/app/src/test/java/kr/trendstage/judge/RejudgeAndVoidTest.java backend/app/src/test/java/kr/trendstage/merge/MergeConcurrencyTest.java backend/app/src/test/java/kr/trendstage/approval/AdjustmentPolicyTest.java backend/app/src/test/java/kr/trendstage/approval/VerdictApprovalTest.java
git commit -m "feat(judge,api-admin): 재판정·항목 VOID 차액 100점 초과는 승인 대기(202), 승인 시 재계산 실행

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 4: ADM-311 유저 원장 API + 수동 ADJ

**Files:**
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/user/AdminUserService.java`
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/user/LedgerAdjustService.java`
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/user/LedgerAdjustExecutor.java`
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminUserController.java`
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminNotFoundException.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminApiExceptionHandler.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/UserRepository.java`
- Test: `backend/app/src/test/java/kr/trendstage/admin/AdminUserApiTest.java`

**Interfaces:**
- Consumes: `ApprovalGate` (`THRESHOLD`, `exceeds`, `request`, `payload`), `ActionType.LEDGER_ADJ`, `ApprovalExecutor` (Task 2); `ScoreLedgerEntry.adjustment(userId, delta, reason, approvalId, approvedByAdminId, halflifeDays, anchor)` (Task 1); SP1의 `GradeInputsReader#read(UUID, Instant): GradeInputs(judgedCount, hitInWindow, missInWindow, activeScore)`, `TrustIndex.compute(int hit, int miss, ParameterSet)`, `CurrentParameterSetResolver#resolve()`
- Produces:
  - `GET /admin/users?handle=` → `List<AdminUserService.UserHit(String id, String handle, String grade, String joinedAt)>`
  - `GET /admin/users/{id}` → `AdminUserService.AdminUserDetail(String id, String handle, String status, String joinedAt, String grade, String gradeComputedAt, double trustIndex, double activeScore, int judgedCount, int hitInWindow, int missInWindow, List<String> basis, long abuseFlagCount, List<LedgerRow> ledger)`, `LedgerRow(String id, String createdAt, String kind, double delta, String reason, String trendItemName, String approvedBy)`
  - `POST /admin/users/{id}/ledger-adjustments {amount, reason}` → `LedgerAdjustService.AdjustResult(String status, String ledgerId, String approvalRequestId, BigDecimal amount)`; 201(`APPLIED`) | 202(`PENDING_APPROVAL`)
  - `AdminNotFoundException(String)` → 404

- [ ] **Step 1: 실패하는 테스트 작성**

`AdminUserApiTest.java`:

```java
package kr.trendstage.admin;

import kr.trendstage.apipublic.service.MeService;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스펙 §10 #14·#20 + Review Focus 2. */
class AdminUserApiTest extends AbstractIntegrationTest {

    @Autowired MeService me;

    @Test
    void detailMatchesAppGradeInputs() throws Exception {   // #20
        Instant now = Instant.parse("2026-09-21T00:00:00Z");
        clock.set(now);
        UUID u = fx.user();
        for (int i = 0; i < 3; i++) fx.judgedSubmission(u, "HIT", now.minus(Duration.ofDays(5)));
        fx.judgedSubmission(u, "MISS", now.minus(Duration.ofDays(5)));
        fx.ledgerRow(u, 40, 90, now);
        var app = me.grade(u);

        mvc.perform(get("/admin/users/{id}", u).with(asAdmin(fx.admin("AUDITOR"), AdminRole.AUDITOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trustIndex").value(closeTo(app.trustIndex(), 1e-9)))
                .andExpect(jsonPath("$.activeScore").value(closeTo(app.activeScore(), 1e-9)))
                .andExpect(jsonPath("$.judgedCount").value(4))
                .andExpect(jsonPath("$.hitInWindow").value(3))
                .andExpect(jsonPath("$.ledger", hasSize(1)))
                .andExpect(jsonPath("$.basis[0]").value(org.hamcrest.Matchers.startsWith("TI ")));
    }

    @Test
    void searchByHandlePrefix() throws Exception {
        UUID u = fx.user();
        String handle = jdbc.queryForObject("SELECT handle FROM users WHERE id = ?", String.class, u);
        // 전체 핸들을 앞부분으로 — 공유 DB에서 짧은 접두어는 상위 20건 밖으로 밀릴 수 있다
        mvc.perform(get("/admin/users").param("handle", handle.toUpperCase()).with(asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + u + "')]", hasSize(1)));
    }

    @Test
    void unknownUserIs404() throws Exception {
        mvc.perform(get("/admin/users/{id}", UUID.randomUUID()).with(asAdmin(fx.admin(), AdminRole.ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void manualAdjustmentByRoleAndAmount() throws Exception {   // #14
        UUID u = fx.user();
        UUID admin = fx.admin();
        adjust(u, admin, AdminRole.ADMIN, "50").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("APPLIED"));
        adjust(u, admin, AdminRole.ADMIN, "150").andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        UUID operator = fx.admin("OPERATOR");
        String body = adjust(u, operator, AdminRole.OPERATOR, "10").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM score_ledger WHERE user_id = ?", Integer.class, u)).isEqualTo(1);

        UUID approvalId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.approvalRequestId"));
        UUID approver = fx.admin();
        mvc.perform(post("/admin/approvals/{id}/approve", approvalId).with(asAdmin(approver, AdminRole.ADMIN)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM score_ledger WHERE user_id = ? AND kind = 'ADJ' "
                + "AND verdict_id IS NULL AND approval_id = ? AND approved_by_admin_id = ? AND delta = 10",
                Integer.class, u, approvalId, approver)).isEqualTo(1);

        mvc.perform(get("/admin/users/{id}", u).with(asAdmin(admin, AdminRole.ADMIN)))
                .andExpect(jsonPath("$.ledger[?(@.approvedBy)]", hasSize(1)));
    }

    @Test
    void manualAdjustmentValidatesAmount() throws Exception {   // Review Focus 2
        UUID u = fx.user();
        UUID admin = fx.admin();
        adjust(u, admin, AdminRole.ADMIN, "0").andExpect(status().isUnprocessableEntity());
        adjust(u, admin, AdminRole.ADMIN, "10000").andExpect(status().isUnprocessableEntity());
        adjust(u, admin, AdminRole.ADMIN, "1.23456").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT delta FROM score_ledger WHERE user_id = ?", java.math.BigDecimal.class, u))
                .isEqualByComparingTo("1.2346");
        mvc.perform(post("/admin/users/{id}/ledger-adjustments", u).with(asAdmin(admin, AdminRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5,\"reason\":\" \"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    private org.springframework.test.web.servlet.ResultActions adjust(UUID user, UUID actor, AdminRole role, String amount) throws Exception {
        return mvc.perform(post("/admin/users/{id}/ledger-adjustments", user).with(asAdmin(actor, role))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + ",\"reason\":\"수집 장애 보정\"}"));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `:app:test --tests "kr.trendstage.admin.AdminUserApiTest"`
Expected: FAIL — `/admin/users/**` 404(컨트롤러 없음).

- [ ] **Step 3: 저장소·예외**

`UserRepository.java`에 추가:

```java
    /** ADM-311 진입 — 핸들 앞부분 일치(대소문자 무시), 최대 20건. */
    List<UserAccount> findTop20ByHandleStartingWithIgnoreCaseOrderByHandleAsc(String prefix);
```

(import `java.util.List`)

`AdminNotFoundException.java`:

```java
package kr.trendstage.apiadmin.web;

/** 404 — 조회 대상이 없음. */
public class AdminNotFoundException extends RuntimeException {
    public AdminNotFoundException(String message) { super(message); }
}
```

`AdminApiExceptionHandler.java`:

```java
    @ExceptionHandler(AdminNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handle(AdminNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem(404, e.getMessage()));
    }
```

- [ ] **Step 4: 조회 서비스**

`AdminUserService.java`:

```java
package kr.trendstage.apiadmin.user;

import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.apiadmin.web.AdminNotFoundException;
import kr.trendstage.domain.grade.Grade;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.TrustIndex;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.entity.ScoreLedgerEntry;
import kr.trendstage.persistence.entity.UserAccount;
import kr.trendstage.persistence.entity.UserGrade;
import kr.trendstage.persistence.grade.GradeInputsReader;
import kr.trendstage.persistence.params.CurrentParameterSetResolver;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.repo.ScoreLedgerRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.UserGradeRepository;
import kr.trendstage.persistence.repo.UserRepository;
import kr.trendstage.persistence.repo.VerdictRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ADM-311 유저 상세·원장. 이의 제기 대응의 근거 화면이라 앱(/v1/me)과 같은 입력(GradeInputsReader)으로 계산하고
 * 산정 근거 문장을 붙인다(관리자 API 규약). 원장은 수정·삭제하지 않는다(R2) — 정정은 LedgerAdjustService의 ADJ뿐.
 */
@Service
public class AdminUserService {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final UserRepository users;
    private final UserGradeRepository grades;
    private final ScoreLedgerRepository ledger;
    private final VerdictRepository verdicts;
    private final TrendItemRepository trendItems;
    private final AdminAccountRepository accounts;
    private final GradeInputsReader gradeInputs;
    private final CurrentParameterSetResolver params;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AdminUserService(UserRepository users, UserGradeRepository grades, ScoreLedgerRepository ledger,
                            VerdictRepository verdicts, TrendItemRepository trendItems, AdminAccountRepository accounts,
                            GradeInputsReader gradeInputs, CurrentParameterSetResolver params, JdbcTemplate jdbc, Clock clock) {
        this.users = users; this.grades = grades; this.ledger = ledger; this.verdicts = verdicts;
        this.trendItems = trendItems; this.accounts = accounts; this.gradeInputs = gradeInputs;
        this.params = params; this.jdbc = jdbc; this.clock = clock;
    }

    public record UserHit(String id, String handle, String grade, String joinedAt) {}
    public record LedgerRow(String id, String createdAt, String kind, double delta, String reason,
                            String trendItemName, String approvedBy) {}
    public record AdminUserDetail(String id, String handle, String status, String joinedAt, String grade,
                                  String gradeComputedAt, double trustIndex, double activeScore, int judgedCount,
                                  int hitInWindow, int missInWindow, List<String> basis, long abuseFlagCount,
                                  List<LedgerRow> ledger) {}

    @Transactional(readOnly = true)
    public List<UserHit> search(String handlePrefix) {
        if (handlePrefix == null || handlePrefix.isBlank()) {
            throw new AdminValidationException("핸들을 입력하세요");
        }
        return users.findTop20ByHandleStartingWithIgnoreCaseOrderByHandleAsc(handlePrefix.strip()).stream()
                .map(u -> new UserHit(u.getId().toString(), u.getHandle(), gradeOf(u.getId()).name(), DISPLAY.format(u.getJoinedAt())))
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminUserDetail detail(UUID userId) {
        UserAccount u = users.findById(userId).orElseThrow(() -> new AdminNotFoundException("존재하지 않는 유저입니다"));
        Instant now = clock.instant();
        GradeInputsReader.GradeInputs in = gradeInputs.read(userId, now);
        ParameterSet p = params.resolve();
        double ti = TrustIndex.compute(in.hitInWindow(), in.missInWindow(), p);
        UserGrade snapshot = grades.findTopByUserIdOrderByComputedAtDesc(userId).orElse(null);

        List<ScoreLedgerEntry> rows = ledger.findByUserIdOrderByCreatedAtDesc(userId);
        Map<UUID, String> approverNames = accounts.findAllById(rows.stream().map(ScoreLedgerEntry::getApprovedByAdminId)
                        .filter(java.util.Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(AdminAccount::getId, AdminAccount::getDisplayName));
        List<LedgerRow> ledgerRows = rows.stream().map(e -> new LedgerRow(
                e.getId().toString(), DISPLAY.format(e.getCreatedAt()), e.getKind().name(), e.getDelta().doubleValue(),
                e.getReason(), itemNameOf(e.getVerdictId()),
                e.getApprovedByAdminId() == null ? null : approverNames.getOrDefault(e.getApprovedByAdminId(), e.getApprovedByAdminId().toString())))
                .toList();

        List<String> basis = List.of(
                String.format(Locale.US, "TI %.2f = (HIT %d + %.0f) / (HIT %d + MISS %d + %.0f) · 최근 %d일",
                        ti, in.hitInWindow(), p.tiAlpha, in.hitInWindow(), in.missInWindow(), p.tiAlpha + p.tiBeta,
                        GradeInputsReader.TI_WINDOW_DAYS),
                String.format(Locale.US, "AS %.1f = 원장 %d행의 Δ × 0.5^(경과일 / 행별 반감기) 합", in.activeScore(), rows.size()),
                String.format(Locale.US, "판정 완료 %d건(전 기간, 시딩 제외)", in.judgedCount()));

        Long flags = jdbc.queryForObject("SELECT count(*) FROM abuse_flags WHERE user_id = ?", Long.class, userId);
        return new AdminUserDetail(u.getId().toString(), u.getHandle(), u.getStatus().name(), DISPLAY.format(u.getJoinedAt()),
                snapshot == null ? Grade.L0.name() : snapshot.getGrade().name(),
                snapshot == null ? null : DISPLAY.format(snapshot.getComputedAt()),
                ti, in.activeScore(), in.judgedCount(), in.hitInWindow(), in.missInWindow(), basis,
                flags == null ? 0 : flags, ledgerRows);
    }

    private Grade gradeOf(UUID userId) {
        return grades.findTopByUserIdOrderByComputedAtDesc(userId).map(UserGrade::getGrade).orElse(Grade.L0);
    }

    private String itemNameOf(UUID verdictId) {
        if (verdictId == null) return null;
        return verdicts.findById(verdictId).flatMap(v -> trendItems.findById(v.getTrendItemId()))
                .map(i -> i.getCanonicalName()).orElse(null);
    }
}
```

- [ ] **Step 5: 수동 ADJ 서비스·실행기**

`LedgerAdjustService.java`:

```java
package kr.trendstage.apiadmin.user;

import kr.trendstage.apiadmin.approval.ActionType;
import kr.trendstage.apiadmin.approval.ApprovalGate;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.entity.ScoreLedgerEntry;
import kr.trendstage.persistence.params.CurrentParameterSetResolver;
import kr.trendstage.persistence.repo.ScoreLedgerRepository;
import kr.trendstage.persistence.repo.UserRepository;
import kr.trendstage.persistence.type.AdminRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * ADM-311 수동 상쇄 원장(ADJ). ADMIN이 100점 이하면 즉시 기록, OPERATOR(상신, 02 §1.1)이거나 100점 초과면 승인 요청(K3).
 * 행은 판정·제보와 연결되지 않는다. 반감기는 현재 파라미터, 감쇠 기준은 기록 시각.
 */
@Service
public class LedgerAdjustService {

    static final BigDecimal MAX_ABS = new BigDecimal("9999");
    static final String REASON_PREFIX = "수동 조정 · ";

    private final UserRepository users;
    private final ScoreLedgerRepository ledger;
    private final ApprovalGate gate;
    private final CurrentParameterSetResolver params;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public LedgerAdjustService(UserRepository users, ScoreLedgerRepository ledger, ApprovalGate gate,
                               CurrentParameterSetResolver params, AuditLogService auditLogService, Clock clock) {
        this.users = users; this.ledger = ledger; this.gate = gate; this.params = params;
        this.auditLogService = auditLogService; this.clock = clock;
    }

    public record AdjustResult(String status, String ledgerId, String approvalRequestId, BigDecimal amount) {
        public boolean pending() { return "PENDING_APPROVAL".equals(status); }
    }

    @Transactional
    public AdjustResult adjust(UUID userId, BigDecimal amount, String reason, UUID actorId, AdminRole actorRole) {
        if (reason == null || reason.isBlank()) throw new AdminValidationException("사유는 필수입니다");
        if (amount == null || amount.signum() == 0) throw new AdminValidationException("금액은 0이 아니어야 합니다");
        BigDecimal amt = amount.setScale(4, RoundingMode.HALF_UP);
        if (amt.abs().compareTo(MAX_ABS) > 0) throw new AdminValidationException("금액은 ±9999 이내여야 합니다");
        if (!users.existsById(userId)) throw new AdminValidationException("존재하지 않는 유저입니다");

        if (actorRole == AdminRole.ADMIN && !gate.exceeds(amt)) {
            ScoreLedgerEntry saved = record(userId, amt, reason, null, null);
            auditLogService.record(actorId, actorRole, "LEDGER_ADJ", "USER", userId, Map.of(
                    "amount", amt.toPlainString(), "reason", reason, "ledgerId", saved.getId().toString()));
            return new AdjustResult("APPLIED", saved.getId().toString(), null, amt);
        }
        ApprovalRequest r = gate.request(ActionType.LEDGER_ADJ, userId,
                Map.of("amount", amt.toPlainString(), "reason", reason), actorId, actorRole);
        return new AdjustResult("PENDING_APPROVAL", null, r.getId().toString(), amt);
    }

    /** 실행기와 공유 — 승인 실행이면 승인 정보를 채운다. */
    ScoreLedgerEntry record(UUID userId, BigDecimal amount, String reason, UUID approvalId, UUID approvedBy) {
        Instant now = clock.instant();
        return ledger.save(ScoreLedgerEntry.adjustment(userId, amount, REASON_PREFIX + reason, approvalId, approvedBy,
                params.resolve().halflifeDays, now));
    }
}
```

`LedgerAdjustExecutor.java`:

```java
package kr.trendstage.apiadmin.user;

import kr.trendstage.apiadmin.approval.ActionType;
import kr.trendstage.apiadmin.approval.ApprovalExecutor;
import kr.trendstage.apiadmin.approval.ApprovalGate;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.entity.ScoreLedgerEntry;
import kr.trendstage.persistence.repo.UserRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/** LEDGER_ADJ 승인 실행 — 요청 금액 그대로 ADJ 한 행(승인 정보 포함). */
@Component
public class LedgerAdjustExecutor implements ApprovalExecutor {

    private final LedgerAdjustService service;
    private final ApprovalGate gate;
    private final UserRepository users;

    public LedgerAdjustExecutor(LedgerAdjustService service, ApprovalGate gate, UserRepository users) {
        this.service = service; this.gate = gate; this.users = users;
    }

    @Override public String actionType() { return ActionType.LEDGER_ADJ.name(); }

    @Override
    public Map<String, String> execute(ApprovalRequest request) {
        Map<String, String> p = gate.payload(request);
        ScoreLedgerEntry saved = service.record(request.getTargetRef(), new BigDecimal(p.get("amount")), p.get("reason"),
                request.getId(), request.getApprover1());
        return Map.of("amount", p.get("amount"), "ledgerId", saved.getId().toString());
    }

    @Override
    public String describe(ApprovalRequest request) {
        Map<String, String> p = gate.payload(request);
        String handle = users.findById(request.getTargetRef()).map(u -> u.getHandle()).orElse("(삭제된 유저)");
        return "원장 조정 · %s · %s점 · 사유: %s".formatted(handle, p.get("amount"), p.get("reason"));
    }
}
```

(`service.record`는 같은 패키지 package-private 메서드 — `LedgerAdjustService` 안에서 부르는 게 아니므로 self-invocation이 아니고, 승인 트랜잭션 안에서 실행된다.)

- [ ] **Step 6: 컨트롤러**

`AdminUserController.java`:

```java
package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.apiadmin.user.AdminUserService;
import kr.trendstage.apiadmin.user.LedgerAdjustService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** ADM-311 유저 원장. 조회 R/O/A/Au(근거 화면), 수동 ADJ는 O/A(OPERATOR는 상신 → 승인). */
@RestController
@RequestMapping("/admin/users")
public class AdminUserController {

    private final AdminUserService users;
    private final LedgerAdjustService adjustments;

    public AdminUserController(AdminUserService users, LedgerAdjustService adjustments) {
        this.users = users;
        this.adjustments = adjustments;
    }

    public record AdjustRequest(BigDecimal amount, String reason) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN', 'AUDITOR')")
    public List<AdminUserService.UserHit> search(@RequestParam(required = false) String handle) {
        return users.search(handle);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN', 'AUDITOR')")
    public AdminUserService.AdminUserDetail detail(@PathVariable UUID id) {
        return users.detail(id);
    }

    @PostMapping("/{id}/ledger-adjustments")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ResponseEntity<LedgerAdjustService.AdjustResult> adjust(@PathVariable UUID id, @RequestBody AdjustRequest req,
                                                                   @AuthenticationPrincipal AdminPrincipal actor) {
        LedgerAdjustService.AdjustResult r = adjustments.adjust(id, req.amount(), req.reason(), actor.id(), actor.role());
        return ResponseEntity.status(r.pending() ? HttpStatus.ACCEPTED : HttpStatus.CREATED).body(r);
    }
}
```

- [ ] **Step 7: 통과 확인**

Run: `compileJava compileTestJava :app:test --tests "kr.trendstage.admin.AdminUserApiTest"`
Expected: PASS (5 tests).

- [ ] **Step 8: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/user/AdminUserService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/user/LedgerAdjustService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/user/LedgerAdjustExecutor.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminUserController.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminNotFoundException.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminApiExceptionHandler.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/UserRepository.java backend/app/src/test/java/kr/trendstage/admin/AdminUserApiTest.java
git commit -m "feat(api-admin): ADM-311 유저 원장 조회·산정 근거, 수동 ADJ(100점 초과·OPERATOR는 승인 요청)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 계정 통제 — 생성·역할 변경 승인, 부트스트랩 예외, 비밀번호 변경

**Files:**
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/account/AdminAccountManagementService.java`
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/account/AccountCreateExecutor.java`
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/account/AccountRoleChangeExecutor.java`
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/auth/AccountPendingException.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/auth/AdminAccountService.java` (승인 대기 로그인 거부)
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/{AdminAccountController,AdminAuthController,AdminApiExceptionHandler}.java`
- Test: `backend/app/src/test/java/kr/trendstage/admin/AccountGovernanceTest.java`
- Test: `backend/app/src/test/java/kr/trendstage/admin/AccountBootstrapModeTest.java` (전용 컨테이너)

**Interfaces:**
- Consumes: `AdminAccount.createdByConsole`, `#activate`, `#changeRole`, `#changePasswordHash`, `#isActive` (Task 1); `ApprovalGate#request/#eligibleApproverCount/#payload`, `ActionType.ACCOUNT_CREATE/ACCOUNT_ROLE_CHANGE`, `ApprovalExecutor`, `ApprovalConflictException` (Task 2)
- Produces:
  - `AdminAccountManagementService#create(String loginId, String displayName, String role, String password, UUID actorId, AdminRole actorRole): CreateResult(String status, AdminAccount account, String approvalRequestId)` — status `CREATED_BOOTSTRAP` | `PENDING_APPROVAL`
  - `#requestRoleChange(UUID accountId, String role, String reason, UUID actorId, AdminRole actorRole): ApprovalRequest`
  - `#changeOwnPassword(UUID actorId, AdminRole actorRole, String current, String next)`
  - `AccountPendingException` → 403
  - `POST /admin/accounts` 201(`CREATED_BOOTSTRAP`)·202(`PENDING_APPROVAL`) `{status, account, approvalRequestId}`; `POST /admin/accounts/{id}/role {role, reason}` 202 `{approvalRequestId}`; `POST /admin/me/password {current, next}` 200
  - `AdminAccountSummary`에 `activatedAt, approverSince, pendingApproval` 추가

- [ ] **Step 1: 실패하는 테스트 작성**

`AccountGovernanceTest.java`:

```java
package kr.trendstage.admin;

import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스펙 §10 #11(승인 대기 경로)·#12·#15 + Review Focus 1. 공유 DB에는 승인 자격자가 늘 있어 부트스트랩 예외가 걸리지 않는다. */
class AccountGovernanceTest extends AbstractIntegrationTest {

    @Autowired PasswordEncoder encoder;

    @Test
    void createNeedsApprovalAndPendingAccountCannotLogIn() throws Exception {
        UUID admin = fx.admin();
        fx.admin();   // 요청자 외 승인 자격자 — 이 클래스가 JVM에서 처음 돌아도 부트스트랩 예외가 걸리지 않게
        String loginId = "new_" + UUID.randomUUID().toString().substring(0, 8);
        String body = mvc.perform(post("/admin/accounts").with(asAdmin(admin, AdminRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"displayName\":\"신규\",\"role\":\"OPERATOR\",\"password\":\"pw-12345678\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.account.pendingApproval").value(true))
                .andReturn().getResponse().getContentAsString();

        login(loginId, "pw-12345678").andExpect(status().isForbidden());

        UUID approvalId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.approvalRequestId"));
        mvc.perform(post("/admin/approvals/{id}/approve", approvalId).with(asAdmin(fx.admin(), AdminRole.ADMIN)))
                .andExpect(status().isOk());
        login(loginId, "pw-12345678").andExpect(status().isOk());
    }

    @Test
    void enablingPendingAccountIsRejected() throws Exception {
        UUID pending = fx.pendingAdmin("REVIEWER");
        mvc.perform(post("/admin/accounts/{id}/enable", pending).with(asAdmin(fx.admin(), AdminRole.ADMIN)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void roleChangeAppliesAfterApprovalAndRestartsGrace() throws Exception {   // #12
        UUID target = fx.admin("OPERATOR");
        String body = mvc.perform(post("/admin/accounts/{id}/role", target).with(asAdmin(fx.admin(), AdminRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\",\"reason\":\"승격\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(role(target)).isEqualTo("OPERATOR");

        clock.set(Instant.parse("2026-09-23T00:00:00Z"));
        approve(UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.approvalRequestId")));
        assertThat(role(target)).isEqualTo("ADMIN");
        assertThat(jdbc.queryForObject("SELECT approver_since FROM admin_accounts WHERE id = ?", Instant.class, target))
                .isEqualTo(Instant.parse("2026-09-30T00:00:00Z"));
    }

    @Test
    void roleChangeApprovalConflictsWhenRoleAlreadyChanged() throws Exception {   // Review Focus 1
        UUID target = fx.admin("REVIEWER");
        UUID requester = fx.admin();
        String first = requestRole(target, requester, "OPERATOR");
        String second = requestRole(target, requester, "AUDITOR");
        approve(UUID.fromString(com.jayway.jsonpath.JsonPath.read(first, "$.approvalRequestId")));

        UUID secondId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(second, "$.approvalRequestId"));
        mvc.perform(post("/admin/approvals/{id}/approve", secondId).with(asAdmin(fx.admin(), AdminRole.ADMIN)))
                .andExpect(status().isConflict());
        assertThat(fx.approvalStatus(secondId)).isEqualTo("PENDING");
        assertThat(role(target)).isEqualTo("OPERATOR");
    }

    @Test
    void cannotChangeOwnRole() throws Exception {
        UUID admin = fx.admin();
        mvc.perform(post("/admin/accounts/{id}/role", admin).with(asAdmin(admin, AdminRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"AUDITOR\",\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownPasswordChange() throws Exception {   // #15
        UUID admin = fx.admin("REVIEWER");
        jdbc.update("UPDATE admin_accounts SET password_hash = ? WHERE id = ?", encoder.encode("old-pass-1234"), admin);
        String loginId = jdbc.queryForObject("SELECT login_id FROM admin_accounts WHERE id = ?", String.class, admin);

        mvc.perform(post("/admin/me/password").with(asAdmin(admin, AdminRole.REVIEWER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"current\":\"wrong-pass\",\"next\":\"new-pass-1234\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/admin/me/password").with(asAdmin(admin, AdminRole.REVIEWER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"current\":\"old-pass-1234\",\"next\":\"new-pass-1234\"}"))
                .andExpect(status().isOk());

        login(loginId, "old-pass-1234").andExpect(status().isUnauthorized());
        login(loginId, "new-pass-1234").andExpect(status().isOk());
        assertThat(fx.auditCount("ACCOUNT_PASSWORD_CHANGE", admin)).isEqualTo(1);
    }

    private String requestRole(UUID target, UUID requester, String role) throws Exception {
        return mvc.perform(post("/admin/accounts/{id}/role", target).with(asAdmin(requester, AdminRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\",\"reason\":\"변경\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
    }

    private void approve(UUID approvalId) throws Exception {
        mvc.perform(post("/admin/approvals/{id}/approve", approvalId).with(asAdmin(fx.admin(), AdminRole.ADMIN)))
                .andExpect(status().isOk());
    }

    private String role(UUID id) {
        return jdbc.queryForObject("SELECT role::text FROM admin_accounts WHERE id = ?", String.class, id);
    }

    private org.springframework.test.web.servlet.ResultActions login(String loginId, String password) throws Exception {
        jakarta.servlet.http.Cookie csrf = mvc.perform(get("/admin/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return mvc.perform(post("/admin/auth/login").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\"}"));
    }
}
```

`AccountBootstrapModeTest.java` — 전용 컨테이너(빈 `admin_accounts`가 전제, `AdminAccountBootstrapTest`와 같은 방식):

```java
package kr.trendstage.admin;

import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.Fixtures;
import kr.trendstage.support.MutableClock;
import kr.trendstage.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스펙 §10 #11 — 승인 자격자가 요청자 외 0명이면 ADMIN 단독 생성(K6), 자격자가 생기면 승인 대기.
 * 공유 DB에는 자격자가 늘 있으므로 전용 컨테이너를 쓴다.
 */
@SpringBootTest(properties = {"logging.level.org.hibernate.SQL=WARN", "logging.level.org.hibernate.orm.jdbc.bind=WARN"})
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class AccountBootstrapModeTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("trd").withUsername("postgres").withPassword("pw");

    static { POSTGRES.start(); }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    @Test
    void soloAdminCreatesUntilAnotherApproverIsEligible() throws Exception {
        Instant t0 = Instant.parse("2026-09-23T00:00:00Z");
        clock.set(t0);
        UUID root = new Fixtures(jdbc).admin();

        create(root, "second").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CREATED_BOOTSTRAP"));
        // second는 7일 유예 중 — 여전히 자격자 0명
        create(root, "third").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CREATED_BOOTSTRAP"));

        clock.set(t0.plus(Duration.ofDays(8)));
        create(root, "fourth").andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }

    private org.springframework.test.web.servlet.ResultActions create(UUID actor, String loginId) throws Exception {
        return mvc.perform(post("/admin/accounts").with(asAdmin(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + loginId + "\",\"displayName\":\"" + loginId + "\",\"role\":\"ADMIN\",\"password\":\"pw-12345678\"}"));
    }

    private RequestPostProcessor asAdmin(UUID id) throws Exception {
        jakarta.servlet.http.Cookie csrf = mvc.perform(get("/admin/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        var principal = new kr.trendstage.apiadmin.auth.AdminPrincipal(id, "root", "root", AdminRole.ADMIN);
        RequestPostProcessor auth = authentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        return r -> { auth.postProcessRequest(r); r.setCookies(csrf); r.addHeader("X-XSRF-TOKEN", csrf.getValue()); return r; };
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `:app:test --tests "kr.trendstage.admin.AccountGovernanceTest" --tests "kr.trendstage.admin.AccountBootstrapModeTest"`
Expected: FAIL — 생성이 200(단독), `/role`·`/admin/me/password` 없음.

- [ ] **Step 3: 로그인 거부**

`AccountPendingException.java`(auth 패키지):

```java
package kr.trendstage.apiadmin.auth;

/** 계정 생성 승인 대기 — 로그인 불가(403). */
public class AccountPendingException extends RuntimeException {
    public AccountPendingException(String message) { super(message); }
}
```

`AdminAccountService.authenticate`: `disabledAt` 검사 바로 뒤에:

```java
        if (account.getActivatedAt() == null) {
            throw new AccountPendingException("승인 대기 중인 계정입니다 — 다른 ADMIN의 승인 후 로그인할 수 있습니다");
        }
```

`@Transactional(noRollbackFor = …)` 목록은 그대로 둔다(실패 카운터를 올리지 않는 경로라 롤백돼도 된다).

`AdminApiExceptionHandler`:

```java
    @ExceptionHandler(AccountPendingException.class)
    public ResponseEntity<Map<String, Object>> handle(AccountPendingException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem(403, e.getMessage()));
    }
```

- [ ] **Step 4: 계정 관리 서비스·실행기**

`AdminAccountManagementService.java`:

```java
package kr.trendstage.apiadmin.account;

import kr.trendstage.apiadmin.approval.ActionType;
import kr.trendstage.apiadmin.approval.ApprovalGate;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.apiadmin.auth.DuplicateLoginIdException;
import kr.trendstage.apiadmin.auth.SelfModificationException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.type.AdminRole;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 관리자 계정 통제(P8, SP3 §5). 생성·역할 변경은 승인을 거친다(K1). 요청자 외 승인 자격자가 0명이면
 * 생성만 단독으로 허용한다(부트스트랩 예외, K6). 새 계정·ADMIN 승격의 승인권은 7일 뒤부터.
 */
@Service
public class AdminAccountManagementService {

    static final int MIN_PASSWORD = 8;

    private final AdminAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final ApprovalGate gate;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public AdminAccountManagementService(AdminAccountRepository accounts, PasswordEncoder passwordEncoder, ApprovalGate gate,
                                         AuditLogService auditLogService, Clock clock) {
        this.accounts = accounts; this.passwordEncoder = passwordEncoder; this.gate = gate;
        this.auditLogService = auditLogService; this.clock = clock;
    }

    public record CreateResult(String status, AdminAccount account, String approvalRequestId) {
        public boolean pending() { return "PENDING_APPROVAL".equals(status); }
    }

    @Transactional
    public CreateResult create(String loginId, String displayName, String role, String password, UUID actorId, AdminRole actorRole) {
        if (isBlank(loginId) || isBlank(displayName) || isBlank(password)) {
            throw new AdminValidationException("아이디·이름·비밀번호는 필수입니다");
        }
        requireStrong(password);
        AdminRole parsed = parseRole(role);
        Instant now = clock.instant();
        boolean bootstrap = gate.eligibleApproverCount(actorId, now) == 0;

        AdminAccount created = AdminAccount.createdByConsole(loginId, displayName, parsed,
                passwordEncoder.encode(password), now, bootstrap);
        try {
            accounts.saveAndFlush(created);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateLoginIdException("이미 존재하는 아이디입니다: " + loginId);
        }

        if (bootstrap) {
            auditLogService.record(actorId, actorRole, "ACCOUNT_CREATE_BOOTSTRAP", "ADMIN_ACCOUNT", created.getId(),
                    Map.of("loginId", loginId, "role", parsed.name()));
            return new CreateResult("CREATED_BOOTSTRAP", created, null);
        }
        ApprovalRequest r = gate.request(ActionType.ACCOUNT_CREATE, created.getId(),
                Map.of("loginId", loginId, "displayName", displayName, "role", parsed.name()), actorId, actorRole);
        return new CreateResult("PENDING_APPROVAL", created, r.getId().toString());
    }

    @Transactional
    public ApprovalRequest requestRoleChange(UUID accountId, String role, String reason, UUID actorId, AdminRole actorRole) {
        if (accountId.equals(actorId)) throw new SelfModificationException("본인 역할은 바꿀 수 없습니다");
        if (isBlank(reason)) throw new AdminValidationException("사유는 필수입니다");
        AdminAccount target = accounts.findById(accountId)
                .orElseThrow(() -> new AdminValidationException("존재하지 않는 계정입니다"));
        AdminRole next = parseRole(role);
        if (target.getRole() == next) throw new AdminValidationException("이미 %s 역할입니다".formatted(next));
        return gate.request(ActionType.ACCOUNT_ROLE_CHANGE, accountId,
                Map.of("from", target.getRole().name(), "to", next.name(), "reason", reason), actorId, actorRole);
    }

    @Transactional
    public void changeOwnPassword(UUID actorId, AdminRole actorRole, String current, String next) {
        AdminAccount me = accounts.findById(actorId).orElseThrow(() -> new AdminValidationException("계정을 찾을 수 없습니다"));
        if (current == null || !passwordEncoder.matches(current, me.getPasswordHash())) {
            throw new AdminValidationException("현재 비밀번호가 맞지 않습니다");
        }
        if (isBlank(next)) throw new AdminValidationException("새 비밀번호를 입력하세요");
        requireStrong(next);
        if (passwordEncoder.matches(next, me.getPasswordHash())) {
            throw new AdminValidationException("새 비밀번호가 현재 비밀번호와 같습니다");
        }
        me.changePasswordHash(passwordEncoder.encode(next));
        auditLogService.record(actorId, actorRole, "ACCOUNT_PASSWORD_CHANGE", "ADMIN_ACCOUNT", actorId, Map.of());
    }

    static AdminRole parseRole(String raw) {
        try {
            return AdminRole.valueOf(raw);
        } catch (Exception e) {
            throw new AdminValidationException("알 수 없는 역할입니다: " + raw);
        }
    }

    private static void requireStrong(String password) {
        if (password.length() < MIN_PASSWORD) throw new AdminValidationException("비밀번호는 8자 이상이어야 합니다");
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
```

`AccountCreateExecutor.java`:

```java
package kr.trendstage.apiadmin.account;

import kr.trendstage.apiadmin.approval.ActionType;
import kr.trendstage.apiadmin.approval.ApprovalExecutor;
import kr.trendstage.apiadmin.approval.ApprovalGate;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;

/** ACCOUNT_CREATE 승인 — 계정을 활성화한다. 반려되면 미활성으로 남는다(아이디 점유). */
@Component
public class AccountCreateExecutor implements ApprovalExecutor {

    private final AdminAccountRepository accounts;
    private final ApprovalGate gate;
    private final Clock clock;

    public AccountCreateExecutor(AdminAccountRepository accounts, ApprovalGate gate, Clock clock) {
        this.accounts = accounts; this.gate = gate; this.clock = clock;
    }

    @Override public String actionType() { return ActionType.ACCOUNT_CREATE.name(); }

    @Override
    public Map<String, String> execute(ApprovalRequest request) {
        AdminAccount account = accounts.findById(request.getTargetRef())
                .orElseThrow(() -> new IllegalStateException("대상 계정 없음: " + request.getTargetRef()));
        account.activate(clock.instant());
        return Map.of("loginId", account.getLoginId(), "role", account.getRole().name());
    }

    @Override
    public String describe(ApprovalRequest request) {
        Map<String, String> p = gate.payload(request);
        return "계정 생성 · %s(%s) · %s".formatted(p.get("loginId"), p.get("displayName"), p.get("role"));
    }
}
```

`AccountRoleChangeExecutor.java`:

```java
package kr.trendstage.apiadmin.account;

import kr.trendstage.apiadmin.approval.ActionType;
import kr.trendstage.apiadmin.approval.ApprovalConflictException;
import kr.trendstage.apiadmin.approval.ApprovalExecutor;
import kr.trendstage.apiadmin.approval.ApprovalGate;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.type.AdminRole;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;

/** ACCOUNT_ROLE_CHANGE 승인 — 요청 시점 역할(from)이 그대로일 때만 바꾼다. 다르면 409, 요청은 PENDING으로 남는다. */
@Component
public class AccountRoleChangeExecutor implements ApprovalExecutor {

    private final AdminAccountRepository accounts;
    private final ApprovalGate gate;
    private final Clock clock;

    public AccountRoleChangeExecutor(AdminAccountRepository accounts, ApprovalGate gate, Clock clock) {
        this.accounts = accounts; this.gate = gate; this.clock = clock;
    }

    @Override public String actionType() { return ActionType.ACCOUNT_ROLE_CHANGE.name(); }

    @Override
    public Map<String, String> execute(ApprovalRequest request) {
        Map<String, String> p = gate.payload(request);
        AdminAccount account = accounts.findById(request.getTargetRef())
                .orElseThrow(() -> new IllegalStateException("대상 계정 없음: " + request.getTargetRef()));
        AdminRole from = AdminRole.valueOf(p.get("from"));
        if (account.getRole() != from) {
            throw new ApprovalConflictException("요청 이후 역할이 이미 %s로 바뀌었습니다 — 반려하세요".formatted(account.getRole()));
        }
        account.changeRole(AdminRole.valueOf(p.get("to")), clock.instant());
        return Map.of("from", p.get("from"), "to", p.get("to"));
    }

    @Override
    public String describe(ApprovalRequest request) {
        Map<String, String> p = gate.payload(request);
        String loginId = accounts.findById(request.getTargetRef()).map(AdminAccount::getLoginId).orElse("(삭제된 계정)");
        return "역할 변경 · %s · %s → %s · 사유: %s".formatted(loginId, p.get("from"), p.get("to"), p.get("reason"));
    }
}
```

- [ ] **Step 5: 컨트롤러**

`AdminAccountController.java`:
- 생성자에 `AdminAccountManagementService management` 추가, `PasswordEncoder` 필드가 쓰이지 않게 되면 뺀다.
- 레코드: `AdminAccountSummary`에 `String activatedAt, String approverSince, boolean pendingApproval` 추가, 새 레코드 `CreateResponse(String status, AdminAccountSummary account, String approvalRequestId)`, `RoleChangeRequest(String role, String reason)`, `ApprovalRef(String approvalRequestId)`.
- `create`를 교체:

```java
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CreateResponse> create(@RequestBody CreateAccountRequest req, @AuthenticationPrincipal AdminPrincipal actor) {
        AdminAccountManagementService.CreateResult r = management.create(req.loginId(), req.displayName(), req.role(),
                req.password(), actor.id(), actor.role());
        return ResponseEntity.status(r.pending() ? HttpStatus.ACCEPTED : HttpStatus.CREATED)
                .body(new CreateResponse(r.status(), toSummary(r.account()), r.approvalRequestId()));
    }

    @PostMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApprovalRef> changeRole(@PathVariable UUID id, @RequestBody RoleChangeRequest req,
                                                  @AuthenticationPrincipal AdminPrincipal actor) {
        ApprovalRequest r = management.requestRoleChange(id, req.role(), req.reason(), actor.id(), actor.role());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ApprovalRef(r.getId().toString()));
    }
```

- `enable`에서 `account.enable();` 앞에:

```java
        if (account.getActivatedAt() == null) {
            throw new AdminValidationException("승인 대기 계정은 활성화할 수 없습니다 — 승인 요청을 처리하세요");
        }
```

- `toSummary`에 새 세 필드(`format(a.getActivatedAt())`, `format(a.getApproverSince())`, `a.getActivatedAt() == null`).
- 파일 상단 주석에서 "2인 승인이 붙으면 … 바뀌어야 한다" 문장을 "생성·역할 변경은 AdminAccountManagementService가 승인 요청으로 만든다(SP3 §5)."로.

`AdminAuthController.java`에 추가(생성자에 `AdminAccountManagementService management`):

```java
    public record PasswordChangeRequest(String current, String next) {}

    @PostMapping("/me/password")
    public void changePassword(@RequestBody PasswordChangeRequest req) {
        AdminPrincipal p = (AdminPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        management.changeOwnPassword(p.id(), p.role(), req.current(), req.next());
    }
```

(이 컨트롤러는 `/admin` 아래 매핑이므로 최종 경로는 `/admin/me/password` — 기존 `/me`와 같은 규칙. 확인: 클래스의 `@RequestMapping` 값.)

- [ ] **Step 6: 통과 확인**

Run: `compileJava compileTestJava :app:test --tests "kr.trendstage.admin.*"`
Expected: PASS(기존 `AdminLoginLockoutTest`·`AdminAccountBootstrapTest` 포함).

- [ ] **Step 7: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/account/AdminAccountManagementService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/account/AccountCreateExecutor.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/account/AccountRoleChangeExecutor.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/auth/AccountPendingException.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/auth/AdminAccountService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminAccountController.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminAuthController.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminApiExceptionHandler.java backend/app/src/test/java/kr/trendstage/admin/AccountGovernanceTest.java backend/app/src/test/java/kr/trendstage/admin/AccountBootstrapModeTest.java
git commit -m "feat(api-admin): 관리자 계정 생성·역할 변경 승인, 부트스트랩 예외, 승인 대기 로그인 거부, 비밀번호 변경

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 세션 재검증 — 비활성·승인 대기·역할 변경은 다음 요청에서 끊는다

**Files:**
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/config/AdminSessionRevalidationFilter.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/config/AdminSecurityConfig.java`
- Test: `backend/app/src/test/java/kr/trendstage/admin/SessionRevalidationTest.java`

**Interfaces:**
- Consumes: `AdminAccount#isActive`, `#getRole` (Task 1)
- Produces: 세션 주체가 `AdminPrincipal`이면 요청마다 계정을 PK로 읽어, 사용 불가·역할 불일치면 세션 무효화 + 401 `{type: "session-revoked"}`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.trendstage.admin;

import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스펙 §10 #12(세션 쪽)·#13 — K7. */
class SessionRevalidationTest extends AbstractIntegrationTest {

    @Test
    void activeAccountPasses() throws Exception {
        mvc.perform(get("/admin/queues/summary").with(asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER)))
                .andExpect(status().isOk());
    }

    @Test
    void disabledAccountSessionIsRevoked() throws Exception {   // #13
        UUID id = fx.admin("OPERATOR");
        jdbc.update("UPDATE admin_accounts SET disabled_at = now() WHERE id = ?", id);
        mvc.perform(get("/admin/queues/summary").with(asAdmin(id, AdminRole.OPERATOR)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("session-revoked"));
    }

    @Test
    void pendingAccountSessionIsRevoked() throws Exception {
        UUID id = fx.pendingAdmin("REVIEWER");
        mvc.perform(get("/admin/queues/summary").with(asAdmin(id, AdminRole.REVIEWER)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roleChangedSinceLoginIsRevoked() throws Exception {   // #12
        UUID id = fx.admin("OPERATOR");
        mvc.perform(get("/admin/queues/summary").with(asAdmin(id, AdminRole.ADMIN)))   // 세션은 ADMIN, DB는 OPERATOR
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("권한이 바뀌었습니다 — 다시 로그인하세요"));
    }

    @Test
    void unauthenticatedCsrfEndpointUnaffected() throws Exception {
        mvc.perform(get("/admin/auth/csrf")).andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `:app:test --tests "kr.trendstage.admin.SessionRevalidationTest"`
Expected: FAIL — 비활성·역할 불일치도 200.

- [ ] **Step 3: 필터 작성**

```java
package kr.trendstage.apiadmin.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.apiadmin.web.Problems;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * 세션 재검증(SP3 K7). 역할·활성 여부가 로그인 시점 값으로 세션에 박히므로, 요청마다 계정을 PK로 다시 읽어
 * 비활성·승인 대기·역할 불일치면 세션을 폐기하고 401로 끊는다 — 비활성화·역할 변경이 즉시 효력을 갖게 한다.
 * @Component로 등록하지 않는다(서블릿 필터로 전역 등록되면 /v1 체인까지 탄다). AdminSecurityConfig가 체인에 넣는다.
 */
public class AdminSessionRevalidationFilter extends OncePerRequestFilter {

    private final AdminAccountRepository accounts;
    private final ObjectMapper objectMapper;

    public AdminSessionRevalidationFilter(AdminAccountRepository accounts, ObjectMapper objectMapper) {
        this.accounts = accounts;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AdminPrincipal principal) {
            Optional<AdminAccount> account = accounts.findById(principal.id());
            String problem = null;
            if (account.isEmpty() || !account.get().isActive()) {
                problem = "사용할 수 없는 계정입니다 — 다시 로그인하세요";
            } else if (account.get().getRole() != principal.role()) {
                problem = "권한이 바뀌었습니다 — 다시 로그인하세요";
            }
            if (problem != null) {
                HttpSession session = request.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(Problems.of(401, "session-revoked", problem)));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
```

`AdminSecurityConfig.adminApi`: 메서드 인자에 `AdminAccountRepository accounts, ObjectMapper objectMapper`를 더하고 체인에:

```java
            .addFilterBefore(new AdminSessionRevalidationFilter(accounts, objectMapper), AuthorizationFilter.class)
```

(import `org.springframework.security.web.access.intercept.AuthorizationFilter`)

- [ ] **Step 4: 통과 확인**

Run: `compileJava compileTestJava :app:test --tests "kr.trendstage.admin.*" --tests "kr.trendstage.merge.*" --tests "kr.trendstage.approval.*"`
Expected: PASS. 기존 관리자 API 테스트가 401로 깨지면 그 테스트가 계정 역할과 다른 역할로 `asAdmin`/`AdminPrincipal`을 만든 것이다 — 테스트를 계정 역할에 맞춘다(서버를 느슨하게 하지 않는다).

- [ ] **Step 5: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/config/AdminSessionRevalidationFilter.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/config/AdminSecurityConfig.java backend/app/src/test/java/kr/trendstage/admin/SessionRevalidationTest.java
git commit -m "feat(api-admin): 요청마다 관리자 세션 재검증 — 비활성·승인 대기·역할 변경은 401

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 역할 정합 + 감사 로그 detail·필터

**Files:**
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/ReportAdminController.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/ParamStudioController.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AuditLogController.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/AdminAuditLogRepository.java`
- Test: `backend/app/src/test/java/kr/trendstage/admin/RoleMatrixTest.java`
- Test: `backend/app/src/test/java/kr/trendstage/admin/AuditLogApiTest.java`

**Interfaces:**
- Produces:
  - `GET /admin/audit-log?action=&actorId=&targetId=&from=&to=&beforeId=` → `AuditLogPage(List<AuditLogEntryResponse> items, Long nextBeforeId)`; `AuditLogEntryResponse(long id, String actor, String role, String action, String targetType, String targetId, Map<String, Object> detail, String createdAt)`
  - 역할: 신고 조회 R/O/A/Au, 신고 `hide` O/A, 드래프트 조회 O/A/Au

- [ ] **Step 1: 실패하는 테스트 작성**

`RoleMatrixTest.java`:

```java
package kr.trendstage.admin;

import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스펙 §10 #18 — 02 §1.1 매트릭스. */
class RoleMatrixTest extends AbstractIntegrationTest {

    @Test
    void auditorReadsEverything() throws Exception {
        UUID auditor = fx.admin("AUDITOR");
        mvc.perform(get("/admin/reports").with(asAdmin(auditor, AdminRole.AUDITOR))).andExpect(status().isOk());
        mvc.perform(get("/admin/params/draft").with(asAdmin(auditor, AdminRole.AUDITOR))).andExpect(status().isOk());
        mvc.perform(get("/admin/users/{id}", fx.user()).with(asAdmin(auditor, AdminRole.AUDITOR))).andExpect(status().isOk());
    }

    @Test
    void reviewerCannotHideButCanRequestExplanation() throws Exception {
        UUID reviewer = fx.admin("REVIEWER");
        UUID item = fx.item(Instant.parse("2026-09-01T00:00:00Z"));
        UUID sub = fx.submission(fx.user(), item, 30, Instant.parse("2026-09-01T00:00:01Z"));
        UUID report = fx.report(item, "OPEN", Instant.parse("2026-09-01T01:00:00Z"));
        String body = "{\"submissionId\":\"" + sub + "\",\"note\":\"확인\"}";

        mvc.perform(post("/admin/reports/{id}/hide", report).with(asAdmin(reviewer, AdminRole.REVIEWER))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/reports/{id}/request-explanation", report).with(asAdmin(reviewer, AdminRole.REVIEWER))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }
}
```

`AuditLogApiTest.java`:

```java
package kr.trendstage.admin;

import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스펙 §10 #19 + Review Focus 4. */
class AuditLogApiTest extends AbstractIntegrationTest {

    @Autowired AuditLogService audit;

    @Test
    void filtersByTargetAndReturnsDetail() throws Exception {
        UUID admin = fx.admin();
        UUID target = UUID.randomUUID();
        for (int i = 0; i < 3; i++) audit.record(admin, AdminRole.ADMIN, "LEDGER_ADJ", "USER", target, Map.of("amount", "1" + i));

        mvc.perform(get("/admin/audit-log").param("targetId", target.toString()).with(asAdmin(fx.admin("AUDITOR"), AdminRole.AUDITOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].detail.amount").value("12"))
                .andExpect(jsonPath("$.nextBeforeId").doesNotExist());
    }

    @Test
    void cursorPagesBackward() throws Exception {
        UUID admin = fx.admin();
        UUID target = UUID.randomUUID();
        for (int i = 0; i < 105; i++) audit.record(admin, AdminRole.ADMIN, "LEDGER_ADJ", "USER", target, Map.of());
        String first = mvc.perform(get("/admin/audit-log").param("targetId", target.toString()).with(asAdmin(admin, AdminRole.ADMIN)))
                .andExpect(jsonPath("$.items", hasSize(100)))
                .andReturn().getResponse().getContentAsString();
        Number next = com.jayway.jsonpath.JsonPath.read(first, "$.nextBeforeId");
        mvc.perform(get("/admin/audit-log").param("targetId", target.toString()).param("beforeId", next.toString())
                        .with(asAdmin(admin, AdminRole.ADMIN)))
                .andExpect(jsonPath("$.items", hasSize(5)));
    }

    @Test
    void operatorSeesOnlyOwnRows() throws Exception {
        UUID operator = fx.admin("OPERATOR");
        UUID other = fx.admin();
        UUID target = UUID.randomUUID();
        audit.record(operator, AdminRole.OPERATOR, "LEDGER_ADJ", "USER", target, Map.of());
        audit.record(other, AdminRole.ADMIN, "LEDGER_ADJ", "USER", target, Map.of());
        mvc.perform(get("/admin/audit-log").param("targetId", target.toString()).with(asAdmin(operator, AdminRole.OPERATOR)))
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void badDateFilterIs422() throws Exception {   // Review Focus 4
        mvc.perform(get("/admin/audit-log").param("from", "어제").with(asAdmin(fx.admin(), AdminRole.ADMIN)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void actionFilter() throws Exception {
        UUID admin = fx.admin();
        UUID target = UUID.randomUUID();
        audit.record(admin, AdminRole.ADMIN, "LEDGER_ADJ", "USER", target, Map.of());
        audit.record(admin, AdminRole.ADMIN, "APPROVAL_REQUEST", "USER", target, Map.of());
        mvc.perform(get("/admin/audit-log").param("targetId", target.toString()).param("action", "LEDGER_ADJ")
                        .with(asAdmin(admin, AdminRole.ADMIN)))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[*].action", everyItem(is("LEDGER_ADJ"))));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `:app:test --tests "kr.trendstage.admin.RoleMatrixTest" --tests "kr.trendstage.admin.AuditLogApiTest"`
Expected: FAIL — AUDITOR 403, REVIEWER hide 200, 응답이 배열(`$.items` 없음).

- [ ] **Step 3: 역할 정합**

- `ReportAdminController`: `queue()`·`submissions()`의 `@PreAuthorize`를 `"hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN', 'AUDITOR')"`로, `hide()`를 `"hasAnyRole('OPERATOR', 'ADMIN')"`로.
- `ParamStudioController`: `GET /draft`를 `"hasAnyRole('OPERATOR', 'ADMIN', 'AUDITOR')"`로. 쓰기 엔드포인트는 그대로.

`GET /draft`는 `ParamStudioService.getOrCreateActiveDraft`를 불러 **활성 드래프트가 없으면 새로 만든다** — AUDITOR의 조회가 쓰기를 일으키면 안 된다. `ParamStudioService`에 추가:

```java
    /** 활성 드래프트 조회만(생성 없음) — AUDITOR 읽기 경로. */
    @Transactional(readOnly = true)
    public Optional<ParameterDraft> findActiveDraft() {
        return drafts.findFirstByStatusInOrderByCreatedAtDesc(ACTIVE_STATUSES);
    }
```

`ParamStudioController.getDraft` 교체(import `kr.trendstage.persistence.type.AdminRole`, `kr.trendstage.domain.params.ParameterSet`):

```java
    @GetMapping("/draft")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN', 'AUDITOR')")
    public ParameterDraftResponse getDraft(@AuthenticationPrincipal AdminPrincipal actor) {
        if (actor.role() == AdminRole.AUDITOR) {
            // 조회는 쓰지 않는다 — 활성 드래프트가 없으면 운영값만 보여 준다(status NONE)
            return service.findActiveDraft().map(this::toResponse).orElseGet(this::operationalView);
        }
        return toResponse(service.getOrCreateActiveDraft(actor.id()));
    }

    private ParameterDraftResponse operationalView() {
        ParameterSet current = service.currentOperationalParams();
        return new ParameterDraftResponse(null, "NONE", current.submitterTarget, current.submitterTarget,
                current.hitThreshold, current.hitThreshold, null);
    }
```

`RoleMatrixTest.auditorReadsEverything` 끝에 단언을 추가한다:

```java
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parameter_drafts WHERE author_id = ?", Integer.class, auditor)).isZero();
```

- [ ] **Step 4: 감사 로그**

`AdminAuditLogRepository`가 `JpaSpecificationExecutor<AdminAuditLogEntry>`도 상속하게 한다(쓰지 않게 된 `findTop200…` 두 메서드는 지운다).

`AuditLogController.java` 교체(주요 부분):

```java
    public record AuditLogEntryResponse(long id, String actor, String role, String action, String targetType,
                                        String targetId, Map<String, Object> detail, String createdAt) {}
    public record AuditLogPage(List<AuditLogEntryResponse> items, Long nextBeforeId) {}

    static final int PAGE = 100;

    @GetMapping("/admin/audit-log")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN', 'AUDITOR')")
    public AuditLogPage list(@AuthenticationPrincipal AdminPrincipal principal,
                             @RequestParam(required = false) String action,
                             @RequestParam(required = false) UUID actorId,
                             @RequestParam(required = false) UUID targetId,
                             @RequestParam(required = false) String from,
                             @RequestParam(required = false) String to,
                             @RequestParam(required = false) Long beforeId) {
        UUID actor = principal.role() == AdminRole.OPERATOR ? principal.id() : actorId;   // OPERATOR는 본인 행만
        Specification<AdminAuditLogEntry> spec = Specification.allOf(
                eq("action", action), eq("actorId", actor), eq("targetId", targetId),
                ge("createdAt", parse(from)), lt("createdAt", parse(to)), ltId(beforeId));
        List<AdminAuditLogEntry> rows = auditLogRepository.findAll(spec,
                PageRequest.of(0, PAGE + 1, Sort.by(Sort.Direction.DESC, "id"))).getContent();
        boolean more = rows.size() > PAGE;
        List<AdminAuditLogEntry> page = more ? rows.subList(0, PAGE) : rows;
        Map<UUID, String> names = displayNames(page);
        return new AuditLogPage(page.stream().map(r -> toResponse(r, names)).toList(),
                more ? page.get(page.size() - 1).getId() : null);
    }

    private static <T> Specification<AdminAuditLogEntry> eq(String field, T value) {
        return value == null ? null : (root, q, cb) -> cb.equal(root.get(field), value);
    }

    private static Specification<AdminAuditLogEntry> ge(String field, Instant value) {
        return value == null ? null : (root, q, cb) -> cb.greaterThanOrEqualTo(root.get(field), value);
    }

    private static Specification<AdminAuditLogEntry> lt(String field, Instant value) {
        return value == null ? null : (root, q, cb) -> cb.lessThan(root.get(field), value);
    }

    private static Specification<AdminAuditLogEntry> ltId(Long beforeId) {
        return beforeId == null ? null : (root, q, cb) -> cb.lessThan(root.get("id"), beforeId);
    }

    private static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            throw new AdminValidationException("날짜는 ISO-8601 형식이어야 합니다(예: 2026-09-23T00:00:00Z): " + iso);
        }
    }

    private AuditLogEntryResponse toResponse(AdminAuditLogEntry row, Map<UUID, String> names) {
        String actor = row.getActorId() == null
                ? (row.getAction().startsWith("SLA_") ? "(시스템)" : "(미인증)")
                : names.getOrDefault(row.getActorId(), row.getActorId().toString());
        return new AuditLogEntryResponse(row.getId(), actor, row.getRole() == null ? "-" : row.getRole().name(),
                row.getAction(), row.getTargetType(), row.getTargetId() == null ? null : row.getTargetId().toString(),
                readDetail(row.getDetail()), DISPLAY_FORMAT.format(row.getCreatedAt()));
    }

    private Map<String, Object> readDetail(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
```

생성자에 `ObjectMapper objectMapper` 추가. `displayNames(page)`는 기존 표시 이름 조회를 메서드로 뺀 것. `Specification.allOf`는 null 항목을 건너뛴다(Spring Data JPA 3.x). 엔티티 필드명(`action`, `actorId`, `targetId`, `createdAt`, `id`)은 `AdminAuditLogEntry`의 필드명과 같다 — 다르면 맞춘다. 클래스 주석을 "필터·커서(100건), detail 포함"으로.

- [ ] **Step 5: 통과 확인**

Run: `compileJava compileTestJava :app:test --tests "kr.trendstage.admin.*"`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/ReportAdminController.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/ParamStudioController.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/params/ParamStudioService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AuditLogController.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/AdminAuditLogRepository.java backend/app/src/test/java/kr/trendstage/admin/RoleMatrixTest.java backend/app/src/test/java/kr/trendstage/admin/AuditLogApiTest.java
git commit -m "feat(api-admin): 역할 매트릭스 정합(AUDITOR 읽기, 임시 비공개 O/A), 감사 로그 detail·필터·커서

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

(`ParamStudioService`는 Step 3에서 바꾼 경우에만 add에 넣는다.)

---

### Task 8: `sla_watch` — 신고 4h 자동 숨김, 병합 24h 마감 연장, 90일 미접속 비활성화

**Files:**
- Modify: `backend/domain-core/src/main/java/kr/trendstage/domain/verdict/DeadlineWindow.java`
- Create: `backend/domain-core/src/test/java/kr/trendstage/domain/DeadlineWindowSlaTest.java`
- Create: `backend/scheduler/src/main/java/kr/trendstage/scheduler/SlaWatchService.java`
- Create: `backend/scheduler/src/main/java/kr/trendstage/scheduler/SlaWatchJob.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/{ReportRepository,MergeQueueRepository,AdminAccountRepository}.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/report/ReportAdminService.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/ReportAdminController.java` (`autoHiddenAt` 응답)
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/queues/QueueSummaryService.java`
- Test: `backend/app/src/test/java/kr/trendstage/sla/SlaWatchTest.java`
- Test: `backend/app/src/test/java/kr/trendstage/sla/SlaLastAdminTest.java` (전용 컨테이너)
- Modify: `backend/app/src/test/java/kr/trendstage/support/AbstractIntegrationTest.java`, `backend/app/src/test/java/kr/trendstage/admin/{AccountBootstrapModeTest,AdminAccountBootstrapTest}.java` (크론 끄기)

**Interfaces:**
- Consumes: `Report#markAutoHidden/getAutoHiddenAt`, `AdminAccount#isActive` (Task 1); SP2의 `MergeService#lockPair(UUID, UUID): Map<UUID, TrendItem>`, `VerdictRepository#existsByTrendItemIdAndSupersedesIsNull(UUID)`
- Produces:
  - `DeadlineWindow.MERGE_SLA_GRACE_HOURS = 24`, `slaExtended(Instant firstSeenAt, Instant override, Instant now): Optional<Instant>`, `ceilingReached(Instant firstSeenAt, Instant override): boolean`
  - `SlaWatchService#autoHideOverdueReports(Instant now): int`, `#extendStalledMerges(Instant now): int`, `#disableInactiveAdmins(Instant now): int`
  - `SlaWatchJob#run()` — `@Scheduled(cron = "${jobs.sla-watch.cron:0 0 * * * *}")`, ShedLock `sla_watch`
  - `ReportAdminService#decide`: `RESTORE`는 `OPEN`·`EXPLAINING` 허용

- [ ] **Step 1: 순수 함수 테스트**

`DeadlineWindowSlaTest.java`:

```java
package kr.trendstage.domain;

import kr.trendstage.domain.verdict.DeadlineWindow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DeadlineWindowSlaTest {

    private static final Instant FIRST = Instant.parse("2026-09-01T00:00:00Z");   // 기본 마감 09-15, 상한 09-22

    @Test void 마감이_24시간_안이면_지금_더하기_24시간으로_민다() {
        Instant now = Instant.parse("2026-09-14T12:00:00Z");
        assertEquals(now.plus(Duration.ofHours(24)), DeadlineWindow.slaExtended(FIRST, null, now).orElseThrow());
    }

    @Test void 이미_충분히_늦으면_그대로() {
        Instant now = Instant.parse("2026-09-10T00:00:00Z");
        assertTrue(DeadlineWindow.slaExtended(FIRST, null, now).isEmpty());
    }

    @Test void 상한_최초_제보_더하기_21일을_넘지_않는다() {
        Instant now = Instant.parse("2026-09-21T12:00:00Z");
        assertEquals(Instant.parse("2026-09-22T00:00:00Z"), DeadlineWindow.slaExtended(FIRST, null, now).orElseThrow());
        assertTrue(DeadlineWindow.slaExtended(FIRST, Instant.parse("2026-09-22T00:00:00Z"), now).isEmpty());
        assertTrue(DeadlineWindow.ceilingReached(FIRST, Instant.parse("2026-09-22T00:00:00Z")));
        assertFalse(DeadlineWindow.ceilingReached(FIRST, null));
    }
}
```

Run: `:domain-core:test` → 컴파일 실패 확인. 그다음 `DeadlineWindow.java`에 추가:

```java
    /** 병합 검수 SLA(24h) 초과 시 보장하는 관측 여유(SP3 K10). */
    public static final int MERGE_SLA_GRACE_HOURS = 24;

    /**
     * 병합 대기가 SLA를 넘겼을 때의 새 마감 — max(현재 마감, now + 24h), 상한 최초 제보 + 21일.
     * 당기지 않는다. 늘릴 게 없으면 empty.
     */
    public static Optional<Instant> slaExtended(Instant firstSeenAt, Instant override, Instant now) {
        Instant current = effectiveDeadline(firstSeenAt, override);
        Instant ceiling = firstSeenAt.plus(Duration.ofDays(MAX_DEADLINE_DAYS));
        Instant wanted = now.plus(Duration.ofHours(MERGE_SLA_GRACE_HOURS));
        Instant target = wanted.isAfter(ceiling) ? ceiling : wanted;
        return target.isAfter(current) ? Optional.of(target) : Optional.empty();
    }

    /** 마감이 상한(최초 제보 + 21일)에 닿았는가 — 더 연장할 수 없다(ADM-010 알림). */
    public static boolean ceilingReached(Instant firstSeenAt, Instant override) {
        return !effectiveDeadline(firstSeenAt, override).isBefore(firstSeenAt.plus(Duration.ofDays(MAX_DEADLINE_DAYS)));
    }
```

Run: `:domain-core:test` → PASS.

- [ ] **Step 2: 실패하는 통합 테스트 작성**

`SlaWatchTest.java`:

```java
package kr.trendstage.sla;

import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.scheduler.SlaWatchJob;
import kr.trendstage.scheduler.SlaWatchService;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스펙 §10 #1·#2·#16·#17 + Review Focus 5. 시계는 실제 현재 근처만 쓴다(Global Constraints). */
class SlaWatchTest extends AbstractIntegrationTest {

    @Autowired SlaWatchService sla;
    @Autowired SlaWatchJob job;

    @Test
    void overdueOpenReportIsAutoHiddenOnce() {   // #1
        Instant now = now();
        UUID item = fx.item(now.minus(Duration.ofDays(2)));
        UUID overdue = fx.report(item, "OPEN", now.minus(Duration.ofHours(4)).minusSeconds(1));
        UUID fresh = fx.report(fx.item(now.minus(Duration.ofDays(2))), "OPEN", now.minus(Duration.ofMinutes(239)));

        sla.autoHideOverdueReports(now);
        sla.autoHideOverdueReports(now);

        assertThat(fx.visibility(item)).isEqualTo("TEMP_HIDDEN");
        assertThat(status(overdue)).isEqualTo("OPEN");
        assertThat(autoHiddenAt(overdue)).isNotNull();
        assertThat(fx.auditCount("SLA_AUTO_HIDE", overdue)).isEqualTo(1);
        assertThat(autoHiddenAt(fresh)).isNull();
    }

    @Test
    void autoHideLeavesNonPublicItemsAlone() {   // Review Focus 5
        Instant now = now();
        UUID item = fx.item(now.minus(Duration.ofDays(2)));
        fx.setVisibility(item, "PERMANENT_HIDDEN");
        UUID report = fx.report(item, "OPEN", now.minus(Duration.ofHours(5)));

        sla.autoHideOverdueReports(now);

        assertThat(fx.visibility(item)).isEqualTo("PERMANENT_HIDDEN");
        assertThat(autoHiddenAt(report)).isNotNull();
    }

    @Test
    void jobRunsAutoHide() {
        Instant now = now();
        clock.set(now);
        UUID item = fx.item(now.minus(Duration.ofDays(2)));
        fx.report(item, "OPEN", now.minus(Duration.ofHours(6)));
        releaseBatchLock("sla_watch");
        job.run();
        assertThat(fx.visibility(item)).isEqualTo("TEMP_HIDDEN");
    }

    @Test
    void openReportCanBeRestoredButNotPermanentlyHidden() throws Exception {   // #2
        Instant now = now();
        clock.set(now);
        UUID item = fx.item(now.minus(Duration.ofDays(2)));
        UUID report = fx.report(item, "OPEN", now.minus(Duration.ofHours(5)));
        sla.autoHideOverdueReports(now);
        UUID operator = fx.admin("OPERATOR");

        mvc.perform(post("/admin/reports/{id}/decide", report).with(asAdmin(operator, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"HIDE_PERMANENT\",\"note\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/admin/reports/{id}/decide", report).with(asAdmin(operator, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"RESTORE\",\"note\":\"오신고\"}"))
                .andExpect(status().isOk());
        assertThat(fx.visibility(item)).isEqualTo("PUBLIC");
        assertThat(status(report)).isEqualTo("DECIDED");
    }

    @Test
    void stalledMergeExtendsDeadlineWithinCeiling() {   // #16
        Instant now = now();
        Instant firstSeen = now.minus(Duration.ofDays(13)).minus(Duration.ofHours(12));   // 기본 마감 = now + 12h
        UUID newItem = fx.item(firstSeen);
        UUID oldItem = fx.item(firstSeen.minus(Duration.ofHours(1)));
        UUID queueId = fx.mergeQueueEntry(newItem, oldItem, 0.80);
        jdbc.update("UPDATE merge_queue SET created_at = ? WHERE id = ?", Timestamp.from(now.minus(Duration.ofHours(25))), queueId);

        sla.extendStalledMerges(now);
        assertThat(fx.deadlineOverride(newItem)).isEqualTo(now.plus(Duration.ofHours(24)));

        // 매시간 반복해도 상한(최초 제보 + 21일)을 넘지 않는다
        Instant later = now;
        for (int i = 0; i < 24 * 9; i++) {
            later = later.plus(Duration.ofHours(1));
            sla.extendStalledMerges(later);
        }
        assertThat(fx.deadlineOverride(newItem)).isEqualTo(firstSeen.plus(Duration.ofDays(21)));
        assertThat(fx.auditCount("SLA_GRACE_EXTEND", newItem)).isGreaterThan(1);
    }

    @Test
    void closedItemReturnsToPendingAndJudgedItemIsUntouched() {   // #16
        Instant now = now();
        UUID closed = fx.item(now.minus(Duration.ofDays(15)));   // 마감 지남
        fx.setState(closed, "JUDGING");
        UUID judged = fx.item(now.minus(Duration.ofDays(15)));
        fx.setState(judged, "RESOLVED");
        fx.verdictRow(judged);
        UUID queueId = fx.mergeQueueEntry(closed, judged, 0.80);
        jdbc.update("UPDATE merge_queue SET created_at = ? WHERE id = ?", Timestamp.from(now.minus(Duration.ofHours(30))), queueId);

        sla.extendStalledMerges(now);

        assertThat(fx.itemState(closed)).isEqualTo("PENDING");
        assertThat(fx.deadlineOverride(closed)).isEqualTo(now.plus(Duration.ofHours(24)));
        assertThat(fx.deadlineOverride(judged)).isNull();
    }

    @Test
    void inactiveAdminIsDisabled() {   // #17
        Instant now = now();
        UUID stale = fx.admin("OPERATOR");
        jdbc.update("UPDATE admin_accounts SET created_at = ?, last_login_at = ? WHERE id = ?",
                Timestamp.from(now.minus(Duration.ofDays(200))), Timestamp.from(now.minus(Duration.ofDays(91))), stale);
        UUID recent = fx.admin("OPERATOR");

        sla.disableInactiveAdmins(now);

        assertThat(jdbc.queryForObject("SELECT disabled_at IS NOT NULL FROM admin_accounts WHERE id = ?", Boolean.class, stale)).isTrue();
        assertThat(jdbc.queryForObject("SELECT disabled_at IS NOT NULL FROM admin_accounts WHERE id = ?", Boolean.class, recent)).isFalse();
        assertThat(fx.auditCount("SLA_ADMIN_DISABLE", stale)).isEqualTo(1);
    }

    /** PostgreSQL timestamptz는 마이크로초까지 — 비교가 어긋나지 않게 잘라 둔다. */
    private static Instant now() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private String status(UUID report) {
        return jdbc.queryForObject("SELECT status::text FROM reports WHERE id = ?", String.class, report);
    }

    private Instant autoHiddenAt(UUID report) {
        Timestamp t = jdbc.queryForObject("SELECT auto_hidden_at FROM reports WHERE id = ?", Timestamp.class, report);
        return t == null ? null : t.toInstant();
    }
}
```

(`fx.setState`·`fx.verdictRow`·`fx.deadlineOverride`는 SP2에서 만든 픽스처다. 시각은 `now()`로 마이크로초까지 잘라 둔다 — PostgreSQL `timestamptz` 정밀도와 맞아야 `isEqualTo`가 성립한다.)

`SlaLastAdminTest.java` — 전용 컨테이너(K11). `AccountBootstrapModeTest`와 같은 선언부(`@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestClockConfig.class)` + 자체 `POSTGRES`)에 테스트 하나:

```java
    @Autowired SlaWatchService sla;
    @Autowired JdbcTemplate jdbc;

    @Test
    void lastActiveAdminIsNeverDisabled() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        UUID only = new Fixtures(jdbc).admin();
        jdbc.update("UPDATE admin_accounts SET created_at = ?, last_login_at = NULL WHERE id = ?",
                Timestamp.from(now.minus(Duration.ofDays(120))), only);

        sla.disableInactiveAdmins(now);

        assertThat(jdbc.queryForObject("SELECT disabled_at IS NULL FROM admin_accounts WHERE id = ?", Boolean.class, only)).isTrue();
    }
```

- [ ] **Step 2b: 테스트에서 시간당 크론 끄기**

`sla_watch`는 매시 정각에 돈다. 테스트 JVM이 정각을 지나면 주입 시계(`MutableClock` — 다른 테스트가 임의 시각으로 옮겨 둔다) 기준으로 공유 DB의 신고·관리자를 건드려 무관한 테스트가 흔들린다. `AbstractIntegrationTest`의 `@SpringBootTest(properties = {…})`에 `"jobs.sla-watch.cron=-"`를 추가하고(Spring의 `-`는 크론 비활성), 전용 컨테이너 테스트(`AccountBootstrapModeTest`, `SlaLastAdminTest`, 기존 `AdminAccountBootstrapTest`)의 `properties`에도 같은 줄을 넣는다. 테스트는 `SlaWatchService`를 직접 부르거나 `job.run()`을 명시적으로 부른다.

- [ ] **Step 3: 실패 확인**

Run: `:app:test --tests "kr.trendstage.sla.*"`
Expected: 컴파일 실패 — `kr.trendstage.scheduler.SlaWatchService` 없음.

- [ ] **Step 4: 저장소**

`ReportRepository.java`:

```java
    /** sla_watch — 4h를 넘긴 OPEN 신고 중 아직 자동 처리하지 않은 것. */
    List<Report> findByStatusAndAutoHiddenAtIsNullAndCreatedAtLessThanEqual(ReportStatus status, Instant cutoff);
```

`MergeQueueRepository.java`:

```java
    /** sla_watch — 24h를 넘긴 처리 대기 병합 후보. */
    List<MergeQueueEntry> findByStatusAndCreatedAtLessThanEqual(MergeQueueStatus status, Instant cutoff);
```

`AdminAccountRepository.java`:

```java
    /** sla_watch — 활성 계정 중 마지막 로그인(없으면 생성)이 cutoff 이전. */
    @Query("SELECT a FROM AdminAccount a WHERE a.disabledAt IS NULL AND a.activatedAt IS NOT NULL "
            + "AND COALESCE(a.lastLoginAt, a.createdAt) <= :cutoff")
    List<AdminAccount> findInactiveSince(@Param("cutoff") Instant cutoff);

    /** 활성 ADMIN 수 — 마지막 ADMIN을 잠그지 않기 위해(K11). */
    long countByRoleAndDisabledAtIsNullAndActivatedAtIsNotNull(AdminRole role);
```

(각 파일에 필요한 import: `java.time.Instant`, `java.util.List`, `kr.trendstage.persistence.type.AdminRole`.)

- [ ] **Step 5: 서비스·잡**

`SlaWatchService.java`:

```java
package kr.trendstage.scheduler;

import kr.trendstage.audit.AuditLogService;
import kr.trendstage.domain.verdict.DeadlineWindow;
import kr.trendstage.merge.MergeService;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.entity.MergeQueueEntry;
import kr.trendstage.persistence.entity.Report;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.repo.MergeQueueRepository;
import kr.trendstage.persistence.repo.ReportRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.VerdictRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.MergeQueueStatus;
import kr.trendstage.persistence.type.ReportStatus;
import kr.trendstage.persistence.type.TrendState;
import kr.trendstage.persistence.type.TrendVisibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * sla_watch의 처리 단계(SP3 §7). 각 메서드가 한 트랜잭션 — SlaWatchJob(다른 빈)이 부른다(self-invocation 금지).
 * 감사 로그 액터는 NULL(시스템). 자동 조치는 가역적인 것만(R4): 임시 비공개, 마감 연장, 계정 비활성화.
 */
@Service
public class SlaWatchService {

    private static final Logger log = LoggerFactory.getLogger(SlaWatchService.class);

    static final Duration REPORT_SLA = Duration.ofHours(4);
    static final Duration MERGE_SLA = Duration.ofHours(24);
    static final Duration ADMIN_INACTIVE = Duration.ofDays(90);
    private static final Set<TrendState> OBSERVING = EnumSet.of(TrendState.DRAFT, TrendState.PENDING, TrendState.JUDGING);

    private final ReportRepository reports;
    private final TrendItemRepository trendItems;
    private final MergeQueueRepository mergeQueue;
    private final MergeService mergeService;
    private final VerdictRepository verdicts;
    private final AdminAccountRepository accounts;
    private final AuditLogService auditLogService;

    public SlaWatchService(ReportRepository reports, TrendItemRepository trendItems, MergeQueueRepository mergeQueue,
                           MergeService mergeService, VerdictRepository verdicts, AdminAccountRepository accounts,
                           AuditLogService auditLogService) {
        this.reports = reports; this.trendItems = trendItems; this.mergeQueue = mergeQueue;
        this.mergeService = mergeService; this.verdicts = verdicts; this.accounts = accounts;
        this.auditLogService = auditLogService;
    }

    /** 신고 4h → 공개 중인 항목을 임시 비공개. 신고는 OPEN 그대로 — 결정은 사람(K9). */
    @Transactional
    public int autoHideOverdueReports(Instant now) {
        int n = 0;
        for (Report r : reports.findByStatusAndAutoHiddenAtIsNullAndCreatedAtLessThanEqual(ReportStatus.OPEN, now.minus(REPORT_SLA))) {
            TrendItem item = trendItems.findByIdForUpdate(r.getTrendItemId())
                    .orElseThrow(() -> new IllegalStateException("신고 대상 항목 없음: " + r.getTrendItemId()));
            TrendVisibility before = item.getVisibility();
            if (before == TrendVisibility.PUBLIC) {
                item.applyVisibility(TrendVisibility.TEMP_HIDDEN);
            }
            r.markAutoHidden(now);
            auditLogService.record(null, null, "SLA_AUTO_HIDE", "REPORT", r.getId(), Map.of(
                    "trendItemId", item.getId().toString(),
                    "visibilityBefore", before.name(),
                    "ageHours", String.valueOf(Duration.between(r.getCreatedAt(), now).toHours())));
            n++;
        }
        return n;
    }

    /** 병합 24h → 두 항목의 관측 마감을 now + 24h까지(상한 최초 제보 + 21일). 판정된 항목은 건드리지 않는다(K10). */
    @Transactional
    public int extendStalledMerges(Instant now) {
        int n = 0;
        for (MergeQueueEntry e : mergeQueue.findByStatusAndCreatedAtLessThanEqual(MergeQueueStatus.PENDING, now.minus(MERGE_SLA))) {
            Map<java.util.UUID, TrendItem> pair = mergeService.lockPair(e.getNewTrendItemId(), e.getOldTrendItemId());
            for (TrendItem item : pair.values()) {
                if (!OBSERVING.contains(item.getState()) || verdicts.existsByTrendItemIdAndSupersedesIsNull(item.getId())) {
                    continue;
                }
                Instant before = DeadlineWindow.effectiveDeadline(item.getFirstSeenAt(), item.getJudgmentDeadlineOverride());
                Optional<Instant> target = DeadlineWindow.slaExtended(item.getFirstSeenAt(), item.getJudgmentDeadlineOverride(), now);
                if (target.isEmpty()) continue;
                item.extendJudgmentDeadline(target.get());
                if (item.getState() == TrendState.JUDGING && target.get().isAfter(now)) {
                    item.transitionTo(TrendState.PENDING);
                }
                auditLogService.record(null, null, "SLA_GRACE_EXTEND", "TREND_ITEM", item.getId(), Map.of(
                        "queueId", e.getId().toString(),
                        "deadlineBefore", before.toString(),
                        "deadlineAfter", target.get().toString()));
                n++;
            }
        }
        return n;
    }

    /** 90일 미접속 관리자 비활성화. 마지막 활성 ADMIN은 건너뛴다(K11). */
    @Transactional
    public int disableInactiveAdmins(Instant now) {
        int n = 0;
        for (AdminAccount a : accounts.findInactiveSince(now.minus(ADMIN_INACTIVE))) {
            if (a.getRole() == AdminRole.ADMIN
                    && accounts.countByRoleAndDisabledAtIsNullAndActivatedAtIsNotNull(AdminRole.ADMIN) <= 1) {
                log.warn("sla_watch: 마지막 활성 ADMIN {}은 90일 미접속이지만 비활성화하지 않습니다", a.getLoginId());
                continue;
            }
            a.disable(now);
            accounts.flush();   // 다음 반복의 활성 ADMIN 수 조회에 반영
            auditLogService.record(null, null, "SLA_ADMIN_DISABLE", "ADMIN_ACCOUNT", a.getId(), Map.of(
                    "loginId", a.getLoginId(),
                    "lastLoginAt", a.getLastLoginAt() == null ? "" : a.getLastLoginAt().toString()));
            n++;
        }
        return n;
    }
}
```

`SlaWatchJob.java`:

```java
package kr.trendstage.scheduler;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

/** 시간당 SLA 감시(SP3 §7). 단계는 서로 독립 — 한 단계가 실패해도 나머지는 돈다. 처리는 SlaWatchService. */
@Component
public class SlaWatchJob {

    private static final Logger log = LoggerFactory.getLogger(SlaWatchJob.class);

    private final SlaWatchService service;
    private final Clock clock;

    public SlaWatchJob(SlaWatchService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(cron = "${jobs.sla-watch.cron:0 0 * * * *}")
    @SchedulerLock(name = "sla_watch", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void run() {
        Instant now = clock.instant();
        int hidden = step("신고 자동 숨김", () -> service.autoHideOverdueReports(now));
        int extended = step("병합 마감 연장", () -> service.extendStalledMerges(now));
        int disabled = step("미접속 관리자 비활성화", () -> service.disableInactiveAdmins(now));
        log.info("sla_watch 완료: 자동 숨김 {} · 마감 연장 {} · 관리자 비활성화 {}", hidden, extended, disabled);
    }

    private static int step(String name, Supplier<Integer> body) {
        try {
            return body.get();
        } catch (RuntimeException e) {
            log.error("sla_watch 단계 실패({}): {}", name, e.getMessage(), e);
            return 0;
        }
    }
}
```

- [ ] **Step 6: 신고 복원·응답·알림**

`ReportAdminService.decide`: 첫 줄 `Report report = requireExplaining(reportId);`를

```java
        // 오신고는 1차 처리 없이 바로 복원할 수 있다(K9). 영구 비공개·수정 후 복원은 소명(EXPLAINING) 뒤에만.
        Report report = decision == ReportDecision.RESTORE ? requireUndecided(reportId) : requireExplaining(reportId);
```

로 바꾸고 추가:

```java
    private Report requireUndecided(UUID id) {
        Report report = requireReport(id);
        if (report.getStatus() == ReportStatus.DECIDED) {
            throw new AdminValidationException("이미 결정된 신고입니다");
        }
        return report;
    }
```

클래스 주석의 "SP3의 sla_watch 잡이 수행한다" 문장을 "sla_watch(scheduler)가 수행한다 — 이 서비스는 사람의 결정만 다룬다"로.

`ReportAdminController.ReportResponse`에 `String autoHiddenAt`을 추가하고 `toResponse`에서 `r.getAutoHiddenAt() == null ? null : DISPLAY_FORMAT.format(r.getAutoHiddenAt())`.

`QueueSummaryService.summarize`의 알림 두 개를 교체하고 상한 알림을 더한다:

```java
        if (mergeOverdue > 0) {
            alerts.add(new Alert("병합 검수 큐 SLA 초과 " + mergeOverdue + "건",
                    "24시간 기준 초과 — 판정 마감은 sla_watch가 자동 연장 중(상한 최초 제보 + 21일)"));
        }
        long atCeiling = pending.stream()
                .filter(e -> Duration.between(e.getCreatedAt(), now).toHours() >= MERGE_SLA_HOURS)
                .flatMap(e -> java.util.stream.Stream.of(e.getNewTrendItemId(), e.getOldTrendItemId()))
                .distinct()
                .map(trendItems::findById).flatMap(Optional::stream)
                .filter(i -> i.getState() == TrendState.PENDING || i.getState() == TrendState.JUDGING)
                .filter(i -> DeadlineWindow.ceilingReached(i.getFirstSeenAt(), i.getJudgmentDeadlineOverride()))
                .count();
        if (atCeiling > 0) {
            alerts.add(new Alert("병합 대기로 판정 마감 상한 도달 " + atCeiling + "건",
                    "최초 제보 + 21일 — 더 연장할 수 없습니다. 병합 결정을 서두르세요"));
        }
        if (reportOverdue > 0) {
            alerts.add(new Alert("신고 콘텐츠 SLA 초과 " + reportOverdue + "건",
                    "4시간 기준 초과 — sla_watch가 임시 비공개함, 1차 처리 필요"));
        }
```

(import `java.util.Optional`. `QueueSummaryTest`는 제목 접두어만 보므로 그대로 통과해야 한다.)

- [ ] **Step 7: 통과 확인**

Run: `compileJava compileTestJava :domain-core:test :app:test --tests "kr.trendstage.sla.*" --tests "kr.trendstage.admin.QueueSummaryTest"`
Expected: PASS.

- [ ] **Step 8: 커밋**

```bash
git add backend/domain-core/src/main/java/kr/trendstage/domain/verdict/DeadlineWindow.java backend/domain-core/src/test/java/kr/trendstage/domain/DeadlineWindowSlaTest.java backend/scheduler/src/main/java/kr/trendstage/scheduler/SlaWatchService.java backend/scheduler/src/main/java/kr/trendstage/scheduler/SlaWatchJob.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/ReportRepository.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/MergeQueueRepository.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/AdminAccountRepository.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/report/ReportAdminService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/ReportAdminController.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/queues/QueueSummaryService.java backend/app/src/test/java/kr/trendstage/sla/SlaWatchTest.java backend/app/src/test/java/kr/trendstage/sla/SlaLastAdminTest.java backend/app/src/test/java/kr/trendstage/support/AbstractIntegrationTest.java backend/app/src/test/java/kr/trendstage/admin/AccountBootstrapModeTest.java backend/app/src/test/java/kr/trendstage/admin/AdminAccountBootstrapTest.java
git commit -m "feat(scheduler): sla_watch — 신고 4h 자동 임시 비공개, 병합 24h 마감 연장, 90일 미접속 관리자 비활성화

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 9: API 계약 (`openapi.yaml`)

**Files:**
- Modify: `backend/api-spec/openapi.yaml`

**Interfaces:**
- Consumes: Task 2~8의 응답 레코드(필드명 그대로)

- [ ] **Step 1: 구 경로 정리**

`grep -n "/admin/users\|/admin/accounts\|/admin/audit-log\|/admin/approvals\|/admin/verdicts\|/admin/reports" backend/api-spec/openapi.yaml`로 현재 정의를 찾는다. `/admin/users/**`는 SP0 때 스펙에만 있던 옛 모양(`/admin/users/{id}` 외 `sanction`·`grade` 등)이다 — 이번에 구현한 세 경로로 교체하고, 구현하지 않은 제재·등급 조정 경로는 지우지 말고 `description`에 "미구현(Phase 2)"를 적는다.

- [ ] **Step 2: 새·바뀐 계약 추가**

아래를 `paths`·`components.schemas`에 반영한다(파일의 기존 들여쓰기·`$ref` 관례를 따른다).

| 경로 | 요청 | 응답 |
|---|---|---|
| `GET /admin/users?handle=` | — | 200 `UserHit[]` · 422 빈 handle |
| `GET /admin/users/{id}` | — | 200 `AdminUserDetail` · 404 |
| `POST /admin/users/{id}/ledger-adjustments` | `{amount: number, reason: string}` | 201 / 202 `LedgerAdjustResult{status, ledgerId, approvalRequestId, amount}` · 422 |
| `POST /admin/verdicts/{id}/void`, `…/rejudge` | `{reason}` | 200 / 202 `VerdictActionResult{status, verdictId, approvalRequestId, adjTotal}` · 409 `approval-pending` |
| `POST /admin/merge-queue/{id}/void` | 기존 | 기존 + 409 `void-needs-approval`, `approval-pending` |
| `GET /admin/approvals` | — | `ApprovalRequest`에 `requiredApprovals: integer`(항상 1), `summary: string` 추가, `actionType` enum = `PARAM_APPLY, VERDICT_REJUDGE, ITEM_VOID, LEDGER_ADJ, ACCOUNT_CREATE, ACCOUNT_ROLE_CHANGE`, `status`에서 `PARTIAL` 설명을 "사용하지 않음(SP3)"으로 |
| `POST /admin/accounts` | 기존 | 201 `CREATED_BOOTSTRAP` / 202 `PENDING_APPROVAL` `{status, account, approvalRequestId}` |
| `POST /admin/accounts/{id}/role` | `{role, reason}` | 202 `{approvalRequestId}` · 403(본인) · 422 |
| `POST /admin/me/password` | `{current, next}` | 200 · 422 |
| `POST /admin/auth/login` | 기존 | + 403 "승인 대기 중인 계정" |
| `GET /admin/audit-log` | `action, actorId, targetId, from, to(ISO-8601), beforeId` | 200 `{items: AuditLogEntry[], nextBeforeId}` — `AuditLogEntry.detail: object` · 422 잘못된 날짜 |
| `GET /admin/reports` | — | `autoHiddenAt: string|null` 추가 |
| `POST /admin/reports/{id}/decide` | 기존 | `RESTORE`는 OPEN·EXPLAINING, 그 외는 EXPLAINING만(422) |
| 모든 `/admin/**` | — | 401 `{type: session-revoked}` — 계정 비활성·승인 대기·역할 변경 |

`AdminAccountSummary`에 `activatedAt`, `approverSince`, `pendingApproval` 추가.

- [ ] **Step 3: 문법 확인**

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$(cygpath -w "$PWD/backend/api-spec"):/spec:ro" node:22-alpine sh -c 'npx --yes @apidevtools/swagger-cli@4 validate /spec/openapi.yaml'
```

Expected: `openapi.yaml is valid`.

- [ ] **Step 4: 커밋**

```bash
git add backend/api-spec/openapi.yaml
git commit -m "docs(api-spec): SP3 — 유저 원장·수동 ADJ, 승인 대기 202, 계정 역할·비밀번호, 감사 로그 필터, 세션 재검증 401

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: 관리자 콘솔

**Files:**
- Modify: `admin/src/api/types.ts`, `admin/src/api/hooks.ts`, `admin/src/api/client.ts`, `admin/src/fixtures.ts`
- Modify: `admin/src/state/role.tsx`, `admin/src/state/auth.tsx`
- Rewrite: `admin/src/screens/UserLedgerScreen.tsx`, `admin/src/screens/AuditLogScreen.tsx`
- Modify: `admin/src/screens/{ApprovalsScreen,VerdictScreen,MergeQueueScreen,ReportQueueScreen,AdminAccountsScreen,ParamStudioScreen}.tsx`
- Create: `admin/src/components/PasswordDialog.tsx`
- Modify: `admin/src/components/Layout.tsx`

**Interfaces:**
- Consumes: Task 9 표의 응답 필드(이름 그대로)

- [ ] **Step 1: 타입**

`types.ts` — `LedgerEntry`·`AdminUserDetail`를 교체하고 나머지를 고친다:

```ts
export interface LedgerRow {
  id: string;
  createdAt: string;
  kind: "HIT" | "MISS" | "VOID" | "ADJ";
  delta: number;
  reason: string;
  trendItemName: string | null;
  approvedBy: string | null;
}
export interface AdminUserDetail {
  id: string;
  handle: string;
  status: string;
  joinedAt: string;
  grade: string;
  gradeComputedAt: string | null;
  trustIndex: number;
  activeScore: number;
  judgedCount: number;
  hitInWindow: number;
  missInWindow: number;
  basis: string[];
  abuseFlagCount: number;
  ledger: LedgerRow[];
}
export interface UserHit { id: string; handle: string; grade: string; joinedAt: string }

/** 승인 게이트를 지나는 작업의 결과 — 200/201 APPLIED, 202 PENDING_APPROVAL. */
export interface ActionResult {
  status: "APPLIED" | "PENDING_APPROVAL";
  approvalRequestId: string | null;
  adjTotal?: number | null;
  amount?: number | null;
}

export interface AuditEntry {
  id: number;
  actor: string;
  role: string;
  action: string;
  targetType: string | null;
  targetId: string | null;
  detail: Record<string, unknown>;
  createdAt: string;
}
export interface AuditPage { items: AuditEntry[]; nextBeforeId: number | null }
export interface AuditFilter { action?: string; targetId?: string; from?: string; to?: string }

export interface AdminAccountSummary {
  id: string;
  loginId: string;
  displayName: string;
  role: string;
  lastLoginAt: string | null;
  disabledAt: string | null;
  createdAt: string;
  activatedAt: string | null;
  approverSince: string | null;
  pendingApproval: boolean;
}
export interface CreateAccountResponse {
  status: "CREATED_BOOTSTRAP" | "PENDING_APPROVAL";
  account: AdminAccountSummary;
  approvalRequestId: string | null;
}
```

`ApprovalRequestView`:

```ts
export interface ApprovalRequestView {
  id: string;
  actionType: "PARAM_APPLY" | "VERDICT_REJUDGE" | "ITEM_VOID" | "LEDGER_ADJ" | "ACCOUNT_CREATE" | "ACCOUNT_ROLE_CHANGE";
  targetRef: string;
  requestedBy: string;
  requestedByName: string;
  approvals: number;
  requiredApprovals: number;
  status: "PENDING" | "APPROVED" | "REJECTED" | "EXECUTED";
  reason: string | null;
  summary: string;
  createdAt: string;
  resolvedAt: string | null;
}
```

`ReportQueueItem`에 `autoHiddenAt: string | null;`. `ParameterDraftView`의 `draftId`를 `string | null`로, `status`에 `"NONE"` 추가.

`fixtures.ts`의 `fxUser`·`fxAudit`·`fxApprovals`·`fxAdminAccounts`·`fxReportQueue`를 새 타입에 맞춘다(없는 필드는 그럴듯한 값 — `fxAudit`은 `AuditPage` 형태 `{ items: [...], nextBeforeId: null }`로, 각 항목에 `detail: {}`; `fxApprovals`는 `requiredApprovals: 1`, `summary`, `status`에서 `PARTIAL` 제거). 여기서 `tsc`가 알려 주는 곳을 전부 고친다.

- [ ] **Step 2: API 훅·클라이언트**

`hooks.ts` — 교체·추가:

```ts
export const useAdminUserSearch = (handle: string) =>
  useQuery({
    queryKey: ["admin", "user-search", handle],
    enabled: !USE_FIXTURES && handle.trim().length >= 2,
    queryFn: () => api.get<UserHit[]>(`/admin/users?handle=${encodeURIComponent(handle.trim())}`),
  });

export const useAdminUser = (id: string | null) =>
  useQuery({
    queryKey: ["admin", "user", id],
    enabled: USE_FIXTURES || id !== null,
    queryFn: USE_FIXTURES ? async () => fx.fxUser : () => api.get<AdminUserDetail>(`/admin/users/${id}`),
  });

export const adjustLedger = (userId: string, amount: number, reason: string) =>
  api.post<ActionResult>(`/admin/users/${userId}/ledger-adjustments`, { amount, reason });

export const useAuditLog = (filter: AuditFilter, beforeId: number | null) => {
  const params = new URLSearchParams();
  Object.entries(filter).forEach(([k, v]) => { if (v) params.set(k, v); });
  if (beforeId !== null) params.set("beforeId", String(beforeId));
  return useData<AuditPage>(["admin", "audit", filter, beforeId], `/admin/audit-log?${params}`, fx.fxAudit);
};

export const createAdminAccount = (req: { loginId: string; displayName: string; role: string; password: string }) =>
  api.post<CreateAccountResponse>("/admin/accounts", req);

export const changeAdminRole = (id: string, role: string, reason: string) =>
  api.post<{ approvalRequestId: string }>(`/admin/accounts/${id}/role`, { role, reason });

export const changeMyPassword = (current: string, next: string) =>
  api.post<void>("/admin/me/password", { current, next });

export const voidVerdict = (trendItemId: string, reason: string) =>
  api.post<ActionResult>(`/admin/verdicts/${trendItemId}/void`, { reason });

export const rejudgeVerdict = (trendItemId: string, reason: string) =>
  api.post<ActionResult>(`/admin/verdicts/${trendItemId}/rejudge`, { reason });
```

(기존 `useAdminUser(id: string)`·`useAuditLog()`·`createAdminAccount`·`voidVerdict`·`rejudgeVerdict` 정의를 이것으로 바꾸고 import 목록에 새 타입을 더한다.)

`client.ts` — 세션 폐기(401) 알림. `request`의 `if (!res.ok)` 블록에서 `throw` 직전에:

```ts
    if (res.status === 401 && path !== "/admin/auth/login") {
      // 세션 재검증(SP3 K7)이 세션을 끊었거나 만료 — 로그인 화면으로
      window.dispatchEvent(new CustomEvent("admin:unauthorized", { detail }));
    }
```

`auth.tsx` — `AuthProvider` 안에 효과 하나:

```tsx
  useEffect(() => {
    const onUnauthorized = (e: Event) =>
      setState({ status: "unauthenticated", error: (e as CustomEvent<string>).detail });
    window.addEventListener("admin:unauthorized", onUnauthorized);
    return () => window.removeEventListener("admin:unauthorized", onUnauthorized);
  }, []);
```

- [ ] **Step 3: 역할 게이트(표시용)**

`role.tsx`의 `CAN`:

```ts
  ledgerAdj: (r: Role) => r === "OPERATOR" || r === "ADMIN",   // OPERATOR는 상신(항상 승인 요청)
  reportTriage: (r: Role) => r === "REVIEWER" || r === "OPERATOR" || r === "ADMIN",   // 소명 요청
  reportHide: (r: Role) => r === "OPERATOR" || r === "ADMIN",   // 임시 비공개(02 §1.1)
```

(`sanctionRequest`는 그대로 두되 화면에서 비활성 — Step 4.)

- [ ] **Step 4: ADM-311 `UserLedgerScreen.tsx` 전체 교체**

```tsx
import React, { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useAdminUser, useAdminUserSearch, adjustLedger } from "../api/hooks";
import { ApiError, USE_FIXTURES } from "../api/client";
import { Card, StateView, Btn } from "../components/ui";
import { C } from "../theme";
import { useRole, CAN } from "../state/role";

const KIND_COLOR: Record<string, string> = { HIT: C.rising, MISS: C.fading, VOID: C.faint, ADJ: C.peak };

/** ADM-311. 원장 수정·삭제 UI는 없다 — 정정은 ADJ 추가뿐(R2). 100점 초과·OPERATOR 요청은 2인 승인. */
export default function UserLedgerScreen() {
  const { role } = useRole();
  const qc = useQueryClient();
  const [handle, setHandle] = useState("");
  const [userId, setUserId] = useState<string | null>(null);
  const hits = useAdminUserSearch(handle);
  const q = useAdminUser(userId);
  const [adjOpen, setAdjOpen] = useState(false);
  const [amt, setAmt] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  const amount = parseFloat(amt);
  const needsApproval = role === "OPERATOR" || Math.abs(amount || 0) > 100;
  const adjReady = !!reason.trim() && !isNaN(amount) && amount !== 0;
  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2800); };

  const submitAdj = async () => {
    if (!userId || !adjReady) return;
    if (USE_FIXTURES) { flash("(데모) ADJ 요청"); return; }
    setBusy(true);
    try {
      const r = await adjustLedger(userId, amount, reason.trim());
      flash(r.status === "APPLIED" ? `ADJ ${amount} 기록됨` : "승인 대기로 올렸습니다 — 다른 ADMIN의 승인 후 반영됩니다");
      setAdjOpen(false); setAmt(""); setReason("");
      await qc.invalidateQueries({ queryKey: ["admin", "user", userId] });
    } catch (e) {
      flash(e instanceof ApiError ? e.message : "ADJ 요청에 실패했습니다");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ maxWidth: 1000 }}>
      {!USE_FIXTURES && (
        <Card style={{ marginBottom: 12 }}>
          <input value={handle} onChange={(e) => setHandle(e.target.value)} placeholder="유저 핸들 앞부분 (2자 이상)" style={inp} />
          {hits.data && hits.data.length > 0 && (
            <div style={{ marginTop: 10, display: "flex", flexWrap: "wrap", gap: 6 }}>
              {hits.data.map((h) => (
                <Btn key={h.id} tone={h.id === userId ? "primary" : undefined} onClick={() => setUserId(h.id)}>
                  {h.handle} · {h.grade}
                </Btn>
              ))}
            </div>
          )}
          {hits.data && hits.data.length === 0 && <div style={{ marginTop: 10, font: "500 12px Pretendard", color: C.faint }}>일치하는 유저가 없습니다</div>}
        </Card>
      )}

      {(USE_FIXTURES || userId) && (
        <StateView query={q}>
          {(u) => (
            <>
              <Card>
                <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                  <span style={{ font: "700 21px ui-monospace, monospace", letterSpacing: "-0.02em" }}>{u.handle}</span>
                  <span style={{ padding: "5px 10px", borderRadius: 100, background: "rgba(27,158,82,0.11)", font: "600 11.5px Pretendard", color: C.rising }}>{u.grade}</span>
                  <span style={{ font: "500 11px Pretendard", color: C.faint }}>{u.status}</span>
                </div>
                <div style={{ font: "500 11.5px Pretendard", color: C.faint, marginTop: 9 }}>
                  가입 {u.joinedAt} · 등급 스냅샷 {u.gradeComputedAt ?? "없음(L0)"} · 어뷰징 플래그 {u.abuseFlagCount}건
                </div>
                <div style={{ display: "flex", gap: 34, marginTop: 20, paddingTop: 18, borderTop: `1px solid ${C.line}` }}>
                  <Stat k="활동 점수 AS" v={u.activeScore.toFixed(1)} />
                  <Stat k="신뢰도 TI" v={u.trustIndex.toFixed(2)} />
                  <Stat k="판정 완료" v={u.judgedCount} />
                  <Stat k="HIT / MISS (180일)" v={`${u.hitInWindow} / ${u.missInWindow}`} />
                </div>
                <div style={{ marginTop: 14, font: "400 11.5px ui-monospace, monospace", color: C.sub, lineHeight: 1.7 }}>
                  {u.basis.map((b) => <div key={b}>{b}</div>)}
                </div>
              </Card>

              <Card style={{ marginTop: 12, padding: 0, overflow: "hidden" }}>
                <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "16px 20px", borderBottom: `1px solid ${C.line}` }}>
                  <span style={{ font: "600 13px Pretendard" }}>점수 원장 <span style={{ font: "500 11px ui-monospace, monospace", color: C.faint, marginLeft: 6 }}>append-only</span></span>
                  <span style={{ font: "400 11.5px Pretendard", color: C.faint }}>수정·삭제 UI는 존재하지 않습니다. 정정은 ADJ 행 추가뿐입니다.</span>
                </div>
                {u.ledger.length === 0 && <div style={{ padding: "18px 20px", font: "500 12.5px Pretendard", color: C.faint }}>원장 행이 없습니다.</div>}
                {u.ledger.map((l) => (
                  <div key={l.id} style={{ display: "grid", gridTemplateColumns: "130px 56px 84px 1fr 150px", alignItems: "center", padding: "12px 20px", borderBottom: "1px solid rgba(20,19,15,0.05)" }}>
                    <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{l.createdAt}</span>
                    <span style={{ justifySelf: "start", font: "600 10.5px ui-monospace, monospace", padding: "4px 6px", borderRadius: 5, background: "rgba(20,19,15,0.05)", color: KIND_COLOR[l.kind] }}>{l.kind}</span>
                    <span style={{ font: "700 13px ui-monospace, monospace", color: KIND_COLOR[l.kind] }}>{l.delta > 0 ? "+" : ""}{l.delta.toFixed(1)}</span>
                    <span style={{ font: "500 12px Pretendard", color: C.sub }}>{l.trendItemName ? `${l.trendItemName} · ` : ""}{l.reason}</span>
                    <span style={{ font: "500 11px Pretendard", color: C.faint }}>{l.approvedBy ? `승인: ${l.approvedBy}` : ""}</span>
                  </div>
                ))}
              </Card>

              <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
                <Btn tone="primary" disabled={!CAN.ledgerAdj(role)} title={!CAN.ledgerAdj(role) ? "OPERATOR 이상 필요" : undefined} onClick={() => setAdjOpen((o) => !o)}>상쇄 원장 추가</Btn>
                <Btn disabled title="미구현(Phase 2)">제재 상신</Btn>
                <Btn disabled title="미구현(Phase 2)">등급 수동 조정</Btn>
              </div>

              {adjOpen && CAN.ledgerAdj(role) && (
                <Card style={{ marginTop: 12 }}>
                  <b style={{ fontSize: 13.5 }}>상쇄 원장 추가 — 기존 행은 그대로 두고 ADJ 행만 덧붙입니다</b>
                  <div style={{ display: "flex", gap: 10, marginTop: 14, alignItems: "flex-start" }}>
                    <div style={{ width: 120, flex: "none" }}>
                      <div style={{ font: "500 11px Pretendard", color: C.faint, marginBottom: 7 }}>Δ 점수</div>
                      <input value={amt} onChange={(e) => setAmt(e.target.value)} style={inp} />
                    </div>
                    <div style={{ flex: 1 }}>
                      <div style={{ font: "500 11px Pretendard", color: C.faint, marginBottom: 7 }}>사유 (필수)</div>
                      <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="예: 수집 장애 구간 보정" style={inp} />
                    </div>
                  </div>
                  <div style={{ marginTop: 12, font: "500 12px Pretendard", color: needsApproval ? C.fading : C.sub }}>
                    {needsApproval ? "승인 대상 — 다른 ADMIN이 승인하면 반영됩니다(100점 초과 또는 OPERATOR 상신)." : "100점 이하 — 바로 기록됩니다. 사유와 금액이 감사 로그에 남습니다."}
                  </div>
                  <div style={{ display: "flex", gap: 8, marginTop: 14 }}>
                    <Btn tone="primary" disabled={!adjReady || busy} onClick={submitAdj}>{needsApproval ? "승인 요청" : "ADJ 기록"}</Btn>
                    <Btn onClick={() => setAdjOpen(false)}>취소</Btn>
                  </div>
                </Card>
              )}
            </>
          )}
        </StateView>
      )}
      {toast && <div style={{ position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)", background: C.ink, color: "#fff", padding: "12px 18px", borderRadius: 10, font: "500 12.5px Pretendard" }}>{toast}</div>}
    </div>
  );
}

const inp: React.CSSProperties = { width: "100%", boxSizing: "border-box", padding: "11px 13px", borderRadius: 9, border: "1px solid rgba(20,19,15,0.12)", background: "#FBFAF7", outline: "none", font: "500 12.5px Pretendard" };

function Stat({ k, v }: { k: string; v: React.ReactNode }) {
  return (
    <div>
      <div style={{ font: "400 11px Pretendard", color: C.faint }}>{k}</div>
      <div style={{ font: "700 24px Pretendard", letterSpacing: "-0.02em", marginTop: 8 }}>{v}</div>
    </div>
  );
}
```

- [ ] **Step 5: ADM-700 `AuditLogScreen.tsx` 전체 교체**

```tsx
import React, { useState } from "react";
import { useAuditLog } from "../api/hooks";
import { Card, StateView, Btn } from "../components/ui";
import { C } from "../theme";
import type { AuditEntry, AuditFilter } from "../api/types";

const COLS = "70px 100px 80px 170px 1fr 130px";

/** ADM-700. 서버가 기록하는 관리자 개입(변경)과 로그인. append-only · 해시 체인. OPERATOR는 본인 행만 보인다. */
export default function AuditLogScreen() {
  const [draft, setDraft] = useState<AuditFilter>({});
  const [filter, setFilter] = useState<AuditFilter>({});
  const [cursor, setCursor] = useState<number | null>(null);
  const [older, setOlder] = useState<AuditEntry[]>([]);
  const [open, setOpen] = useState<number | null>(null);
  const q = useAuditLog(filter, cursor);

  const apply = () => { setOlder([]); setCursor(null); setFilter(draft); };
  const more = (rows: AuditEntry[], next: number | null) => { setOlder((o) => [...o, ...rows]); setCursor(next); };

  return (
    <div style={{ maxWidth: 1100 }}>
      <Card style={{ marginBottom: 12, display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
        <input placeholder="액션 (예: LEDGER_ADJ)" value={draft.action ?? ""} onChange={(e) => setDraft({ ...draft, action: e.target.value || undefined })} style={inp} />
        <input placeholder="대상 ID" value={draft.targetId ?? ""} onChange={(e) => setDraft({ ...draft, targetId: e.target.value || undefined })} style={{ ...inp, width: 300 }} />
        <input type="datetime-local" onChange={(e) => setDraft({ ...draft, from: e.target.value ? new Date(e.target.value).toISOString() : undefined })} style={inp} />
        <span style={{ color: C.faint }}>~</span>
        <input type="datetime-local" onChange={(e) => setDraft({ ...draft, to: e.target.value ? new Date(e.target.value).toISOString() : undefined })} style={inp} />
        <Btn tone="primary" onClick={apply}>조회</Btn>
      </Card>

      <Card style={{ padding: 0, overflow: "hidden" }}>
        <div style={{ display: "grid", gridTemplateColumns: COLS, padding: "13px 20px", borderBottom: `1px solid ${C.line}`, background: "rgba(20,19,15,0.02)" }}>
          {["ID", "관리자", "역할", "액션", "대상", "일시"].map((h) => (
            <span key={h} style={{ font: "600 10.5px Pretendard", letterSpacing: ".06em", color: C.faint }}>{h}</span>
          ))}
        </div>
        <StateView query={q}>
          {(page) => {
            const rows = [...older, ...page.items];
            return (
              <>
                {rows.length === 0 && <div style={{ padding: "22px 20px", font: "500 12.5px Pretendard", color: C.faint }}>기록이 없습니다.</div>}
                {rows.map((r) => (
                  <div key={r.id} style={{ borderBottom: "1px solid rgba(20,19,15,0.05)" }}>
                    <div onClick={() => setOpen(open === r.id ? null : r.id)} style={{ display: "grid", gridTemplateColumns: COLS, alignItems: "center", padding: "13px 20px", cursor: "pointer" }}>
                      <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{r.id}</span>
                      <span style={{ font: "500 12.5px Pretendard" }}>{r.actor}</span>
                      <span style={{ font: "600 10.5px ui-monospace, monospace", color: C.sub }}>{r.role}</span>
                      <span style={{ font: "600 11px ui-monospace, monospace", padding: "3px 6px", borderRadius: 5, background: "rgba(20,19,15,0.05)", justifySelf: "start", color: r.action.startsWith("SLA_") ? C.peak : C.ink }}>{r.action}</span>
                      <span style={{ font: "500 12px Pretendard", color: C.sub }}>{r.targetType} {r.targetId}</span>
                      <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{r.createdAt}</span>
                    </div>
                    {open === r.id && (
                      <pre style={{ margin: 0, padding: "0 20px 14px 90px", font: "500 11.5px ui-monospace, monospace", color: C.sub, whiteSpace: "pre-wrap" }}>
                        {JSON.stringify(r.detail, null, 2)}
                      </pre>
                    )}
                  </div>
                ))}
                <div style={{ padding: "14px 20px", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                  <span style={{ font: "400 11.5px Pretendard", color: C.faint }}>관리자의 변경·로그인과 sla_watch 자동 조치가 기록됩니다. 조회 행위는 기록하지 않습니다.</span>
                  {page.nextBeforeId !== null && <Btn onClick={() => more(page.items, page.nextBeforeId)}>더 보기</Btn>}
                </div>
              </>
            );
          }}
        </StateView>
      </Card>
    </div>
  );
}

const inp: React.CSSProperties = { padding: "9px 11px", borderRadius: 8, border: `1px solid ${C.line}`, font: "500 12px Pretendard" };
```

- [ ] **Step 6: 나머지 화면**

`ApprovalsScreen.tsx`:
- `ACTION_LABEL`을 교체:

```ts
const ACTION_LABEL: Record<ApprovalRequestView["actionType"], string> = {
  PARAM_APPLY: "파라미터 적용",
  VERDICT_REJUDGE: "재판정(100+)",
  ITEM_VOID: "판정 VOID(100+)",
  LEDGER_ADJ: "원장 조정",
  ACCOUNT_CREATE: "계정 생성",
  ACCOUNT_ROLE_CHANGE: "역할 변경",
};
```

- `STATUS_LABEL`에서 `PARTIAL` 줄을 지운다.
- 표의 "사유" 열 헤더를 "요약"으로, 셀을 `{row.summary}`로. 승인현황 셀을 `{row.approvals}/{row.requiredApprovals}`로.
- 승인 실패 토스트는 서버 `detail`(`e.message`)을 그대로 보여 준다(유예 중이면 "승인권은 …부터 생깁니다").

`VerdictScreen.tsx` `submit()`의 VOID·재판정 분기:

```tsx
      if (dialog.kind === "void" || dialog.kind === "rejudge") {
        const r = dialog.kind === "void" ? await voidVerdict(id, reason) : await rejudgeVerdict(id, reason);
        flash(r.status === "PENDING_APPROVAL"
          ? `${label} — 차액 ${r.adjTotal}점, 승인 대기로 올렸습니다`
          : `${label} ${actionLabel(dialog.kind)} 처리됨 (차액 ${r.adjTotal}점)`);
      } else {
        await extendVerdictGrace(id, days, reason);
        flash(`${label} ${actionLabel(dialog.kind)} 처리됨`);
      }
      await qc.invalidateQueries({ queryKey: ["admin", "verdicts"] });
      setDialog(null);
```

`catch`에서 `e instanceof ApiError && e.type === "approval-pending"`이면 `setError("이 항목에 대기 중인 승인 요청이 있습니다 — 승인 대기함(ADM-620)에서 처리하세요")`. 재판정 안내문(SP1) 아래에 한 줄: "되돌리는 점수가 100점을 넘으면 다른 ADMIN의 승인 후 반영됩니다."

`MergeQueueScreen.tsx` `DECISION_MESSAGES`에 추가:

```ts
  "void-needs-approval": "판정된 항목이라 되돌릴 점수가 100점을 넘습니다 — 판정 관리(ADM-200)에서 VOID하세요(2인 승인)",
  "approval-pending": "이 항목에 대기 중인 승인 요청이 있습니다",
```

`ReportQueueScreen.tsx`:
- 신고 카드 제목 옆에 `report.autoHiddenAt`이 있으면 배지 `자동 숨김 {report.autoHiddenAt}`(색 `C.peak`).
- `OPEN` 블록의 "즉시비공개 + 소명요청" 버튼 `disabled`를 `!CAN.reportHide(role) || busy`로, 권한 안내 문구를 "임시 비공개는 OPERATOR 이상, 소명 요청은 REVIEWER 이상"으로.
- `OPEN` 블록에 버튼 추가(`CAN.reportDecide(role)`일 때 활성): `<Btn onClick={restoreOpen}>오신고 — 복원</Btn>`, 핸들러는 `decideReport(report.id, "RESTORE", note || "오신고")` 후 목록 무효화.
- 입력 placeholder "처리 근거 (선택 · 감사 로그에 기록됩니다)"는 서버가 실제로 기록하므로 그대로 둔다.

`AdminAccountsScreen.tsx`:
- 파일 주석을 "ADM-800. 생성·역할 변경은 다른 ADMIN의 승인 후 반영(SP3). 승인할 사람이 없을 때만 생성 즉시 활성(부트스트랩 예외)."으로.
- `submitCreate` 성공 메시지: `r.status === "PENDING_APPROVAL" ? \`${loginId} — 승인 대기(다른 ADMIN 승인 후 로그인 가능)\` : \`${loginId} 계정을 만들었습니다(승인할 ADMIN이 없어 즉시 활성)\``.
- 목록 행: `acc.pendingApproval`이면 배지 "승인 대기", `acc.role === "ADMIN" && acc.approverSince`가 미래면 "승인권 {approverSince}부터".
- 행마다 "역할 변경" 버튼(ADMIN, 본인 제외): 다이얼로그(역할 select + 사유 필수) → `changeAdminRole` → "승인 대기로 올렸습니다".
- 하단 "역할 부여/회수의 2인 승인 워크플로는 아직…" 문구를 "생성·역할 변경은 승인 대기함(ADM-620)을 거칩니다. 비활성화는 즉시 적용됩니다."로.

`ParamStudioScreen.tsx`: `draft.draftId === null`(AUDITOR, 진행 중 드래프트 없음)이면 제목 줄을 "진행 중인 드래프트 없음 — 운영값"으로 보여 주고(`draft.draftId.slice` 호출 전에 분기), 편집·시뮬레이션·승인 요청 버튼을 렌더하지 않는다. "ADMIN 2인" 문구를 "다른 ADMIN 1명의 승인"으로.

- [ ] **Step 7: 비밀번호 변경·헤더 문구**

`components/PasswordDialog.tsx`:

```tsx
import React, { useState } from "react";
import { changeMyPassword } from "../api/hooks";
import { ApiError } from "../api/client";
import { Card, Btn } from "./ui";
import { C } from "../theme";

/** 내 비밀번호 변경(POST /admin/me/password). 부트스트랩 비밀번호를 바꾸는 유일한 수단. */
export default function PasswordDialog({ onClose }: { onClose: () => void }) {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  const submit = async () => {
    setError(null);
    if (next.length < 8) { setError("새 비밀번호는 8자 이상이어야 합니다"); return; }
    if (next !== confirm) { setError("새 비밀번호 확인이 다릅니다"); return; }
    try {
      await changeMyPassword(current, next);
      setDone(true);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "변경에 실패했습니다");
    }
  };

  return (
    <div style={{ position: "fixed", inset: 0, background: "rgba(20,19,15,0.35)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 20 }}>
      <Card style={{ width: 380 }}>
        <b style={{ fontSize: 14 }}>비밀번호 변경</b>
        {done ? (
          <div style={{ marginTop: 14, font: "500 12.5px Pretendard" }}>변경했습니다. 다음 로그인부터 새 비밀번호를 쓰세요.</div>
        ) : (
          <>
            {[["현재 비밀번호", current, setCurrent], ["새 비밀번호(8자 이상)", next, setNext], ["새 비밀번호 확인", confirm, setConfirm]].map(([label, value, set]) => (
              <input key={label as string} type="password" placeholder={label as string} value={value as string}
                onChange={(e) => (set as (v: string) => void)(e.target.value)}
                style={{ width: "100%", boxSizing: "border-box", marginTop: 10, padding: "10px 12px", borderRadius: 8, border: `1px solid ${C.line}`, font: "500 12.5px Pretendard" }} />
            ))}
            {error && <div style={{ marginTop: 10, font: "500 12px Pretendard", color: C.fading }}>{error}</div>}
          </>
        )}
        <div style={{ display: "flex", gap: 8, marginTop: 14, justifyContent: "flex-end" }}>
          {!done && <Btn tone="primary" onClick={submit}>변경</Btn>}
          <Btn onClick={onClose}>닫기</Btn>
        </div>
      </Card>
    </div>
  );
}
```

`Layout.tsx`: 헤더 문구 `모든 조회·개입은 감사 로그에 기록됩니다` → `모든 개입(변경)은 감사 로그에 기록됩니다`. 역할 배지 앞에 `<Btn onClick={() => setPwOpen(true)}>비밀번호 변경</Btn>`과 `{pwOpen && <PasswordDialog onClose={() => setPwOpen(false)} />}`(`const [pwOpen, setPwOpen] = useState(false)`, fixture 모드에서는 버튼을 숨긴다: `!USE_FIXTURES &&`).

- [ ] **Step 8: 빌드 확인**

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$(cygpath -w "$PWD"):/src:ro" node:22-alpine sh -c 'cp -r /src/admin /w && cd /w && rm -rf node_modules && npm ci --no-audit --no-fund >/dev/null && npm run build'
```

Expected: `tsc -b && vite build` 성공.

- [ ] **Step 9: 브라우저 확인**

Docker로 전체를 띄워(Task 12 Step 4의 1) 콘솔에 로그인해 확인한다: ADM-311 핸들 검색·원장·ADJ 50(즉시)·150(승인 대기), ADM-620에 요약과 `0/1`, 다른 ADMIN으로 승인, ADM-700 필터·펼치기, 비밀번호 변경 후 재로그인. 확인한 화면을 PR 본문에 적는다. 확인하지 못한 화면은 "확인 안 함"으로 적는다.

- [ ] **Step 10: 커밋**

```bash
git add admin/src/api/types.ts admin/src/api/hooks.ts admin/src/api/client.ts admin/src/fixtures.ts admin/src/state/role.tsx admin/src/state/auth.tsx admin/src/screens/UserLedgerScreen.tsx admin/src/screens/AuditLogScreen.tsx admin/src/screens/ApprovalsScreen.tsx admin/src/screens/VerdictScreen.tsx admin/src/screens/MergeQueueScreen.tsx admin/src/screens/ReportQueueScreen.tsx admin/src/screens/AdminAccountsScreen.tsx admin/src/screens/ParamStudioScreen.tsx admin/src/components/PasswordDialog.tsx admin/src/components/Layout.tsx
git commit -m "feat(admin): ADM-311 실데이터·수동 ADJ, 승인 대기 안내, 감사 로그 필터, 계정 역할·비밀번호, 자동 숨김 표시

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: 문서

**Files:** `CLAUDE.md`, `02-admin-console.md`, `04-development-plan.md`, `05-screen-endpoint-map.md`, `README.md`, `infra/.env.example`

원칙: 코드에 실재하게 된 것은 "미구현(SP3)" 표기를 지우고 현재형으로 쓴다. 이번에 하지 않은 것(제재·등급 조정 실행기, 이의 제기, 2FA, PII_VIEW, SLA 통보 채널)은 표기를 남기되 근거를 이 스펙으로 바꾼다. 없는 것을 있다고 쓰는 것은 결함이다.

- [ ] **Step 1: CLAUDE.md**
  - 배치 표 `sla_watch`: "**미구현(SP3)**" → "신고 4h(OPEN, 공개 중) → 임시 비공개 + `auto_hidden_at` / 병합 대기 24h → 두 항목 마감을 now + 24h까지(상한 최초 제보 + 21일) / 90일 미접속 관리자 비활성화(마지막 ADMIN 제외). 시간당, 코드 기본 `0 0 * * * *`".
  - 관리자 콘솔 절 "초과 시 동작은 전부 미구현이다" 인용 → "신고·병합 초과 동작은 `sla_watch`가 한다. 알림 채널(O10)은 Phase 2 — 지금은 감사 로그와 ADM-010 배너. 어뷰징·이의 제기는 큐 자체가 Phase 2다."
  - "2인 승인 대상" 문단 뒤에: "**2인 승인 = 요청자 + 승인자 1명**(승인자: 활성 ADMIN, 요청자 아님, 승인권 유예 7일 경과). 승인할 ADMIN이 요청자 외 0명이면 계정 생성만 단독 허용(부트스트랩 예외, 감사 `ACCOUNT_CREATE_BOOTSTRAP`)."
  - "통제는 서버 한 곳(`ApprovalGate`)에서 — 미구현(SP3). 현재는 재판정이 승인 경로를 우회한다." → "통제는 서버 한 곳(`ApprovalGate`)에서. 재판정·판정된 항목 VOID·수동 ADJ가 원장을 100점 넘게(변동 절댓값 합) 움직이면 아무것도 쓰지 않고 승인 요청(202)이 되고, 승인되면 그 시점 입력으로 다시 계산해 실행한다. 관리자 세션은 요청마다 재검증한다(비활성·승인 대기·역할 변경 → 401)."
  - Phase 1의 ADM-311 문장: "ADM-311은 백엔드가 없어 픽스처로만 떠 있다(**미구현(SP3)**)" → "ADM-311은 `/admin/users/**`(SP3)로 원장·산정 근거·수동 ADJ를 제공한다".
  - Phase 2의 `POST /v1/appeals` 문장: "(404, **미구현(SP3)**)" → "(404, 미구현 — Phase 2, SP3 스펙 §0 비범위)".
- [ ] **Step 2: 02-admin-console.md** — §1.1 아래 "미구현(SP3) — AUDITOR…" 문단과 "위 표는 목표 권한이다…" 문단을 현재 상태로(AUDITOR 전 영역 읽기·임시 비공개 O/A 구현, 제재·등급 조정은 Phase 2, 개인정보 열람은 가릴 컬럼이 없어 미구현). ADM-010 SLA 표 두 줄(`sla_watch` 구현). ADM-200(100점 초과 → 승인 대기), ADM-311(API·산정 근거·ADJ 규칙), ADM-410(자동 숨김 배지, OPEN에서 복원, 임시 비공개 O/A), ADM-620(요청자 + 1명, 실행기 6종), ADM-700(detail·필터·커서, 조회 행위는 기록하지 않음), ADM-800(생성·역할 변경 승인, 부트스트랩 예외, 7일 유예, 비밀번호 변경). 권한 매트릭스 "관리자 계정 생성·권한 변경 ✔(2인)"과 "개인정보 필드 열람" 행의 비고.
- [ ] **Step 3: 04-development-plan.md** — §6 배치 표 `sla_watch`, §7.2 재판정 ADJ 승인 문장("미구현(SP3)" → 구현), §8 2인 승인 행("미구현(SP3) — 현행은 `PARAM_APPLY` executor 하나뿐" → 실행기 6종·요청자 + 1명), 모듈 트리 `scheduler/`에 `SlaWatchJob`.
- [ ] **Step 4: 05-screen-endpoint-map.md** — `/admin/users/**`·`/admin/accounts/{id}/role`·`/admin/me/password`·감사 로그 파라미터 행을 구현 상태로, ADM-200 VOID·재판정 행의 "`ApprovalGate` 미구현(SP3)"을 "100점 초과 → 202 승인 대기", 응답 코드 열에 401 `session-revoked`.
- [ ] **Step 5: README** — "현재 구현 상태" 표: "api-admin — 신고 큐 · 이의제기 · 어뷰징/제재 · 유저관리·등급정책" 행을 쪼개 "유저 원장(ADM-311)·계정 통제·감사 로그 필터 ✅(SP3)"와 "이의제기·어뷰징/제재·등급정책 🔴 Phase 2"로. "2인 승인 실행 경로" 행에 "요청자 + 승인자 1명, 실행기 6종". 배치 행에 `sla_watch`. Docker 절에 "첫 ADMIN이 두 번째 ADMIN을 만들면 7일 동안은 승인할 사람이 없다(부트스트랩 예외로 계정은 계속 만들 수 있다)" 한 줄.
- [ ] **Step 6: `infra/.env.example`** — 배치 스케줄 절 끝에 `# sla_watch 매시 정각(코드 기본). 바꾸려면 JOBS_SLA_WATCH_CRON=0 0 * * * *` 한 줄. (compose는 이 변수를 넘기지 않는다 — 코드 기본값을 쓴다. 넘기려면 `docker-compose.yml`의 backend `environment`에 `JOBS_SLA_WATCH_CRON: "${JOBS_SLA_WATCH_CRON:-0 0 * * * *}"`도 추가한다. 이번에는 주석만.)
- [ ] **Step 7: 확인**

```bash
grep -rnE "미구현\(SP3\)|SP3에서 (신설|구현)|ApprovalGate.*미구현|PARTIAL" CLAUDE.md 0[1-5]-*.md README.md
```

Expected: 남은 줄마다 이번 비범위(제재·등급 조정·이의 제기·2FA·PII·통보 채널)인지 확인한다. 비범위가 아닌데 남아 있으면 고친다.

- [ ] **Step 8: 커밋**

```bash
git add CLAUDE.md 02-admin-console.md 04-development-plan.md 05-screen-endpoint-map.md README.md infra/.env.example
git commit -m "docs: SP3 반영 — 승인 게이트(요청자+1인), ADM-311, 계정 통제·세션 재검증, sla_watch, 감사 로그 필터

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: 최종 검증·PR

- [ ] **Step 1: 전체 백엔드** — Global Constraints의 컨테이너 명령에 `build`. Expected: 전부 PASS. 테스트 수를 기록한다(SP2 병합 시점 141개).
- [ ] **Step 2: 정적 확인**

```bash
grep -rn "approveSecond\|ApprovalStatus.PARTIAL\|findTop200" backend --include=*.java
grep -rn "judgeService.rejudge\|judgeService.voidItem" backend --include=*.java
```

Expected: 첫 줄 결과 없음. 둘째 줄은 전부 `AdjustmentPolicy` 인자를 넘긴다(관리자 경로는 `limitedTo(ApprovalGate.THRESHOLD)`, 실행기는 `approved(...)`).

- [ ] **Step 3: 콘솔 빌드** — Task 10 Step 8.
- [ ] **Step 4: Docker 확인**(스펙 §12)
  1. `docker compose -f infra/docker-compose.yml down` → `docker volume rm infra_trd-db`(초기화 필요 없지만 부트스트랩 확인을 위해 빈 DB로) → `ADMIN_BOOTSTRAP_LOGIN_ID=admin ADMIN_BOOTSTRAP_PASSWORD=<임시값> docker compose -f infra/docker-compose.yml up -d --build`. backend healthy, 로그에 `Successfully applied 30 migrations`.
  2. 콘솔에서 `admin`으로 로그인 → 비밀번호 변경 → 로그아웃·새 비밀번호로 로그인.
  3. ADM-800에서 ADMIN `second` 생성 → "즉시 활성(부트스트랩)" · 목록에 "승인권 {7일 뒤}부터". `third` 생성도 즉시 활성(두 번째가 유예 중).
  4. 백엔드 로그에 `sla_watch 완료` 줄이 매시 정각에 찍히는지(정각을 기다리기 어려우면 생략하고 PR에 "통합 테스트로 대체"라 적는다).
  5. 확인 뒤 `down` + `volume rm infra_trd-db`로 되돌린다(임베딩 캐시는 유지).
- [ ] **Step 5: 스펙 체크** — 스펙 §12 완료 기준을 한 줄씩 확인해 PR 본문에 적는다.
- [ ] **Step 6: push·PR** (`main`은 소유자만 병합 — PR까지만)

```bash
git -c credential.helper= -c 'credential.helper=!"/c/Program Files/GitHub CLI/gh.exe" auth git-credential' push -u origin sp3-console-control
```

```bash
"/c/Program Files/GitHub CLI/gh.exe" pr create --repo KangGuYong/TRD --base main --head sp3-console-control --title "SP3: 콘솔 통제·SLA — 승인 게이트(요청자+1인), ADM-311 원장, 계정 통제, sla_watch" --body-file <본문 파일>
```

PR 본문: 요약(스펙 §0 표 10항목의 해결), 결정 K1~K12, 검증(테스트 수, 케이스 번호, 콘솔 빌드·브라우저 확인한 화면, Docker), 운영 안내(**첫 7일 승인 공백**, 비밀번호 변경 경로, 세션 재검증으로 역할 변경 즉시 재로그인), 범위 밖(스펙 §0 비범위·§13), 끝에 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
