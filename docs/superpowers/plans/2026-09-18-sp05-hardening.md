# SP0.5 즉시 수정 묶음 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관리자 콘솔의 CSRF·세션 고정·로그인 무차별 대입 방어를 넣고, 큐 요약·사이드바 뱃지·기본 설정값을 실제 상태에 맞춘다.

**Architecture:** 백엔드 먼저(Spring Security 6.3 쿠키-헤더 이중 제출 CSRF, 로그인 잠금 컬럼 V27, `QueueSummaryService` 실데이터화), 그다음 관리자 SPA(fetch 래퍼 한 곳에서 CSRF 헤더, 뱃지를 큐 요약 API에 연결). 검증은 프로젝트 첫 통합 테스트 기반(Testcontainers + 실제 PostgreSQL/pgvector)으로 한다.

**Tech Stack:** Java 17, Spring Boot 3.3.5 (Spring Security 6.3), Flyway, PostgreSQL 16 + pgvector, Testcontainers, JUnit 5, MockMvc; React 18 + Vite + TypeScript + TanStack Query.

**Spec:** `docs/superpowers/specs/2026-09-18-sp05-hardening-design.md`

---

## 전제 (모든 태스크 공통)

- 브랜치: `sp0.5-hardening`. 시작 전 `git -C /d/project/TRD branch --show-current`로 확인. 다른 브랜치로 전환하지 않는다.
- 작업 트리에 사용자 소유의 미커밋 변경(`README.md`)과 미추적 파일(`.claude/worktrees/`, `06-pitch-deck-outline.md`, `AGENTS.md`, `hs_err_*.log`, `replay_*.log`, `backend/persistence/src/main/resources/db/seed/merge_queue_test_data.sql`)이 있다. **`git add -A`·`git add .` 금지.** 항상 파일명을 지정한다.
- `backend/app/.env`는 사용자 소유 파일이다. **읽기·수정 금지.**
- 백엔드 명령은 `backend/` 디렉터리에서 `./gradlew …`(Windows에서도 Bash 도구로 실행). 통합 테스트는 **Docker가 실행 중**이어야 한다(`docker info`로 확인).
- 커밋 메시지 끝에 반드시 다음 줄:
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  ```
- 이미 적용된 Flyway 마이그레이션(V1~V26)은 **절대 수정하지 않는다**(체크섬 불일치로 기동 실패). V26의 "4h 자동 임시비공개는 의도적으로 넣지 않는다" 주석도 그대로 둔다.
- 순서: Task 1~6 백엔드(API 먼저) → Task 7~9 프론트 → Task 10 문서 → Task 11 최종 검증·PR.

## 파일 구조

| 파일 | 책임 | 태스크 |
|---|---|---|
| `backend/app/build.gradle.kts` | 테스트 의존성 | 1 |
| `backend/app/src/test/java/kr/trendstage/support/AbstractIntegrationTest.java` | Testcontainers PG + SpringBootTest + MockMvc 공통 기반 | 1 |
| `backend/app/src/test/java/kr/trendstage/support/MutableClock.java` | 테스트용 조작 가능한 Clock | 1 |
| `backend/app/src/test/java/kr/trendstage/support/TestClockConfig.java` | `@Primary` Clock 빈 | 1 |
| `backend/app/src/test/java/kr/trendstage/ContextSmokeTest.java` | 컨텍스트 기동 + Flyway 전체 적용 확인 | 1 |
| `backend/persistence/src/main/resources/db/migration/V27__admin_login_hardening.sql` | 잠금 컬럼 + twofa 정렬 | 2 |
| `backend/persistence/src/main/java/kr/trendstage/persistence/entity/AdminAccount.java` | 잠금 상태·메서드, twofa 기본값 | 2 |
| `backend/app/src/test/java/kr/trendstage/admin/AdminAccountDefaultsTest.java` | twofa 기본값 테스트 | 2 |
| `backend/api-admin/src/main/java/kr/trendstage/apiadmin/auth/AccountLockedException.java` | 잠금 예외 | 3 |
| `backend/api-admin/src/main/java/kr/trendstage/apiadmin/auth/AdminAccountService.java` | 잠금 로직 | 3 |
| `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminApiExceptionHandler.java` | 423 매핑 | 3 |
| `backend/app/src/test/java/kr/trendstage/admin/AdminLoginLockoutTest.java` | 잠금 테스트 | 3 |
| `backend/api-admin/src/main/java/kr/trendstage/apiadmin/config/SpaCsrfTokenRequestHandler.java` | SPA용 CSRF 요청 핸들러 | 4 |
| `backend/api-admin/src/main/java/kr/trendstage/apiadmin/config/CsrfCookieFilter.java` | 응답마다 토큰 로드 | 4 |
| `backend/api-admin/src/main/java/kr/trendstage/apiadmin/config/AdminSecurityConfig.java` | CSRF 활성화 | 4 |
| `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminAuthController.java` | `/auth/csrf`, 세션 고정 방어 | 4 |
| `backend/api-public/src/main/java/kr/trendstage/apipublic/config/PublicSecurityConfig.java` | CSRF 비대상 사유 주석 | 4 |
| `backend/app/src/test/java/kr/trendstage/admin/AdminCsrfTest.java` | CSRF 테스트 | 4 |
| `backend/persistence/src/main/java/kr/trendstage/persistence/repo/ReportRepository.java` | 상태별 카운트 | 5 |
| `backend/api-admin/src/main/java/kr/trendstage/apiadmin/queues/QueueSummaryService.java` | ADM-410 실데이터, `available`, 알림 문구 | 5 |
| `backend/app/src/test/java/kr/trendstage/admin/QueueSummaryTest.java` | 큐 요약 테스트 | 5 |
| `backend/app/src/main/resources/application.yml` | DB 기본 호스트 | 6 |
| `admin/src/api/client.ts` | CSRF 헤더, `ensureCsrf`, `USE_FIXTURES` | 7 |
| `admin/src/state/auth.tsx` | 부팅·로그인 전 `ensureCsrf` | 7 |
| `admin/.env.example` | 신설 | 7 |
| `admin/src/api/types.ts`, `admin/src/fixtures.ts` | `available` | 8 |
| `admin/src/components/Layout.tsx`, `admin/src/screens/TodayScreen.tsx` | 뱃지·타일 | 8 |
| `backend/api-spec/openapi.yaml`, `05-screen-endpoint-map.md`, `CLAUDE.md`, `02-admin-console.md`, `04-development-plan.md`, `docs/superpowers/specs/2026-09-17-design-review-design.md` | 문서 | 10 |

---

### Task 1: 통합 테스트 기반 (Testcontainers)

**Files:**
- Modify: `backend/app/build.gradle.kts`
- Create: `backend/app/src/test/java/kr/trendstage/support/MutableClock.java`
- Create: `backend/app/src/test/java/kr/trendstage/support/TestClockConfig.java`
- Create: `backend/app/src/test/java/kr/trendstage/support/AbstractIntegrationTest.java`
- Create: `backend/app/src/test/java/kr/trendstage/ContextSmokeTest.java`

- [ ] **Step 1: Docker 확인**

Run: `docker info --format '{{.ServerVersion}}'`
Expected: 버전 문자열(예: `29.2.1`). 오류면 사용자에게 Docker Desktop 실행을 요청하고 멈춘다(BLOCKED).

- [ ] **Step 2: 테스트 의존성 추가**

`backend/app/build.gradle.kts`의 `dependencies { … }` 블록 끝(`spring-dotenv` 줄 다음)에 추가:

```kotlin
    // 통합 테스트: 실제 PostgreSQL(pgvector)을 Testcontainers로 띄워 Flyway 전체 적용 + 보안 필터체인까지 검증.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
```

같은 파일 끝에 JUnit 플랫폼 설정 추가:

```kotlin
tasks.withType<Test> {
    useJUnitPlatform()
}
```

(버전은 Spring Boot BOM이 관리한다 — `io.spring.dependency-management` 플러그인이 이미 적용돼 있다.)

- [ ] **Step 3: `MutableClock` 작성**

```java
package kr.trendstage.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트에서 시각을 앞당기기 위한 Clock. 기본은 실제 현재 시각에서 시작. */
public class MutableClock extends Clock {

