# ADM-600 파라미터 스튜디오 — 드래프트·시뮬레이션·승인요청

## 배경

`ParamStudioScreen.tsx`는 fixture(`fxParams`/`fxSimulation`)만으로 렌더링되는 목업이고, 대응하는 백엔드 API가 전혀 없다. 반면 판정·점수 엔진(`ParameterSet`, `VerdictEngine`, `ScoreEngine`, `TrustIndex`, `ActiveScore`)은 이미 전부 파라미터를 주입받는 순수 함수로 설계돼 있고, DB 스키마(V6)도 `parameter_drafts`(드래프트→시뮬결과→승인id→예약적용)와 `approval_requests`(2인 승인, 요청자≠승인자 DB CHECK)를 이미 갖추고 있다. `ParameterSetProvider.current()`는 `ParameterSet.defaults()`만 반환하는 스텁으로, "TODO: parameter_drafts(APPLIED) 최신본 조회" 주석이 이미 박혀 있다.

CLAUDE.md의 열린 결정사항 O1(submitterTarget·판정 임계값)·O4(확신도 간격)·O5(반감기)·O7(임베딩 임계값)은 전부 "Phase 0 백테스트로 보정 필수"인데, "시뮬레이션 없이 승인 요청 불가"가 ADM-600의 필수 게이트라 이 화면이 없으면 그 어떤 값도 정식으로 확정할 수 없다.

## 목표

1. 드래프트(`parameter_drafts`)를 생성·수정할 수 있게 한다.
2. 과거 180일 판정을 새 파라미터로 재평가해 변동 건수를 보여준다(시뮬레이션).
3. 시뮬레이션을 거쳐야만 2인 승인 요청(`approval_requests`)을 생성할 수 있게 강제한다.

## 범위

- 편집 가능 파라미터: `submitterTarget`(목표 제보자 수), `hitThreshold`(판정 임계값) 2개만. `ParameterSet`의 나머지 14개 필드(band, 확산배수, 선점가중, 반감기, TI α/β)는 `defaults()` 고정값 유지.
- 시뮬레이션 범위: 판정(HIT/MISS/reach) 변동만. 등급(TI/AS/강등·승급) 계산은 제외.
- 승인·적용 워크플로: 드래프트 생성 → 시뮬레이션 → 2인 승인 **요청 생성**까지. 실제 승인 클릭(`approver_1`/`approver_2` 채우기), `APPROVED`→`APPLIED` 전이, `ParameterSetProvider`가 `APPLIED` 값을 읽어 운영에 반영하는 배선은 범위 밖.
- 신규 DB 마이그레이션 불필요 — `parameter_drafts`/`approval_requests`는 V6에 이미 존재하고 append-only 트리거(V7) 대상이 아니라 UPDATE 가능.

## 아키텍처

### 드래프트 라이프사이클

```
DRAFT ──값 수정 + "적용"──▶ DRAFT (sim_result 리셋)
DRAFT ──시뮬레이션 실행──▶ DRAFT (sim_result 채움)
DRAFT ──승인 요청 (sim_result 필수)──▶ REVIEW (approval_requests PENDING 행 생성)
```

활성 드래프트(`DRAFT`/`REVIEW` 상태)는 항상 1개만 존재한다. 없으면 `defaults()` 기준으로 새로 만들고, 있으면 계속 그 드래프트를 수정한다. `REVIEW` 상태에서는 수정을 막는다(승인 취소 기능은 범위 밖).

### 시뮬레이션 데이터 소스

`submissions` 테이블을 다시 조회하지 않는다. `Verdict.evidence_json`에 판정 당시 `distinctSubmitters`/`distinctPlatforms`가 이미 동결돼 있으므로(`VerdictRunner.buildEvidence`), 최근 180일 내 판정(VOID 제외, 최신본만)의 evidence를 파싱해 `SubmissionSignal`을 복원하고, 드래프트 파라미터로 `VerdictEngine.evaluate()`를 다시 호출해 저장된 실제 결과와 비교한다. 완전히 읽기 전용이며 운영이 쓰는 것과 동일한 순수 엔진을 재사용한다(ADM-100의 `MergeComputation`과 같은 패턴 — 03/04 문서의 "엔진은 파라미터를 주입받아 운영/시뮬레이션이 같은 코드를 공유" 원칙).

## 백엔드 설계

### 신규 엔티티/리포지토리 (`persistence` 모듈)

```java
@Entity @Table(name = "parameter_drafts")
public class ParameterDraft {
    @Id @GeneratedValue UUID id;
    UUID authorId;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM) ParamStatus status;
    @JdbcTypeCode(SqlTypes.JSON) String payload;     // {"submitterTarget":20,"hitThreshold":0.2}
    @JdbcTypeCode(SqlTypes.JSON) String simResult;   // nullable
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM) ApplyMode applyMode; // 기본 SCHEDULED
    UUID approvalId;                                  // nullable
    Instant createdAt;
    @Version Long version;                            // 낙관적 락 — 동시 "적용" 클릭 방어

    // getStatus/getPayload/getSimResult/getApprovalId + setStatus/setPayload/setSimResult/setApprovalId
    // (append-only 아님 — 일반 setter 노출)
}
```

