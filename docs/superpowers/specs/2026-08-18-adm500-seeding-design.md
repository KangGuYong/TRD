# ADM-500 시딩 관리 — 설계

## 배경

클로즈드 베타(Phase 1) 진입 전, 운영진이 주 20건씩 트렌드를 직접 등록("시딩")해 초기 데이터를 채운다. 시딩 제보는 유저 제보와 똑같이 `VerdictRunner`에 의해 정상적으로 HIT/MISS 판정되지만, `score_ledger`에는 기록되지 않는다(`is_seed=true`는 이미 `VerdictRunner.judgeOne()`에 반영된 기존 규칙: `if (!sub.isSeed() && line.kind() != VerdictResult.VOID) { ledger.save(...) }`).

지금은 시딩 제보를 등록할 화면도, 담당자별 적중률을 볼 화면도 없다 — 필요 시 DB에 직접 INSERT하는 임시 방편만 가능한 상태다. `02-admin-console.md`의 ADM-500 요구사항("운영진 직접 등록분을 유저 제보와 명확히 분리", "시딩 담당자별 적중률을 별도 집계 → 판정 임계값 보정에 활용")을 실제 화면으로 구현한다.

## 목표

1. 운영자가 관리자 콘솔에서 직접 트렌드를 시딩 제보로 등록할 수 있게 한다.
2. 시딩 담당자(운영자)별 적중률을 실시간으로 보여준다.
3. 시딩 제보와 유저 제보를 데이터 레벨에서 명확히 분리한다.

## 아키텍처 개요

### 제보자 추적 방식

시딩 제보도 `submissions.user_id` 컬럼을 그대로 써야 한다(스키마 변경 없이 기존 판정 파이프라인을 그대로 태우기 위해). 따라서 "운영자가 시딩했다"는 사실을 추적하려면 운영자마다 전용 합성 `UserAccount`를 자동 생성해 매핑한다.

- `admin_accounts`에 `seed_user_id UUID REFERENCES users(id)` (nullable) 컬럼 추가.
- 운영자의 최초 시딩 등록 시점에 `UserAccount(handle = "seed_" + loginId)`를 자동 생성하고 `admin_accounts.seed_user_id`에 저장. 이후 등록부터는 기존 값을 재사용.
- 이 방식을 선택한 이유: 기존 TI/등급 계산 파이프라인(`TrustIndex.compute()`, `submissions.result` 집계)을 신규 코드 없이 그대로 재사용할 수 있다. 별도의 "시딩 담당자" 개념을 추적하는 새 테이블을 만드는 대안도 검토했으나, 판정 결과 집계 로직을 통째로 다시 구현해야 해 불필요한 중복이었다.

### 적중률 집계 방식

시딩 제보는 정상적으로 판정되므로 `score_ledger`가 아니라 `submissions.result` 컬럼에서 직접 hit/miss를 센다. 주간 스냅샷인 `UserGrade`는 갱신 주기가 느려 운영 화면에는 부적합하므로 쓰지 않는다 — 매 요청마다 `SubmissionRepository`로 즉석 카운트한 뒤 기존 `TrustIndex.compute(hit, miss, ParameterSet)`(`GradeRecalcJob`이 이미 쓰는 함수)로 계산한다.

### 모듈 배치

시딩 제보 생성 로직(정규화 → 트렌드 항목 완전일치 매칭/생성 → 동일 유저 중복 체크)은 `api-public`의 `SubmissionService.create()`와 사실상 동일하다. 하지만 `api-admin`에 새 의존성(`api-admin → api-public`)을 추가하는 대신, `api-admin` 내부에 작게 중복 구현한다 — `VerdictAdminService`가 이미 `VerdictRunner`의 판정 로직을 같은 이유로 부분 중복하고 있는 것과 동일한 전례를 따른다(모듈 간 결합보다 국소적 중복이 더 안전하다는 이 코드베이스의 기존 판단).

## 백엔드 설계

### 마이그레이션

```sql
-- V18__admin_seed_account.sql
ALTER TABLE admin_accounts ADD COLUMN seed_user_id UUID REFERENCES users(id);
```

### `AdminAccount` 엔티티 수정

`seedUserId` 필드(nullable) + getter + `linkSeedUser(UUID)` 세터 추가. 기존 `recordLogin`/`disable` 패턴과 동일하게 좁은 목적의 세터 하나만 노출한다.

### 신규 서비스 `AdminSeedService` (`api-admin` 모듈, 신규 패키지 `kr.trendstage.apiadmin.seed`)