    private volatile Instant now = Instant.now();

    public void set(Instant instant) { this.now = instant; }
    public void advance(Duration d) { this.now = this.now.plus(d); }
    public void reset() { this.now = Instant.now(); }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
}
```

- [ ] **Step 4: `TestClockConfig` 작성**

```java
package kr.trendstage.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 운영 ClockConfig(systemUTC)보다 우선하는 조작 가능한 Clock. */
@TestConfiguration
public class TestClockConfig {
    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock();
    }
}
```

- [ ] **Step 5: `AbstractIntegrationTest` 작성**

```java
package kr.trendstage.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트 공통 기반. 실제 PostgreSQL(pgvector, infra/docker-compose와 같은 이미지)을 한 번 띄워
 * 모든 테스트 클래스가 공유한다 — Flyway가 V1부터 전부 적용되므로 신규 마이그레이션 검증도 겸한다.
 *
 * Docker가 없으면 컨테이너 기동에서 실패한다. disabledWithoutDocker로 조용히 건너뛰게 하지 않는다 —
 * 테스트를 돌리지 않고 초록불이 뜨면 검증한 것처럼 보이기 때문이다.
 */
@SpringBootTest(properties = {
        // 로컬 전용 application-local.yml(있다면)이 켜는 SQL·바인딩 로그를 테스트에서는 끈다.
        "logging.level.org.hibernate.SQL=WARN",
        "logging.level.org.hibernate.orm.jdbc.bind=WARN"
})
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("trd")
            .withUsername("postgres")
            .withPassword("pw");

    static {
        POSTGRES.start();
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.reset();
    }
}
```

- [ ] **Step 6: 스모크 테스트 작성**

```java
package kr.trendstage;

import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextSmokeTest extends AbstractIntegrationTest {

    @Test
    void contextLoadsAndFlywayAppliedAllMigrations() {
        Integer failed = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(failed).isZero();
        assertThat(applied).isGreaterThanOrEqualTo(26);
    }
}
```

- [ ] **Step 7: 실행**

Run (in `backend/`): `./gradlew :app:test --tests kr.trendstage.ContextSmokeTest`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

**문제 해결:**
- `Could not resolve placeholder 'FIREBASE_CREDENTIALS_PATH'` 또는 Firebase 초기화 오류가 나면: Gradle 테스트의 작업 디렉터리는 `backend/app`이고 `spring-dotenv`가 `backend/app/.env`를 읽는다. 이 환경에서는 `.env`와 `firebase-service-account.json`이 있으므로 정상 기동해야 한다. 실패하면 **`.env`를 수정하지 말고** 오류 전문과 함께 NEEDS_CONTEXT로 보고한다.
- 다른 빈 초기화 실패도 우회 코드를 넣지 말고 오류 전문과 함께 보고한다.

- [ ] **Step 8: 커밋**

```bash
git add backend/app/build.gradle.kts backend/app/src/test/java/kr/trendstage/support/MutableClock.java backend/app/src/test/java/kr/trendstage/support/TestClockConfig.java backend/app/src/test/java/kr/trendstage/support/AbstractIntegrationTest.java backend/app/src/test/java/kr/trendstage/ContextSmokeTest.java
git commit -m "test(app): Testcontainers 기반 통합 테스트 인프라 도입

pgvector/pgvector:pg16 컨테이너를 공유하는 AbstractIntegrationTest,
조작 가능한 MutableClock, Flyway 전체 적용 스모크 테스트.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: V27 마이그레이션 + `AdminAccount` 잠금 상태·twofa 기본값

**Files:**
- Create: `backend/persistence/src/main/resources/db/migration/V27__admin_login_hardening.sql`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/AdminAccount.java`
- Test: `backend/app/src/test/java/kr/trendstage/admin/AdminAccountDefaultsTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.trendstage.admin;

import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAccountDefaultsTest extends AbstractIntegrationTest {

    @Autowired AdminAccountRepository accounts;

    @Test
    void jpaCreatedAccountHasTwofaEnabledAndNoLock() {
        AdminAccount saved = accounts.saveAndFlush(
                new AdminAccount("defaults-" + UUID.randomUUID(), "기본값", AdminRole.OPERATOR, "x"));

        Boolean twofa = jdbc.queryForObject(
                "SELECT twofa_enabled FROM admin_accounts WHERE id = ?", Boolean.class, saved.getId());
        Integer failed = jdbc.queryForObject(
                "SELECT failed_login_count FROM admin_accounts WHERE id = ?", Integer.class, saved.getId());

        assertThat(twofa).isTrue();
        assertThat(failed).isZero();
        assertThat(saved.getLockedUntil()).isNull();
    }

    @Test
    void fifthFailureLocksAndResetsCounter() {
        AdminAccount a = new AdminAccount("lockunit-" + UUID.randomUUID(), "잠금", AdminRole.OPERATOR, "x");
        Instant now = Instant.parse("2026-09-18T00:00:00Z");

        for (int i = 1; i <= 4; i++) {
            assertThat(a.recordFailedLogin(now, 5, Duration.ofMinutes(15))).isFalse();
        }
        assertThat(a.recordFailedLogin(now, 5, Duration.ofMinutes(15))).isTrue();

        assertThat(a.isLocked(now)).isTrue();
        assertThat(a.isLocked(now.plus(Duration.ofMinutes(15)))).isFalse();
        assertThat(a.getFailedLoginCount()).isZero();

        a.recordLogin(now.plus(Duration.ofMinutes(16)));
        assertThat(a.getLockedUntil()).isNull();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:test --tests kr.trendstage.admin.AdminAccountDefaultsTest`
Expected: 컴파일 실패 — `getLockedUntil()`, `recordFailedLogin(...)`, `isLocked(...)`, `getFailedLoginCount()` 없음.

- [ ] **Step 3: 마이그레이션 작성**

`V27__admin_login_hardening.sql`:

```sql
-- V27 · 관리자 로그인 보강 (SP0.5)
-- 1) 무차별 대입 방어: 5회 연속 실패 시 15분 자동 잠금(수동 해제 경로 없음 — 시간 경과로만 해제).
--    카운터·잠금 시각은 계정 단위. 성공 로그인 시 초기화.
ALTER TABLE admin_accounts
    ADD COLUMN failed_login_count INT NOT NULL DEFAULT 0,
    ADD COLUMN locked_until       TIMESTAMPTZ;

COMMENT ON COLUMN admin_accounts.failed_login_count IS '연속 로그인 실패 횟수. 성공 또는 잠금 시 0으로 초기화.';
COMMENT ON COLUMN admin_accounts.locked_until IS '이 시각 전까지 로그인 거부(423). NULL 또는 과거면 잠금 아님.';

-- 2) twofa_enabled 정렬: DB 기본값은 TRUE인데 JPA 엔티티 기본값이 false여서 콘솔로 만든 계정이
--    전부 false로 들어갔다. 2FA 도입 시 기존 계정이 면제 상태로 남지 않도록 일괄 TRUE.
--    의미는 "이 계정은 2FA 적용 대상"(2FA 자체는 미구현 — SP3). 현재 이 값을 읽는 코드는 없다.
UPDATE admin_accounts SET twofa_enabled = TRUE;
COMMENT ON COLUMN admin_accounts.twofa_enabled IS '이 계정이 2FA 적용 대상인지. 2FA 검사는 미구현(SP3).';
```

- [ ] **Step 4: 엔티티 수정**

`AdminAccount.java`에서:

클래스 Javadoc을 교체:
```java
/**
 * 관리자 계정 (admin_accounts). 앱 users와 분리.
 * twofaEnabled는 "2FA 적용 대상" 표시 — 2FA 검사 자체는 미구현(SP3).
 * 로그인 잠금: 연속 실패가 임계에 닿으면 lockedUntil까지 로그인 거부(V27).
 */
```

필드 `twofaEnabled` 기본값 변경:
```java
    @Column(name = "twofa_enabled", nullable = false)
    private boolean twofaEnabled = true;
```

`seedUserId` 필드 다음에 추가:
```java
    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;
