# 백엔드 기능 테스트 스위트 — 설계

> 작성 2026-10-05 · 브랜치 `functional-test-suite` · TC 목록은 [`2026-10-05-functional-test-catalog.md`](2026-10-05-functional-test-catalog.md)

## 0. 목적과 범위

**목적.** Phase 1(클로즈드 베타) 전에 앱·콘솔이 쓰는 백엔드 기능을 HTTP 계약 수준에서 회귀 방지한다. 지금 관리자 API는 촘촘하지만 유저 API(`/v1/**`, 20개)는 `POST /v1/submissions` 하나만 HTTP로 검증되고, "제보 → 병합 → 판정 → 원장 → 등급"을 잇는 테스트가 없다.

**범위.**

| 영역 | 대상 |
|---|---|
| `USR` | `/v1/**` 엔드포인트 20개 전부 |
| `ADM` | HTTP 테스트가 없는 관리자 엔드포인트 19개(`/admin/me`, 계정 비활성화·목록, 배치 수동 실행, 병합 큐 목록·미리보기·병합·분리, 파라미터 드래프트 4종, 신고 제보 목록, 시딩 2종, 항목 목록·상세, 판정 목록·유예 연장) |
| `JRN` | 유저 HTTP + 배치 + 관리자 HTTP를 잇는 여정 8개 |

**비범위.**
- 임베딩 유사도 자동 병합(`cluster_merge`의 TEI 호출) — 테스트 환경에 TEI가 없다. 병합은 완전일치(제보 시점)와 관리자 큐 처리로만 만든다.
- Firebase 토큰 실제 검증 — 외부 의존. 토큰 없음/미설정 → 401만 본다.
- 프런트(`app/`, `admin/`) — 별도 작업.
- 이미 테스트가 있는 관리자 엔드포인트(승인·감사 로그·신고 결정·재판정/VOID·유저 원장 등)의 재작성.
- 관리자 응답 "산정 근거" 필드의 전면 도입(§5 D6) — API 설계 변경이라 테스트가 정할 일이 아니다.

## 1. 접근

기존 `AbstractIntegrationTest`(Testcontainers `pgvector/pgvector:pg16` 1개 공유 + `MutableClock` + MockMvc)를 확장한다. MockMvc도 Spring Security 필터 체인을 통과하므로 401·403·CSRF가 실제로 검증된다. 새 의존성 없음. 실서버 포트(TestRestTemplate)나 docker compose 블랙박스는 시계를 못 돌려 D+14·주 경계를 검증할 수 없어 기각했다.

## 2. 코드 구조

```
backend/app/src/test/java/kr/trendstage/functional/
  user/     UserAuthTest · UserSubmissionTest · UserTrendTest · UserReportTest
            UserReadWatchTest · UserMeTest
  admin/    AdminMeAccountTest · AdminBatchTest · AdminMergeQueueTest · AdminParamStudioTest
            AdminReportSubmissionsTest · AdminSeedTest · AdminTrendItemTest · AdminVerdictTest
  journey/  JourneyTest
```

- 클래스 = TC 기능 코드 단위. 메서드마다 `@DisplayName("USR-SUB-03 같은 항목 중복 제보는 409 + dupeRank")` — TC-ID로 Gradle 리포트와 카탈로그를 1:1 추적한다.
- 새 Spring 컨텍스트를 만드는 `@MockBean`·`@SpyBean`·`@DynamicPropertySource`를 쓰지 않는다(스위트 시간 보호).

### 2.1 공통 기반 변경 (`AbstractIntegrationTest`)

1. **크론 전부 끄기** — 지금은 `sla_watch`만 꺼져 있어 `verdict_runner`(18:00 UTC)·`cluster_merge`(17:00 UTC)·`grade_recalc`(월 00:00 KST)가 스위트 도중 공유 `MutableClock`으로 돌 수 있다. `jobs.verdict-runner.cron=-`, `jobs.cluster-merge.cron=-`, `jobs.grade-recalc.cron=-` 추가. 기존 테스트의 잠재적 흔들림도 같이 없어진다.
2. **임베딩 주소 고정** — `embedding.service.url=http://127.0.0.1:1`. 로컬에 TEI가 떠 있어도 테스트가 붙지 않게 해 `ADM-BAT` 결과(전부 `failed`)를 결정적으로 만든다.
3. **`asUser(UUID)`** — `authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()))`. `/v1` 체인은 STATELESS·CSRF 없음이라 이것으로 충분하다(기존 `SubmissionQuotaIntegrationTest`와 같은 방식).

