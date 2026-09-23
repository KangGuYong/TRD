# SP2 병합 무결성 — 설계

- 작성일: 2026-09-21
- 상위 문서: `docs/superpowers/specs/2026-09-17-design-review-design.md` §2.3 "병합 (SP2)", §3 SP2 행
- 선행: SP1(`sp1-verdict-pipeline`, PR #6). 이 브랜치(`sp2-merge-integrity`)는 SP1 위에 쌓는다. SP1이 먼저 병합되면 base를 `main`으로 바꾼다.
- 작업 순서: API(백엔드) 먼저, 그다음 콘솔 로직.

---

## 0. 범위

상위 문서가 정의한 SP2 중 **이중 점수와 데이터 불일치를 막는 무결성 묶음**만 한다. 큐 워크플로·정규화는 다음 묶음으로 넘긴다(§11).

| # | 항목 | 지금 무엇이 틀렸나 |
|---|---|---|
| 1 | 병합 가드 | 가드는 `state == MERGED` 하나. 판정된 항목도 병합되어, 흡수된 제보가 다음 판정에서 원장에 다시 실린다(이중 점수). `cluster_merge` 후보 스캔이 `JUDGING`·`RESOLVED`를 포함해 자동 병합 경로에서 지금도 발생 가능 |
| 2 | 행 잠금 | `MergeService.merge()`는 `findById`(잠금 없음). `JudgeService`는 `findByIdForUpdate`를 잡는데 병합은 안 잡아 병합과 판정이 나란히 돈다. 큐 행도 잠금 없는 조회라 검수자 둘이 동시에 통과 |
| 3 | 멱등키 | 서버가 `Idempotency-Key`를 읽지 않고 콘솔도 보내지 않는다. 재요청이 그대로 재실행된다 |
| 4 | 큐 UNIQUE | `merge_queue_one_open_per_new UNIQUE (new_trend_item_id)`가 전역이라 한 번 처리된 항목은 다시 큐에 못 들어가고, 배치의 두 번째 삽입이 조용히 실패한다 |
| 5 | 죽은 항목으로의 제보 | 패자(MERGED)가 `normalized_key`를 계속 쥐고 있어(V24 전역 UNIQUE + watches FK) 패자 이름으로 제보하면 `ItemClosedException`으로 영구 차단. 워치·시딩도 같은 조회 |
| 6 | 동시 첫 제보 | "조회 → 없으면 삽입"이라 같은 새 이름이 동시에 오면 하나가 UNIQUE 위반 500 |
| 7 | 관측 마감 앞당김 | 병합으로 `first_seen_at`이 당겨지면 마감이 이미 지난 시점이 될 수 있다. 흡수된 쪽 제보자는 관측 기간을 잃는다 |
| 8 | 미리보기 순위 | `MergeComputation.computeCombinedOrder`가 시딩을 빼지 않아, 시딩을 빼는 `submission_order_rank` 뷰(V28)와 어긋난다 |
| 9 | 배치 트랜잭션 | `ClusterMergeCandidateService.processOne`이 트랜잭션 없이 병합·큐 적재·확인 표시를 따로 커밋한다 |
| 10 | 큐 VOID | 새 항목이 그사이 병합돼 MERGED면 `JudgeService.voidItem`의 409가 원인 없는 토스트로 뜨고 큐 행은 남는다 |
| 11 | 병합의 제보권 부수효과 | 중복 제보 VOID가 제보권을 돌려주는데 어디에도 드러나지 않고 테스트도 없다 |

**비범위**: 큐 상태 모델(클레임·보류·에스컬레이션)과 ADM-100 해당 UI, 정규화 규칙·기존 키 백필, `aliases` 조회 활용, 분리(split) 실구현, 판정 후 병합(ADJ 경로, Phase 2 — O9). §11 참조.

---

## 1. 결정

| # | 결정 | 이유 |
|---|---|---|
| K1 | 병합 가능 = 두 항목 모두 판정 전 상태(`PENDING`, 또는 저장 직전의 `DRAFT`) **이고** 현행 판정 행 없음(`verdicts.existsByTrendItemIdAndSupersedesIsNull` 거짓) | 상태와 판정 행 중 어느 한쪽이 어긋나도 막히게 두 겹으로 건다 |
| K2 | 거부는 409. 본문 `type`으로 `merge-judging`(일시, 재시도 의미 있음)과 `merge-resolved`(Phase 2 전까지 영구)를 구분 | 지금의 422는 "요청이 틀렸다"는 뜻이라 재시도 판단을 못 한다 |
| K3 | 두 항목을 id 오름차순으로 `SELECT … FOR UPDATE`. 큐 행도 `FOR UPDATE` | `JudgeService`와 같은 잠금을 잡아 병합과 판정을 직렬화. id 순서로 교착을 피한다 |
| K4 | 자동 병합 경로에서 상대가 판정 전 상태가 아니면 병합도 큐 적재도 하지 않고 감사 로그 `MERGE_SKIPPED_JUDGED`만 남긴다(사용자 결정 2026-09-21) | Phase 2 판정 후 병합 설계 때 빈도 근거 |
| K5 | 결정 요청(병합·분리·VOID)은 `Idempotency-Key` 필수. `merge_queue.decision_key UNIQUE`로 DB가 보장. 같은 키 재요청은 이전 결과 그대로 | 코드 가드가 아니라 DB 제약으로 멱등(프로젝트 규약) |
| K6 | 완전일치 조회가 MERGED를 만나면 `merged_into`를 따라 승자로 합류. 제보·워치·시딩이 같은 헬퍼 하나를 쓴다 | 패자 키는 FK 때문에 비울 수 없다(상위 문서 A3 정정) |
| K7 | 병합 후 관측 마감이 `병합 시점 + 3일`보다 이르면 `judgment_deadline_override`를 그 시점으로 잡되 `새 first_seen_at + 21일`을 넘기지 않는다. 병합은 기존 마감을 **당기지 않는다**(사용자 결정 2026-09-21) | 흡수된 쪽 제보자의 관측 기간 보호. 3일은 운영 안전장치라 상수(파라미터 스튜디오 대상 아님) |
| K8 | 병합 중복 VOID의 제보권 반환은 유지하고, 미리보기에 "제보권 반환 대상"으로 드러낸다 | 무효 처리된 제보의 제보권을 돌려받는 것이 공정. 숨은 부수효과만 없앤다 |
| K9 | 구현된 경로(`POST /admin/merge-queue/{id}/merge|separate|void`)를 유지하고 openapi를 실제에 맞춘다 | openapi의 `…/{id}/action` 단일 엔드포인트는 구현된 적 없다. 콘솔이 이미 세 경로를 쓴다 |

---

## 2. 병합 가드와 잠금

### 2.1 `MergeService.merge(survivorId, loserId, …)`

1. `lockBoth(a, b)`: 두 id를 오름차순으로 정렬해 `trendItems.findByIdForUpdate`를 차례로 호출한다. 이미 있는 리포지토리 메서드(`TrendItemRepository.findByIdForUpdate`, `PESSIMISTIC_WRITE`)를 쓴다.
2. `requireMergeable(item)`을 두 항목에 적용한다.
   - `state == MERGED` → `MergeConflictException(TARGET_MERGED)` (409 `merge-target-merged`)
   - `state ∈ {JUDGING}` → `MergeConflictException(JUDGING)` (409 `merge-judging`)
   - `state ∈ {RESOLVED, VOID}` 또는 현행 판정 행 존재 → `MergeConflictException(RESOLVED)` (409 `merge-resolved`)
3. 기존 순서대로 진행: 중복 제보 VOID → 제보 재배정 → aliases → `pullForwardFirstSeen` → 대표명 재계산 → **마감 보장(§4.3)** → `loser.mergeInto`.
4. 감사 `MERGE`의 `detail`에 기존 필드 + 마감 조정(`deadlineBefore`, `deadlineAfter`, 조정 없으면 생략).

지금의 "이미 MERGED면 조용히 반환"은 없앤다. 멱등은 큐 경로에서 K5가, 자동 경로에서 가드(패자가 MERGED → 409, 배치는 이를 건너뜀)가 맡는다.

`MergeConflictException`은 `merge` 모듈에 두고 `reason` enum(`JUDGING`, `RESOLVED`, `TARGET_MERGED`, `QUEUE_DECIDED`)을 가진다. `AdminApiExceptionHandler`가 409로 매핑하며 본문에 `type`을 넣는다(§7).

### 2.2 `order_rank` 재계산

`submission_order_rank`는 뷰라서 제보의 `trend_item_id`를 옮기면 같은 트랜잭션 안에서 자동으로 다시 파생된다. 코드 변경은 없지만 **테스트로 건다**(CLAUDE.md: "빼먹어도 에러가 나지 않고 선점 가중치만 조용히 틀린다"). 먼저 제보한 사람이 앞, 시딩·VOID 제외, 동시각은 같은 순위.

### 2.3 자동 병합 경로 (`ClusterMergeCandidateService`)

- 후보 스캔: `findByStateInAndMergeCheckedAtIsNull(DRAFT, PENDING)` — `JUDGING`·`RESOLVED`를 뺀다.
- `TrendEmbeddingDao.findMostSimilar`는 지금처럼 MERGED만 빼고 가장 닮은 항목을 돌려준다(상태 필터를 넣지 않는다 — 판정된 항목과 닮았다는 사실을 알아야 K4를 기록할 수 있다).
- 결과 분기:
  - 상대가 판정 전 상태가 아니고 유사도 ≥ 0.75 → 감사 `MERGE_SKIPPED_JUDGED`(actor 없음, target = 후보 항목, detail = 상대 id·상태·유사도). 병합·큐 없음. 확인 표시.
  - 그 외는 기존대로(≥0.85 자동 병합, 0.75~0.85 큐, 그 미만 별개).
  - 병합이 `MergeConflictException`으로 거부되면(판정과 경합한 경우) 확인 표시를 하지 않아 다음 실행에서 다시 본다.
- 트랜잭션: 임베딩 계산(외부 HTTP)과 `updateEmbedding`은 트랜잭션 밖. **결정 + 확인 표시**는 새 빈 `ClusterMergeDecider`의 `@Transactional decide(candidateId, otherId, similarity)` 하나로 묶는다(같은 클래스 안 호출이면 프록시를 안 타므로 별도 빈).

### 2.4 큐 행 잠금

`MergeQueueRepository.findByIdForUpdate(id)`(`PESSIMISTIC_WRITE`)를 추가하고, 컨트롤러의 결정 경로가 이걸로 큐 행을 먼저 잠근 뒤 항목 잠금을 잡는다. 잠금 순서는 항상 **큐 행 → 항목(id 오름차순)**.

---

## 3. 멱등키

### 3.1 스키마 (V29)

- `merge_queue.decision_key VARCHAR(80)` + `UNIQUE`(NULL 허용 — 처리 대기 행과 V29 이전 행은 NULL).
- `merge_queue_one_open_per_new`(전역 UNIQUE)를 지우고 `CREATE UNIQUE INDEX merge_queue_one_pending_per_new ON merge_queue(new_trend_item_id) WHERE status = 'PENDING'`.

결정 결과는 기존 컬럼(`status`, `resolved_by`, `resolved_at`)과 패자의 `merged_into`에서 복원한다. 새 결과 컬럼은 만들지 않는다.

### 3.2 동작

결정 엔드포인트 세 개(`merge`, `separate`, `void`)는 `@RequestHeader("Idempotency-Key")`를 받는다.

| 상황 | 응답 |
|---|---|
| 헤더 없음·빈 값·80자 초과 | 400 `idempotency-key-required` |
| 큐 행이 PENDING, 이 키 처음 | 결정 실행, `decision_key` 기록, 200 + `MergeDecisionResponse` |
| 큐 행의 `decision_key == 이 키`, 같은 결정 | 이전 결과 그대로 200 (`replayed: true`) |
| 큐 행의 `decision_key == 이 키`, 다른 결정 | 422 `idempotency-key-mismatch` |
| 큐 행이 이미 다른 키로 처리됨 | 409 `merge-queue-decided` + 기존 결과 |
| 이 키가 다른 큐 행에 이미 쓰임 | 422 `idempotency-key-mismatch` (UNIQUE 위반을 변환) |

`MergeDecisionResponse { queueId, decision (MERGE|SEPARATE|VOID), status, survivorId?, loserId?, decidedBy, decidedAt, replayed }`. 지금은 세 엔드포인트가 본문 없이 200을 준다 — 콘솔은 응답 본문을 쓰지 않으므로 호환에 문제없다.

키 확인·결정·`decision_key` 기록은 큐 행 `FOR UPDATE` 아래 한 트랜잭션이다. 동시에 같은 키 두 요청이 오면 두 번째는 잠금을 기다렸다가 "같은 키, 같은 결정" 경로로 재생된다.

### 3.3 콘솔

`decideMergeCandidate`가 호출마다 `crypto.randomUUID()`로 키를 만들고, 네트워크 오류·5xx로 재시도할 때는 같은 키를 쓴다. 성공·4xx 응답을 받으면 키를 버린다.

---

## 4. 병합 이후의 흐름

### 4.1 죽은 항목 따라가기 (K6)

`TrendItemResolver.resolveLive(normalizedKey)`(persistence 모듈 또는 공용 위치의 작은 컴포넌트):

1. `findByNormalizedKey` → 없으면 `Optional.empty()`.
2. `state == MERGED`면 `merged_into`로 이동. 반복. 방문한 id를 기억해 순환이면, 10단계를 넘으면 `IllegalStateException`(데이터 손상 — 500이 맞다).
3. MERGED가 아닌 첫 항목을 돌려준다.

사용처: `SubmissionService.create`, `WatchService`(`:56`), `AdminSeedService`(`:99`). 제보는 승자에 붙고 중복 검사·관측 마감·제보권은 모두 승자 기준. `SubmissionService.requireOpen`의 MERGED 분기와 "SP2가 합류시키기 전까지" 주석은 지운다.

제보의 `normalized_key`에는 유저가 입력한 키(패자 키)가 그대로 남는다 — 원 입력 보존.

### 4.2 동시 첫 제보

`SubmissionService`에서 새 항목 생성을 `TrendItemCreator.createOrJoin(name, normalized, category, now)`로 옮긴다.

- `INSERT … ON CONFLICT (normalized_key) DO NOTHING RETURNING id`(네이티브)로 삽입을 시도한다. 반환 행이 없으면 먼저 생긴 항목이 있다는 뜻이므로 `resolveLive`로 다시 찾아 합류한다.
- `ON CONFLICT`는 예외를 던지지 않아 제보 트랜잭션이 망가지지 않는다(PostgreSQL에서 UNIQUE 위반 예외는 트랜잭션 전체를 abort 상태로 만든다).
- 합류한 항목에는 §4.1과 같은 검사(마감·중복·제보권)가 적용된다.

시딩(`AdminSeedService`)의 새 항목 생성도 같은 컴포넌트를 쓴다.

### 4.3 최소 관측 기간 보장 (K7)

순수 함수 `DeadlineWindow.afterMerge(Instant newFirstSeen, Instant currentOverride, Instant now)` → `Optional<Instant>`(새 override, 바꿀 필요 없으면 empty).

```
current  = effectiveDeadline(newFirstSeen, currentOverride)
floor    = now + 3일
ceiling  = newFirstSeen + 21일
target   = min(floor, ceiling)
current >= target  → empty            (병합은 마감을 당기지 않는다)
그 외              → Optional.of(target)
```

- 아주 오래된 클러스터라 `ceiling`이 이미 과거면 `target = ceiling`도 과거다. 그래도 `current < target`이면 override를 `ceiling`으로 잡는다 — 마감이 과거이므로 다음 배치에서 판정된다. **D+21 상한을 넘겨 연장하지 않는다는 규칙이 최소 관측 3일보다 우선한다.** 이 경우 감사 로그 `detail`에 `minObservationCapped: true`를 남긴다.
- 상수 `MERGE_MIN_OBSERVATION_DAYS = 3`, `MAX_DEADLINE_DAYS = 21`은 `DeadlineWindow`에 둔다. `VerdictAdminService.extendGrace`의 `JUDGE_WINDOW_DAYS + MAX_GRACE_DAYS`(=21)와 같은 값이므로 그쪽도 이 상수를 참조하게 바꾼다.

### 4.4 병합의 제보권 부수효과 (K8)

동작은 그대로다. `MergeService.preview`의 `PreviewResult`에 `quotaRefundSubmissionIds`(중복 VOID 대상 중 시딩이 아닌 제보)를 추가한다. 현재 `dedupVoidedSubmissionIds`와 같은 목록에서 시딩만 뺀 것이다.

---

## 5. 미리보기·큐 VOID·콘솔

### 5.1 미리보기 순위 (항목 8)

`MergeComputation.computeCombinedOrder`를 뷰와 같은 규칙으로 맞춘다 — 시딩·VOID 제외, `created_at` 오름차순, 동시각은 같은 순위(`RANK`). `SubmissionInput`에 `seed` 필드를 추가한다. 시딩 제보는 `orderAfter`에서 빠지고, 콘솔은 SP1 ADM-111처럼 시딩을 순위 자리 대신 "시딩"으로 따로 보여준다.

### 5.2 큐 VOID (항목 10)

컨트롤러의 VOID 경로가 새 항목이 MERGED인 것을 먼저 확인한다. MERGED면 `JudgeService.voidItem`을 부르지 않고, 큐 행을 `SKIPPED`로 정리하고 감사 `MERGE_QUEUE_STALE`을 남긴 뒤 409 `merge-target-merged`를 돌려준다(대상이 사라진 후보). 병합·분리 경로도 대상 MERGED면 같은 처리. 상태 이름은 다음 묶음에서 `SEPARATED`로 개명할 때 함께 정리한다.

### 5.3 ADM-100 콘솔 (최소)

- 결정 버튼: 멱등키(§3.3).
- 409 `type`별 안내: `merge-judging` "판정 중인 항목입니다. 잠시 후 다시 시도하세요", `merge-resolved` "이미 판정된 항목은 병합할 수 없습니다(판정 후 병합은 Phase 2)", `merge-queue-decided`·`merge-target-merged` "이미 처리된 후보입니다" + 목록 갱신.
- 미리보기: 시딩 행 "시딩" 표시, 제보권 반환 대상 표시, 마감 조정이 생기면 "관측 마감 → YYYY-MM-DD HH:mm (최소 관측 보장)" 표시. 이를 위해 `PreviewResult`에 `deadlineBefore`·`deadlineAfter`를 추가한다(순수 함수 `afterMerge`를 미리보기도 쓴다).
- "보류 → 다음" 버튼은 서버 호출 없이 토스트만 띄우는 가짜라 비활성 + "미구현" 표시. J/K/M/S/V 단축키 안내 문구도 지금은 동작하지 않으니 뺀다.

---

## 6. 스키마 — `V29__sp2_merge_integrity.sql`

```sql
-- 멱등키: 결정 요청의 Idempotency-Key. 같은 키 재요청은 이전 결과를 돌려준다(K5).
ALTER TABLE merge_queue ADD COLUMN decision_key VARCHAR(80);
ALTER TABLE merge_queue ADD CONSTRAINT merge_queue_decision_key_key UNIQUE (decision_key);

-- 처리 대기 행에만 "새 항목당 1건". 전역 UNIQUE는 처리된 항목의 재적재를 영구히 막았다.
ALTER TABLE merge_queue DROP CONSTRAINT merge_queue_one_open_per_new;
CREATE UNIQUE INDEX merge_queue_one_pending_per_new ON merge_queue(new_trend_item_id) WHERE status = 'PENDING';

COMMENT ON COLUMN merge_queue.decision_key IS '결정 요청의 Idempotency-Key. 처리 대기 행은 NULL.';
```

V29 이전에 처리된 행은 `decision_key`가 NULL로 남는다(재생 대상 아님). CHECK로 "처리된 행은 키 필수"를 걸지 않는다 — 배치가 정리하는 행(§5.2 `MERGE_QUEUE_STALE`)은 키가 없다.

---

## 7. API 계약

- `POST /admin/merge-queue/{id}/merge|separate|void`: 헤더 `Idempotency-Key`(필수), 본문 `{reason}`, 200 `MergeDecisionResponse`, 400 `idempotency-key-required`, 409(`merge-judging`·`merge-resolved`·`merge-target-merged`·`merge-queue-decided`), 422 `idempotency-key-mismatch`.
- 409·422·400 본문은 기존 `{type, status, detail}` 형태에 `type`만 위 값으로 채운다. `AdminApiExceptionHandler.problem(status, detail)`에 `type`을 받는 오버로드를 추가한다(기존 호출은 `about:blank` 유지).
- `GET /admin/merge-queue/{id}/preview`: `quotaRefundSubmissionIds`, `deadlineBefore`, `deadlineAfter`, 순위 목록의 시딩 표시 추가. `baselineShifted`는 피벗 잔재(상위 문서 A3)라 제거하고 `deadlineAfter` 유무로 대체.
- `openapi.yaml`: 구현되지 않은 `POST /admin/merge-queue/{id}/action`을 지우고 위 세 경로를 실제대로 적는다(K9).
- 제보 API: `POST /v1/submissions`에서 패자 이름이 더 이상 422 `item-closed`가 아니라 승자 합류로 성공한다. openapi 설명을 고친다.

---

## 8. 테스트

모두 `AbstractIntegrationTest` 기반. `Fixtures`에 `mergeQueueEntry(newItem, oldItem, similarity)`, `mergedItem(survivor)`(병합된 패자 만들기) 헬퍼를 추가한다.

| # | 케이스 | 클래스(예정) |
|---|---|---|
| 1 | 같은 두 항목 병합을 동시에 두 번 → 하나만 성공, 다른 하나 409, 제보 재배정 1회 | `MergeConcurrencyTest` |
| 2 | 병합과 판정이 동시에 → 둘 중 하나가 기다렸다가 가드에 걸려 이중 점수 없음(원장 행 수 확인) | `MergeConcurrencyTest` |
| 3 | `JUDGING` 항목 병합 → 409 `merge-judging` | `MergeGuardTest` |
| 4 | `RESOLVED` 항목 병합 → 409 `merge-resolved` / 상태는 PENDING인데 판정 행이 있는 항목 → 409 `merge-resolved` | `MergeGuardTest` |
| 5 | 자동 경로: 판정된 항목과 유사도 0.9 → 병합·큐 없음, 감사 `MERGE_SKIPPED_JUDGED` 1건 | `ClusterMergeGuardTest` |
| 6 | 같은 멱등키 재요청 → 같은 응답(`replayed: true`), 병합 1회 / 같은 키 다른 결정 → 422 / 다른 키로 이미 처리된 행 → 409 `merge-queue-decided` / 헤더 없음 → 400 | `MergeIdempotencyTest` |
| 7 | 처리된 항목이 다시 큐에 적재된다(부분 UNIQUE) | `MergeIdempotencyTest` |
| 8 | 병합 후 순위: 흡수된 쪽 제보가 먼저면 그 제보자가 1위, 시딩 제외 | `MergeRecomputeTest` |
| 9 | 마감 보장: 병합으로 마감이 과거가 되면 override = 병합 + 3일 / 상한 D+21 적용 / 기존 override가 더 늦으면 그대로 | `MergeRecomputeTest` + `DeadlineWindowTest`(순수) |
| 10 | 패자 이름으로 제보 → 승자에 붙음 / 2단계 체인 / 이미 승자에 제보한 유저가 패자 이름으로 → 409 중복 | `TombstoneJoinTest` |
| 11 | 패자 이름으로 워치·시딩 → 승자 기준 | `TombstoneJoinTest` |
| 12 | 같은 새 이름 동시 첫 제보 → 항목 1개, 제보 2개, 500 없음 | `TombstoneJoinTest` |
| 13 | 병합 중복 VOID → 그 유저 제보권 1장 반환, 미리보기 `quotaRefundSubmissionIds`에 포함(시딩 제외) | `MergeRecomputeTest` |
| 14 | 큐 VOID인데 새 항목이 이미 MERGED → 409 `merge-target-merged`, 큐 행 정리 | `MergeIdempotencyTest` |
| 15 | 미리보기 순위가 뷰와 일치(시딩·동시각 포함) | `MergeComputationTest`(순수) + `MergeRecomputeTest` |

동시성 테스트는 `CountDownLatch`로 동시에 출발시키고, 서비스 빈을 직접 호출한다(MockMvc는 스레드 간 공유가 불안정).

---

## 9. 문서

- `CLAUDE.md`: "first_seen_at이 바뀌면…" 절의 **미구현(SP2)** 표기 해소, 배치 표 `cluster_merge` 비고에 "판정된 항목은 병합하지 않고 기록만", 코드 규약의 "병합 연산은 단일 트랜잭션 + 행 잠금 + 멱등성 키"는 구현됨으로.
- `03-merge-clustering.md`: 트랜잭션 절차·동시성 절을 실제 구현(id 오름차순 잠금, 큐 행 → 항목 순서)으로, 멱등키 절, 죽은 항목 합류, 최소 관측 보장. 큐 상태 모델·정규화는 "다음 묶음" 표기 유지.
- `05-screen-endpoint-map.md`: ADM-100 행 — 멱등키·409 type·미리보기 필드, "보류" 미구현.
- `02-admin-console.md`: ADM-100 버튼·단축키 표기 정정.
- `backend/api-spec/openapi.yaml`: §7.
- 상위 스펙 `2026-09-17-design-review-design.md` §3 SP2 행: 이번 묶음과 다음 묶음(SP2b) 분할 표기, 이 문서 링크.

---

## 10. 완료 기준

- [ ] §8의 15개 케이스와 기존 테스트 전부 통과(`./gradlew build`)
- [ ] 콘솔 `npm run build` 통과
- [ ] 브라우저(일회용 DB): ADM-100에서 병합 → 같은 버튼 연타해도 병합 1회, 판정된 항목과의 후보 병합 시 409 안내 문구, 미리보기의 시딩·제보권 반환·마감 조정 표시
- [ ] `MergeService`·`ClusterMergeCandidateService`에 잠금 없는 `findById`로 항목을 읽어 병합하는 경로 없음
- [ ] openapi에 구현되지 않은 병합 엔드포인트 없음

---

## 11. 다음 묶음(SP2b)으로 넘기는 것

- 큐 상태 모델 `PENDING → CLAIMED → (MERGED | SEPARATED | VOIDED | HELD)`, 클레임 15분 만료, `HELD` 3회 → `ESCALATED`, `SKIPPED` → `SEPARATED` 개명
- ADM-100 클레임·보류·에스컬레이션 UI와 키보드 단축키
- 정규화 규칙(특수문자·조사·반복 문자·영한 혼용·외래어 표기)과 기존 `normalized_key` 백필·충돌 병합, `toLowerCase(Locale.ROOT)`, 160자 상한
- `aliases` 조회 활용(현재 쓰기 전용), 패자 `canonical_name`의 aliases 기록
- 분리(split) 실구현(현재는 후보 기각 감사 로그뿐)
- 판정 후 병합(ADJ 상쇄 경로) — Phase 2(O9)
