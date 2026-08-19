# "오늘의 5개" 하루 고정 + 개인화 설계

## 배경

현재 `TrendQueryService.home(daily=true)`는 완전 실시간 쿼리다: `PENDING`/`JUDGING` 상태 `TrendItem`을 매 요청마다 전부 가져와 `StageEvaluator.actionPriority`로 정렬한 뒤 상위 5개를 반환한다. 개인화 축이 전혀 없고(모든 유저에게 동일), 날짜 개념도 없다 — 요청 사이에 새 항목이 들어오거나 단계가 바뀌면 구성이 바뀔 수 있다.

최근 구현한 완독 추적(`trend_reads` 테이블, [ReadService.java](../../backend/api-public/src/main/java/kr/trendstage/apipublic/service/ReadService.java))은 "오늘 주어진 5개를 다 읽었는가"를 전제로 하는데, 그 5개 자체가 흔들리면 이 개념이 무의미해진다. 유저 요구사항: **매일 5개가 (유저별로 개인화되어) 고정 배정되고, 그걸 읽었는지를 추적하는 게 맞다.**

## 목표

- 유저별로 하루(KST 달력 기준) 한 번, 관심 카테고니에 맞춰 개인화된 5개를 고정 배정한다.
- 배정된 5개는 그날 안에는 재계산되지 않는다 — 완독 추적이 안정적인 기준을 갖는다.
- 비로그인 사용자는 지금과 동일하게 실시간 전체 top-5를 본다(개인화 불가, 저장도 안 함).
- DB 부하는 늘지 않는다(오히려 줄어듦 — 아래 "성능" 참고).

## 아키텍처

### 새 테이블 `daily_selections`

```sql
CREATE TABLE daily_selections (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users(id),
    selection_date DATE NOT NULL,           -- KST 달력 날짜
    trend_item_id  UUID NOT NULL REFERENCES trend_items(id),
    rank           SMALLINT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, selection_date, rank),
    UNIQUE (user_id, selection_date, trend_item_id)
);
```

`selection_date`는 `Instant.now()`를 `Asia/Seoul`로 변환한 `LocalDate`. 저장 자체는 다른 테이블들과 동일하게 UTC(`created_at`)이고, `selection_date`만 KST 달력 개념을 담는 컬럼이다.

### 생성 시점: 지연 생성(lazy), 배치 아님

`GET /v1/trends?daily=true` 호출 시:

1. 로그인 유저의 오늘자(KST) `daily_selections` 행이 있으면 → 그 5개를 rank 순으로 반환.
2. 없으면 → 그 자리에서 계산해 저장 후 반환.

전체 유저를 도는 자정 배치를 만들지 않는다 — 앱을 안 켠 유저 것까지 미리 계산할 이유가 없고, 트리거 로직이 훨씬 단순하다.

### 선정 알고리즘

후보군은 기존과 동일(`PENDING`/`JUDGING` 상태 `TrendItem`). 정렬 기준만 바뀐다:

1. `TrendItem.category`가 `user_preferences.categories`(온보딩에서 고른 관심 카테고리 3개)에 속하는 항목 우선
2. 그 안에서 기존 `StageEvaluator.actionPriority`(단계 기준, "지금 행동해야 하는 순") 순
3. 카테고리 일치 항목이 5개 미만이면 나머지는 전체 후보군을 `actionPriority` 순으로 채운다

로그인했지만 `user_preferences`가 없는 예외적 상태(정상 흐름에서는 없어야 함 — 온보딩이 로그인+preferences 기반 게이팅이므로)면 카테고리 필터 없이 `actionPriority` 순으로만 계산한다.

### 하루 안에서 재조회 시 데이터 신선도