```

기존 `public void recordLogin(Instant at) { this.lastLoginAt = at; }`를 교체하고 잠금 메서드 추가:
```java
    /** 로그인 성공 — 마지막 로그인 시각 기록, 실패 카운터·잠금 초기화. */
    public void recordLogin(Instant at) {
        this.lastLoginAt = at;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /**
     * 로그인 실패 기록. 임계에 닿으면 now+lockFor까지 잠그고 카운터를 0으로 되돌린다
     * (잠금이 풀린 뒤 다시 maxFailures번의 기회를 준다).
     * @return 이번 실패로 잠금이 걸렸으면 true
     */
    public boolean recordFailedLogin(Instant now, int maxFailures, java.time.Duration lockFor) {
        this.failedLoginCount++;
        if (this.failedLoginCount >= maxFailures) {
            this.lockedUntil = now.plus(lockFor);
            this.failedLoginCount = 0;
            return true;
        }
        return false;
    }
```

getter 추가(`getSeedUserId()` 다음):
```java
    public int getFailedLoginCount() { return failedLoginCount; }
    public Instant getLockedUntil() { return lockedUntil; }
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :app:test --tests kr.trendstage.admin.AdminAccountDefaultsTest --tests kr.trendstage.ContextSmokeTest`
Expected: PASS (3 tests). `ddl-auto: validate`가 새 컬럼과 엔티티 매핑을 검증한다.

- [ ] **Step 6: 커밋**

```bash
git add backend/persistence/src/main/resources/db/migration/V27__admin_login_hardening.sql backend/persistence/src/main/java/kr/trendstage/persistence/entity/AdminAccount.java backend/app/src/test/java/kr/trendstage/admin/AdminAccountDefaultsTest.java
git commit -m "feat(persistence): V27 관리자 로그인 잠금 컬럼, twofa_enabled 기본값 DB와 일치

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: 로그인 잠금 (서비스·예외·423)

**Files:**
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/auth/AccountLockedException.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/auth/AdminAccountService.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminApiExceptionHandler.java`
- Test: `backend/app/src/test/java/kr/trendstage/admin/AdminLoginLockoutTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

로그인 요청에는 `csrf().asHeader()`를 붙인다 — 지금은 CSRF가 꺼져 있어 무해하고, Task 4에서 켠 뒤에도 그대로 통과해야 한다.

```java
package kr.trendstage.admin;

import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminLoginLockoutTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-1";

    @Autowired AdminAccountRepository accounts;
    @Autowired PasswordEncoder passwordEncoder;

    private String loginId;
    private UUID accountId;

    @BeforeEach
    void createAccount() {
        loginId = "lock-" + UUID.randomUUID();
        AdminAccount a = accounts.saveAndFlush(
                new AdminAccount(loginId, "잠금테스트", AdminRole.OPERATOR, passwordEncoder.encode(PASSWORD)));
        accountId = a.getId();
    }

    private ResultActions login(String password) throws Exception {
        return mvc.perform(post("/admin/auth/login")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\"}"));
    }

    private int failedCount() {
        return jdbc.queryForObject("SELECT failed_login_count FROM admin_accounts WHERE id = ?",
                Integer.class, accountId);
    }

    private boolean lockedUntilSet() {
        return jdbc.queryForObject("SELECT locked_until IS NOT NULL FROM admin_accounts WHERE id = ?",
                Boolean.class, accountId);
    }

    @Test
    void fourFailuresDoNotLock_andCounterPersists() throws Exception {
        for (int i = 0; i < 4; i++) login("wrong").andExpect(status().isUnauthorized());
        // 카운터가 예외와 함께 롤백되지 않았는지(noRollbackFor 회귀)
        assertThat(failedCount()).isEqualTo(4);
        assertThat(lockedUntilSet()).isFalse();
    }

    @Test
    void fifthFailureLocks_andIsAudited() throws Exception {
        for (int i = 0; i < 5; i++) login("wrong").andExpect(status().isUnauthorized());

        assertThat(lockedUntilSet()).isTrue();
        Integer audits = jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_log WHERE action = 'LOGIN_LOCKED' AND target_id = ?",
                Integer.class, accountId);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void lockedAccountRejectsEvenCorrectPasswordWith423() throws Exception {
        for (int i = 0; i < 5; i++) login("wrong");

        login(PASSWORD)
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.status").value(423))
                .andExpect(jsonPath("$.detail").value("로그인 시도 횟수를 초과했습니다. 잠시 후 다시 시도하세요"));
    }

    @Test
    void lockExpiresAfter15Minutes_andSuccessResets() throws Exception {
        for (int i = 0; i < 5; i++) login("wrong");

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        login(PASSWORD).andExpect(status().isOk());
        assertThat(failedCount()).isZero();
        assertThat(lockedUntilSet()).isFalse();
    }

    @Test
    void successResetsCounter() throws Exception {
        for (int i = 0; i < 3; i++) login("wrong");
        login(PASSWORD).andExpect(status().isOk());
        for (int i = 0; i < 4; i++) login("wrong").andExpect(status().isUnauthorized());

        assertThat(lockedUntilSet()).isFalse();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:test --tests kr.trendstage.admin.AdminLoginLockoutTest`
Expected: FAIL — `fourFailuresDoNotLock_andCounterPersists`(카운터 0), `fifthFailureLocks…`, `lockedAccount…423`(200 또는 401) 등.

- [ ] **Step 3: 예외 클래스**

```java
package kr.trendstage.apiadmin.auth;

/** 연속 로그인 실패로 잠긴 계정. 423으로 매핑된다(AdminApiExceptionHandler). */
public class AccountLockedException extends RuntimeException {
    public AccountLockedException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: 서비스 수정**

`AdminAccountService.java` 전체를 다음으로 교체:

```java
package kr.trendstage.apiadmin.auth;

import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 관리자 로그인 검증. 2FA는 검사하지 않는다(미구현, SP3).
 * 무차별 대입 방어: 연속 MAX_FAILED_LOGINS회 실패 시 LOCK_DURATION 동안 잠금(V27).
 * 기준값은 도메인 파라미터가 아니므로 파라미터 스튜디오 대상이 아니다.
 */
@Service
public class AdminAccountService {

    static final int MAX_FAILED_LOGINS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    static final String LOCKED_MESSAGE = "로그인 시도 횟수를 초과했습니다. 잠시 후 다시 시도하세요";
    private static final String INVALID_MESSAGE = "아이디 또는 비밀번호가 올바르지 않습니다";

    private final AdminAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public AdminAccountService(AdminAccountRepository repository, PasswordEncoder passwordEncoder,
                                AuditLogService auditLogService, Clock clock) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /**
     * noRollbackFor: 실패는 RuntimeException으로 알리지만, 실패 카운터·잠금 시각 증가는 커밋돼야 한다.
     * 이게 없으면 카운터가 예외와 함께 롤백되어 잠금이 절대 걸리지 않는다.
     * (감사 로그는 AuditLogService가 REQUIRES_NEW라 원래부터 영향 없음.)
     */
    @Transactional(noRollbackFor = {InvalidCredentialsException.class, AccountLockedException.class})
    public AdminAccount authenticate(String loginId, String rawPassword) {
        AdminAccount account = repository.findByLoginId(loginId)
                .orElseThrow(() -> new InvalidCredentialsException(INVALID_MESSAGE));

        if (account.getDisabledAt() != null) {
            throw new AccountDisabledException("비활성화된 계정입니다");
        }

        Instant now = clock.instant();

        // 잠긴 동안에는 비밀번호를 검사하지 않는다 — 맞았는지 틀렸는지 알려주지 않기 위해.
        if (account.isLocked(now)) {
            throw new AccountLockedException(LOCKED_MESSAGE);
        }

        if (!passwordEncoder.matches(rawPassword, account.getPasswordHash())) {
            auditLogService.record(null, null, "LOGIN_FAILED", "ADMIN_ACCOUNT", account.getId(),
                    Map.of("loginId", loginId));
            boolean lockedNow = account.recordFailedLogin(now, MAX_FAILED_LOGINS, LOCK_DURATION);
            if (lockedNow) {
                auditLogService.record(null, null, "LOGIN_LOCKED", "ADMIN_ACCOUNT", account.getId(),
                        Map.of("loginId", loginId,
                               "failedCount", MAX_FAILED_LOGINS,
                               "lockedUntil", account.getLockedUntil().toString()));
            }
            throw new InvalidCredentialsException(INVALID_MESSAGE);
        }

        account.recordLogin(now);
        return account;
    }
}
```

- [ ] **Step 5: 423 매핑**

`AdminApiExceptionHandler.java`:
import 추가 `import kr.trendstage.apiadmin.auth.AccountLockedException;`
`AccountDisabledException` 핸들러 바로 다음에 추가:
```java
    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<Map<String, Object>> handle(AccountLockedException e) {
        return ResponseEntity.status(HttpStatus.LOCKED).body(problem(423, e.getMessage()));
    }
```

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :app:test --tests kr.trendstage.admin.AdminLoginLockoutTest`
Expected: PASS (5 tests).

- [ ] **Step 7: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/auth/AccountLockedException.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/auth/AdminAccountService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminApiExceptionHandler.java backend/app/src/test/java/kr/trendstage/admin/AdminLoginLockoutTest.java
git commit -m "feat(api-admin): 관리자 로그인 5회 연속 실패 시 15분 잠금(423)

잠금 중에는 비밀번호를 검사하지 않고, 실패 카운터가 예외와 함께
롤백되지 않도록 noRollbackFor를 건다. 잠금 시 LOGIN_LOCKED 감사 기록.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: CSRF 활성화 + 세션 고정 방어

**Files:**
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/config/SpaCsrfTokenRequestHandler.java`
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/config/CsrfCookieFilter.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/config/AdminSecurityConfig.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminAuthController.java`
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/config/PublicSecurityConfig.java` (주석만)
- Test: `backend/app/src/test/java/kr/trendstage/admin/AdminCsrfTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

이 테스트는 `csrf()` 테스트 헬퍼를 쓰지 않고 **SPA와 같은 실제 흐름**(쿠키 받기 → 헤더로 되돌려 보내기)으로 검증한다.

```java
package kr.trendstage.admin;

import jakarta.servlet.http.Cookie;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminCsrfTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "csrf-pass-1";
    private static final String COOKIE = "XSRF-TOKEN";
    private static final String HEADER = "X-XSRF-TOKEN";

    @Autowired AdminAccountRepository accounts;
    @Autowired PasswordEncoder passwordEncoder;

    private String loginId;

    @BeforeEach
    void createAccount() {
        loginId = "csrf-" + UUID.randomUUID();
        accounts.saveAndFlush(new AdminAccount(loginId, "CSRF", AdminRole.OPERATOR, passwordEncoder.encode(PASSWORD)));
    }

    private String body() {
        return "{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private Cookie fetchCsrfCookie() throws Exception {
        MvcResult r = mvc.perform(get("/admin/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists(COOKIE))
                .andExpect(cookie().httpOnly(COOKIE, false))
                .andReturn();
        return r.getResponse().getCookie(COOKIE);
    }

    @Test
    void loginWithoutTokenIs403() throws Exception {
        mvc.perform(post("/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isForbidden());
    }

    @Test
    void csrfEndpointIssuesReadableCookie() throws Exception {
        Cookie c = fetchCsrfCookie();
        assertThat(c.getValue()).isNotBlank();
    }

    @Test
    void loginWithTokenSucceeds_rotatesSessionAndToken() throws Exception {
        Cookie token = fetchCsrfCookie();
        MockHttpSession session = new MockHttpSession();
        String sessionIdBefore = session.getId();

        MvcResult r = mvc.perform(post("/admin/auth/login")
                        .session(session)
                        .cookie(token).header(HEADER, token.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk())
                .andExpect(cookie().exists(COOKIE))
                .andReturn();

        MockHttpServletResponse res = r.getResponse();
        assertThat(res.getCookie(COOKIE).getValue()).isNotEqualTo(token.getValue());
        assertThat(r.getRequest().getSession(false).getId()).isNotEqualTo(sessionIdBefore);
    }

    @Test
    void authenticatedWriteRequiresToken() throws Exception {
        Cookie token = fetchCsrfCookie();
        MvcResult login = mvc.perform(post("/admin/auth/login")
                        .cookie(token).header(HEADER, token.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        Cookie rotated = login.getResponse().getCookie(COOKIE);

        mvc.perform(post("/admin/auth/logout").session(session))
                .andExpect(status().isForbidden());

        mvc.perform(post("/admin/auth/logout").session(session)
                        .cookie(rotated).header(HEADER, rotated.getValue()))
                .andExpect(status().isOk());
    }

    @Test
    void publicApiChainIsUnaffected() throws Exception {
        // 앱 체인은 STATELESS Bearer — CSRF 대상이 아니므로 토큰 없이도 403이 아니라 인증 실패(401)여야 한다.
        mvc.perform(post("/v1/submissions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:test --tests kr.trendstage.admin.AdminCsrfTest`
Expected: FAIL — `/admin/auth/csrf` 없음(401), 토큰 없는 로그인이 403이 아님 등. `publicApiChainIsUnaffected`는 이미 통과할 수 있다(정상).

- [ ] **Step 3: `SpaCsrfTokenRequestHandler`**

```java
package kr.trendstage.apiadmin.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * SPA용 CSRF 요청 핸들러(Spring Security 6.3 공식 문서 예제. 6.4의 csrf.spa()에 해당).
 * - 응답 렌더링은 XOR 핸들러에 위임(BREACH 방어).
 * - SPA는 쿠키 원문을 X-XSRF-TOKEN 헤더로 보내므로, 헤더가 있으면 원문 그대로 해석한다.
 */
public final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

    private final CsrfTokenRequestHandler delegate = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        this.delegate.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        if (StringUtils.hasText(request.getHeader(csrfToken.getHeaderName()))) {
            return super.resolveCsrfTokenValue(request, csrfToken);
        }
        return this.delegate.resolveCsrfTokenValue(request, csrfToken);
    }
}
```

- [ ] **Step 4: `CsrfCookieFilter`**

```java
package kr.trendstage.apiadmin.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 지연 로딩되는 CSRF 토큰을 요청마다 로드해, 새로 만들어진 토큰이 응답 쿠키로 실리게 한다.
 * 이게 없으면 토큰을 한 번도 읽지 않은 응답에는 XSRF-TOKEN 쿠키가 발급되지 않는다.
 */
final class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute("_csrf");
        if (csrfToken != null) {
            csrfToken.getToken();
        }
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 5: `AdminSecurityConfig` 수정**

클래스 Javadoc 교체:
```java
/**
 * 콘솔 API(/admin/**) 보안 체인. 앱(/v1/**)과 별도 필터체인으로 분리(04 §2.2).
 * 세션 기반(단일 인스턴스, 인메모리 HttpSession). 2FA는 미구현(SP3).
 *
 * CSRF: 쿠키-헤더 이중 제출. 서버가 XSRF-TOKEN 쿠키(JS 읽기 가능)를 발급하고, SPA는 쓰기 요청마다
 * 그 값을 X-XSRF-TOKEN 헤더로 되돌려 보낸다. 로그인 요청도 CSRF 검사를 받는다(login CSRF 방지) —
 * SPA는 부팅 시 GET /admin/auth/csrf로 쿠키를 먼저 받는다.
 */
```

import 추가:
```java
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
```

`securityContextRepository()` 빈 다음에 추가:
```java
    /** 로그인 성공 시 컨트롤러가 토큰을 재발급해야 하므로 체인과 같은 인스턴스를 빈으로 공유. */
    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setCookiePath("/");
        return repo;
    }