```java
@Entity @Table(name = "approval_requests")
public class ApprovalRequest {
    @Id @GeneratedValue UUID id;
    String actionType;        // "PARAM_APPLY"
    UUID targetRef;            // parameter_drafts.id
    UUID requestedBy;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM) ApprovalStatus status; // PENDING
    Instant createdAt;
}
```

```java
public interface ParameterDraftRepository extends JpaRepository<ParameterDraft, UUID> {
    Optional<ParameterDraft> findFirstByStatusInOrderByCreatedAtDesc(List<ParamStatus> statuses);
}
public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, UUID> {}
```

`VerdictRepository`에 추가:

```java
@Query("SELECT v FROM Verdict v WHERE v.judgedAt >= :since AND v.result <> 'VOID' " +
       "AND NOT EXISTS (SELECT 1 FROM Verdict v2 WHERE v2.supersedes = v.id)")
List<Verdict> findCurrentNonVoidSince(@Param("since") Instant since);
```

### `ParamStudioService` (`api-admin` 모듈)

- `getActiveDraft()` — 활성 드래프트 조회, 없으면 `defaults()` 기준 새 DRAFT 생성 후 반환
- `updateDraftValues(int submitterTarget, double hitThreshold)` — REVIEW면 `DraftLockedException`(409). 아니면 payload 갱신 + `simResult=null`
- `simulate()` — 아래 로직 실행 후 `simResult` 저장, 반환
- `requestApproval(String reason)` — `simResult == null`이면 `SimulationRequiredException`(400). `ApprovalRequest(PENDING)` 저장 + 드래프트 `status=REVIEW`, `approvalId` 연결

### 시뮬레이션 로직 (순수 함수로 추출: `ParamSimulation.run(List<VerdictSnapshot>, ParameterSet)`)

```java
public record VerdictSnapshot(VerdictResult result, ReachLevel reach, int distinctSubmitters, int distinctPlatforms) {}
public record SimulationSummary(int changed, int total, int missToHit, int hitToMiss, int reachChanged) {}

public final class ParamSimulation {
    private ParamSimulation() {}
    public static SimulationSummary run(List<VerdictSnapshot> snapshots, ParameterSet p) {
        int missToHit = 0, hitToMiss = 0, reachChanged = 0;
        for (VerdictSnapshot v : snapshots) {
            SubmissionSignal sig = new SubmissionSignal(v.distinctSubmitters(), v.distinctPlatforms());
            VerdictOutcome after = VerdictEngine.evaluate(sig, p);
            if (v.result() == VerdictResult.MISS && after.result() == VerdictResult.HIT) missToHit++;
            else if (v.result() == VerdictResult.HIT && after.result() == VerdictResult.MISS) hitToMiss++;
            else if (v.result() == VerdictResult.HIT && after.result() == VerdictResult.HIT
                     && v.reach() != after.reach()) reachChanged++;
        }
        int changed = missToHit + hitToMiss + reachChanged;
        return new SimulationSummary(changed, snapshots.size(), missToHit, hitToMiss, reachChanged);
    }
}
```

`ParamStudioService.simulate()`는 `VerdictRepository.findCurrentNonVoidSince(now - 180일)`로 `Verdict` 목록을 읽고, `evidence_json`을 파싱해 `VerdictSnapshot`으로 변환한 뒤 이 순수 함수를 호출한다. `evidence_json` 파싱은 Jackson `ObjectMapper`로 `distinctSubmitters`/`distinctPlatforms`/`reach`/`result` 필드를 읽는다(이미 `VerdictRunner.buildEvidence`가 만드는 형식과 동일).

### 엔드포인트 (`ParamStudioController`, `@PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")`)

| Method | Path | 동작 |
|---|---|---|
| GET | `/admin/params/draft` | 활성 드래프트 + 현재 운영값(`ParameterSetProvider.current()`) 비교 반환 |
| PUT | `/admin/params/draft` | `{submitterTarget, hitThreshold}` → 드래프트 값 갱신 |
| POST | `/admin/params/draft/simulate` | 시뮬레이션 실행, 결과 반환 |
| POST | `/admin/params/draft/request-approval` | `{reason}` → 승인 요청 생성 |

응답은 CLAUDE.md 관례("산정 근거 함께 반환")에 따라 필드별 diff를 포함한다:

```json
{
  "draftId": "...", "status": "DRAFT",
  "submitterTarget": 24, "currentSubmitterTarget": 20,
  "hitThreshold": 0.20, "currentHitThreshold": 0.20,
  "simResult": { "changed": 28, "total": 342, "missToHit": 11, "hitToMiss": 17, "reachChanged": 4 }
}
```

