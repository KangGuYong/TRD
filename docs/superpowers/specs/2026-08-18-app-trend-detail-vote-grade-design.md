# 앱 API 1단계: 상세 · 투표 · 동의 · 등급 · 원장

## 배경

`app/`(React Native/Expo)는 `docs`의 OpenAPI 스펙과 프론트 훅(`app/src/api/hooks.ts`)이 다 갖춰져 있는데, `backend/api-public`에는 목록(`GET /v1/trends`)과 제보(`POST/GET /v1/submissions`)만 구현돼 있다. 그 결과 "오늘의 5개"에서 카드를 눌러 상세로 들어가면 `GET /v1/trends/{id}`가 404라 "연결에 실패했어요"만 뜨고, "나" 탭도 등급·원장 API가 없어 빈 화면이다.

이번 스코프는 **기존 테이블·기존 순수 함수를 재사용해서 바로 만들 수 있는 5개 엔드포인트**로 한정한다. 워치(`/v1/me/watch`)·온보딩 설정 저장(`/v1/me/preferences`)·나 탭 요약(`/v1/me/summary`, `/v1/me/reads`)은 새 테이블과 "제보권 수량" 같은 별도 product 결정이 필요해서 다음 스코프로 미룬다.

## 범위

| 엔드포인트 | 설명 | 인증 |
|---|---|---|
| `GET /v1/trends/{id}` | 상세(뜻/판정/전파경로/투표수) | 불필요(둘러보기) |
| `POST /v1/trends/{id}/vote` | "이거 더 뜰까요" 투표. 유저당 1표, 토글 | 필요 |
| `POST /v1/trends/{id}/endorse` | 중복 제보를 동의로 전환 | 필요 |
| `GET /v1/me/grade` | 등급 · AS · TI · 다음 등급 부족분 | 필요 |
| `GET /v1/me/ledger` | 점수 원장 전건 | 필요 |

## 아키텍처

기존 패턴을 그대로 따른다: `TrendController`/`TrendQueryService`를 확장하고, `SubmissionController` 옆에 `MeController`(신규, `/v1/me/grade`·`/v1/me/ledger`)를 추가한다. 인증이 필요한 엔드포인트는 기존 `PublicSecurityConfig`가 이미 `/v1/**`에 인증을 요구하므로 별도 설정 없이 `Authentication.getPrincipal()`(UUID)만 꺼내 쓴다(`SubmissionController` 패턴).

```
TrendController          (기존 확장: detail/vote/endorse 추가)
  └─ TrendQueryService    (기존 확장: detail() 메서드 추가)
       ├─ VerdictRepository.findCurrentByTrendItemId  (기존)
       ├─ VoteRepository                              (기존)
       ├─ SubmissionRepository                        (기존)
       └─ EndorsementRepository                       (기존)

MeController (신규)       /v1/me/grade, /v1/me/ledger
  └─ MeService (신규)
       ├─ SubmissionRepository.countByUserIdAndResult(HIT/MISS)  (기존)
       ├─ ScoreLedgerRepository.findByUserIdOrderByCreatedAtDesc (기존)
       ├─ TrustIndex.compute() / ActiveScore.compute() / GradePolicy.evaluate()  (domain-core 순수함수, 기존 — GradeRecalcJob과 동일 조합)
       └─ TrendItemRepository (원장 항목명 표시용, 기존)
```

새 테이블·마이그레이션 없음. 새 엔티티 없음.

## TrendDetail 필드 매핑

`TrendSummaryResponse`를 상속하듯 확장한다(OpenAPI `TrendDetail = TrendSummary + 확장 필드`).

| 필드 | 소스 | 비고 |
|---|---|---|
| word/meaning/stage/stageLabel/lifeText/pathText/reachedCount/ageShort | 기존 `toSummary()` 로직 그대로 | |
| `verdict` | `VerdictRepository.findCurrentByTrendItemId()` 있으면 "적중했어요"/"빗나갔어요", 없으면(PENDING/JUDGING) `null` | **판정 전엔 잠정 결과를 절대 보여주지 않는다** — D+14 전 조기 판정처럼 보이는 걸 방지(R1 취지) |
| `verdictWhy` | HIT/MISS일 때만: "서로 다른 제보자 N명 확인 · T=0.NN" (distinctSubmitters, verdict.scoreT) | 판정 전엔 `null` |
| `reachLevel` | `verdict.getReachLevel()` (HIT만 존재) | 그 외 `null` |
| `origin`/`example`/`ageSentence`/`ages` | 없음 → 항상 `null`/`[]` | 이 데이터를 만들 원천(유래 작성, 연령 수집)이 시스템에 없음. 지어내지 않는다 |
| `propagationPath` | 해당 트렌드의 `submissions`를 플랫폼별 최초 등장 시각순으로 묶어 `{channel, reached:true, note:null, date}` 배열 | `pathText`와 같은 데이터를 구조화한 것 |
| `voteCount` | `VoteRepository`로 총 투표수 + 찬성비율. 0표면 `null`(문구 자체 숨김) | "1,234명 참여 · 뜬다 82%" |
| `watched` | 항상 `false` (워치 테이블 없음, 2단계에서 실데이터로 교체) | |

