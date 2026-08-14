# ADM-100 병합 검수 화면 개선 — 확인 정보 보강 + 병합 후 미리보기

## 배경

ADM-100(`MergeQueueScreen.tsx` + `MergeQueueController`)은 임베딩 유사도 0.75~0.85 회색지대 항목을 사람이 판단하도록 큐로 넘긴다. `03-merge-clustering.md`가 직접 예시로 드는 함정들(`탕후루` vs `딸기 탕후루`처럼 상위/하위 개념, `○○챌린지` vs `○○챌린지 2탄`처럼 속편, `마라탕` vs `마라샹궈`처럼 발음만 유사)은 구조적 메타데이터(카테고리·플랫폼·제보자 등급)만으로는 구분할 수 없고, 제보 원문을 읽어야 판단 가능하다. 그런데 현재 화면은:

- 신규 클러스터: 첫 제보 1건의 요약 정보만 (raw_input/one_line/evidence_url 자체는 안 보임)
- 기존 클러스터: 제보 건수·경과일·상태만 (원문 전혀 안 보임)
- 병합 시 실제로 무엇이 바뀌는지(대표명 재계산, 선점 순위 변화, `first_seen_at` 이동에 따른 baseline 재계산 영향)에 대한 정보가 전혀 없음

또한 조사 과정에서 기존 `buildOrderPreview()`가 `MergeService.merge()`가 실제로 수행하는 동일 유저 중복 VOID 처리(`dedupSameUserSubmissions`)를 반영하지 않아, 실제 병합 결과와 미묘하게 어긋날 수 있는 기존 버그를 발견했다.

## 목표

1. 검수자가 "이 두 제보가 진짜 같은 대상을 가리키는가"를 판단할 수 있도록 제보 원문 정보를 노출한다.
2. 검수자가 "병합하면 실제로 무엇이 바뀌는가"를 병합 전에 확인할 수 있게 한다.
3. 기존 순위 미리보기의 드리프트 버그를 구조적으로 고친다(계산 로직을 `merge()`와 공유).

## 범위

- ADM-100 큐 화면(`MergeQueueScreen.tsx`)과 그 백엔드(`MergeQueueController`, `MergeService`)만 대상.
- 병합 실행 로직(`MergeService.merge()`) 자체의 동작 변경은 없음 — 계산 부분을 순수 함수로 추출해 재사용할 뿐, 결과는 동일해야 함.
- Type 2(관리자가 임의로 두 클러스터를 고르는 수동 병합)는 여전히 범위 밖(기존 제약 유지).

## 아키텍처

기존 API·화면을 유지한 채 두 가지를 추가한다.

### A. 확인 정보 보강 (조회 시 즉시 포함)

`GET /admin/merge-queue` 응답에 신규/기존 클러스터 각각의 **전체 활성 제보 목록**을 추가로 담는다. 이미 조회하는 데이터를 확장하는 것뿐이라 별도 API 호출이 필요 없다 — "더 보기"는 이미 받은 데이터를 펼치는 순수 UI 동작.

### B. 병합 후 미리보기 (온디맨드)

신규 엔드포인트 `GET /admin/merge-queue/{id}/preview`를 추가한다. "병합 후 미리보기" 버튼을 누를 때만 호출되며, `MergeService.merge()`가 실제로 쓰는 것과 **동일한 순수 계산 함수**를 DB에 쓰지 않는 dry-run으로 재사용해 결과를 반환한다.

큐가 여러 건일 때 목록 조회마다 전 항목을 dry-run 계산하면 서버 부하·검수자 간 락 경합 위험이 있으므로, 미리보기는 항상 온디맨드(버튼 클릭 시 1건만) 호출로 제한한다.

## 백엔드 설계

### `MergeService` 리팩터링

`merge()` 내부에 있던 계산 로직을 저장 여부와 무관한 순수 함수로 추출해 `merge()`(실행, 저장함)와 `preview()`(dry-run, 저장 안 함) 둘 다 재사용한다.

```java
private record DedupPlan(Set<UUID> voidedSubmissionIds) {}
private DedupPlan computeDedup(List<Submission> a, List<Submission> b) { ... }

private String computeCanonicalName(List<Submission> activeAfterDedup) { ... }

private record OrderComputed(UUID submissionId, String handle, int rank) {}
private List<OrderComputed> computeCombinedOrder(List<Submission> activeAfterDedup) { ... }
```

`merge()`는 이 세 함수의 결과를 엔티티에 반영·저장한다(현재 동작과 동일, 순수 리팩터링). 신규 `preview(UUID survivorId, UUID loserId)`는 같은 함수들을 호출하되 저장하지 않고 DTO로 조립해 반환한다. `@Transactional(readOnly = true)`로 명시해 실수로 저장 코드가 섞이는 걸 방지한다.

### 신규 DTO / 엔드포인트

```java
public record OrderEntry(String handle, int rankBefore, int rankAfter) {}

public record MergePreviewResponse(
    String newCanonicalName,
    List<OrderEntry> orderRank,
    String firstSeenAtBefore, String firstSeenAtAfter,
    boolean baselineShifted,
    List<String> dedupVoidedHandles
) {}
```

```java
@GetMapping("/{id}/preview")
@PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN', 'AUDITOR')")  // list()와 동일 — 읽기 전용
public MergePreviewResponse preview(@PathVariable UUID id) {
    MergeQueueEntry entry = requirePending(id);
    TrendItem newItem = trendItems.findById(entry.getNewTrendItemId()).orElseThrow();
    TrendItem oldItem = trendItems.findById(entry.getOldTrendItemId()).orElseThrow();
    TrendItem survivor = newItem.getFirstSeenAt().isBefore(oldItem.getFirstSeenAt()) ? newItem : oldItem;
    TrendItem loser = survivor == newItem ? oldItem : newItem;
    return mergeService.preview(survivor.getId(), loser.getId()); // DTO 조립은 컨트롤러에서
}
```