```

`adminApi` 빈 시그니처와 본문 교체:
```java
    @Bean
    @Order(2)
    public SecurityFilterChain adminApi(HttpSecurity http, SecurityContextRepository securityContextRepository,
                                         CookieCsrfTokenRepository csrfTokenRepository) throws Exception {
        http
            .securityMatcher("/admin/**")
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/admin/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/admin/auth/login").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
```

- [ ] **Step 6: `AdminAuthController` 수정**

import 추가:
```java
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
```

필드·생성자에 `CsrfTokenRepository csrfTokenRepository` 추가:
```java
    private final CsrfTokenRepository csrfTokenRepository;

    public AdminAuthController(AdminAccountService accountService, AuditLogService auditLogService,
                                SecurityContextRepository securityContextRepository,
                                CsrfTokenRepository csrfTokenRepository) {
        this.accountService = accountService;
        this.auditLogService = auditLogService;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
    }
```

`login()` 안에서 `accountService.authenticate(...)` 다음, `AdminPrincipal principal = …` 앞에 삽입:
```java
        // 세션 고정 방어: 컨트롤러가 직접 로그인하므로 Spring 인증 필터의 세션 ID·CSRF 토큰 교체가
        // 일어나지 않는다. 여기서 명시적으로 수행한다. 세션이 아직 없으면 saveContext가 새로 만든다.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        CsrfToken rotated = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(rotated, request, response);
```

`me()` 메서드 다음에 추가:
```java
    /** SPA 부팅용 — 응답에 XSRF-TOKEN 쿠키를 싣는 것 외에 하는 일이 없다(CsrfCookieFilter가 발급). */
    @GetMapping("/auth/csrf")
    public void csrf() {
    }
```

- [ ] **Step 7: `PublicSecurityConfig` 주석**

`.csrf(csrf -> csrf.disable())` 줄 바로 위에 주석 추가(코드는 그대로):
```java
            // /v1/**는 STATELESS Bearer JWT — 브라우저가 자격증명을 자동 첨부하지 않으므로 CSRF 대상이 아니다.
            // (쿠키 세션을 쓰는 /admin/**만 CSRF를 켠다 — AdminSecurityConfig)
```

- [ ] **Step 8: 통과 확인**

Run: `./gradlew :app:test --tests kr.trendstage.admin.AdminCsrfTest --tests kr.trendstage.admin.AdminLoginLockoutTest`
Expected: PASS (10 tests). 잠금 테스트는 `csrf().asHeader()` 덕에 CSRF가 켜진 뒤에도 통과해야 한다.

- [ ] **Step 9: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/config/SpaCsrfTokenRequestHandler.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/config/CsrfCookieFilter.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/config/AdminSecurityConfig.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/AdminAuthController.java backend/api-public/src/main/java/kr/trendstage/apipublic/config/PublicSecurityConfig.java backend/app/src/test/java/kr/trendstage/admin/AdminCsrfTest.java
git commit -m "feat(api-admin): 관리자 콘솔 CSRF 활성화(쿠키-헤더 이중 제출) + 세션 고정 방어

XSRF-TOKEN 쿠키 → X-XSRF-TOKEN 헤더. 로그인도 CSRF 검사 대상.
GET /admin/auth/csrf로 SPA 부팅 시 쿠키 발급. 로그인 성공 시 세션 ID와
CSRF 토큰을 교체한다. /v1/**(Bearer)는 대상 아님.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: 큐 요약 — ADM-410 실데이터, `available`, 알림 문구

**Files:**
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/ReportRepository.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/queues/QueueSummaryService.java`
- Test: `backend/app/src/test/java/kr/trendstage/admin/QueueSummaryTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

서비스를 직접 호출한다. 컨테이너를 모든 테스트가 공유하므로 매 테스트 시작 시 `reports`를 비운다(append-only 트리거 대상 아님). 신고의 `created_at`은 JDBC로 명시해 시계와 맞춘다.

```java
package kr.trendstage.admin;

import kr.trendstage.apiadmin.queues.QueueSummaryService;
import kr.trendstage.apiadmin.queues.QueueSummaryService.QueueSummaryResponse;
import kr.trendstage.apiadmin.queues.QueueSummaryService.QueueTile;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueueSummaryTest extends AbstractIntegrationTest {

    @Autowired QueueSummaryService service;

    private UUID userId;
    private UUID trendItemId;
    private final Instant now = Instant.parse("2026-09-18T12:00:00Z");

    @BeforeEach
    void setUp() {
        clock.set(now);
        jdbc.update("DELETE FROM reports");
        userId = UUID.randomUUID();
        trendItemId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, handle) VALUES (?, ?)", userId, "qs-" + userId);
        jdbc.update("INSERT INTO trend_items (id, canonical_name, normalized_key, category, state, first_seen_at) "
                        + "VALUES (?, ?, ?, 'MEME'::trend_category, 'PENDING'::trend_state, ?)",
                trendItemId, "큐요약", "qs-" + trendItemId, Timestamp.from(now));
    }

    private void report(String status, Duration age) {
        jdbc.update("INSERT INTO reports (trend_item_id, reporter_id, reason, status, created_at) "
                        + "VALUES (?, ?, 'OTHER'::report_reason, ?::report_status, ?)",
                trendItemId, userId, status, Timestamp.from(now.minus(age)));
    }

    private QueueTile tile(QueueSummaryResponse r, String id) {
        return r.queues().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void reportQueueCountsOpenAndExplaining_excludesDecided() {
        report("OPEN", Duration.ofHours(1));
        report("EXPLAINING", Duration.ofHours(10));
        report("DECIDED", Duration.ofHours(1));

        QueueTile t = tile(service.summarize(), "ADM-410");

        assertThat(t.count()).isEqualTo(2);
        assertThat(t.available()).isTrue();
        assertThat(t.slaExceeded()).isFalse();
        assertThat(t.oldest()).isEqualTo("1h");
    }

    @Test
    void openReportOlderThan4hBreachesSla() {
        report("OPEN", Duration.ofHours(5));

        QueueSummaryResponse r = service.summarize();
        QueueTile t = tile(r, "ADM-410");

        assertThat(t.slaExceeded()).isTrue();
        assertThat(r.slaBreaches()).isGreaterThanOrEqualTo(1);
        assertThat(r.alerts()).anyMatch(a -> a.title().startsWith("신고 콘텐츠 SLA 초과"));
    }

    @Test
    void explainingOlderThan4hDoesNotBreachSla() {
        report("EXPLAINING", Duration.ofHours(30));

        assertThat(tile(service.summarize(), "ADM-410").slaExceeded()).isFalse();
    }

    @Test
    void unimplementedQueuesAreMarkedUnavailable() {
        QueueSummaryResponse r = service.summarize();

        assertThat(tile(r, "ADM-300").available()).isFalse();
        assertThat(tile(r, "ADM-400").available()).isFalse();
        assertThat(tile(r, "ADM-100").available()).isTrue();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:test --tests kr.trendstage.admin.QueueSummaryTest`
Expected: 컴파일 실패 — `QueueTile.available()` 없음.

- [ ] **Step 3: 리포지토리 메서드 추가**

`ReportRepository.java`에 추가(기존 메서드 유지):
```java
    /** ADM-010 큐 요약 — 결정이 남은 신고 수(OPEN + EXPLAINING). */
    long countByStatusIn(List<ReportStatus> statuses);

    /** ADM-010 SLA — 1차 처리(OPEN) 대기 중인 신고, 오래된 순. */
    List<Report> findByStatusOrderByCreatedAtAsc(ReportStatus status);
```

- [ ] **Step 4: 서비스 수정**

`QueueSummaryService.java`:

import 추가:
```java
import kr.trendstage.persistence.entity.Report;
import kr.trendstage.persistence.repo.ReportRepository;
import kr.trendstage.persistence.type.ReportStatus;
import java.util.ArrayList;
```

클래스 Javadoc 교체:
```java
/**
 * ADM-010 오늘의 작업. 병합 검수(ADM-100)와 신고 콘텐츠(ADM-410)는 실데이터.
 * 어뷰징(ADM-300)·이의 제기(ADM-400)는 큐 자체가 없어(Phase 2) available=false로 응답한다 —
 * 0건으로 표시하면 "처리할 게 없다"로 읽히므로 미구현임을 숨기지 않는다.
 */
```

상수 추가(`MERGE_SLA_HOURS` 다음):
```java
    private static final int REPORT_SLA_HOURS = 4;
```

필드·생성자에 `ReportRepository reports` 추가:
```java
    private final ReportRepository reports;

    public QueueSummaryService(MergeQueueRepository mergeQueue, TrendItemRepository trendItems,
                                SubmissionRepository submissions, ReportRepository reports, Clock clock) {
        this.mergeQueue = mergeQueue;
        this.trendItems = trendItems;
        this.submissions = submissions;
        this.reports = reports;
        this.clock = clock;
    }
```

`QueueTile` 레코드 교체:
```java
    public record QueueTile(String id, String name, int count, String oldest, boolean slaExceeded, boolean available) {}
```

`summarize()`에서 `Instant seedWindowStart …` 계산 앞에 신고 집계 추가:
```java
        long reportPending = reports.countByStatusIn(List.of(ReportStatus.OPEN, ReportStatus.EXPLAINING));
        List<Report> openReports = reports.findByStatusOrderByCreatedAtAsc(ReportStatus.OPEN);
        long reportOverdue = openReports.stream()
                .filter(r -> Duration.between(r.getCreatedAt(), now).toHours() >= REPORT_SLA_HOURS)
                .count();
        String reportOldest = openReports.isEmpty() ? "-" : formatAgo(openReports.get(0).getCreatedAt(), now);
```

`queues`·`alerts`·반환문 교체:
```java
        List<QueueTile> queues = List.of(
                new QueueTile("ADM-100", "병합 검수", pending.size(), oldest, mergeOverdue > 0, true),
                new QueueTile("ADM-300", "어뷰징", 0, "-", false, false),
                new QueueTile("ADM-400", "이의 제기", 0, "-", false, false),
                new QueueTile("ADM-410", "신고 콘텐츠", (int) reportPending, reportOldest, reportOverdue > 0, true)
        );

        List<Alert> alerts = new ArrayList<>();
        if (mergeOverdue > 0) {
            alerts.add(new Alert("병합 검수 큐 SLA 초과 " + mergeOverdue + "건",
                    "24시간 기준 초과 — 판정 유예 연장은 ADM-200에서 수동 처리"));
        }
        if (reportOverdue > 0) {
            alerts.add(new Alert("신고 콘텐츠 SLA 초과 " + reportOverdue + "건",
                    "4시간 기준 초과 — 자동 임시 비공개는 미구현, 수동 처리 필요"));
        }

        return new QueueSummaryResponse((int) (mergeOverdue + reportOverdue), queues, alerts,
                seedRatio, (int) judgedToday, (int) imminent24h);
```

`Report.getCreatedAt()`이 없으면 `Report.java`에 getter가 있는지 확인하고, 없을 때만 추가: `public Instant getCreatedAt() { return createdAt; }`.

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :app:test --tests kr.trendstage.admin.QueueSummaryTest`
Expected: PASS (4 tests).

- [ ] **Step 6: 커밋**

```bash
git add backend/persistence/src/main/java/kr/trendstage/persistence/repo/ReportRepository.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/queues/QueueSummaryService.java backend/app/src/test/java/kr/trendstage/admin/QueueSummaryTest.java
git commit -m "feat(api-admin): 큐 요약 ADM-410 실데이터화, 미구현 큐 available=false, 알림 문구 정정

신고 큐가 존재하는데 0건으로 고정돼 있던 문제 수정(OPEN+EXPLAINING, OPEN 4h SLA).
'미처리 시 판정 유예 자동 연장'처럼 사실이 아닌 문구를 고친다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

(`Report.java`를 수정했다면 `git add`에 포함한다.)

---

### Task 6: DB 기본 호스트

**Files:**
- Modify: `backend/app/src/main/resources/application.yml`

- [ ] **Step 1: 기본 URL 교체**

찾기:
```yaml
    url: ${DB_URL:jdbc:postgresql://192.168.0.56:5433/trd}   # 5433 = infra/docker-compose 매핑(로컬 PG 충돌 회피)
```
바꾸기:
```yaml
    url: ${DB_URL:jdbc:postgresql://localhost:5433/trd}   # 5433 = infra/docker-compose 매핑(로컬 PG 충돌 회피). 다른 호스트는 DB_URL로 지정
```

- [ ] **Step 2: 전체 백엔드 테스트**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL` — `:app`(통합 5개 클래스), `:domain-core`, `:merge`, `:audit` 전부 통과. (통합 테스트는 `@ServiceConnection`이 URL을 덮어쓰므로 이 변경의 영향을 받지 않는다.)

- [ ] **Step 3: 커밋**

```bash
git add backend/app/src/main/resources/application.yml
git commit -m "fix(config): DB 기본 호스트를 사설 IP에서 localhost로

다른 호스트의 DB를 쓰면 DB_URL 환경변수로 지정한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: 프론트 — CSRF 연동, `USE_FIXTURES` 기본값

**Files:**
- Modify: `admin/src/api/client.ts`
- Modify: `admin/src/state/auth.tsx`
- Create: `admin/.env.example`

- [ ] **Step 1: `client.ts` 수정**

`request()` 함수 앞에 헬퍼 추가:
```ts
/** CSRF: 서버가 발급한 XSRF-TOKEN 쿠키를 쓰기 요청 헤더로 되돌려 보낸다(쿠키-헤더 이중 제출). */
const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";

function readCookie(name: string): string | null {
  const match = document.cookie.split("; ").find((c) => c.startsWith(name + "="));
  return match ? decodeURIComponent(match.slice(name.length + 1)) : null;
}
```

`request()`의 `fetch` 호출부 교체:
```ts
async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (method !== "GET") {
    // 매 요청마다 쿠키를 새로 읽는다 — 로그인 직후 서버가 토큰을 교체해도 따로 처리할 필요가 없다.
    const token = readCookie(CSRF_COOKIE);
    if (token) headers[CSRF_HEADER] = token;
  }
  const res = await fetch(path, {
    method,
    credentials: "include",
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
```
(나머지 본문은 그대로.)

`api` 객체 다음에 추가:
```ts
/** SPA 부팅·로그인 직전에 호출 — XSRF-TOKEN 쿠키를 받아 둔다. */
export async function ensureCsrf(): Promise<void> {
  await request<void>("GET", "/admin/auth/csrf");
}
```

`USE_FIXTURES` 정의 교체:
```ts
/** 시연용 픽스처 사용 여부. 기본은 실제 API — VITE_USE_FIXTURES=true 를 명시했을 때만 픽스처. */
export const USE_FIXTURES = (import.meta.env.VITE_USE_FIXTURES ?? "false") === "true";
```

- [ ] **Step 2: `auth.tsx` 수정**

import 교체:
```ts
import { api, ApiError, ensureCsrf, USE_FIXTURES } from "../api/client";
```

컴포넌트 주석의 "(VITE_USE_FIXTURES=true, 기본값)"을 "(VITE_USE_FIXTURES=true일 때만)"으로 고친다.

`useEffect` 본문 교체:
```ts
  useEffect(() => {
    if (USE_FIXTURES) return;
    ensureCsrf()
      .catch(() => {})
      .then(() => api.get<AdminPrincipal>("/admin/me"))
      .then((principal) => setState({ status: "authenticated", principal }))
      .catch(() => setState({ status: "unauthenticated" }));
  }, []);
```

`login` 함수의 `try` 첫 줄에 추가(`api.post` 앞):
```ts
      await ensureCsrf();
```

(423 잠금 안내는 이미 `ApiError.message`(= 서버 `detail`)를 그대로 보여주므로 로그인 화면 수정은 필요 없다.)

- [ ] **Step 3: `.env.example` 신설**

`admin/.env.example`:
```
# 관리자 콘솔 로컬 설정 예시 — 복사해 .env로 쓴다(.env는 gitignore).
# 픽스처 모드: true면 백엔드 없이 가짜 데이터로 화면만 본다. 기본(미지정)은 실제 API.
VITE_USE_FIXTURES=false
```

- [ ] **Step 4: 타입 검사·빌드**

Run: `npm run build --prefix admin`
Expected: 오류 없이 빌드 완료.

- [ ] **Step 5: 커밋**

```bash
git add admin/src/api/client.ts admin/src/state/auth.tsx admin/.env.example
git commit -m "feat(admin): CSRF 헤더 연동, USE_FIXTURES 기본값을 실제 API로

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 8: 프론트 — 사이드바 뱃지·오늘의 작업 타일

**Files:**
- Modify: `admin/src/api/types.ts`
- Modify: `admin/src/fixtures.ts`
- Modify: `admin/src/components/Layout.tsx`
- Modify: `admin/src/screens/TodayScreen.tsx`

- [ ] **Step 1: 타입**

`types.ts`의 `QueueSummary.queues` 항목 타입 교체:
```ts
  queues: { id: string; name: string; count: number; oldest: string; slaExceeded: boolean; available: boolean }[];
```

- [ ] **Step 2: 픽스처**

`fixtures.ts`의 `fxQueueSummary` 교체:
```ts
export const fxQueueSummary: QueueSummary = {
  slaBreaches: 1,
  queues: [
    { id: "ADM-100", name: "병합 검수", count: 24, oldest: "4h", slaExceeded: false, available: true },
    { id: "ADM-300", name: "어뷰징", count: 0, oldest: "-", slaExceeded: false, available: false },
    { id: "ADM-400", name: "이의 제기", count: 0, oldest: "-", slaExceeded: false, available: false },
    { id: "ADM-410", name: "신고 콘텐츠", count: 2, oldest: "5h", slaExceeded: true, available: true },
  ],
  alerts: [
    { title: "신고 콘텐츠 SLA 초과 1건", detail: "4시간 기준 초과 — 자동 임시 비공개는 미구현, 수동 처리 필요" },
  ],
  seedRatio: 0.41,
  judgedToday: 17,
  imminent24h: 9,
};
```

- [ ] **Step 3: `Layout.tsx` 뱃지**

import 추가:
```ts
import { useQueueSummary } from "../api/hooks";
```

`NAV` 타입에서 `badge?: number`를 제거하고, 항목에서 `badge: 24`, `badge: 6`, `badge: 3`, `badge: 2`를 모두 삭제한다. 타입 선언은:
```ts
const NAV: { group: string; items: { id: ScreenId | "stub"; label: string; code: string }[] }[] = [
```

`NAV` 선언 다음에 뱃지 컴포넌트 추가:
```tsx
type Tile = { id: string; count: number; slaExceeded: boolean; available: boolean };

/** 큐 요약 API의 실제 값만 보여준다. 로딩·실패 시 아무것도 그리지 않는다(가짜 숫자 금지). */
function QueueBadge({ tile }: { tile?: Tile }) {
  if (!tile) return null;
  if (!tile.available) {
    return <span style={{ font: "600 9.5px Pretendard", padding: "3px 5px", borderRadius: 5, background: "rgba(255,255,255,0.06)", color: "rgba(255,255,255,0.35)" }}>미구현</span>;
  }
  if (tile.count === 0) return null;
  return (
    <span style={{ font: "600 10px ui-monospace, monospace", padding: "3px 5px", borderRadius: 5,
      background: tile.slaExceeded ? C.fading : "rgba(255,255,255,0.12)",
      color: tile.slaExceeded ? "#fff" : "rgba(255,255,255,0.7)" }}>{tile.count}</span>
  );
}
```

`Layout` 함수 본문 첫 부분(`const principal = …` 다음)에 추가:
```ts
  const summary = useQueueSummary();
  const tiles = new Map((summary.data?.queues ?? []).map((t) => [t.id, t] as const));
```

기존 뱃지 렌더링 블록:
```tsx
                    {n.badge != null && (
                      <span style={{ font: "600 10px ui-monospace, monospace", padding: "3px 5px", borderRadius: 5, background: "rgba(255,255,255,0.12)", color: "rgba(255,255,255,0.7)" }}>{n.badge}</span>
                    )}
```
를 교체:
```tsx
                    <QueueBadge tile={tiles.get(n.code)} />
```

`useQueueSummary()`가 TanStack Query 결과(`.data`)를 반환하는지 `admin/src/api/hooks.ts`의 `useData` 반환 타입으로 확인한다. 다른 형태면 그 형태에 맞춰 `summary.data` 접근만 조정한다.

- [ ] **Step 4: `TodayScreen.tsx` 타일**

타일 버튼 안의 숫자·최장 대기 두 줄:
```tsx
                  <div style={{ font: "700 34px Pretendard", letterSpacing: "-0.03em", marginTop: 14, color: qc.slaExceeded ? C.fading : C.ink }}>{qc.count}</div>
                  <div style={{ font: "500 11.5px Pretendard", marginTop: 9, color: qc.slaExceeded ? C.fading : C.sub }}>최장 대기 {qc.oldest}{qc.slaExceeded ? " ⚠" : ""}</div>
```
를 교체:
```tsx
                  {qc.available ? (
                    <>
                      <div style={{ font: "700 34px Pretendard", letterSpacing: "-0.03em", marginTop: 14, color: qc.slaExceeded ? C.fading : C.ink }}>{qc.count}</div>
                      <div style={{ font: "500 11.5px Pretendard", marginTop: 9, color: qc.slaExceeded ? C.fading : C.sub }}>최장 대기 {qc.oldest}{qc.slaExceeded ? " ⚠" : ""}</div>
                    </>
                  ) : (
                    <>
                      <div style={{ font: "700 20px Pretendard", marginTop: 20, color: C.faint }}>미구현</div>
                      <div style={{ font: "500 11.5px Pretendard", marginTop: 9, color: C.faint }}>Phase 2 대상</div>
                    </>
                  )}
```

- [ ] **Step 5: 빌드**

Run: `npm run build --prefix admin`
Expected: 오류 없음. `n.badge` 참조가 남아 있으면 타입 오류로 드러난다.

- [ ] **Step 6: 커밋**

```bash
git add admin/src/api/types.ts admin/src/fixtures.ts admin/src/components/Layout.tsx admin/src/screens/TodayScreen.tsx
git commit -m "feat(admin): 사이드바 뱃지를 큐 요약 API에 연결, 미구현 큐는 '미구현' 표시

하드코딩 뱃지(24/6/3/2) 제거. 로딩·실패 시 뱃지를 그리지 않는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 9: 브라우저 검증

코드 변경 없음. 결과를 보고서에 스크린샷과 함께 남긴다.

- [ ] **Step 1: 백엔드·DB 기동**

- DB: `docker ps`에 `trd-db`가 없으면 `infra/docker-compose`로 `db` 서비스를 올린다(`docker compose -f infra/docker-compose.yml up -d db` — 파일명은 `ls infra`로 확인).
- 백엔드: `backend/`에서 `./gradlew :app:bootRun`을 **백그라운드**로 실행하고 `curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/admin/auth/csrf`가 `200`이 될 때까지 기다린다.
- 로그인할 계정이 없으면 테스트용 계정을 DB에 만든다(비밀번호는 BCrypt 해시가 필요하므로, 백엔드 로그에서 부트스트랩 계정 존재 여부를 먼저 확인하고, 없으면 사용자에게 로그인 계정을 요청 — NEEDS_CONTEXT).

- [ ] **Step 2: 콘솔 기동**

`preview_start`로 `admin` 구성을 실행한다(`.claude/launch.json`).

- [ ] **Step 3: 확인 항목**

1. 로그인 화면 로드 시 `XSRF-TOKEN` 쿠키가 생긴다 — `javascript_tool`로 `document.cookie` 확인.
2. 로그인 성공 후 쓰기 동작 하나(예: ADM-900 배치 실행 또는 로그아웃)가 200이다 — `read_network_requests`로 요청에 `X-XSRF-TOKEN` 헤더가 있는지 확인.
3. 헤더를 뺀 쓰기 요청이 403이다 — `javascript_tool`로 `fetch("/admin/auth/logout",{method:"POST",credentials:"include"}).then(r=>r.status)` 결과 `403`.
4. 존재하는 계정으로 틀린 비밀번호 5회 → 6회째 로그인 화면에 "로그인 시도 횟수를 초과했습니다…" 표시.
5. 사이드바: 어뷰징·이의 제기에 "미구현", 병합 검수·신고 콘텐츠는 실제 건수(0이면 뱃지 없음). 오늘의 작업 타일도 같은 규칙.

각 항목 스크린샷 또는 네트워크 로그를 보고서에 첨부한다.

- [ ] **Step 4: 정리**

`preview_stop`으로 콘솔을 내리고, 백그라운드 백엔드 프로세스를 종료한다. Windows에서는 `netstat -ano | grep :8080`으로 PID를 찾아 종료했는지 확인한다(`TaskStop` 후에도 JVM이 남는 경우가 있다). 잠금 테스트로 잠긴 계정은 15분 뒤 자동 해제되므로 따로 손대지 않는다.

---

### Task 10: 문서 갱신

**Files:**
- Modify: `backend/api-spec/openapi.yaml`
- Modify: `05-screen-endpoint-map.md`
- Modify: `CLAUDE.md`, `02-admin-console.md`, `04-development-plan.md`
- Modify: `docs/superpowers/specs/2026-09-17-design-review-design.md`

- [ ] **Step 1: `openapi.yaml`**

`QueueSummary.queues.items.properties`에 추가(`slaExceeded` 다음):
```yaml
              available: { type: boolean, description: "false면 큐 자체가 미구현(Phase 2) — count는 의미 없음" }
```

`/admin/queues/summary:` 경로 블록 바로 앞에 인증 경로 추가:
```yaml
  # ─────────────────────────────  콘솔 · 인증  ─────────────────────────────
  /admin/auth/csrf:
    get:
      tags: [admin-auth]
      summary: CSRF 쿠키 발급 (SPA 부팅용) — 응답에 XSRF-TOKEN 쿠키
      responses:
        '200': { description: OK (본문 없음) }
  /admin/auth/login:
    post:
      tags: [admin-auth]
      summary: 관리자 로그인 — X-XSRF-TOKEN 헤더 필수. 5회 연속 실패 시 15분 잠금
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [loginId, password]
              properties:
                loginId: { type: string }
                password: { type: string }
      responses:
        '200': { description: 로그인 성공 — 세션 ID·CSRF 토큰 교체 }
        '401': { description: 아이디 또는 비밀번호 불일치 }
        '403': { description: CSRF 토큰 누락·불일치 또는 비활성 계정 }
        '423': { description: 연속 실패로 잠금 중 }
```

`cookieAuth` 보안 스킴의 `description`을 교체:
```yaml
      description: 콘솔 세션(30분 타임아웃, 2FA 미구현). 쓰기 요청은 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 보내야 한다. RBAC는 서버 권한 매트릭스로 강제(02 §1.1)
```

- [ ] **Step 2: `05-screen-endpoint-map.md`**

- ADM-010 행 비고에서 "ADM-300/400/410 타일은 0 고정 … 사이드바 뱃지 하드코딩" 서술을 "ADM-410은 실데이터(OPEN+EXPLAINING, OPEN 4h SLA), ADM-300/400은 `available=false`로 '미구현' 표시. 사이드바 뱃지도 같은 API"로 교체.
- §B 앞(또는 ADM-800 근처 인증 관련 위치)에 콘솔 인증 표를 추가: `GET /admin/auth/csrf`(스펙 ✅ · 구현 ✅ · 전체), `POST /admin/auth/login`(스펙 ✅ · 구현 ✅ · 전체, "X-XSRF-TOKEN 필수 · 5회 실패 15분 잠금(423)").

- [ ] **Step 3: 미구현 표기 정리**

각 파일에서 SP0.5로 해소된 서술을 찾아 현재 상태로 고친다. 찾기: `grep -n "CSRF\|csrf\|lockout\|잠금\|192.168\|USE_FIXTURES\|하드코딩\|0 고정\|twofa" CLAUDE.md 02-admin-console.md 04-development-plan.md 05-screen-endpoint-map.md`
- CSRF off·lockout 없음·DB 기본 호스트 사설 IP·`USE_FIXTURES` 기본 true·뱃지 하드코딩·ADM-410 0 고정·`twofaEnabled` 불일치 → 해결됨으로 고치거나 "(SP0.5에서 수정)" 류 미래형 표기를 과거·현재형으로 바꾼다.
- **2FA 자체는 여전히 미구현(SP3)** — 그 표기는 유지한다.
- **ADM-410 "4h 자동 임시 비공개"는 여전히 미구현(SP3 `sla_watch`)** — 유지한다. SP0.5는 SLA 초과를 *표시*할 뿐 조치하지 않는다.

- [ ] **Step 4: 상위 스펙**

`docs/superpowers/specs/2026-09-17-design-review-design.md` §3 표의 SP0.5 행 범위에서 "플랫폼 enum(`platform` 컬럼 CHECK)"을 빼고, SP4 행 범위에 "플랫폼 enum(값 목록 확정·기존 자유 텍스트 이관·앱·관리자 폼·API·DB CHECK)"을 추가한다. SP0.5 행 끝에 "→ `2026-09-18-sp05-hardening-design.md`"를 덧붙인다.

- [ ] **Step 5: 검증**

Run: `grep -n "192.168" CLAUDE.md 02-admin-console.md 04-development-plan.md 05-screen-endpoint-map.md backend/app/src/main/resources/application.yml`
Expected: 출력 없음.

- [ ] **Step 6: 커밋**

```bash
git add backend/api-spec/openapi.yaml 05-screen-endpoint-map.md CLAUDE.md 02-admin-console.md 04-development-plan.md docs/superpowers/specs/2026-09-17-design-review-design.md
git commit -m "docs: SP0.5 반영 — CSRF·로그인 잠금 API 명세, 큐 요약 available, 해소된 미구현 표기 정리

플랫폼 enum은 SP4로 이관.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 11: 최종 검증 + PR

- [ ] **Step 1: 전체 테스트**

Run (in `backend/`): `./gradlew test`
Expected: `BUILD SUCCESSFUL`. 실패하면 멈추고 보고.

- [ ] **Step 2: 콘솔 빌드**

Run: `npm run build --prefix admin`
Expected: 오류 없음.

- [ ] **Step 3: 작업 트리 확인**

Run: `git status --short`
Expected: `README.md`(사용자 소유)와 기존 미추적 파일만 남고, 이번 태스크 파일은 전부 커밋됨.

- [ ] **Step 4: 푸시 + PR**

```bash
git push -u origin sp0.5-hardening
```

PR은 base `docs/design-review`(SP0 PR #3 위에 쌓음):
```bash
gh pr create --base docs/design-review --head sp0.5-hardening --title "SP0.5: 관리자 콘솔 CSRF·로그인 잠금·큐 요약 실데이터·기본값 정합" --body "$(cat <<'EOF'
## 요약
- 관리자 콘솔 CSRF 활성화(쿠키-헤더 이중 제출) + 세션 고정 방어
- 로그인 5회 연속 실패 시 15분 잠금(423), LOGIN_LOCKED 감사 기록
- 큐 요약: ADM-410 신고 실데이터, 미구현 큐 available=false, 사이드바 뱃지 연결(하드코딩 제거)
- USE_FIXTURES 기본값 false, DB 기본 호스트 localhost, twofa_enabled 기본값 DB와 일치(V27)
- 프로젝트 첫 통합 테스트 기반(Testcontainers + pgvector)

스펙: `docs/superpowers/specs/2026-09-18-sp05-hardening-design.md`
플랫폼 enum은 SP4로 이관.

## ⚠ 로컬 환경 안내
DB 기본 호스트가 `192.168.0.56` → `localhost`로 바뀌었습니다. DB가 다른 머신에 있다면
`backend/app/.env`에 `DB_URL=jdbc:postgresql://192.168.0.56:5433/trd`를 추가하세요.

## 검증
- `./gradlew test` 통과(통합 테스트는 Docker 필요)
- `npm run build --prefix admin` 통과
- 브라우저: CSRF 쿠키 발급·헤더 전송, 무헤더 403, 잠금 안내, 뱃지 표시 확인

## 머지 순서
SP0 PR #3 병합 후 이 PR의 base를 `main`으로 바꿔 병합합니다.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR URL 출력. 병합은 사용자가 GitHub에서 수행한다.