### 2.2 `Fixtures` 추가

필요할 때 최소로: 투표·인정 대량 생성, 신고에 `submission_id`·`EXPLAINING` 지정, `user_preferences`, 시딩용 관리자(`seed_user_id`) 등. 이름·키는 지금처럼 랜덤.

## 3. 테스트 작성 규칙

**공유 DB.** 테스트끼리 DB를 공유하고 롤백하지 않는다. `verdict_runner`·`closeDue`·`grade_recalc`는 DB 전체를 처리한다.
- 단언은 **자기가 만든 ID에만** 한다. 전역 개수(`GET /admin/trend-items` 길이, 판정 목록 길이 등)는 단언하지 않고 jsonPath 필터로 자기 행을 찾는다.
- 파라미터 드래프트는 전역 1개다 — 만든 드래프트는 `finally`에서 `fx.deleteDraft`.
- 배치 호출 전 항상 `releaseBatchLock(name)`(DELETE 금지 — 기존 주석 참조).

**시계.** `clock.set(...)`으로 고정 시각(예: 2026-09-21 월 10:00 KST)부터 시작하고, 제보 → `clock.set(마감 + 1s)` → 배치 순서로 진행한다. 아래 값은 `MutableClock`이 아니라 벽시계(`Instant.now()`)를 쓰므로 **단언 대상에서 뺀다**:
- `GET /v1/trends?daily=true`의 날짜 경계(`LocalDate.now(KST)`)
- `reports.explanation_submitted_at`, 병합 큐 `ago`
- `score_ledger.created_at`·`verdicts.created_at`·`user_grades.computed_at`(정렬·최신 판단이 실제 시간을 따른다 — 같은 테스트 안 순서는 유지되므로 문제없음)

**단언 우선순위.** HTTP 응답(상태코드·핵심 필드·Problem `type`) → 응답에 드러나지 않는 불변 조건만 `jdbc`(원장 행 수가 줄지 않음, `is_seed`, `voided_at`, 감사 로그 action).

**기존 테스트와의 중복.** 서비스 수준에서 이미 검증된 규칙(예: 마감 경계, 동시 제보, 병합 멱등성)은 HTTP 테스트가 규칙 변형을 다시 돌리지 않는다. HTTP 매핑(상태코드·바디 형태)만 한 번 확인하고, 카탈로그에 "기존: `클래스#메서드`"로 링크한다.

## 4. 결함 처리

- 기대결과의 근거 우선순위: **CLAUDE.md 원칙(R1~R5) > 설계서 01~05 > SP 스펙 > OpenAPI**. 문서가 침묵하고 현재 동작이 합리적이면 현재 동작을 기대값으로 고정한다(특성화 테스트).
- 테스트가 근거와 다른 동작을 드러내면 **기대값을 코드에 맞춰 고치지 않는다.** 카탈로그 상태를 `BUG-n`으로 바꾸고, 테스트에 `@Disabled("BUG-n: <한 줄>")`를 달아 커밋하고, PR 본문에 목록으로 보고한다. 수정은 승인 후 별도 커밋.
- 근거끼리 충돌하거나 근거가 없는데 현재 동작이 의심스러운 항목은 §5 결정 항목(D-n)으로 올린다(D1~D9는 2026-10-05 결정 완료).

## 5. 결정이 필요한 항목

탐색 중 발견한 설계·코드 불일치. **결정(2026-10-05): D1~D9 전부 아래 '추천'대로.** 추천이 현재 동작과 다른 D1·D2·D3·D4·D5·D8은 테스트가 추천 동작을 기대하므로 실패한다 → §4에 따라 `BUG-n`으로 기록하고 수정은 별도 승인 후. D6은 범위 밖, D7은 코드 기준, D9는 현재 동작 고정.