`baselineShifted`는 `loser.getFirstSeenAt().isBefore(survivor.getFirstSeenAt())`로 판정(승자보다 패자가 더 이르면 survivor의 first_seen_at이 앞당겨짐).

### `toResponse()` 확장

기존 `newRows`/`oldRows`(요약 KV)는 그대로 유지하고, 병렬로 전체 제보 배열을 추가한다.

```java
public record SubmissionDetail(String handle, String rawInput, String oneLine,
                                String evidenceUrl, String createdAt) {}
public record MergeCandidateResponse(..., List<SubmissionDetail> newSubmissions,
                                      List<SubmissionDetail> oldSubmissions) {}
```

신규/기존 양쪽 다 `submissions.findByTrendItemIdAndResultNot(id, VOID)` 전체를 조회해서 채운다(현재는 신규 쪽 첫 제보 1건만 조회).

## 프론트엔드 설계

### 타입 확장 (`admin/src/api/types.ts`)

```ts
export interface SubmissionDetail {
  handle: string; rawInput: string; oneLine: string;
  evidenceUrl: string; createdAt: string;
}
export interface MergeCandidate {
  // ...기존 필드 유지
  newSubmissions: SubmissionDetail[];
  oldSubmissions: SubmissionDetail[];
}
export interface OrderEntry { handle: string; rankBefore: number; rankAfter: number }
export interface MergePreview {
  newCanonicalName: string;
  orderRank: OrderEntry[];
  firstSeenAtBefore: string; firstSeenAtAfter: string;
  baselineShifted: boolean;
  dedupVoidedHandles: string[];
}
```

### `MergeQueueScreen.tsx` 변경

1. 기존 `newRows`/`oldRows` 요약 아래에 `[더 보기 (제보 N건)]` 토글. 펼치면 `newSubmissions`/`oldSubmissions`를 `raw_input` + `one_line` + `evidence_url`(외부 링크, `target="_blank"`, URL이 아니면 일반 텍스트로 표시) 목록으로 렌더링. 네트워크 호출 없음(이미 받은 데이터).
2. 액션 버튼 줄에 **"병합 후 미리보기"** 버튼 추가. 클릭 시 `GET /admin/merge-queue/{id}/preview` 호출 → 모달 오픈.
3. **미리보기 모달** 구성:
   - 대표명: `oldName → newCanonicalName` (바뀌는 경우만 화살표로 강조)
   - 선점 순위: 제보자별 `rankBefore → rankAfter` 테이블
   - `first_seen_at`: `firstSeenAtBefore → firstSeenAtAfter`. `baselineShifted`가 true면 경고 배너("기존 판정 기준선이 재계산 대상이 됩니다") — 경고만 표시, 병합 버튼은 계속 활성 상태 유지(검수 속도 우선, 실수 방지는 배너로 충분).
   - `dedupVoidedHandles`가 비어있지 않으면 "동일 유저 중복 제보로 VOID 처리될 제보자: ..." 안내.
   - 하단에 사유(reason) 입력칸 + **"이 내용으로 병합"** 버튼 → 기존 `POST /admin/merge-queue/{id}/merge`를 그대로 호출, 성공 시 모달 닫고 큐 목록 리페치.
4. 기존 카드의 "병합" 버튼은 그대로 유지(미리보기를 강제 경유하지 않고 바로 병합할 수 있는 경로도 남겨둠 — 명백한 오타 수준 표기 차이 등).

## 예외 처리 / 동시성

- **미리보기 조회~확정 사이 시간차**: 모달의 확정 버튼은 기존 `POST /merge`를 그대로 호출하므로, 기존 `requirePending()`(큐 상태 재확인)과 `TrendItem.version` 낙관적 락이 그대로 방어한다. 신규 동시성 로직 불필요.
- **`preview()`는 부작용 없음을 보장**: `save()`/감사로그 기록을 절대 호출하지 않고 `@Transactional(readOnly = true)`로 강제.
- **`evidence_url` 비어있거나 유효하지 않음**: 링크 대신 일반 텍스트로 표시.
- **preview 호출 자체 실패**: 모달 안에서만 에러 표시, 카드의 기존 "병합" 버튼은 계속 사용 가능(미리보기는 병합의 필수 경유 단계가 아님).

## 테스트 계획

- **백엔드**: `computeDedup`/`computeCanonicalName`/`computeCombinedOrder` 단위 테스트. 특히 `preview()` 반환값이 실제 `merge()` 실행 후 DB에 반영된 값과 **일치**하는지 회귀 테스트로 고정(기존 드리프트 버그 재발 방지).
- **컨트롤러**: `/preview` RBAC(REVIEWER 이상 조회 가능), 이미 처리된 큐 항목 조회 시 404.
- **프론트/브라우저**: "더 보기" 펼침 확인, `baselineShifted` true/false 두 케이스 각각 미리보기 모달 확인, 모달에서 확정 병합까지 end-to-end.

## 비범위 (Out of scope)

- Type 2 수동 병합(관리자가 임의로 두 클러스터 선택)
- 제보자 등급/TI를 병합 판단에 반영하는 것 (기존 방침 유지: 참고 정보일 뿐, 공정성 문제로 배제)
- 미리보기를 병합의 필수 단계로 강제하는 것(선택적 도구로 유지)
