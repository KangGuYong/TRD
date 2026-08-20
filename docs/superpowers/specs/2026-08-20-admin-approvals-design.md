# A1 · 2인 승인 실행 경로 (`/admin/approvals`)

## 배경

`approval_requests` 테이블(V6)·`ApprovalRequest` 엔티티·`ApprovalRequestRepository`는 이미 있고, `ParamStudioService.requestApproval()`이 `PARAM_APPLY` 승인 요청을 실제로 생성한다. 그런데 그 요청을 승인/반려할 엔드포인트가 하나도 없다 — `ApprovalRequest` 엔티티는 getter만 있고 `approver_1`/`approver_2`/`resolved_at`을 채우는 메서드조차 없다. 지금 ADM-600은 "시뮬레이션 → 승인 요청 생성(0/2)"에서 끊기고, 그 이후 실제로 파라미터가 운영에 반영되는 경로가 없다. `ParameterSetProvider.current()`도 `ParameterSet.defaults()`만 반환하는 스텁으로 `// TODO: parameter_drafts(APPLIED) 최신본 조회` 주석이 그대로 남아있다.

DB의 `approver1_not_requester`·`approver2_not_requester`·`approvers_distinct` CHECK 제약은 이미 깔려 있으므로 그 규칙을 신뢰하고 서비스/컨트롤러만 채우면 된다.

B3(어뷰징·제재)·B4(유저 관리·등급 정책)도 최종적으로 이 승인 경로를 타야 하므로, 이번 작업은 A1 자체 완성이자 두 후속 작업의 선행 조건이다.

## 목표

1. 대기 중인 승인 요청 목록을 조회할 수 있게 한다.
2. 두 명의 서로 다른 관리자가 승인하면 승인 요청과 연결된 실제 작업(현재는 `PARAM_APPLY` 하나)이 실행되게 한다.
3. 승인 대기 상태를 되돌릴 방법이 없는 문제를 반려(reject) 엔드포인트로 해결한다.
4. 액션 타입별 실행 로직을 플러그인처럼 등록할 수 있는 구조를 만들어, B3/B4가 각자의 실행기만 추가하면 되게 한다.

## 범위

- 다루는 액션 타입: 이번 스코프에서 실제로 승인 요청을 만드는 곳은 `ParamStudioService.requestApproval()` (`PARAM_APPLY`)뿐이다. `SANCTION`/`GRADE_ADJUST`/`LEDGER_ADJ_OVER100`은 그 요청을 만드는 기능(B3/B4) 자체가 아직 없으므로 실행기를 구현하지 않는다 — 대신 실행기 레지스트리 구조만 만들어 이후 확장 지점을 남긴다.
- `parameter_drafts.superseded_by`(원클릭 롤백용 버전 체인)는 이번 스코프에서 건드리지 않는다. `ParameterSetProvider`는 "가장 최근 `APPLIED`" 한 건만 보면 되므로 체인이 없어도 동작에 지장이 없다.
- `applyMode=RETROACTIVE`(소급 적용) 처리는 범위 밖. 현재 `requestApproval()`이 `applyMode`/`applyAt`을 입력받지 않고 항상 `SCHEDULED` 기본값을 쓰므로, 이번 실행 로직도 "승인 즉시 적용(신규 판정부터 반영)"만 구현한다. 사유서·영향 유저 사전 통보를 요구하는 소급 적용 플로우는 별도 스코프.
- 신규 DB 마이그레이션 없음 — `approver_1`/`approver_2`/`resolved_at`(`approval_requests`), `applied_at`(`parameter_drafts`) 컬럼은 V6에 이미 있고 Java 엔티티 매핑만 빠져 있었다.

## 아키텍처

### 승인 상태 머신

```
PENDING ──1차 승인(approver_1)──▶ PARTIAL ──2차 승인(approver_2, approver_1과 달라야 함)──▶ APPROVED ──실행 성공──▶ EXECUTED
PENDING ──반려──▶ REJECTED
PARTIAL ──반려──▶ REJECTED
```