### 감사 로그

기존 `AuditLogService.record()`를 그대로 재사용해 3개 액션 기록: `PARAM_DRAFT_UPDATE`, `PARAM_SIMULATE`, `PARAM_APPROVAL_REQUEST`. 신규 인프라 불필요.

## 프론트엔드 설계

### 타입 확장 (`admin/src/api/types.ts`)

```ts
export interface SimulationSummary {
  changed: number; total: number;
  missToHit: number; hitToMiss: number; reachChanged: number;
}
export interface ParameterDraftView {
  draftId: string;
  status: "DRAFT" | "REVIEW";
  submitterTarget: number; currentSubmitterTarget: number;
  hitThreshold: number; currentHitThreshold: number;
  simResult: SimulationSummary | null;
}
```

기존 `ParameterPayload`/`SimulationResult`(fixtures.ts에서 쓰던 `affectedUsers`/`avgDelta`/`demotions`/`promotions` 포함 타입)는 제거하고 위 타입으로 교체한다.

### 훅 추가 (`admin/src/api/hooks.ts`)

```ts
export const useParamDraft = () =>
  useData<ParameterDraftView>(["admin", "param-draft"], "/admin/params/draft", fx.fxParamDraft);

export const updateParamDraft = (body: { submitterTarget: number; hitThreshold: number }) =>
  api.put<ParameterDraftView>("/admin/params/draft", body);

export const simulateParamDraft = () =>
  api.post<ParameterDraftView>("/admin/params/draft/simulate");

export const requestParamApproval = (reason: string) =>
  api.post<void>("/admin/params/draft/request-approval", { reason });
```

### `ParamStudioScreen.tsx` 변경

- `fxParams`/`fxSimulation` 직접 import 제거, `useParamDraft()` 훅으로 교체(다른 화면들과 동일 fixture/real-API 전환 패턴).
- `+`/`−` 버튼은 로컬 state만 변경한다. 별도 **"적용"** 버튼을 눌러야 `updateParamDraft()`가 호출되고, 서버는 `simResult`를 리셋한다(값이 바뀌었는데 이전 시뮬 결과가 남아있으면 오해 소지가 있으므로) — 화면은 로컬 state와 서버 `simResult` 유무를 함께 보고 "시뮬레이션 필요" 여부를 판단한다.
- "시뮬레이션 실행" → `simulateParamDraft()` 호출, 응답의 `simResult`로 화면 갱신.
- 결과 3번째 행 문구를 "등급 강등/승급"에서 "HIT 확산 레벨 변경"(`reachChanged`)으로 교체 — 등급 계산이 스코프 밖이므로.
- "승인 요청" 버튼 옆에 사유(reason) 인라인 입력칸 추가(현재 화면에는 없음) → `requestParamApproval(reason)` 호출.

### fixture 확장 (`admin/src/fixtures.ts`)

```ts
export const fxParamDraft: ParameterDraftView = {
  draftId: "pd-17", status: "DRAFT",
  submitterTarget: 20, currentSubmitterTarget: 20,
  hitThreshold: 0.2, currentHitThreshold: 0.2,
  simResult: null,
};
```

## 예외 처리 · 동시성

- 시뮬레이션 없이 승인 요청 → 400 `SimulationRequiredException`.
- `submitterTarget < 1` 등 잘못된 입력 → `ParameterSet` 생성자의 기존 `IllegalArgumentException` → 400.
- `REVIEW` 상태에서 값 수정 시도 → 409 `DraftLockedException`.
- 두 운영자가 동시에 "적용" 클릭 → `ParameterDraft.version`(`@Version`) 낙관적 락으로 방어, 충돌 시 409.

## 테스트 계획

- **백엔드 단위**: `ParamSimulation.run()`을 `ParamSimulationTest`로 검증 — 임계값 낮춤(MISS→HIT), target 낮춤(HIT reach 상승), target 높임(HIT→MISS), 변동 없음 케이스.
- **컨트롤러**: RBAC(OPERATOR/ADMIN만 접근 가능, REVIEWER/AUDITOR는 403), 시뮬 없이 승인 요청 시 400, REVIEW 상태에서 수정 시 409.
- **프론트/브라우저**: 값 변경 → 적용 → 시뮬레이션 실행 → 결과 표시 → 승인 요청까지 end-to-end.

## 비범위 (Out of scope)

- 실제 2인 승인 클릭 플로우(`approver_1`/`approver_2` 채우기) 및 `APPROVED`→`APPLIED` 전이
- `ParameterSetProvider`가 `APPLIED` 드래프트를 실제로 읽어 운영에 반영하는 배선
- 등급(TI/AS) 영향 시뮬레이션, 강등/승급 카운트
- `ParameterSet`의 나머지 14개 필드 편집(band, 확산배수, 선점가중, 반감기, TI α/β)
- 소급 적용(`RETROACTIVE` 모드), 드래프트 롤백
