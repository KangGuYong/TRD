# SP0.5 즉시 수정 묶음 — 관리자 콘솔 보안·설정 정합

- 작성일: 2026-09-18
- 상태: 승인 대기 (브레인스토밍 4개 섹션 승인 완료)
- 상위 스펙: `2026-09-17-design-review-design.md` §3 SP0.5
- 브랜치: `sp0.5-hardening` (base `docs/design-review` — SP0 PR #3 병합 후 `main`으로 재지정)

## 0. 범위

설계 논쟁이 없는 보안·설정 수정 다섯 건. 상위 스펙이 SP0.5에 넣었던 **플랫폼 enum은 SP4로 이관**한다 — 값 목록 확정(틱톡·더쿠 포함 여부), 자유 텍스트 기존 데이터 이관, 앱·관리자 폼·API·DB 동시 변경이 필요한 설계 작업이고, 플랫폼 다양성 신호는 SP4 전까지 판정에 쓰이지 않는다.

| # | 항목 | 이유 |
|---|---|---|
| 1 | 관리자 콘솔 CSRF 활성화 + 세션 고정 방어 | 쿠키 세션인데 CSRF off — 외부 페이지 한 번으로 관리자 권한 요청이 나간다 |
| 2 | 관리자 로그인 잠금 | 실패는 감사 로그만 남고 무차별 대입에 제한이 없다 |
| 3 | 큐 요약 실데이터화 + 사이드바 뱃지 연결 | 실재하는 신고 큐를 0건으로 표시, 뱃지 24/6/3/2 하드코딩 |
| 4 | `USE_FIXTURES` 기본값 false | `.env` 없는 빌드가 읽기=픽스처·쓰기=실서버 혼합 모드가 된다 |
| 5 | DB 기본 호스트 `localhost`, `twofaEnabled` 기본값 DB와 일치 | 사설 IP 하드코딩, 엔티티(false)·DB(TRUE) 기본값 반대 |

**비범위**: 플랫폼 enum(SP4), 2FA 구현·ADM-311 백엔드·확인 모달·단축키(SP3), `/v1/**` 인증 방식 변경.

**실행 순서**: 백엔드(§1·§2) → 통합 테스트(§4) → 프론트(§3) → 문서(§5). API를 먼저 끝내고 프론트는 실제 응답 스키마에 맞춘다.

---

## 1. 백엔드 — CSRF와 로그인 보강

### 1.1 CSRF (쿠키-헤더 이중 제출)

`AdminSecurityConfig`의 `/admin/**` 체인:

- `csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())` — `XSRF-TOKEN` 쿠키(경로 `/`)를 JS가 읽을 수 있게 발급, 요청은 `X-XSRF-TOKEN` 헤더로 받는다.
- `csrf.csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())` — Spring Security 6.3에는 내장되어 있지 않아(6.4의 `csrf.spa()`에 해당) 공식 문서 예제대로 클래스를 추가한다. 응답 렌더링은 `XorCsrfTokenRequestAttributeHandler`(BREACH 방어), 헤더로 온 원문 토큰 해석은 `CsrfTokenRequestAttributeHandler`에 위임한다.
- `CsrfCookieFilter`(`BasicAuthenticationFilter` 뒤) — 요청마다 지연 토큰을 로드해 응답에 쿠키가 실리게 한다. 로그인 직후 교체된 토큰이 같은 응답으로 나가는 근거.
- 신규 `GET /admin/auth/csrf` — `permitAll`, 본문 없음(200). SPA가 부팅 시 쿠키를 받는 용도.
- `POST /admin/auth/login`은 `permitAll`이지만 **CSRF 검사는 받는다**(login CSRF 방지).
- 클래스 주석의 "SPA가 없어서 비활성화" TODO를 삭제한다.

`PublicSecurityConfig`(`/v1/**`)는 바꾸지 않는다. STATELESS Bearer JWT라 브라우저가 자격증명을 자동 첨부하지 않으므로 CSRF 대상이 아니다 — 그 이유를 주석으로 남긴다.

### 1.2 세션 고정 방어

로그인은 컨트롤러가 직접 수행하므로 Spring 인증 필터의 `SessionAuthenticationStrategy`(세션 ID 교체·CSRF 토큰 교체)가 동작하지 않는다. `AdminAuthController.login()` 성공 경로에서:

1. `request.changeSessionId()`
2. CSRF 토큰 재발급 — `CsrfTokenRepository`로 기존 토큰을 지우고(`saveToken(null, …)`) 새 토큰을 생성·저장해 응답 쿠키로 내보낸다. 이를 위해 `CookieCsrfTokenRepository`를 `@Bean`으로 선언해 보안 체인과 컨트롤러가 **같은 인스턴스**를 쓰게 한다(기존 `SecurityContextRepository` 빈과 같은 패턴).
3. 그 뒤에 `securityContextRepository.saveContext(...)`.

### 1.3 로그인 잠금

**스키마** — `V27__admin_login_hardening.sql`:
```sql
ALTER TABLE admin_accounts
    ADD COLUMN failed_login_count INT NOT NULL DEFAULT 0,
    ADD COLUMN locked_until       TIMESTAMPTZ;
```
(§2.3의 `twofa_enabled` 갱신도 같은 파일.)

**`AdminAccount`** — `isLocked(Instant now)`, `recordFailedLogin(Instant now)`(카운터 증가, 임계 도달 시 `locked_until` 설정, 잠금이 걸렸는지 반환), `recordLogin(now)`는 카운터·`locked_until`을 초기화하도록 확장.

**`AdminAccountService.authenticate()`** 순서:

1. 계정 없음 → `InvalidCredentialsException`(401, 기존 문구).
2. 비활성 → 기존 처리(`AccountDisabledException`).
3. `isLocked(now)` → **비밀번호를 검사하지 않고** `AccountLockedException` → **423**, 문구 "로그인 시도 횟수를 초과했습니다. 잠시 후 다시 시도하세요". 카운터는 올리지 않는다.
4. 비밀번호 불일치 → `recordFailedLogin(now)`. 기존 `LOGIN_FAILED` 감사 기록 유지. 이번 실패로 잠금이 걸렸으면 `LOGIN_LOCKED` 감사 기록 추가(detail: `failedCount`, `lockedUntil`). 이후 `InvalidCredentialsException`(401).
5. 성공 → `recordLogin(now)`(초기화).

**롤백 함정** — `authenticate()`는 `@Transactional`이고 실패는 `RuntimeException`이다. 그대로 두면 카운터 증가가 예외와 함께 롤백된다. `@Transactional(noRollbackFor = {InvalidCredentialsException.class, AccountLockedException.class})`로 막는다. (감사 로그는 이미 `REQUIRES_NEW`라 영향 없음.)

**상수** — `MAX_FAILED_LOGINS = 5`, `LOCK_DURATION = 15분`. 도메인 파라미터가 아니므로 파라미터 스튜디오 대상이 아니다.

**예외 매핑** — `AccountLockedException` → 423 + 기존 `problem()` 형식(`{type,status,detail}`).

**트레이드오프(수용)** — 잠금 중 423·전용 문구는 해당 login ID의 존재를 드러낸다(계정 열거). 계정 수가 적은 내부 콘솔이라 잠긴 관리자가 원인을 아는 편을 택했다. 잠금 해제는 15분 경과로만 이루어지며 수동 해제 경로는 두지 않는다.

---

## 2. 백엔드 — 큐 요약, DB 기본값, 2FA 기본값

### 2.1 큐 요약 (`QueueSummaryService`)

**ADM-410 실데이터화** (`ReportRepository`에 카운트·조회 메서드 추가):

| 값 | 정의 |
|---|---|
| `count` | `status IN (OPEN, EXPLAINING)` 건수 — 결정이 남은 신고 |
| `slaExceeded` | `OPEN`이면서 `created_at`이 4시간 이상 지난 건이 1건 이상 |
| `oldest` | 가장 오래된 `OPEN`의 경과 시간(기존 `formatAgo`), 없으면 `-` |

`slaBreaches` 합계에 신고 SLA 초과 건수를 더한다.

**미구현 큐 구분** — `QueueTile`에 `available: boolean` 추가. ADM-100·410은 `true`, ADM-300·400은 `false`(count 0, oldest `-`).

**알림 문구 정정**
- 병합 SLA: "미처리 시 판정 유예 자동 연장"(사실 아님) → "24시간 기준 초과 — 판정 유예 연장은 ADM-200에서 수동 처리".
- 신고 SLA(신규): "신고 콘텐츠 SLA 초과 N건" / "4시간 기준 초과 — 자동 임시 비공개는 미구현, 수동 처리 필요".
- 클래스 주석의 "신고는 서브시스템 자체가 미구현"을 사실대로 고친다.

### 2.2 DB 기본 호스트

`application.yml`: `${DB_URL:jdbc:postgresql://localhost:5433/trd}`(5433 = `infra/docker-compose` 매핑).

⚠ 현재 로컬 백엔드는 기본값 `192.168.0.56`으로 기동돼 왔다. 그 주소가 이 PC면 그대로 동작하고, 다른 머신 DB면 `backend/app/.env`에 `DB_URL=jdbc:postgresql://192.168.0.56:5433/trd`를 추가해야 한다. `.env`는 사용자 소유 파일이므로 **구현에서 수정하지 않고** PR 설명에 안내한다.

### 2.3 `twofaEnabled` 기본값

- `AdminAccount.twofaEnabled` 필드 기본값 `false` → `true`(DB `DEFAULT TRUE`와 일치).
- `V27`에 `UPDATE admin_accounts SET twofa_enabled = TRUE;` — JPA로 생성돼 `false`로 들어간 기존 계정을 정렬한다. 이 값을 읽는 코드는 아직 없어 동작 변화는 없다.
- 컬럼 의미를 "이 계정은 2FA 적용 대상"으로 엔티티 주석에 명시한다(2FA 자체는 **미구현(SP3)**).

---

## 3. 프론트엔드 — 관리자 콘솔

§1·§2 백엔드가 병합 가능한 상태가 된 뒤 착수한다.

### 3.1 CSRF 연동 (`admin/src/api/client.ts`)

- `readCookie("XSRF-TOKEN")` 헬퍼. `request()`에서 메서드가 GET이 아니면 `X-XSRF-TOKEN` 헤더를 붙인다. 쿠키를 **요청마다** 읽으므로 로그인 후 토큰 교체를 따로 처리하지 않는다.
- `ensureCsrf()` — `GET /admin/auth/csrf` 호출. `state/auth.tsx`가 앱 부팅 시(`/admin/me` 확인 전)와 로그인 직전에 호출한다.

### 3.2 로그인 잠금 안내

로그인 화면은 423이면 서버 `detail`을 그대로 표시한다(일반 실패 문구로 덮지 않음).

### 3.3 `USE_FIXTURES` 기본값

- `USE_FIXTURES = (import.meta.env.VITE_USE_FIXTURES ?? "false") === "true"` — 기본은 실 API, 픽스처는 명시 시에만.
- `admin/.env.example` 신설: `VITE_USE_FIXTURES=false` + 용도 주석.

### 3.4 사이드바 뱃지 (`admin/src/components/Layout.tsx`)

- `NAV`의 하드코딩 `badge` 숫자를 제거하고 `useQueueSummary()` 결과를 화면 코드(ADM-100/300/400/410)로 매칭한다.

| 상태 | 표시 |
|---|---|
| `available=false` | 회색 "미구현" 라벨 |
| `count=0` | 뱃지 없음 |
| `count>0` | 숫자 |
| `slaExceeded` | 빨간 숫자 뱃지 |
| 요약 로딩 중·실패 | 뱃지 없음 |

- `TodayScreen` 큐 타일에도 `available=false` → "미구현" 규칙 적용.

### 3.5 타입·픽스처

- `admin/src/api/types.ts` `QueueTile`에 `available: boolean`.
- `admin/src/fixtures.ts` `fxQueueSummary` 갱신(ADM-300/400 `available=false`).

---

## 4. 통합 테스트 (Testcontainers)

프로젝트 첫 통합 테스트 기반이며 SP1(판정 트랜잭션 검증)에서 재사용한다.

### 4.1 기반

- 위치: `backend/app/src/test/java/kr/trendstage/…` — `@SpringBootApplication`이 있는 유일한 모듈.
- `app/build.gradle.kts` 테스트 의존성: `spring-boot-starter-test`, `spring-security-test`, `spring-boot-testcontainers`, `org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter`.
- `AbstractIntegrationTest`:
  - static `PostgreSQLContainer`(`pgvector/pgvector:pg16`, docker-compose와 동일) + `@ServiceConnection`. 전 테스트 클래스가 컨테이너 하나를 공유.
  - `@SpringBootTest` + `@AutoConfigureMockMvc`. Flyway가 V1~V27을 실제 적용 — 신규 마이그레이션 검증 겸용.
  - `MutableClock`을 `@Primary` `Clock` 빈으로 제공(`@TestConfiguration`). 테스트마다 시각 설정.
  - 테스트 데이터는 고유 login ID 등으로 격리한다(테이블 truncate 없음).
- **Docker 부재 시 실패**한다(`disabledWithoutDocker` 사용 금지 — 조용한 스킵은 검증한 척이 된다).

### 4.2 케이스

**`AdminCsrfTest`**
1. 토큰 없는 `POST /admin/auth/login` → 403.
2. `GET /admin/auth/csrf` → `XSRF-TOKEN` 쿠키, `HttpOnly` 아님.
3. 토큰 포함 로그인 → 200, 세션 ID 변경, 응답에 새 `XSRF-TOKEN`(이전 값과 다름).
4. 로그인 상태 쓰기 요청(`POST /admin/auth/logout`): 토큰 없음 → 403, 있음 → 200.
5. `POST /v1/submissions` 무토큰·무인증 → 401(403 아님) — 앱 체인 무영향.

**`AdminLoginLockoutTest`**
1. 4회 실패 → 각 401, 잠기지 않음.
2. 5회째 실패 → 401, `locked_until` 설정, `LOGIN_LOCKED` 감사 행 존재.
3. 잠금 중 **올바른 비밀번호** → 423.
4. 시계 +15분 → 성공, `failed_login_count = 0`.
5. 3회 실패 → 성공 → 4회 실패 → 잠기지 않음(성공 시 초기화).
(1·2가 통과하려면 카운터가 롤백되지 않아야 하므로 `noRollbackFor` 회귀가 함께 잡힌다.)

**`QueueSummaryTest`**
1. `OPEN`·`EXPLAINING` 신고가 ADM-410 `count`에 포함, `DECIDED` 제외.
2. 4시간 넘은 `OPEN` 존재 시 `slaExceeded=true`, 알림 목록에 신고 SLA 항목.
3. ADM-300·400 `available=false`, ADM-100·410 `available=true`.

**`AdminAccountDefaultsTest`**
1. JPA로 생성한 계정의 `twofa_enabled = true`.

### 4.3 실행

`./gradlew :app:test`(Docker 필요) + 기존 `:domain-core:test :merge:test :audit:test` 전부 통과.

### 4.4 프론트 검증

프론트 테스트 도구가 없으므로 `.claude/launch.json`의 `admin` 구성으로 브라우저 확인하고 스크린샷을 남긴다.
1. 부팅 시 `XSRF-TOKEN` 쿠키 발급 → 로그인 → 병합·신고 등 쓰기 동작 정상.
2. 헤더를 제거한 쓰기 요청 → 403.
3. 5회 실패 후 잠금 안내 문구 표시.
4. 뱃지·오늘의 작업 타일이 실제 수치와 "미구현"으로 표시.

---

## 5. 문서 갱신

- `backend/api-spec/openapi.yaml`: `GET /admin/auth/csrf`, 로그인 423 응답, `QueueTile.available`, CSRF 헤더(`X-XSRF-TOKEN`) 요구를 명시.
- `05-screen-endpoint-map.md`: ADM-010 큐 요약 비고(ADM-410 실데이터, 300/400 `available=false`), `GET /admin/auth/csrf` 행 추가.
- `CLAUDE.md`·`04-development-plan.md`·`02-admin-console.md`: SP0.5로 해소된 항목(CSRF, 로그인 잠금, DB 기본 호스트, ADM-410 0 고정, 뱃지 하드코딩, `USE_FIXTURES`)의 미구현 표기를 걷어낸다.
- 상위 스펙(`2026-09-17-design-review-design.md`) §3 SP0.5 행: 플랫폼 enum을 SP4로 옮겼음을 반영.

## 6. 완료 기준

- `./gradlew :app:test` 및 기존 모듈 테스트 통과(Docker 실행 상태).
- §4.4 브라우저 검증 4항목 확인·스크린샷.
- `admin` 빌드(`npm run build --prefix admin`) 타입 오류 없음.
- PR `sp0.5-hardening` → `docs/design-review`(SP0 병합 후 `main`).