`APPROVED`는 트랜잭션 내부에서만 잠깐 거치는 상태다 — 2차 승인이 확정되면 같은 트랜잭션 안에서 바로 실행기를 호출하고 `EXECUTED`로 전이한다. 실행기가 예외를 던지면 트랜잭션 전체가 롤백되어 승인 요청은 `PARTIAL` 그대로 남고(2차 승인 자체가 없었던 것처럼), 원인을 고친 뒤 다시 승인 시도할 수 있다.

### 동시성

두 관리자가 동시에 "승인"을 누르는 경합은 실제로 발생할 수 있다(CLAUDE.md 규약: "검수자 동시 처리 충돌은 실제로 발생한다"). `ApprovalRequestRepository`에 `@Lock(PESSIMISTIC_WRITE)` 행 잠금 조회를 추가해 승인 처리를 직렬화한다. DB CHECK 제약(`approver1_not_requester` 등)은 마지막 방어선으로 유지하되, 서비스 레이어에서 같은 조건을 먼저 검사해 사용자에게 의미 있는 409 메시지를 준다.

### 실행기 레지스트리

```java
// api-admin/approval 패키지 (신규)
public interface ApprovalExecutor {
    String actionType();                    // "PARAM_APPLY" 등 approval_requests.action_type과 매칭
    void execute(ApprovalRequest request);  // 2/2 승인 시 호출. 예외 시 트랜잭션 롤백.
    default void onReject(ApprovalRequest request) {} // 반려 시 대상 리소스 원복. 기본은 아무 것도 안 함.
}
```

`ApprovalService`가 `List<ApprovalExecutor>`를 생성자로 주입받아 `actionType()` 기준 `Map<String, ApprovalExecutor>`을 만든다. 등록된 실행기가 없는 `actionType`의 요청이 2/2에 도달하면 `IllegalStateException`(설정 오류 — 발생하면 안 되는 상황)을 던진다.

### `ParamApplyExecutor` (유일한 실 구현체)

```java
@Component
public class ParamApplyExecutor implements ApprovalExecutor {
    public String actionType() { return "PARAM_APPLY"; }

    public void execute(ApprovalRequest request) {
        ParameterDraft draft = drafts.findById(request.getTargetRef())
            .orElseThrow(() -> new IllegalStateException("대상 드래프트 없음: " + request.getTargetRef()));
        if (draft.getStatus() != ParamStatus.REVIEW) {
            throw new AdminValidationException("드래프트가 이미 처리된 상태입니다: " + draft.getStatus());
        }
        draft.markApplied(clock.instant());
    }

    public void onReject(ApprovalRequest request) {
        drafts.findById(request.getTargetRef()).ifPresent(ParameterDraft::returnToDraft);
    }
}
```

`ParameterDraft`에 추가할 메서드:
- `markApplied(Instant appliedAt)` — `status=APPLIED`, `appliedAt` 기록.
- `returnToDraft()` — `status=DRAFT`, `approvalId=null` (반려 시 재수정 가능하도록. `ParamStudioService.getOrCreateActiveDraft()`가 `DRAFT`/`REVIEW` 상태만 "활성"으로 보므로 이 값으로 되돌리면 자동으로 다시 편집 대상이 된다).

### `ParameterSetProvider` 배선 완성

```java
@Component
public class ParameterSetProvider {
    public ParameterSet current() {
        return drafts.findFirstByStatusOrderByAppliedAtDesc(ParamStatus.APPLIED)
                .map(d -> d.toParameterSet(objectMapper))
                .orElseGet(ParameterSet::defaults);
    }
}
```

JSON payload → `ParameterSet` 변환 로직은 현재 `ParamStudioService`에 private 메서드로 중복될 뻔한 코드라, `ParameterDraft.toParameterSet(ObjectMapper)`로 엔티티에 옮기고 두 곳(`ParamStudioService`, `ParameterSetProvider`)에서 재사용한다. `scheduler` 모듈 `build.gradle.kts`에 `jackson-databind` 명시적 의존성을 추가한다(현재 `persistence` 모듈이 JSON 컬럼 처리로 이미 Jackson을 쓰고 있지만 `scheduler`가 `ObjectMapper` 타입을 컴파일 타임에 쓰려면 명시적 선언이 필요).

