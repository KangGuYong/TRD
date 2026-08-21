# cluster_merge 수동 실행 (관리자 배치 트리거) 설계

## 배경

`cluster_merge`(임베딩 유사도 병합 후보 탐지)를 포함한 배치 잡 3종(`cluster_merge`/`verdict_runner`/`grade_recalc`)은 전부 `@Scheduled` 크론으로만 실행되고, 관리자가 필요할 때 즉시 돌릴 수 있는 API가 없다. 로컬 개발 중 병합 검수 큐(ADM-100)가 비어있는지 확인하려면 크론 표현식을 환경변수로 덮어쓰고 앱을 재부팅해야 했다 — 운영 환경에서도 마찬가지로 불편하다(예: 장애 복구 후 즉시 재계산이 필요한 상황).

이 문서는 세 배치 중 **`cluster_merge` 하나만**을 대상으로, 운영 환경에서도 상시 사용 가능한 정식 관리자 기능으로 수동 실행을 추가하는 설계다.

## 범위

**포함:**
- `cluster_merge` 수동 실행 API + 관리자 콘솔 화면

**제외 (이번 스코프 아님):**
- `verdict_runner` 수동 트리거 — R1 핵심 판정 엔진이라 수동 트리거 시 관리자가 판정 시점을 임의로 당길 수 있게 되는 리스크가 커서 별도 검토 필요.
- `grade_recalc` 수동 트리거 — "제보권 이월 없음" 정책상 주중 임의 실행이 실질적으로 추가 제보권을 주는 악용 경로가 될 수 있어 별도 검토 필요.
- 실행 이력 UI(과거 실행 결과 목록) — 감사 로그(ADM-700)에는 남지만, 배치 관리 화면 자체에는 이번엔 안 넣는다. 필요해지면 후속 작업으로.
- `abuse_scan`/`l4_quota` — 아직 배치 자체가 구현 안 됨(스코프 밖).

## 백엔드 설계

### 동시성 보호

기존 `ClusterMergeJob.run()`은 `@SchedulerLock(name="cluster_merge", lockAtMostFor="PT60M", lockAtLeastFor="PT5M")`로 크론 중복 실행만 막는다. 이 어노테이션은 락을 못 잡으면 메서드 본문을 조용히 건너뛰고 정상 리턴한다 — 관리자가 API로 호출했을 때 이 방식이면 "실행은 됐는데 결과가 전부 0"으로 보여 혼란을 준다.

**결정: `LockProvider`를 관리자 트리거 경로에서 명시적으로 사용한다.**
- 관리자 트리거 API는 `LockProvider.lock(new LockConfiguration("cluster_merge", ...))`로 직접 락 획득을 시도한다.
- 획득 실패(크론이 마침 돌고 있거나, 다른 관리자가 동시에 누름) → API는 즉시 `409 Conflict` + "이미 실행 중입니다" 메시지로 응답한다.
- 획득 성공 → 처리 후 락을 해제하고 결과를 반환한다.
- 크론 쪽 `@SchedulerLock`과 관리자 쪽 명시적 `LockProvider` 호출은 같은 락 이름(`cluster_merge`)을 공유하므로 상호 배타적이다.

### `ClusterMergeJob` 리팩터링

현재 `run()`의 루프 본문(후보 순회 + `processOne` 호출 + 카운트 집계)을 `runNow()`로 추출한다.

```java
public record ClusterMergeResult(int candidates, int autoMerged, int queued, int separated, int failed) {}

/** 크론 진입점과 관리자 수동 트리거 양쪽이 호출하는 공용 실행 로직. */
public ClusterMergeResult runNow() {
    List<UUID> candidateIds = trendItems.findByStateInAndMergeCheckedAtIsNull(
                    List.of(TrendState.DRAFT, TrendState.PENDING, TrendState.JUDGING, TrendState.RESOLVED))
            .stream().map(TrendItem::getId).toList();

    int autoMerged = 0, queued = 0, separated = 0, failed = 0;
    for (UUID candidateId : candidateIds) {
        try {
            ProcessOutcome outcome = processOne(candidateId);
            switch (outcome) {
                case AUTO_MERGED -> autoMerged++;
                case QUEUED -> queued++;
                case SEPARATED -> separated++;
            }
        } catch (Exception e) {
            log.error("cluster_merge 처리 실패 item={} : {}", candidateId, e.getMessage(), e);
            failed++;
        }
    }
    return new ClusterMergeResult(candidateIds.size(), autoMerged, queued, separated, failed);
}
```

- `processOne`은 지금처럼 `boolean` 대신 `ProcessOutcome`(`AUTO_MERGED`/`QUEUED`/`SEPARATED`) enum을 반환하도록 바꾼다 — 지금은 "큐적재"와 "별개확정"이 `other`로 뭉뚱그려져 있는데, 관리자 화면에 결과를 보여주려면 구분이 필요하다.
- 기존 `@Scheduled run()`은 `runNow()`를 호출하고 로그를 남기는 얇은 래퍼로 남는다. `@SchedulerLock`은 그대로 `run()`에 유지(크론 경로 보호).