| # | 현재 동작 | 근거 | 추천 |
|---|---|---|---|
| D1 | 비공개(`TEMP_HIDDEN`·`PERMANENT_HIDDEN`) 항목에 **투표·인정**이 된다. 상세 조회는 404 | 상세가 404인데 상호작용은 되는 비대칭 | 투표·인정도 404(상세와 일치). 제보는 지금처럼 허용(같은 이름 제보를 막으면 비공개 항목의 존재가 드러나고, 판정 신호는 계속 쌓여야 함) |
| D2 | 자기가 제보한 항목에 인정(endorse)할 수 있다 | OpenAPI: 409 "이미 동의/제보한 유저" | OpenAPI대로 409 |
| D3 | 소명 마감(`explanation_deadline`, 48h)이 검사되지 않고, `EXPLAINING` 동안 재제출 시 덮어쓴다 | 02 §신고: 소명 요청 48h | 마감 후 제출은 409. 마감 전 재제출(덮어쓰기)은 허용 |
| D4 | 파라미터 드래프트가 `REVIEW`(승인 대기)여도 `simulate`가 `sim_result`를 덮어쓴다(PUT은 409) | 승인자가 보는 시뮬레이션이 요청 후에 바뀜 | PUT과 같이 409 |
| D5 | `hitThreshold`를 검증하지 않는다(음수·1 초과 허용). `submitterTarget<1`만 422 | — | `0 < hitThreshold ≤ 1` 밖이면 422 |
| D6 | 이번 범위 19개 관리자 엔드포인트 중 "산정 근거"(`basis`) 필드를 주는 곳이 없다 | CLAUDE.md "관리자 API는 모든 응답에 산정 근거" | 이번 스위트 범위 밖. 별도 설계 과제로 분리(테스트는 현재 필드로 작성) |
| D7 | OpenAPI와 코드 차이: 시딩 200(스펙 201), 파라미터 승인 요청 "시뮬레이션 없음" 422(스펙 409)·`applyMode` 없음, `/admin/trends` 경로·필터 없음(`/admin/trend-items`), `/v1/trends`의 `q`·`sort` 미구현, `GET /v1/trends`는 인증 불필요(스펙 401) | OpenAPI가 옛 값 | 테스트는 코드 기준. OpenAPI 정정은 별도 문서 커밋 |
| D8 | 유예 연장이 MERGED·VOID(판정 행 없음)·DRAFT 항목에도 된다 | 02 ADM-200 — 판정 대기 항목 대상 | PENDING·JUDGING만 허용, 그 외 422 |
| D9 | 시딩이 JUDGING·RESOLVED 항목에도 된다(유저 제보는 422 `item-closed`) | 시딩은 T·순위·원장에서 빠지므로 판정 영향 없음 | 현재 동작 유지(특성화 테스트로 고정) |

**결정 없이 BUG로 분류하는 것**(근거가 분명함):
- 오늘의 5개(`GET /v1/trends?daily=true`)가 선정 후 비공개·병합된 항목을 그날 계속 노출한다 — 비공개는 명예훼손 대응 조치(ADM-410)라 노출되면 안 된다. 기대: 읽을 때 PUBLIC·비병합만.
- 투표·인정 동시 첫 요청이 UNIQUE 위반으로 500 — 기대: 하나는 성공, 나머지는 투표면 같은 결과(멱등), 인정이면 409.
- 문서 오류: `05-screen-endpoint-map.md:106` "1~90일" — 코드·02는 1~7일. 문서만 고친다.

## 6. 실행

이 PC는 JDK가 없으므로 Docker로 실행한다(메모 참조).

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "D:\\PJ\\TRD\\backend:/src:ro" -v trd-gradle-test-cache:/root/.gradle -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal eclipse-temurin:17-jdk bash -c 'cp -r /src /workspace && cd /workspace && sed -i "s/\r$//" gradlew && ./gradlew --no-daemon test --tests "kr.trendstage.functional.*"'
```

PR마다 `--tests` 없이 전체 스위트를 한 번 돌려 기존 테스트 회귀가 없는지 확인한다.

## 7. 전달 단위

| PR | 내용 | 완료 기준 |
|---|---|---|
| 1 | §2.1 기반 변경 + `USR-*` | 기존 전체 스위트 초록 + USR TC 전부 PASS 또는 `BUG-n`/`D-n` |
| 2 | `ADM-*` | 동일 |
| 3 | `JRN-*` + 문서 오류 정정 | 동일 + 카탈로그 상태 최신화 |

각 PR은 카탈로그의 상태 칸을 갱신한다. main 병합은 소유자만 한다(브랜치 + PR).