## 투표 (`POST /v1/trends/{id}/vote`)

`VoteRepository.findByUserIdAndTrendItemId` 로 기존 표가 있으면 `toggleTo()`, 없으면 신규 `Vote` 생성. 응답은 `VoteResult{willTrend, voteCount}` — voteCount는 위 상세와 같은 포맷 문자열.

## 동의 (`POST /v1/trends/{id}/endorse`)

이미 해당 유저가 그 트렌드에 유효 제보 또는 동의가 있으면 409(`EndorsementRepository.existsByTrendItemIdAndUserId`). 아니면 `Endorsement` 신규 생성 후 201. `srcSubmission`은 없음(단순 동의, 원 제보 연결 없이 null 허용 — 엔티티에 이미 nullable).

## 등급 (`GET /v1/me/grade`)

`UserGrade` 스냅샷 테이블(`user_grades`)은 주 1회 `grade_recalc` 배치만 채운다. 이걸 그대로 읽으면 배치 이후 새로 생긴 판정이 반영 안 된 채로 최대 일주일 묵은 값을 보여줄 수 있다. 그래서 앱 API는 스냅샷을 읽지 않고, **`GradeRecalcJob.recalcOne()`과 완전히 동일한 조합의 순수 함수를 매 요청마다 실시간으로 호출**한다:

```
hit   = SubmissionRepository.countByUserIdAndResult(userId, HIT)
miss  = SubmissionRepository.countByUserIdAndResult(userId, MISS)
judged = hit + miss
ti    = TrustIndex.compute(hit, miss, ParameterSet.defaults())
as    = ActiveScore.compute(ledger.findByUserIdOrderByCreatedAtDesc(userId)에서 만든 Aged 리스트, ParameterSet.defaults())
status = GradePolicy.evaluate(judged, ti, as)
```

가입 직후 판정이 하나도 없는 유저도 `hit=0, miss=0` → `TrustIndex.compute(0,0,p)`가 CLAUDE.md의 초기값 0.4를 자연스럽게 산출하므로 별도 분기가 필요 없다(거짓 데이터가 아니라 공식 그대로 나온 값).

`GradePolicy.evaluate()`가 반환하는 `GradeStatus(current, next, requirements)`를 그대로 `GradeStatusResponse`(OpenAPI)로 매핑. `note` 필드는 다음 등급이 없으면(이미 L4 등) "최고 등급입니다" 같은 안내, 아니면 `null`.

`user_grades` 스냅샷 테이블 자체는 손대지 않는다(관리자 콘솔 이력 조회용으로 계속 `grade_recalc`가 채움).

## 원장 (`GET /v1/me/ledger`)

`ScoreLedgerRepository.findByUserIdOrderByCreatedAtDesc(userId)` 그대로 사용, 페이지네이션은 이번 스코프에선 생략(스펙엔 `cursor` 파라미터가 있지만 전건이 아직 많지 않으므로 `nextCursor: null` 고정 — 데이터 양이 문제될 때 재작업). 각 행의 `word`는 `ScoreLedgerEntry`에 저장된 trendItemId로 `TrendItemRepository`를 조회해 `canonicalName`을 붙인다.

## 에러 처리

기존 `ApiExceptionHandler` 패턴을 그대로 따른다(신규 예외 타입 불필요 — 404는 `TrendItemRepository.findById` 결과가 없을 때 기존에 쓰던 방식대로, 동의 중복은 409).

## 테스트 계획

- `TrendQueryService.detail()`: PENDING(판정 전, verdict null)/RESOLVED-HIT/RESOLVED-MISS 세 케이스로 verdict/verdictWhy/reachLevel 분기 검증.
- 투표 토글: 첫 투표(생성) → 같은 유저 재투표(토글, insert 아님) 검증.
- 동의 중복 방지: 같은 유저 두 번째 동의 시도 409.
- `MeService.grade()`: 판정 0건 신규 유저(TI=0.4로 수렴) / HIT·MISS 혼합 유저 두 케이스로 `GradeRecalcJob`과 동일한 조합(`TrustIndex`+`ActiveScore`+`GradePolicy`)을 타는지 확인.
- 컨트롤러 통합 테스트보다는 서비스 단위 테스트 위주(기존 프로젝트 관례).
