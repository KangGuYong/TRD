# 앱 API 2단계-a: 워치

## 배경

1단계(상세·투표·동의·등급·원장)는 이미 구현·커밋됐다. 2단계는 나머지 스코프(워치·온보딩 설정 저장·일일 다이제스트/읽음 스트릭·나 탭 요약)로, 규모가 커서 하위 기능별로 쪼개 진행한다. 이번 스펙은 그중 가장 단순한 **워치**만 다룬다.

`app/`의 `useWatch()`(`GET /v1/me/watch`), `useToggleWatch()`(`POST/DELETE /v1/me/watch`)는 이미 구현돼 있고, `DetailScreen`의 "워치에 추가하고 알림 받기" 버튼과 `WatchScreen`(워치 탭) 둘 다 이 API를 기다리고 있다. `TrendDetailResponse.watched`는 1단계에서 테이블이 없어 항상 `false`로 고정해뒀다 — 이번에 실데이터로 교체한다.

## 범위

| 엔드포인트 | 설명 |
|---|---|
| `GET /v1/me/watch` | 워치 목록 (매칭되는 트렌드의 현재 stage 포함) |
| `POST /v1/me/watch` | 워치 추가 (`{keyword}`) |
| `DELETE /v1/me/watch/{keyword}` | 워치 해제 |

**스코프 밖**: 실제 푸시 알림 발송("급상승 진입 시 즉시 알림"은 OpenAPI 설명일 뿐, 디바이스 토큰 등록·단계 전환 감지 배치·`expo-notifications` 권한 요청이 전부 이번 스코프 밖). 이번엔 CRUD + 목록 조회 + 상세 화면 `watched` 필드 실데이터화까지만.

## 데이터 모델

새 테이블 `watches` (마이그레이션 `V19__watches.sql`):

```sql
CREATE TABLE watches (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id),
    keyword         VARCHAR(120) NOT NULL,
    normalized_key  VARCHAR(160) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, normalized_key)
);
```

`Vote`/`Endorsement` 엔티티와 동일한 스타일(유니크 제약으로 중복 방지, 생성자에서 필드 채움)로 `Watch` 엔티티 + `WatchRepository`를 만든다.

## 매칭 로직

새 정규화 로직을 만들지 않는다 — 제보 완전일치 병합에 이미 쓰는 `NameNormalizer.normalize()`(domain-core, 순수 함수)를 그대로 재사용해서 `watches.normalized_key`를 `trend_items.normalized_key`와 대조한다.

```
POST /v1/me/watch {keyword}
  → normalized = NameNormalizer.normalize(keyword)
  → 이미 (userId, normalized) 워치 있으면 그냥 200(멱등)
  → 없으면 Watch 저장, 201
```

```
GET /v1/me/watch
  각 워치 행마다:
    trends.findByNormalizedKey(row.normalizedKey) 로 TrendItem 탐색
      못 찾으면 → { keyword: row.keyword, stage: null, note: "아직 관측되지 않음" }
      찾았는데 state == MERGED → mergedInto를 한 번 따라가서 그 항목 기준으로 재계산
        (병합이 여러 번 연쇄될 가능성은 낮다고 보고 1단계까지만 — 03 문서의 병합은 패자→승자 단방향 tombstone이라 보통 1홉으로 충분)
      찾았으면 → stage는 TrendQueryService.home()과 동일하게 SubmissionRepository.findDistinctPlatforms 기반 StageEvaluator.fromReach()로 계산, note는 null
```

```
DELETE /v1/me/watch/{keyword}
  → normalized = NameNormalizer.normalize(keyword)
  → watches.deleteByUserIdAndNormalizedKey(userId, normalized) — 없어도 204(멱등, 에러 아님)
```

## `TrendDetailResponse.watched` 실데이터화

`TrendController.detail()`은 인증 없이도 열려 있다(`PublicSecurityConfig`의 `permitAll`). 다만 앱은 로그인 상태면 항상 `Authorization` 헤더를 보내므로, `FirebaseAuthenticationFilter`가 토큰을 검증해 `SecurityContext`에 인증 정보를 채워둔다 — 즉 로그인한 유저가 상세를 열면 `Authentication`이 null이 아니다.

`TrendQueryService.detail()`에 `UUID viewerId`(nullable) 파라미터를 추가한다. `null`이면 `watched: false` 고정(비로그인). 아니면 `watches.existsByUserIdAndNormalizedKey(viewerId, item.getNormalizedKey())`로 실제 값을 채운다.

`TrendController.detail()`은 `Authentication auth`를 받되 필수(`required`)로 강제하지 않는다 — Spring MVC에서 `Authentication` 파라미터는 인증이 안 됐으면 자동으로 `null`이 주입되므로 추가 설정 없이 그대로 nullable로 쓸 수 있다.

## 에러 처리

기존 `ApiExceptionHandler` 패턴 재사용 — 이번 스코프엔 새 예외 타입이 필요 없다(워치 추가/삭제 둘 다 멱등이라 409/404가 없음).

## 테스트 계획

이 프로젝트의 `api-public` 모듈 관례(1단계와 동일)를 따라 컨트롤러/서비스 단위 테스트는 작성하지 않는다. 빌드 컴파일 + curl 수동 검증으로 확인한다.

- 워치 추가 → 목록 조회 → stage 채워지는지(매칭 성공 케이스, `trend_items` 테스트 데이터 활용)
- 매칭 안 되는 키워드로 워치 추가 → 목록에서 `stage: null, note` 확인
- 같은 키워드 두 번 추가 → 멱등 확인(행 1개만 남는지 DB로 확인)
- 상세 조회 시 로그인 토큰 유무에 따라 `watched` 값이 갈리는지
