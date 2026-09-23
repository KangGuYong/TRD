# SP3 · 콘솔 통제·SLA — 설계

- 상위 스펙: `docs/superpowers/specs/2026-09-17-design-review-design.md` §2.3 "콘솔 통제·SLA (SP3)", §3 SP3 행
- 선행: SP1(#6, 판정 저장·`JudgeService`), SP2(#7, 병합 무결성) — 둘 다 main에 병합됨
- 작성: 2026-09-23. 브레인스토밍에서 사용자가 정한 것: 범위(핵심 통제만), 2인 승인 = 요청자 + 승인자 1명, 100점 초과 차액은 승인 후 재계산해 실행

## 0. 범위

Phase 1(클로즈드 베타) 전에 콘솔의 통제를 **UI가 아니라 서버가** 하게 만든다. 판정·점수 공식은 바꾸지 않는다.

| # | 항목 | 지금의 문제 (근거) |
|---|---|---|
| 1 | 승인 게이트 | 재판정·항목 VOID가 만드는 ADJ에 상한이 없다(`JudgeService.rejudge`·`voidItem` → `settle`). "100점 초과 상쇄 원장 2인 승인"(CLAUDE.md)이 무력하다 |
| 2 | 승인 인원 | `ApprovalService`는 요청자 외 승인자 2명(`approver_1`·`approver_2`, 서로 달라야 함)을 요구한다 — 사실상 3명. ADMIN이 부트스트랩 1명이면 어떤 승인도 끝나지 않는다 |
| 3 | 실행기 부재 | `ApprovalExecutor` 구현은 `ParamApplyExecutor` 하나. V6가 선언한 `LEDGER_ADJ_OVER100` 등은 실행기·엔드포인트가 없다 |
| 4 | ADM-311 유저 원장 | `/admin/users/**` 컨트롤러가 없다. 화면은 `useAdminUser("user_4410")` 픽스처 고정. 수동 ADJ 버튼은 API를 부르지 않는다 |
| 5 | 계정 통제 | 계정 생성이 ADMIN 단독(`AdminAccountController.create`). 역할 변경·비밀번호 변경 엔드포인트가 없다(부트스트랩 비밀번호를 바꿀 수 없다). 신규 계정 승인권 유예(P8) 없음 |
| 6 | 세션 | 역할·비활성화가 세션(`AdminPrincipal`)에 로그인 시점 값으로 박힌다 — 비활성화된 관리자도 기존 세션으로 계속 쓴다 |
| 7 | 역할 불일치 | AUDITOR가 신고 큐·파라미터 드래프트를 못 본다. REVIEWER가 임시 비공개를 단독 실행한다(02 §1.1은 O/A) |
| 8 | 감사 로그 | `AuditLogEntryResponse`에 `detail`이 없다(DB에는 있음). 필터 없이 최신 200건 고정 |
| 9 | `sla_watch` | 잡이 없다. 신고 4h 자동 임시 비공개(P1), 병합 24h 판정 유예 연장, 90일 미접속 관리자 비활성화 전부 미구현 |
| 10 | 거짓 문구 | `UserLedgerScreen`의 개인정보 열람 토글·"감사 로그에 기록됩니다", `Layout.tsx:123` "모든 조회·개입은 감사 로그에 기록됩니다", `AuditLogScreen.tsx:31` "조회행위(PII_VIEW 등)도 기록됩니다" — 서버는 조회를 기록하지 않는다 |

**비범위**
- 제재(`SANCTION`)·등급 수동 조정(`GRADE_ADJUST`) 실행기 — 제재의 효과(정지 시 제보 차단 등)가 정의돼 있지 않다. ADM-320과 함께 Phase 2
- 이의 제기(`POST /v1/appeals`, ADM-400), 어뷰징 큐(ADM-300) — Phase 2
- 2FA 검사 — 별도 과제
- 개인정보 열람 기록(`PII_VIEW`) — `users`에 가릴 개인정보 컬럼이 없다(핸들·가입일·Firebase UID뿐). 본인인증으로 실명·연락처가 생길 때 함께
- SLA 통보 채널(메신저 웹훅, O10) — Phase 2. 이번에는 로그·감사 로그·ADM-010 알림
- 병합 큐 클레임·보류·에스컬레이션, 정규화 — SP2b
- 파라미터 스튜디오 편집 항목 확대

**개발 DB**: 초기화 불필요. V30은 기존 행을 채우는 방식이다(§7).

## 1. 결정

| # | 결정 | 이유 |
|---|---|---|
| K1 | **2인 승인 = 요청자 + 승인자 1명.** 승인 1회로 실행(`PENDING → EXECUTED`). `PARTIAL`은 쓰지 않는다. `PARAM_APPLY`도 같다 | 4-eyes의 본래 뜻. 3명 체계는 Phase 1 운영 인원으로 모든 승인이 막힐 수 있다(사용자 결정) |
| K2 | 승인자 자격: **활성 ADMIN · 승인 대기 계정 아님 · 요청자 아님 · `approver_since` 경과** | P8. 계정 수준이 아니라 사람 수준의 4-eyes |
| K3 | **100점 = 그 작업이 만드는 원장 변동 절댓값의 합.** 재판정·항목 VOID는 제보별 차액 절댓값의 합, 수동 ADJ는 금액 절댓값. **100 초과**가 승인 대상(정확히 100은 통과) | A에게 +60, B에게 −60도 120점을 움직인 것이다. 기준값 100은 CLAUDE.md·02 §1.1 |
| K4 | 한도를 넘으면 **아무것도 쓰지 않고 승인 요청만** 만든다(202). 승인되면 **그 시점 입력으로 다시 계산해 실행**하고, 예상·실제 차액을 둘 다 감사 로그에 남긴다 | 승인 대기 중 제보가 더 VOID되는 것이 정상적인 흐름이다. 재판정은 "지금의 입력"으로 도는 작업이다(사용자 결정) |
| K5 | **항목당 대기 중인 재판정·VOID 요청은 하나.** 대기 요청이 있으면 그 항목의 재판정·VOID는 금액과 무관하게 409(`approval-pending`). DB 부분 UNIQUE + 항목 행 잠금 아래에서 확인 | 대기 중에 작은 재판정이 먼저 반영되면 승인자가 본 전제가 깨진다 |
| K6 | **부트스트랩 예외는 계정 생성에만.** 요청자 외에 승인 자격자가 0명이면 ADMIN 단독으로 계정을 만들고 즉시 활성화한다(`ACCOUNT_CREATE_BOOTSTRAP` 감사). 부트스트랩 러너가 만든 계정은 `approver_since = created_at` | 없으면 두 번째 ADMIN을 영영 만들 수 없다. 대신 첫 7일은 승인 자격자가 없어 모든 승인(파라미터 적용 포함)이 막힌다 — 알려진 비용 |
| K7 | **세션을 요청마다 재검증.** 계정이 비활성·승인 대기이거나 DB 역할이 세션 역할과 다르면 세션을 폐기하고 401 | 비활성화·역할 변경이 즉시 효력을 가져야 통제다. 요청당 PK 조회 1회 |
| K8 | **ADM-100 큐 VOID는 게이트를 넘으면 409(`void-needs-approval`)** — 큐 항목은 `PENDING`으로 남고, 판정된 항목의 VOID는 ADM-200에서 하도록 안내 | 큐 결정은 멱등키로 즉시 확정되는 흐름이라 "승인 대기"를 끼우면 큐 상태 모델이 복잡해진다. 판정된 항목이 큐에 남는 경우는 드물다(SP2 이후 큐는 판정 전 항목만 적재) |
| K9 | 자동 임시 비공개 후에도 **신고는 `OPEN`으로 남아 사람이 처리한다.** 오신고 처리를 위해 **`OPEN`에서 `RESTORE` 결정을 허용**한다. 영구 비공개는 지금처럼 소명(`EXPLAINING`) 후에만 | R4 — 자동은 가역적 조치까지, 확정은 사람. 소명 기회 없는 영구 비공개는 제재 방어가 안 된다 |
| K10 | 병합 24h 초과 시 두 항목의 관측 마감을 **`max(현재 마감, 지금 + 24h)`, 상한 최초 제보 + 21일**로 민다. 매시간 반복 | 결정 전에 판정이 나지 않게 한다. 상한은 기존 유예 연장(`MAX_DEADLINE_DAYS`)과 같다 |
| K11 | 90일 미접속 비활성화는 **마지막 활성 ADMIN이면 건너뛴다**(로그 경고) | 마지막 ADMIN이 잠기면 DB 수정 없이는 복구할 수 없다. 부트스트랩 러너는 계정이 0개일 때만 돈다 |
| K12 | 개인정보 열람 기록은 이번에 만들지 않고, **거짓 문구를 지운다** | 가릴 컬럼이 없다(사용자 결정). 기록하지 않는 것을 기록한다고 쓰는 것이 더 나쁘다 |

## 2. 승인 게이트

### 2.1 `ApprovalService` 변경 (K1·K2)

- `approve(actorId, actorRole, id)`: 행 잠금 → 상태가 `PENDING`인지 → 승인자 자격(K2) 확인 → `approver_1` 기록 → 실행기 `execute` → `EXECUTED`. 실행기가 예외를 던지면 트랜잭션 전체 롤백(요청은 `PENDING`으로 남아 재시도 가능, 지금 규칙과 같다).
- 자격 위반 메시지(409 `ApprovalConflictException`):
  - 요청자 본인: "요청자 본인은 승인할 수 없습니다"
  - 유예 중: "승인권은 {approver_since KST}부터 생깁니다"
  - 미활성·비활성 계정: 세션 재검증(K7)이 먼저 막는다
- `ApprovalRequest.approveSecond`·`PARTIAL` 분기를 지운다. `ApprovalStatus.PARTIAL`은 DB enum에 남기되 코드에서 쓰지 않는다(V30이 기존 `PARTIAL` 행을 `PENDING`으로 되돌린다).
- 새 조회 `ApprovalService.eligibleApproverCount(excludingId)` — K6 부트스트랩 판정용.

### 2.2 `ApprovalGate` (신설, `api-admin/approval`)

```java
public static final BigDecimal THRESHOLD = new BigDecimal("100");

/** 승인 요청을 만든다. 같은 트랜잭션에서 호출 — 대상 행 잠금은 호출 측 책임. */
ApprovalRequest request(ActionType type, UUID targetRef, Map<String, Object> payload, AdminPrincipal requester);

boolean exceeds(BigDecimal amount);   // amount.abs() > THRESHOLD
```

- `ActionType` enum(`api-admin`): `PARAM_APPLY`, `VERDICT_REJUDGE`, `ITEM_VOID`, `LEDGER_ADJ`, `ACCOUNT_CREATE`, `ACCOUNT_ROLE_CHANGE`. DB 컬럼은 계속 `VARCHAR(40)`.
- `request`는 `approval_requests` INSERT + 감사 `APPROVAL_REQUEST`(`detail`: actionType, payload 요약)를 남긴다.
- 기존 `ParamStudioService.requestApproval`도 `ApprovalGate.request`를 쓰도록 바꾼다(한 곳).
- `ApprovalExecutor`에 `String describe(ApprovalRequest)`를 추가한다 — 승인 화면 요약 문구("재판정 · 두바이초콜릿 · 예상 차액 125.0점 · 사유: 카르텔 적발"). 대상 이름은 실행기가 조회한다.

### 2.3 실행기 5종 (+기존 1종)

| action_type | target_ref | payload | 실행 | 반려 |
|---|---|---|---|---|
| `PARAM_APPLY` | 드래프트 | 기존 | 기존 | 기존(드래프트 복귀) |
| `VERDICT_REJUDGE` | 항목 | reason, expectedAdjTotal, expectedResult | `JudgeService.rejudge(item, reason, now, approved(requestId, approverId))` | 없음 |
| `ITEM_VOID` | 항목 | reason, expectedAdjTotal | `JudgeService.voidItem(item, reason, now, approved(...))` | 없음 |
| `LEDGER_ADJ` | 유저 | amount, reason | 수동 ADJ 행 INSERT(§4.2) | 없음 |
| `ACCOUNT_CREATE` | 계정 | loginId, displayName, role | `activated_at = now` | `disabled_at = now`(미활성 그대로 — 대기로 남지 않음, 최종 리뷰 #4) |
| `ACCOUNT_ROLE_CHANGE` | 계정 | from, to | 현재 역할이 `from`이 아니면 409 · 역할 = `to` · `to == ADMIN`이면 `approver_since = now + 7일` | 없음 |

- 판정 실행기는 실행 직전 감사 `APPROVAL_EXECUTE`의 `detail`에 `expectedAdjTotal`과 실제 `adjTotal`을 함께 남긴다(K4).
- 요청 시점과 실행 시점 사이에 항목이 VOID·병합돼 실행이 불가능하면 `JudgeConflictException` → 승인 트랜잭션 롤백 → 요청은 `PENDING`으로 남는다. 승인자는 반려로 정리한다.

## 3. 판정 연동 (`judge` 모듈)

### 3.1 `JudgeService` 변경

한도 판단을 **쓰기 전에** 한다. 예외가 아니라 결과값으로 돌려준다 — 예외가 `@Transactional` 프록시를 지나면 호출 측 트랜잭션이 rollback-only가 되어 승인 요청을 저장할 수 없다.

```java
/** 이 작업의 원장 변동을 어떻게 다룰지. */
public record AdjustmentPolicy(BigDecimal limit, UUID approvalId, UUID approvedBy) {
    public static AdjustmentPolicy limitedTo(BigDecimal limit);            // 관리자 직접 실행
    public static AdjustmentPolicy approved(UUID approvalId, UUID by);    // 승인 실행기 — 한도 없음
}

public sealed interface Outcome {
    record Applied(Optional<Verdict> verdict, BigDecimal adjTotal) implements Outcome {}
    record NeedsApproval(BigDecimal adjTotal, VerdictResult expectedResult) implements Outcome {}
}

public Outcome rejudge(UUID itemId, String reason, Instant now, AdjustmentPolicy policy);
public Outcome voidItem(UUID itemId, String reason, Instant now, AdjustmentPolicy policy);
```

- `settle`을 둘로 나눈다: `diffs(chain, lines)` → 제보별 `(userId, amount)` 맵(순수 계산), 그리고 쓰기. `adjTotal = Σ|amount|`(원장 자릿수 4, `amount()`와 같은 반올림).
- `rejudge`: 잠금 → 체인 → 입력 수집 → 계획 → **diffs → `limit` 초과면 `NeedsApproval` 반환(쓰기 없음)** → 판정 쌓기 → 원장 → 제보 결과 → 상태.
- `voidItem`: 잠금 → 상태 확인 → 현행 판정이 있으면 diffs(전액 상쇄) → **초과면 `NeedsApproval`** → 제보 VOID → VOID 판정 → 원장 → 상태. 판정 전 항목은 `adjTotal = 0`이라 항상 `Applied`.
- `approved(...)`로 실행하면 ADJ 행에 `approval_id`·`approved_by_admin_id`를 채운다 — `ScoreLedgerEntry.verdictAdjustment`에 두 인자 추가.
- 배치 판정(`judge`)은 바뀌지 않는다 — 원 판정은 ADJ가 아니다.

### 3.2 `VerdictAdminService` (ADM-200)

```
voidVerdict / requestRejudge:
  사유 필수 → trendItems.findByIdForUpdate(item)     // K5의 확인과 요청 생성을 직렬화
  → 대기 중인 VERDICT_REJUDGE·ITEM_VOID 요청이 있으면 409 approval-pending
  → judgeService.xxx(..., limitedTo(THRESHOLD))
  → Applied: 감사 VERDICT_VOID/VERDICT_REJUDGE(adjTotal 포함), 200 {status:"APPLIED", verdictId, adjTotal}
  → NeedsApproval: gate.request(...), 202 {status:"PENDING_APPROVAL", approvalRequestId, adjTotal}
```

- 실행기도 같은 잠금 → 대기 확인(자기 자신 제외) 순서를 탄다.
- 응답 타입 `VerdictActionResponse(status, verdictId, approvalRequestId, adjTotal)`. 컨트롤러는 `status`에 따라 200/202.

### 3.3 `MergeDecisionService` (ADM-100 VOID, K8)

- VOID 전에 같은 대기 확인(409 `approval-pending`).
- `judgeService.voidItem(..., limitedTo(THRESHOLD))`가 `NeedsApproval`이면 409 `void-needs-approval`("판정된 항목이라 차액 N점 — ADM-200 판정 관리에서 VOID하세요(2인 승인)"). 큐 항목은 `PENDING`, 멱등키는 기록하지 않는다.
- `MergeConflictException.Reason`에 두 사유를 더하고 `AdminApiExceptionHandler`가 `type`으로 내보낸다(SP2 방식과 같다).

## 4. ADM-311 유저 원장 (`AdminUserController`, 신설)

### 4.1 조회 — R/O/A/Au

- `GET /admin/users?handle={prefix}` → 최대 20건 `[{id, handle, grade, joinedAt}]`. 핸들 앞부분 일치, 대소문자 무시. 빈 값이면 422. (ADM-310 목록은 Phase 2 — 이것은 화면 진입 수단이다.)
- `GET /admin/users/{id}` → `AdminUserDetail`:
  - `handle`, `status`, `joinedAt`, `grade`(주간 스냅샷, 없으면 L0), `gradeComputedAt`
  - `trustIndex`, `activeScore`, `judgedCount`, `hitInWindow`, `missInWindow` — `GradeInputsReader`(SP1, `/v1/me`와 같은 입력). `basis` 문자열 목록 동봉(관리자 API 산정 근거 규약): "TI 0.52 = (HIT 6 + 2) / (HIT 6 + MISS 4 + 5) · 최근 180일"
  - `abuseFlagCount`(`abuse_flags`, 지금은 0)
  - `ledger`: 최신순 전체 `[{id, createdAt, kind, delta, reason, trendItemName, verdictId, approvedBy(표시 이름), approvalId}]`
- 존재하지 않는 유저 404.

### 4.2 수동 ADJ — `POST /admin/users/{id}/ledger-adjustments {amount, reason}`

- O/A. 사유 필수, `amount ≠ 0`, `|amount| ≤ 9999`(NUMERIC(10,4)), 소수 4자리 반올림.
- ADMIN이고 `|amount| ≤ 100` → 즉시 INSERT, 201 `{status:"APPLIED", ledgerId}`, 감사 `LEDGER_ADJ`.
- OPERATOR(항상, 02 §1.1 "상신") 또는 `|amount| > 100` → `LEDGER_ADJ` 승인 요청, 202.
- 행: `kind=ADJ`, `submission_id`·`verdict_id` NULL, `halflife_days` = 현재 파라미터, `decay_anchor_at` = 기록 시각, `reason` = "수동 조정 · {사유}". 승인 실행이면 `approval_id`·`approved_by_admin_id`.
- 기존 `score_ledger.approved_by`(users FK)는 쓰지 않는다 — 관리자를 가리킬 수 없는 잔재. V30 주석으로 표시.

## 5. 계정 통제

### 5.1 스키마 (V30)

- `admin_accounts.approver_since TIMESTAMPTZ NOT NULL` — 승인권이 생기는 시각. 기존 행은 `created_at`.
- `admin_accounts.activated_at TIMESTAMPTZ` — NULL = 승인 대기. 기존 행은 `created_at`.

### 5.2 동작

- **생성** `POST /admin/accounts`(ADMIN): 계정 행을 `activated_at = NULL`, `approver_since = now + 7일`로 만든다(비밀번호 해시를 승인 payload에 넣지 않으려고 행을 먼저 만든다). 그 다음
  - 승인 자격자(요청자 제외) ≥ 1 → `ACCOUNT_CREATE` 요청, 202, 계정은 "승인 대기"
  - 0 → 즉시 `activated_at = now`, 감사 `ACCOUNT_CREATE_BOOTSTRAP`, 201 (K6)
- **로그인**: `activated_at IS NULL`이면 403 "승인 대기 중인 계정입니다"(`AccountDisabledException`과 같은 계열). 실패 카운트는 올리지 않는다.
- **역할 변경** `POST /admin/accounts/{id}/role {role, reason}`(ADMIN): 본인 불가(`SelfModificationException`), 같은 역할이면 422, 사유 필수 → `ACCOUNT_ROLE_CHANGE` 요청, 202. 부트스트랩 예외 없음.
- **비밀번호 변경** `POST /admin/me/password {current, next}`(모든 역할, 본인): 현재 비밀번호 일치, 새 비밀번호 8자 이상·현재와 다름 → 해시 교체, 감사 `ACCOUNT_PASSWORD_CHANGE`(`detail`에 비밀번호 없음). 틀리면 422(로그인 잠금 카운터와 무관).
- **비활성화·활성화**: 지금처럼 ADMIN 단독. 활성화는 `disabled_at`만 지우고 `activated_at`은 건드리지 않는다(승인 대기 계정을 활성화로 우회하지 못한다 — 422).
- 부트스트랩 러너(`AdminAccountBootstrapRunner`)는 `approver_since = activated_at = created_at`으로 만든다.

### 5.3 세션 재검증 (K7)

- `AdminSessionRevalidationFilter`(api-admin 필터 체인, 인증 이후): 세션 주체가 있으면 `admin_accounts`를 PK로 읽어
  - 없음·`disabled_at` 있음·`activated_at` 없음 → 세션 무효화, 401
  - DB 역할 ≠ 세션 역할 → 세션 무효화, 401("권한이 바뀌었습니다 — 다시 로그인하세요")
- 로그인·로그아웃·CSRF 엔드포인트는 제외.

## 6. 역할·감사 로그

### 6.1 역할 정합 (02 §1.1)

| 엔드포인트 | 지금 | SP3 |
|---|---|---|
| `GET /admin/reports`, `…/{id}/submissions` | R/O/A | R/O/A/**Au** |
| `POST /admin/reports/{id}/hide` | R/O/A | **O/A** |
| `POST /admin/reports/{id}/request-explanation` | R/O/A | R/O/A (유지) |
| `GET /admin/params/draft` | O/A | O/A/**Au** |
| `/admin/users/**` 조회 | 없음 | R/O/A/Au |
| `POST /admin/users/{id}/ledger-adjustments` | 없음 | O/A |

프론트 `role.ts`의 `CAN`도 같은 값으로 맞춘다(표시용).

### 6.2 감사 로그 (ADM-700)

- `GET /admin/audit-log?action=&actorId=&targetId=&from=&to=&beforeId=` — 최신순 100건, `beforeId` 커서, `nextBeforeId` 반환. `from`/`to`는 ISO-8601.
- 응답에 `detail`(JSON 객체) 추가. 액터가 NULL이면 `SLA_` 행은 "(시스템)", 그 밖은 "(미인증)".
- OPERATOR는 필터와 무관하게 본인 행만.
- 저장소: `JpaSpecificationExecutor`로 조건 조합. 인덱스는 기존 PK 순회로 충분(Phase 1 규모).

## 7. `sla_watch`

- `SlaWatchJob`(scheduler): `@Scheduled(cron = "${jobs.sla-watch.cron:0 0 * * * *}")`, `@SchedulerLock(name = "sla_watch", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")`. 목록 순회·로그만 한다. 처리는 별도 빈 `SlaWatchService`(scheduler)의 `@Transactional` 메서드 세 개 — 같은 빈 self-invocation 금지(CLAUDE.md).
- 세 단계는 서로 독립이다. 단계 안에서도 행 하나가 한 트랜잭션이라(후보 id 조회 → 단위별 잠금·재확인·처리) 한 행이 실패해도 나머지는 돈다(단위별 try/catch, 실패는 로그 — 최종 리뷰 R11).
- 감사 로그 액터는 NULL(시스템), 역할 NULL.

### 7.1 신고 4h → 자동 임시 비공개 (K9)

- 대상: `status = OPEN AND auto_hidden_at IS NULL AND created_at ≤ now − 4h`.
- 신고마다: 항목 행 잠금 → `visibility = PUBLIC`이면 `TEMP_HIDDEN`(이미 숨김·영구 비공개면 그대로) → `reports.auto_hidden_at = now` → 감사 `SLA_AUTO_HIDE`(`detail`: reportId, trendItemId, 이전 visibility, 경과 시간).
- 신고 상태는 `OPEN` 그대로. 사람이 `hide`(소명 대상 지목) 또는 `request-explanation`으로 이어가거나, 오신고면 `OPEN`에서 `RESTORE`.
- `ReportAdminService.decide`: `RESTORE`는 `OPEN`·`EXPLAINING` 둘 다 허용, `HIDE_PERMANENT`·`EDIT_RESTORE`는 `EXPLAINING`에서만(지금과 같음). 단 `OPEN`에서의 `RESTORE`는 항목이 `PERMANENT_HIDDEN`이거나 같은 항목의 다른 신고가 `EXPLAINING`이면 409 `report-restore-blocked`(최종 리뷰 R9 — 다른 신고의 결정·소명을 뒤집지 않게).
- 멱등: `auto_hidden_at`이 있으면 다시 처리하지 않는다.

### 7.2 병합 24h → 판정 마감 연장 (K10)

- 대상: `merge_queue.status = PENDING AND created_at ≤ now − 24h`의 두 항목(신규·기존).
- 항목마다(id 오름차순 잠금 — `MergeService.lockPair` 재사용): 상태가 `DRAFT`·`PENDING`·`JUDGING`이고 원 판정이 없을 때만.
  - `target = min(max(현재 마감, now + 24h), first_seen_at + 21일)`
  - `target > 현재 마감`이면 `judgment_deadline_override = target`, 감사 `SLA_GRACE_EXTEND`(`detail`: queueId, 이전·새 마감). `JUDGING`이고 `target > now`면 `PENDING`으로(ADM-200 `extendGrace`와 같은 규칙).
- 새 순수 함수 `DeadlineWindow.slaExtended(firstSeenAt, override, now)` → `Optional<Instant>`.
- 상한에 닿아 더 못 미는 경우 감사 로그를 반복해 남기지 않는다. 대신 ADM-010 알림: `QueueSummaryService`가 "병합 대기로 판정 마감 상한(최초 제보 + 21일)에 닿은 항목 N건"을 계산해 보여 준다.

### 7.3 90일 미접속 관리자 → 비활성화 (K11)

- 대상: `disabled_at IS NULL AND activated_at IS NOT NULL AND greatest(last_login_at, activated_at, last_enabled_at, created_at) ≤ now − 90일`(NULL은 건너뜀). `last_enabled_at`은 수동 재활성화 시각 — 재활성화·늦은 승인 직후 다시 꺼지지 않게(최종 리뷰 R10).
- 대상이 활성 ADMIN이고 그를 비활성화하면 활성 ADMIN이 0명이 되면 건너뛰고 WARN 로그.
- 비활성화 → 감사 `SLA_ADMIN_DISABLE`(`detail`: 마지막 로그인). 세션은 K7이 다음 요청에서 끊는다.

### 7.4 ADM-010 문구

`QueueSummaryService` 알림을 실제 동작에 맞게 바꾼다.
- 신고: "4시간 초과 N건 — 자동 임시 비공개됨, 처리 필요"
- 병합: "24시간 초과 N건 — 판정 마감 자동 연장 중" + 상한 도달 건수(있으면)

## 8. 스키마 — `V30__sp3_console_control.sql`

```sql
-- 계정 통제(K2·K6)
ALTER TABLE admin_accounts
    ADD COLUMN approver_since TIMESTAMPTZ,
    ADD COLUMN activated_at   TIMESTAMPTZ;
UPDATE admin_accounts SET approver_since = created_at, activated_at = created_at;
ALTER TABLE admin_accounts ALTER COLUMN approver_since SET NOT NULL;

-- 자동 임시 비공개 기록(K9)
ALTER TABLE reports ADD COLUMN auto_hidden_at TIMESTAMPTZ;

-- 승인된 ADJ의 승인자(관리자). 기존 approved_by(users FK)는 관리자를 못 가리키는 잔재 — 쓰지 않는다
ALTER TABLE score_ledger
    ADD COLUMN approved_by_admin_id UUID REFERENCES admin_accounts(id),
    ADD CONSTRAINT ledger_approval_pair CHECK ((approval_id IS NULL) = (approved_by_admin_id IS NULL));

-- 승인 1명 체계(K1): 남은 PARTIAL 요청을 처음부터 다시 승인받게 한다
UPDATE approval_requests SET status = 'PENDING', approver_1 = NULL WHERE status = 'PARTIAL';

-- 항목당 대기 중인 재판정·VOID 요청 하나(K5)
CREATE UNIQUE INDEX approval_one_pending_verdict_change
    ON approval_requests (target_ref)
    WHERE status = 'PENDING' AND action_type IN ('VERDICT_REJUDGE', 'ITEM_VOID');
```

주석(`COMMENT ON`)을 새 컬럼마다 단다(V23 관례). `score_ledger`의 append-only 트리거는 행 UPDATE만 막으므로 `ADD COLUMN`은 영향 없다.

## 9. 프론트 (백엔드가 커밋·리뷰를 통과한 뒤 — 상위 스펙 §4 규칙 1)

- **ADM-311 `UserLedgerScreen`**: 핸들 검색 → 선택 → 실데이터. 산정 근거 표시. 개인정보 토글·마스킹 안내 삭제. 수동 ADJ는 응답에 따라 "기록됨"/"승인 대기로 올렸습니다(차액 N점)". 제재 상신·등급 수동 조정 버튼은 "미구현(Phase 2)"으로 비활성.
- **ADM-200 `VerdictScreen`**: 202면 "차액 N점 — 승인 대기로 올렸습니다", 409 `approval-pending`이면 "이 항목에 대기 중인 승인 요청이 있습니다".
- **ADM-100 `MergeQueueScreen`**: 409 `void-needs-approval` 안내(ADM-200으로 이동 링크).
- **`ApprovalsScreen`**: 6종 표시, `describe` 요약, 진행 `0/1`, 유예 중 승인 시 서버 메시지.
- **ADM-800 `AdminAccountsScreen`**: 생성 결과 "승인 대기"/"생성됨(부트스트랩)", 승인 대기 배지, 역할 변경 다이얼로그(사유 필수), "승인권 {날짜}부터" 표시. 하단 "2인 승인 워크플로는 아직…" 문구 교체.
- **내 비밀번호 변경**: 헤더 계정 메뉴에서 다이얼로그.
- **ADM-410 `ReportQueueScreen`**: "자동 숨김" 배지, `OPEN`에서 복원, REVIEWER에게 숨김 버튼 비활성.
- **ADM-700 `AuditLogScreen`**: 필터 바, `detail` 펼치기, "더 보기". "조회행위(PII_VIEW 등)도 기록됩니다" 삭제.
- **`Layout.tsx:123`**: "모든 개입(변경)은 감사 로그에 기록됩니다"로.
- 401(세션 폐기)이면 로그인 화면으로 — 기존 인터셉터 동작 확인.

## 10. 테스트

통합 테스트(Testcontainers, 공유 DB — 이름·키 랜덤, 배치 전 `releaseBatchLock`).

| # | 케이스 |
|---|---|
| 1 | 신고 4h 초과 → `sla_watch` 1회에 `TEMP_HIDDEN`·`auto_hidden_at`·감사. 두 번 돌려도 감사 1행. 3h59m은 그대로. 이미 `PERMANENT_HIDDEN`이면 그대로 **(완료 기준)** |
| 2 | 자동 숨김 후 `OPEN`에서 `RESTORE` → `PUBLIC`, `DECIDED`. `OPEN`에서 `HIDE_PERMANENT`는 422 |
| 3 | 재판정 차액 101점 → 202, 판정·원장 불변, 요청 `PENDING`. 승인 → 판정 supersede, ADJ에 `approval_id`·`approved_by_admin_id`, 감사에 예상·실제 차액 **(완료 기준)** |
| 4 | 재판정 차액 100점 이하 → 200 즉시 반영 |
| 5 | 대기 요청이 있는 항목의 재판정·VOID(금액 무관) → 409 `approval-pending`. 동시 요청 두 개 → 하나만 성공(DB 인덱스) |
| 6 | 판정된 항목 VOID 차액 > 100 → 202, 승인 → 제보 VOID·원장 전액 상쇄 |
| 7 | ADM-100 큐 VOID가 한도 초과 → 409 `void-needs-approval`, 큐 `PENDING`, 멱등키 미기록 |
| 8 | 승인 요청 후 그사이 제보가 VOID됨 → 승인 실행이 새 입력으로 계산(실제 차액 ≠ 예상) |
| 9 | 승인자 자격: 요청자 본인·유예 중 ADMIN·OPERATOR → 거부. 자격 있는 ADMIN 1명 승인으로 `EXECUTED` |
| 10 | `PARAM_APPLY`가 승인 1회로 적용된다(회귀) |
| 11 | 부트스트랩: 자격자 0명이면 계정 즉시 활성, 자격자가 생기면 생성이 202·승인 대기·로그인 403 |
| 12 | 역할 변경 승인 → 역할 반영·`approver_since` 재설정. 대상의 기존 세션 요청 → 401 |
| 13 | 비활성화된 관리자의 기존 세션 요청 → 401 |
| 14 | 수동 ADJ: ADMIN 50 → 201 즉시, ADMIN 150 → 202, OPERATOR 10 → 202. 승인 후 행의 감쇠 기준·승인자 |
| 15 | 비밀번호 변경: 틀린 현재 비밀번호 422, 성공 후 새 비밀번호로 로그인 |
| 16 | 병합 대기 24h 초과 → 두 항목 마감 = now + 24h, 매시간 반복해도 상한(최초 + 21일) 초과 없음. `JUDGING` → `PENDING` 복귀. 판정된 항목은 건드리지 않음 |
| 17 | 90일 미접속 → 비활성화·감사. 마지막 활성 ADMIN이면 건너뜀 |
| 18 | 역할: AUDITOR 신고 큐·드래프트·유저 원장 조회 200, REVIEWER `hide` 403 |
| 19 | 감사 로그: `detail` 포함, `action`·`from`/`to`·`beforeId` 필터, OPERATOR는 본인 행만 |
| 20 | `GET /admin/users/{id}`의 TI·AS가 `/v1/me/grade`와 같은 값, 원장에 승인자 이름 |

단위: `DeadlineWindow.slaExtended`, `ApprovalGate.exceeds` 경계(100 / 100.0001), `JudgeService` diffs 합계.

## 11. 문서·설정

- CLAUDE.md: 배치 표 `sla_watch`(구현), 관리자 콘솔 절의 "초과 시 동작은 전부 미구현" 문단, `ApprovalGate` 미구현 문장, 2인 승인 정의(요청자 + 승인자 1명·승인권 유예 7일·부트스트랩 예외), Phase 1 ADM-311 문장.
- 02: §1.1 매트릭스 아래 미구현 문단, ADM-010 SLA 표, ADM-200·311·410·700·800, 승인 인원 정의.
- 04 §6 배치 표·§7.2·§8, 05 해당 행, `openapi.yaml`(`/admin/users/**` 실제 경로·필드, 202 응답, 409 type, 감사 로그 파라미터, 계정 role·password), README 구현 상태.
- `infra/.env.example`: `JOBS_SLA_WATCH_CRON`(선택) 주석 한 줄.

## 12. 완료 기준

- 상위 스펙 SP3 기준: 신고 4h 초과 자동 `TEMP_HIDDEN` 통합 테스트(#1), 재판정 101점 ADJ가 승인 대기로 전환(#3), UI의 "감사 기록" 문구는 서버 기록이 있는 곳만(§9).
- §10 전 케이스와 기존 테스트 통과, 콘솔 빌드 통과.
- 코드에 `PARTIAL` 분기, 원장을 쓰면서 한도를 보지 않는 관리자 경로(재판정·VOID·수동 ADJ)가 없다.
- Docker 수동 확인: 빈 DB 기동 → 부트스트랩 ADMIN이 두 번째 ADMIN을 단독 생성(부트스트랩 예외) → 그 계정에 "승인권 {7일 뒤}부터" 표시 → 두 번째 ADMIN이 유예 중이라 세 번째 계정도 부트스트랩 예외로 즉시 생성됨(K6). 유예가 지난 뒤 승인 대기로 바뀌는 것은 통합 테스트 #11이 `MutableClock`으로 확인한다.

## 13. 리스크·후속

- **첫 7일 승인 공백(K6)**: 부트스트랩 뒤 만든 ADMIN은 7일 동안 승인할 수 없어 파라미터 적용·100점 초과 정정이 막힌다. 그 사이에는 부트스트랩 예외로 계정을 더 만들 수 있다 — 부트스트랩 기간의 단독 권한은 감사 로그(`ACCOUNT_CREATE_BOOTSTRAP`)로만 추적된다.
- **ADM-100 VOID 제한(K8)**: 판정된 항목을 큐에서 VOID할 수 없게 된다. 운영자는 ADM-200으로 가야 한다.
- **자동 숨김 뒤 복원과 다른 신고**: 한 항목에 신고가 여럿이면 한 신고의 `RESTORE`가 항목을 공개로 돌린다(단 `OPEN` 신고의 `RESTORE`는 영구 비공개 항목·다른 신고 소명 중이면 409, R9). 이미 자동 처리된 다른 신고는 다시 숨기지 않는다 — 사람이 큐에서 이어 처리한다.
- **세션 재검증 비용**: 관리자 요청마다 PK 조회 1회. 콘솔 사용량에서 문제없다.
- **`sanctions.requested_by`가 `users`를 참조**하는 스키마 오류는 제재 실행기(Phase 2)와 함께 고친다.
- 2FA·PII_VIEW·SLA 통보 채널·이의 제기는 비범위(§0) — CLAUDE.md·02에 남은 "미구현" 표기를 이 스펙 기준으로 정리한다.
