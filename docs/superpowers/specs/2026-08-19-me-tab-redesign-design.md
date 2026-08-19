# 나 탭 재구성 + 온보딩 게이팅 수정 — 설계

## 배경

"나" 탭(`MeScreen.tsx`)이 등급 카드 + (사실상 죽어있는) 통계 카드 + 원장만 있고, 로그아웃 버튼도 없어서 구성이 빈약하다는 문제 제기로 시작. 조사 중 두 가지 추가 문제를 발견:

1. `MeScreen`이 이미 `useMeSummary()`를 호출하고 있지만 `GET /v1/me/summary`가 백엔드에 없어서 항상 실패 — 통계 카드는 지금 조용히 안 뜬다.
2. `App.tsx`의 온보딩 게이트가 인메모리 `useState(false)`라서 **앱을 새로고침할 때마다, 로그인 여부와 무관하게** 관심 카테고리 선택 화면이 뜬다. 코드에 이미 "나중에 서버 preferences로 대체" TODO가 있었다.

이번 스펙은 이 둘을 함께 고친다 — 온보딩이 서버에 저장한 `Preferences` 존재 여부로 게이팅되도록 바꾸려면 어차피 `GET /v1/me/preferences`가 필요하고, 마침 나 탭에 설정 진입점을 추가하려는 참이라 같은 인프라를 공유한다.

## 목표 / 비목표

**목표:**
- 나 탭: 로그아웃, 등급 카드(쉬운 문구), 제보권 스트립(+ 스트릭 자리는 "개발 예정"), 예측 적중률, 워치 요약, 내 제보 현황 요약, 원장, 설정 진입점.
- 온보딩: 로그인한 사용자에 한해 최초 1회만. 비로그인 둘러보기는 건너뜀. 이미 preferences를 저장한 유저는 재로그인/재시작해도 다시 안 뜸.
- 설정 화면: 관심 카테고리·알림 시간을 온보딩 이후에도 재편집 가능.

**비목표 (이번 스코프 아님):**
- 스트릭(streakDays)·읽은 트렌드 수(totalRead) 실제 구현 — `reads` 테이블/일일 다이제스트 배치가 필요한 별도 서브프로젝트. 이번엔 자리만 만들고 "개발 예정" 문구.
- 제보권 소진 시 실제 제출 차단(enforcement) — 이번엔 표시만.

## 아키텍처

### 1. 등급 카드 문구 — `kind` 필드 추가 (백엔드 문구는 안 건드림)

`GradeRequirement`(domain-core, 순수 함수)의 `label`/`basis`는 관리자 콘솔의 "산정 근거 그대로 반환" 요구사항(CLAUDE.md)과 공유되므로 그대로 둔다. 대신 어떤 요구 항목인지 프론트가 안전하게 구분할 수 있도록 `kind` enum을 추가한다.

```java
// domain-core
public enum GradeRequirementKind { JUDGED_COUNT, TRUST_INDEX, ACTIVE_SCORE }
public record GradeRequirement(GradeRequirementKind kind, String label, double current, double required, boolean met, String basis) {}
```

`GradePolicy.evaluate()`가 `req()` 호출 시 kind를 함께 넘긴다. `GradeRequirementResponse`(api-public DTO)에도 `kind` 필드 추가. 프론트는 `kind`로 분기해 쉬운 문구를 렌더링하고, 백엔드가 준 `label`/`basis`(정확한 근거)는 화면에 노출하지 않는다(필요하면 나중에 "자세히" 토글로 노출 가능하게 남겨둠).

프론트 매핑 (예시, `current`/`required`는 API가 이미 raw 값으로 줌):
- `JUDGED_COUNT`: "판정 완료된 제보 {current}건 (목표 {required}건)"
- `TRUST_INDEX`: "예측 적중률 {current*100 반올림}% (목표 {required*100 반올림}%)"
- `ACTIVE_SCORE`: "활동 점수 {current 반올림}점 (목표 {required 반올림}점)"

### 2. `GET /v1/me/summary` — quotaUsed/quotaMax/voteHitRate만 실제 구현, streak/totalRead는 고정값