```java
@Service
public class AdminSeedService {
    // AdminAccountRepository, UserAccountRepository, TrendItemRepository,
    // SubmissionRepository, AuditLogService, Clock 주입

    @Transactional
    public SeedSubmissionResult registerSeed(UUID actorId, AdminRole actorRole, SeedSubmissionRequest req) {
        AdminAccount actor = adminAccounts.findById(actorId).orElseThrow();
        UUID seedUserId = actor.getSeedUserId();
        if (seedUserId == null) {
            UserAccount seedUser = users.save(new UserAccount("seed_" + actor.getLoginId()));
            actor.linkSeedUser(seedUser.getId());
            seedUserId = seedUser.getId();
        }

        String normalized = NameNormalizer.normalize(req.name());
        TrendItem item = trendItems.findByNormalizedKey(normalized).orElse(null);
        if (item != null) {
            boolean dup = submissions.existsByTrendItemIdAndUserIdAndResultNot(
                    item.getId(), seedUserId, SubmissionResult.VOID);
            if (dup) throw new AdminValidationException("이미 이 계정으로 시딩한 항목입니다");
        } else {
            item = trendItems.save(new TrendItem(req.name(), normalized, req.category(), clock.instant()));
            item.transitionTo(TrendState.PENDING);
        }

        Submission sub = submissions.save(new Submission(
                seedUserId, item.getId(), req.name(), normalized,
                req.confidence().shortValue(), req.platform(), req.evidenceUrl(), req.oneLine(),
                false, true)); // disclosure=false, seed=true

        auditLogService.record(actorId, actorRole, "SEED_SUBMISSION_CREATE", "TREND_ITEM", item.getId(), Map.of(
                "name", req.name(), "confidence", req.confidence()));

        return new SeedSubmissionResult(sub.getId().toString(), item.getCanonicalName(), item.getId().toString());
    }

    public List<SeedAccuracyRow> listAccuracy() {
        return adminAccounts.findAll().stream()
                .filter(a -> a.getSeedUserId() != null)
                .map(a -> {
                    long hit = submissions.countByUserIdAndResult(a.getSeedUserId(), SubmissionResult.HIT);
                    long miss = submissions.countByUserIdAndResult(a.getSeedUserId(), SubmissionResult.MISS);
                    double ti = TrustIndex.compute((int) hit, (int) miss, params.current());
                    return new SeedAccuracyRow(a.getDisplayName(), (int) hit, (int) miss, (int) (hit + miss), ti);
                })
                .toList();
    }
}
```

### 신규 리포지토리 메서드

```java
// SubmissionRepository
long countByUserIdAndResult(UUID userId, SubmissionResult result);
```

### DTO (`AdminSeedController` 내부 record)

```java
public record SeedSubmissionRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull TrendCategory category,
        @NotBlank @Size(max = 60) String platform,
        @NotBlank String evidenceUrl,
        @NotNull @Min(10) @Max(50) Integer confidence,
        @NotBlank @Size(max = 200) String oneLine
) {}
public record SeedSubmissionResult(String submissionId, String canonicalName, String trendItemId) {}
public record SeedAccuracyRow(String operatorName, int hit, int miss, int judged, double trustIndex) {}
```

`confidence`는 10/30/50 중 하나만 허용해야 하지만 Bean Validation 표준 애노테이션으로는 이산값 제약이 어려우므로, `@Min(10) @Max(50)`으로 1차 필터링 후 서비스 진입부에서 `if (confidence != 10 && confidence != 30 && confidence != 50) throw new AdminValidationException(...)`로 정확히 검증한다(`SubmissionService.create()`가 이미 쓰는 검증 방식과 동일).

### 엔드포인트 (`AdminSeedController`, `/admin/seed`)

| Method | Path | RBAC | 동작 |
|---|---|---|---|
| POST | `/admin/seed/submissions` | OPERATOR, ADMIN | 시딩 제보 등록 |
| GET | `/admin/seed/accuracy` | REVIEWER, OPERATOR, ADMIN, AUDITOR | 담당자별 적중률 조회 |

## 프론트엔드 설계

기존 화면 스캐폴딩이 전혀 없으므로 처음부터 만든다. ADM-600/ADM-010의 fixture/hook/screen 3종 패턴을 그대로 따른다.

### `admin/src/api/types.ts` 추가

```ts
export interface SeedSubmissionRequest {
  name: string;
  category: "MEME" | "PRODUCT" | "PERSON_CHANNEL" | "CHALLENGE" | "SLANG" | "ETC";
  platform: string;
  evidenceUrl: string;
  confidence: 10 | 30 | 50;
  oneLine: string;
}
export interface SeedSubmissionResult {
  submissionId: string;
  canonicalName: string;
  trendItemId: string;
}
export interface SeedAccuracyRow {
  operatorName: string;
  hit: number;
  miss: number;
  judged: number;
  trustIndex: number;
}
```

### `admin/src/api/fixtures.ts` 추가

`fxSeedAccuracy: SeedAccuracyRow[]` — 담당자 2~3명, hit/miss를 다르게 섞어 TI 차이가 시각적으로 드러나는 샘플.

### `admin/src/api/hooks.ts` 추가