## 백엔드 설계

### `ApprovalRequest` 엔티티 확장

```java
@Column(name = "approver_1") private UUID approver1;
@Column(name = "approver_2") private UUID approver2;
@Column(name = "resolved_at") private Instant resolvedAt;

public void approveFirst(UUID approverId) { this.approver1 = approverId; this.status = ApprovalStatus.PARTIAL; }
public void approveSecond(UUID approverId, Instant now) { this.approver2 = approverId; this.status = ApprovalStatus.APPROVED; this.resolvedAt = now; }
public void markExecuted() { this.status = ApprovalStatus.EXECUTED; }
public void reject(Instant now) { this.status = ApprovalStatus.REJECTED; this.resolvedAt = now; }
```

### `ApprovalRequestRepository` 추가 메서드

```java
List<ApprovalRequest> findByStatusInOrderByCreatedAtAsc(List<ApprovalStatus> statuses); // 대기 큐: PENDING, PARTIAL

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select a from ApprovalRequest a where a.id = :id")
Optional<ApprovalRequest> findByIdForUpdate(@Param("id") UUID id);
```

### `ApprovalService` (신규, `api-admin` 모듈)

```java
@Transactional
public ApprovalRequest approve(UUID actorId, AdminRole actorRole, UUID id) {
    ApprovalRequest req = approvals.findByIdForUpdate(id).orElseThrow(...);
    if (req.getStatus() != PENDING && req.getStatus() != PARTIAL) throw new AdminValidationException("이미 처리된 요청입니다");
    if (req.getRequestedBy().equals(actorId)) throw new AdminValidationException("요청자 본인은 승인할 수 없습니다");

    if (req.getStatus() == PENDING) {
        req.approveFirst(actorId);
        auditLogService.record(actorId, actorRole, "APPROVAL_APPROVE", req.getActionType(), req.getTargetRef(),
            Map.of("approvalRequestId", id, "stage", "1/2"));
        return req;
    }

    // PARTIAL
    if (req.getApprover1().equals(actorId)) throw new AdminValidationException("이미 승인했습니다");
    req.approveSecond(actorId, clock.instant());
    auditLogService.record(actorId, actorRole, "APPROVAL_APPROVE", req.getActionType(), req.getTargetRef(),
        Map.of("approvalRequestId", id, "stage", "2/2"));

    executor(req.getActionType()).execute(req);
    req.markExecuted();
    auditLogService.record(actorId, actorRole, "APPROVAL_EXECUTE", req.getActionType(), req.getTargetRef(),
        Map.of("approvalRequestId", id));
    return req;
}

@Transactional
public ApprovalRequest reject(UUID actorId, AdminRole actorRole, UUID id, String reason) {
    requireReason(reason);
    ApprovalRequest req = approvals.findByIdForUpdate(id).orElseThrow(...);
    if (req.getStatus() != PENDING && req.getStatus() != PARTIAL) throw new AdminValidationException("이미 처리된 요청입니다");
    req.reject(clock.instant());
    executorOpt(req.getActionType()).ifPresent(e -> e.onReject(req));
    auditLogService.record(actorId, actorRole, "APPROVAL_REJECT", req.getActionType(), req.getTargetRef(),
        Map.of("approvalRequestId", id, "reason", reason));
    return req;
}
```

### `ApprovalController`

| 메서드 | 경로 | 권한 |
|---|---|---|
| GET | `/admin/approvals` | `ADMIN`, `AUDITOR` |
| POST | `/admin/approvals/{id}/approve` | `ADMIN` |
| POST | `/admin/approvals/{id}/reject` | `ADMIN` (요청 본문: `{ reason }`, 필수) |

응답 DTO(목록·단건 공용):

```java
public record ApprovalRequestResponse(
    String id, String actionType, String targetRef,
    String requestedBy, String requestedByName,   // AdminAccountRepository로 표시명 조회
    String status, int approvals,                  // approvals = approver1/2 non-null 개수
    String reason,                                  // payload.reason (있으면)
    String createdAt, String resolvedAt) {}
```