**제보권**: `SubmissionQuota`(domain-core, 신규 순수 함수/상수)에 등급별 주간 한도(CLAUDE.md 표: L0=2,L1=3,L2=5,L3=8,L4=12)를 정의. `quotaMax = SubmissionQuota.weeklyLimit(현재등급)`. `quotaUsed`는 이번 주 월요일 00:00 KST 이후 해당 유저의 VOID 아닌 제보 수 — `SubmissionRepository`에 파생 쿼리 추가:
```java
long countByUserIdAndCreatedAtAfterAndResultNot(UUID userId, Instant since, SubmissionResult excluded);
```
`since` 계산은 `QueueSummaryService`가 이미 쓰는 패턴(`ZoneId.of("Asia/Seoul")` + `atStartOfDay`) 그대로, 요일을 월요일로 back-off.

**예측 적중률**: `Vote`(userId, trendItemId, willTrend) × 해당 trend item의 "현재" 판정(`VerdictRepository.findCurrentByTrendItemId`, 재판정 체인 끝) 결과를 조합.
- 대상: 판정이 난(HIT 또는 MISS) trend item에 대한 투표만. VOID·미판정은 분모에서 제외.
- `correct = vote.willTrend == (verdict.result == HIT)`
- `votesTotal` = 판정 난 항목에 투표한 수, `votesCorrect` = 맞춘 수, `voteHitRate = votesCorrect / votesTotal`(0표면 0).
- 신규 `VoteRepository` 쿼리: 유저의 전체 투표 목록(`findByUserId`) — 개수가 많지 않을 유저 단위라 인메모리 조합 허용.

**streakDays / totalRead**: `reads` 테이블이 없어 계산 불가. `MeSummaryResponse`에는 필드를 유지하되 항상 `0`을 반환하고, **프론트는 이 두 값을 아예 읽지 않는다** — 나 탭의 스트릭 영역은 API 값과 무관한 고정 "개발 예정" 문구다. 이렇게 하면 나중에 실제 구현할 때 API 계약을 안 깨고 프론트만 갈아끼우면 된다.

### 3. `GET /v1/me/preferences` 신규 + `PUT` 신규 구현 (기존엔 컨트롤러 자체가 없었음)

조사 중 발견: OpenAPI엔 `PUT /v1/me/preferences`가 명세돼 있고 프론트(`OnboardingScreen`)도 이미 호출하지만, **백엔드에 테이블도 컨트롤러도 없어서 실제로는 조용히 실패하고 있었다.** 이번에 처음부터 만든다.

```sql
-- V20__user_preferences.sql
CREATE TABLE user_preferences (
    user_id UUID PRIMARY KEY REFERENCES user_accounts(id),
    categories TEXT[] NOT NULL,
    notify_hour SMALLINT NOT NULL CHECK (notify_hour BETWEEN 0 AND 23),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```
(1인 1행이라 append-only 원칙 R2 대상 아님 — 원장/판정과 달리 "설정값"은 최신값만 의미 있음. 기존 유저 설정 테이블들과 동일한 패턴.)

- `UserPreference` 엔티티 + `UserPreferenceRepository`(`findByUserId`).
- `MeController`에 `GET /v1/me/preferences`(없으면 404 — 온보딩 완료 여부 판단에 씀), `PUT /v1/me/preferences`(upsert) 추가.
- `Category`는 이미 정의된 6종 enum(MEME/PRODUCT/PERSON_CHANNEL/CHALLENGE/SLANG/ETC) 재사용.

### 4. 온보딩 게이팅 수정 (`App.tsx`)

현재: `useState(false)`로 시작 → 최상단에서 무조건 온보딩부터 렌더링(로그인 여부 무관, 새로고침마다 리셋).

변경 후 흐름:
```
AuthProvider 초기화 완료(initializing=false)
  ├─ user 없음(비로그인) → RootNavigator 바로 진입 (온보딩 스킵, 둘러보기 가능)
  └─ user 있음(로그인) → GET /v1/me/preferences 조회
        ├─ 200(이미 저장됨) → RootNavigator 진입
        └─ 404(아직 없음) → OnboardingScreen → 저장 성공 시 RootNavigator 진입
```
`useMyPreferences()` 훅 추가(react-query, 404를 에러가 아니라 "없음"으로 취급 — `ApiError.status === 404`일 때 `null` 반환하도록 `queryFn`에서 캐치). 로딩 중엔 스플래시(빈 화면 또는 기존 로딩 인디케이터) 유지.