### 신규 API

`backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/BatchJobController.java` (신설)

```
POST /admin/batch-jobs/cluster-merge/run
```

- 권한: `@PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")` — `MergeQueueController`의 merge/void와 동일 기준.
- 응답(성공, 200): `ClusterMergeResult` — `{candidates, autoMerged, queued, separated, failed}`
- 응답(락 획득 실패, 409): `{"message": "이미 실행 중입니다"}`
- 실행 성공 시 `AuditLogService.record(actor.id(), actor.role(), "CLUSTER_MERGE_MANUAL_TRIGGER", "BATCH_JOB", null, Map.of("candidates", ..., "autoMerged", ..., "queued", ..., "separated", ..., "failed", ...))` 호출 — ADM-700 감사 로그에 그대로 남는다.

### 안전장치

- **임베딩 서비스 장애**: `embeddingClient.embed()` 실패는 기존처럼 개별 `catch`로 `failed` 카운트에 집계되고 나머지 후보는 계속 처리된다(기존 `processOne` 예외 처리 패턴 재사용, 신규 로직 없음).
- **R1/R4 원칙 위배 없음**: 이 기능은 "언제 배치를 돌리느냐"만 사람이 정할 뿐, 병합 여부 판단 로직(0.85 자동병합 / 0.75~0.85 큐적재 / 그 미만 별개확정)은 그대로 배치 공식이 결정한다. 그레이존 항목은 여전히 `merge_queue`에 적재만 되고 최종 병합/분리/VOID 결정은 사람(ADM-100)만 내린다.

## 프론트엔드 설계

### 신규 화면: `admin/src/screens/BatchJobsScreen.tsx`

기존 `SeedScreen.tsx`(ADM-500)와 같은 톤·구조로 작성한다.

- `Layout.tsx`/`App.tsx`에 새 `ScreenId`("BATCH-JOBS" 등) 등록, 사이드바 메뉴에 "배치 관리" 추가.
- `admin/src/state/role.tsx`에 `CAN.batchTrigger = (role) => role === 'OPERATOR' || role === 'ADMIN'` 추가.
- 카드 하나(cluster_merge 전용, 다른 배치가 추가되면 카드를 늘리는 구조로 확장 가능하게 작성):
  - 잡 이름 + 한 줄 설명("완전일치로 안 걸러진 항목의 임베딩 유사도를 계산해 자동병합/큐적재/별개확정")
  - "지금 실행" 버튼 — `CAN.batchTrigger`가 false면 비활성화 + "OPERATOR 이상만 실행할 수 있습니다" 안내(`SeedScreen`의 `canRegister` 패턴 재사용)
  - 클릭 → 확인 모달("지금 cluster_merge를 실행하시겠습니까?") → 확인 시 버튼이 로딩 상태로 바뀌고 API 응답까지 동기 대기
  - 성공 시 카드 아래 결과 요약 표시: `후보 N건 · 자동병합 N · 큐적재 N · 별개확정 N · 실패 N`(실패 > 0이면 강조 색상)
  - 409(이미 실행 중) 시 토스트로 "이미 실행 중입니다" 안내
  - 결과는 세션 로컬 상태로만 유지 — 페이지 새로고침하면 사라짐(히스토리는 이번 스코프 아님)
- 데모 모드(`USE_FIXTURES`)에서는 `SeedScreen`처럼 가짜 결과를 토스트로 보여주고 실제 API 호출 없음.

### API 클라이언트

`admin/src/api/hooks.ts`에 `triggerClusterMerge(): Promise<ClusterMergeResult>` 추가, `admin/src/api/types.ts`에 `ClusterMergeResult` 타입 추가.

## 테스트 계획

- 백엔드: `ClusterMergeJob.runNow()` 단위 테스트 — 자동병합/큐적재/별개확정/실패 카운트가 정확히 집계되는지. `BatchJobController` 락 획득 실패 시 409를 반환하는지(두 번째 호출이 첫 번째가 끝나기 전에 들어오는 상황을 재현).
- 프론트: `tsc --noEmit` 클린.
- E2E: 이번에 DB에 직접 넣어둔 `무라벨 탄산수 챌린지` 시드 데이터를 지우고, "지금 실행" 버튼으로 실제 병합 큐가 채워지는지 확인.

## 열려 있는 후속 과제 (이번 스코프 아님)

- `verdict_runner`/`grade_recalc` 수동 트리거 여부 — 판정 시점·제보권 이월 정책과 얽혀 있어 별도 브레인스토밍 필요.
- 배치 관리 화면에 실행 이력(과거 N회) 표시 — 감사 로그 API(`GET /admin/audit-log`)가 현재 `details` 필드를 응답에 안 내려주고 있어서, 필요해지면 그것부터 확장해야 함.