목록은 `findByStatusInOrderByCreatedAtAsc(List.of(PENDING, PARTIAL))` — 처리 완료된 것은 큐에서 빠진다(병합 큐 `ADM-100`과 동일 패턴).

## 프론트엔드 설계 — ADM-620 승인 대기함

기존 IA 문서(`02-admin-console.md`)에는 별도 화면 번호가 없지만, 여러 화면(ADM-320 제재 확정, ADM-600 파라미터 적용, ADM-610 등급 정책)이 공통으로 `/admin/approvals/{id}/approve`를 호출하므로 이를 한 곳에서 처리하는 큐 화면이 필요하다. `ADM-010`(오늘의 작업)의 "큐 중심 설계" 철학과 일치한다. 정책 그룹에 `ADM-620 승인 대기함`으로 추가한다.

- 테이블: 액션타입 뱃지 · 대상(targetRef 앞 8자) · 요청자 · 사유 · 승인현황(`0/2`/`1/2`) · 생성시각 · 승인/반려 버튼.
- `role.tsx`에 `CAN.approvalConfirm = (r: Role) => r === "ADMIN"` 추가(현재 `paramApply`와 조건은 같지만, 파라미터 전용이 아닌 일반 2인 승인 권한이라는 의미로 이름을 분리 — B3/B4가 이 화면을 그대로 재사용할 것이므로).
- 승인/반려 버튼은 `requestedBy === principal.id`이면 비활성화(클라이언트 UX 게이트, 서버가 최종 검증).
- 반려는 `MergeQueueScreen`의 사유 입력 패턴을 재사용 — 인라인 텍스트 입력 후 반려 버튼.
- `api/types.ts`에 `ApprovalRequestView` 추가, `api/hooks.ts`에 `useApprovals`/`approveRequest`/`rejectRequest` 추가(기존 `useData`/`api.post` 패턴 그대로), `fixtures.ts`에 `fxApprovals` 샘플 추가.
- `Layout.tsx` NAV에 `{ id: "ADM-620", label: "승인 대기함", code: "ADM-620" }` 추가(정책 그룹, 파라미터 스튜디오 다음).
- `App.tsx`에 라우팅 추가.

## 감사 로그

`APPROVAL_APPROVE`(매 승인 클릭마다, `stage` 필드로 1/2·2/2 구분) / `APPROVAL_EXECUTE`(실행 성공 시) / `APPROVAL_REJECT`. 기존 `AuditLogService.record()`를 그대로 사용 — 신규 서비스 불필요.

## 테스트 관점

- 요청자 본인 승인 시도 → 409.
- 같은 승인자가 두 번 승인 시도 → 409.
- 1차 승인 후 2차 승인 시 실제로 `parameter_drafts.status`가 `APPLIED`로 바뀌고 `ParameterSetProvider.current()`가 새 값을 반환하는지.
- 실행기가 예외를 던지는 상황(대상 드래프트가 이미 `APPLIED`로 바뀐 레이스) → 승인 요청이 `PARTIAL`로 남고 재시도 가능한지.
- 반려 후 드래프트가 `DRAFT`로 돌아가 `ParamStudioService.getOrCreateActiveDraft()`가 그 드래프트를 다시 반환하는지.
- 동시에 두 관리자가 같은 요청에 2차 승인을 시도할 때 한쪽만 성공하는지(행 잠금 검증).

## 스코프 밖 (명시)

- `SANCTION`/`GRADE_ADJUST`/`LEDGER_ADJ_OVER100` 실행기 구현 — B3/B4에서 각자 추가.
- `parameter_drafts.superseded_by` 롤백 체인, 버전 이력 UI.
- `applyMode=RETROACTIVE` 소급 적용 플로우(사유서·영향 유저 사전 통보 포함).
- 승인 대기 SLA/알림(콘솔 설계서에 명시된 큐들처럼 초과 시 자동 조치) — 승인 큐는 현재 SLA 표(02 §0)에 없음.