### 5. 나 탭 구성 (`MeScreen.tsx`)

위에서 아래로:
1. 헤더: 이메일 + 로그아웃 버튼 (완료됨, 이번 스펙에 포함해 기록만)
2. 등급 카드 — `kind` 기반 쉬운 문구로 교체
3. 스트릭·제보권 스트립: 좌측 "🔥 스트릭 — 개발 예정" 고정 텍스트, 우측 "이번 주 제보권 {quotaUsed}/{quotaMax}" 실데이터
4. 통계 카드 1개: "예측 적중률 {voteHitRate}% ({votesCorrect}/{votesTotal})" — 기존 2칸 통계 카드에서 "읽은 트렌드" 칸 제거(스트릭과 같은 이유로 미구현)
5. 워치 요약: `useWatch()`(이미 구현됨) 재사용. 키워드 칩 최대 4~5개 + "N개 워치 중 · 전체 보기" → 워치 탭으로 이동
6. 내 제보 현황 요약: `useMySubmissions()`(이미 구현됨) 재사용. 클라이언트에서 status별 카운트 집계 — "판정 대기 {pending} · 적중 {hit} · 실패 {miss}"
7. 점수 원장 — 기존 그대로, 순서만 아래로
8. 하단 "설정" 진입점(작은 텍스트/아이콘 행) → `SettingsScreen`(신규, 관심 카테고리 멀티선택 + 알림 시간, `OnboardingScreen`의 입력 UI 재사용/추출)

### 6. `SettingsScreen` 신규

`OnboardingScreen`의 카테고리 선택 + 알림 시간 입력 UI를 그대로 쓰되, 진입 시 `useMyPreferences()`로 현재 값을 프리필한다. 저장은 기존 `useSavePreferences()`(PUT) 재사용. 네비게이션: 나 탭에서 모달 또는 스택 push로 진입(구현 시 네비게이션 구조 확인 후 결정 — `HomeStack`처럼 별도 스택 불필요, 나 탭 자체를 `MeStack`으로 승격하거나 모달 프레젠테이션 사용).

## 영향받는 파일 (개요)

**백엔드 신규:**
- `V20__user_preferences.sql`
- `persistence/entity/UserPreference.java`, `repo/UserPreferenceRepository.java`
- `domain-core/grade/GradeRequirementKind.java`
- `domain-core/submission/SubmissionQuota.java` (또는 유사 위치)

**백엔드 수정:**
- `domain-core/grade/GradeRequirement.java`(kind 필드), `GradePolicy.java`(kind 전달)
- `api-public/web/GradeRequirementResponse.java`(kind 필드)
- `api-public/service/MeService.java`(summary() 신규 메서드, preferences 위임)
- `api-public/web/MeController.java`(GET/PUT preferences, GET summary 라우트)
- `api-public/web/MeSummaryResponse.java`(신규 DTO)
- `persistence/repo/SubmissionRepository.java`(countByUserIdAndCreatedAtAfterAndResultNot)
- `persistence/repo/VoteRepository.java`(findByUserId 추가 여부 확인)

**프론트 신규:**
- `app/src/screens/SettingsScreen.tsx`
- `app/src/api/hooks.ts`(useMeSummary는 기존 재사용, useMyPreferences 추가)
- `app/src/api/types.ts`(GradeRequirement에 kind 추가, MeSummary는 기존 유지)

**프론트 수정:**
- `app/App.tsx`(온보딩 게이팅 로직)
- `app/src/screens/MeScreen.tsx`(전체 재구성)
- `app/src/screens/OnboardingScreen.tsx`(입력 UI를 공용 컴포넌트로 추출해 SettingsScreen과 공유 — 세부는 계획 단계에서 결정)
- `app/src/navigation/RootNavigator.tsx`(설정 화면 라우팅 추가)

## 미해결 상세 (계획 단계에서 결정)

- 나 탭 → 설정 화면 네비게이션 방식(모달 vs 스택 push vs 별도 탭 스택)은 계획 작성 시 `RootNavigator.tsx` 구조를 보고 확정.
- `OnboardingScreen`과 `SettingsScreen`의 입력 UI 공유 정도(완전 추출 vs 일부 복붙)는 계획 작성 시 `OnboardingScreen.tsx` 전체를 보고 결정.