```ts
export function useSeedAccuracy() {
  return useQuery({
    queryKey: ["seedAccuracy"],
    queryFn: () => USE_FIXTURES ? Promise.resolve(fx.fxSeedAccuracy) : api.get<SeedAccuracyRow[]>("/admin/seed/accuracy"),
  });
}

export function useRegisterSeed() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (req: SeedSubmissionRequest) =>
      USE_FIXTURES ? Promise.resolve({ submissionId: "fx", canonicalName: req.name, trendItemId: "fx" })
                    : api.post<SeedSubmissionResult>("/admin/seed/submissions", req),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["seedAccuracy"] }),
  });
}
```

### `admin/src/screens/SeedScreen.tsx` (신규)

`ParamStudioScreen.tsx`의 폼+테이블 레이아웃 패턴을 따른다.

- **상단**: 등록 폼 — name / category select / platform / evidenceUrl / confidence 라디오(10·30·50) / oneLine / 제출 버튼. 성공 시 폼 리셋 + 토스트("시딩 등록 완료: {canonicalName}"). 실패 시 `catch (e) { flash(e instanceof ApiError ? e.message : "...") }` (ADM-600에서 적용한 에러 토스트 패턴 재사용).
- **하단**: `useSeedAccuracy()` 테이블 — 담당자명 / HIT / MISS / 판정건수 / TI(%) 컬럼. TI ≥ 0.5는 강조색, < 0.3은 경고색으로 가볍게 구분.

### 라우팅

`Layout.tsx`/`App.tsx`의 `ScreenId` 유니온에 `"ADM-500"` 추가, 사이드바 메뉴에 새 항목 추가(ADM-100과 ADM-600 사이).

**이 화면은 완전히 독립된 화면이다** — 유저가 쓰는 공개 제보 폼(`api-public`)이나 다른 관리자 화면(ADM-100/600)과 UI·코드를 공유하지 않는다. 입력 필드 모양만 유저 제보 폼과 맞췄을 뿐 컨트롤러·훅·컴포넌트는 전부 별도이며, 접근 권한도 RBAC으로 분리되어 있다(등록은 OPERATOR/ADMIN만, 조회는 4개 역할 전부).

## 에러 처리

| 상황 | 처리 |
|---|---|
| 동일 계정으로 같은 항목 중복 시딩 | `AdminValidationException` → 422, "이미 이 계정으로 시딩한 항목입니다" |
| `confidence`가 10/30/50 외 값 | 서비스 진입부 명시적 검증 → `AdminValidationException` → 422 |
| `name`/`platform`/`evidenceUrl`/`oneLine` 공백 | `@NotBlank` → 400 |
| 로그인 계정에 `seedUserId`가 없는 최초 등록 | 예외 아님 — `registerSeed()` 내부에서 자동 프로비저닝 후 정상 진행 |
| `UserAccount` handle 유니크 충돌 (`seed_{loginId}` 중복) | 발생 불가 — `loginId` 자체가 `admin_accounts`에서 유니크이므로 `seed_` 접두사를 붙여도 유니크 보장됨 |
| TrendItem 정규화 매칭·생성 실패 | `SubmissionService.create()`와 동일 — DB 장애 외에는 실패 경로 없음, 기본 500으로 충분 |

`AdminApiExceptionHandler`에 `AdminValidationException` → 422 매핑 1건 추가(기존 `DraftLockedException` 등과 동일한 패턴, 이미 존재하면 재사용).

## 테스트 계획

- **백엔드 단위**: 신규 `domain-core` 순수 함수 없음(정규화·매칭 로직은 `api-public`에 이미 테스트된 알고리즘의 국소 중복). `listAccuracy()`의 TI 계산은 기존 `TrustIndex`가 이미 커버.
- **서비스 통합 테스트** (`AdminSeedServiceTest`):
  - 최초 시딩 등록 시 `seedUserId`가 자동 생성되고 `admin_accounts.seed_user_id`에 저장되는지
  - 두 번째 시딩부터는 기존 `seedUserId` 재사용(신규 `UserAccount` 미생성) 확인
  - 동일 계정·동일 정규화명 중복 등록 시 422 확인
  - `listAccuracy()`가 hit/miss 집계와 `TrustIndex.compute()` 결과를 정확히 반환하는지(예: HIT 2건 MISS 1건 → TI = 4/8 = 0.5 직접 검증)
- **브라우저 E2E**: 폼 제출 → 등록 성공 토스트·폼 리셋 확인(적중률은 판정 후에나 바뀌므로 등록 직후 테이블 값이 바뀌는지는 검증 대상 아님). 실 DB에서 시딩 제보 하나를 수동으로 HIT 판정 처리한 뒤 적중률 테이블 갱신 확인.

## 비범위 (Out of scope)

- ADM-311(담당자별 상세 드릴다운) — 이번 화면은 요약 테이블만 제공
- 시딩 계정 비활성화/삭제 UI — Phase 1 스코프 밖
- 시딩 목표 건수(주 20건) 진행률 표시 — 별도 지표, 이번 설계 밖