고정되는 건 **"구성 5개(어떤 `TrendItem`인가)"와 "그 안의 순서(rank)"** 뿐이다. 각 항목의 표시 데이터(단계, 의미 텍스트, 도달 플랫폼 수 등)는 그날 안에도 최신 값으로 보여준다 — `daily_selections`에 저장된 `trend_item_id` 5개를 **상태 필터 없이 ID로 직접 조회**해서 그때그때 `TrendSummaryResponse`를 만든다. (상태 필터를 걸면, 이미 배정된 항목이 하루 중 `RESOLVED`/`MERGED`로 바뀔 때 카드가 사라져 버려 "오늘 읽어야 할 5개"가 줄어드는 문제가 생긴다 — 배정된 건 그날 끝까지 보여준다.)

### 익명(비로그인) 사용자

개인화가 불가능하므로 지금과 동일한 동작 유지: 매 요청 실시간 전체 top-5, 아무것도 저장하지 않는다. `GET /v1/trends`는 현재 `permitAll`([PublicSecurityConfig.java](../../backend/api-public/src/main/java/kr/trendstage/apipublic/config/PublicSecurityConfig.java))이라 익명 접근을 계속 허용해야 한다.

### `daily=false`(전체 목록)

변경 없음 — "더보기" 목록은 원래도 고정 개념이 아니었으므로 그대로 실시간 계산 유지.

### `trend_reads`(완독 추적)

구조 변경 불필요. 이미 `(user_id, trend_item_id)` 키라 고정된 5개 셋과 자연히 맞물린다 — 지금까지는 셋 자체가 흔들려서 무의미했을 뿐, 테이블/API 자체는 그대로 재사용한다.

## 동시성

하루 첫 요청이 (앱 재실행, 탭 두 번 등으로) 거의 동시에 두 번 오면 같은 유저에 대해 5개를 두 번 계산해 넣으려 시도할 수 있다. `UNIQUE(user_id, selection_date, rank)` 제약을 건 뒤 `INSERT ... ON CONFLICT DO NOTHING`으로 저장을 시도하고, 그 트랜잭션 안에서 다시 `SELECT`해서 반환한다 — 둘 중 하나만 실제로 insert에 성공하고 나머지는 이미 저장된 결과를 읽게 되어 멱등하다.

## 성능

- **쓰기량**: DAU × 5행/일. 유저 10만 명이어도 하루 50만 행, 한 달 1500만 행 — 행 하나가 UUID 몇 개 + 숫자뿐이라 Postgres에 부담 없는 수준.
- **읽기량**: `UNIQUE(user_id, selection_date)` 인덱스 조회라 유저 수가 늘어도 조회 비용은 그대로.
- **연산 부하**: 후보군 전체를 가져와 정렬하는 연산이 지금은 **요청마다** 발생하는데, 이 설계에서는 **유저당 하루 1번**으로 줄어든다. 순부하는 오히려 감소.

## Out of Scope (향후 과제로만 기록)

- `daily_selections` 오래된 행 정리 배치(예: 90일 지난 행 삭제) — 지금 당장 필요하지 않음(YAGNI). 완독 추적/디버깅 목적 외엔 쓸모가 없어지는 시점에 붙이면 됨.
- 카테고리 일치 항목이 아예 없는 유저에 대한 별도 UX(현재는 그냥 전체 `actionPriority` 순으로 채워짐 — 문제로 판단되면 별도 논의).

## 영향받는 파일 (구현 단계에서 다룰 것)

- 신규: `backend/persistence/.../migration/V22__daily_selections.sql`
- 신규: `DailySelection` 엔티티, `DailySelectionRepository`
- 수정: `TrendQueryService.home(boolean daily)` — 로그인 여부에 따라 분기, 선정/조회 로직 추가
- 수정: `TrendController` — `Authentication`을 옵셔널로 받아 `home()`에 유저 ID 전달(현재 `permitAll`이므로 익명 요청 시 `Authentication`이 null일 수 있음 — 이미 다른 곳에서 쓰는 패턴 확인 필요)
- 프론트엔드 변경 불필요 예상(`GET /v1/trends?daily=true` 응답 형태는 동일 — 토큰이 있으면 자동으로 개인화된 응답을 받음)
