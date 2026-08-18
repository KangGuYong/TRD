# ADM-110/111 트렌드 항목 목록/상세 — 설계

## 배경

관리자 콘솔의 "트렌드" 메뉴 그룹에는 항목 목록(ADM-110)과 항목 상세(ADM-111)가 있어야 하지만, 지금은 둘 다 `Layout.tsx`에 `stub`로만 존재한다. `02-admin-console.md`에는 ADM-111의 와이어프레임만 있고 ADM-110(목록)은 우선순위 표에만 이름이 있을 뿐 세부 설계가 없다.

기존 ADM-200(판정 관리)이 "예외 처리 전용"으로 스코프가 좁은 것과 달리, ADM-110/111은 모든 상태(DRAFT~MERGED)의 트렌드 항목을 훑어보고 개별 항목의 제보 시계열·예상 판정을 들여다보는 범용 브라우징 화면이다.

## 목표

1. 전체 트렌드 항목을 상태·카테고리로 필터링하고 이름으로 검색할 수 있는 목록 화면을 만든다.
2. 개별 항목의 제보 시계열(제보자·확신도·선점순위·TI)과 "현재 시점 기준 예상 판정"을 보여주는 상세 화면을 만든다.
3. 목록에서 상세로, 상세에서 기존 ADM-200 액션(VOID·유예 연장)으로 이어지는 흐름을 만든다.

## 범위

**포함**: 목록 조회, 상세 조회(제보 시계열 + 드라이런 예상 판정), 기존 VOID/유예 연장 액션 재사용.

**제외**:
- **항목 분리(병합 취소)** — ADM-111 와이어프레임의 세 번째 버튼이지만, 코드베이스 어디에도 절차가 없고 `03-merge-clustering.md`에도 없어 별도 사이클로 분리하기로 결정(사용자 확인 완료).
- ADM-110 서버사이드 페이지네이션/검색 — 관리자 콘솔 규모(Phase 0/1)에서는 클라이언트 필터링으로 충분, 기존 ADM-100/ADM-200과 동일한 패턴.
- 제보 이력 무한스크롤.

## 아키텍처

- **ADM-110**은 `TrendItemRepository.findAll()`로 전체 항목을 가져와 클라이언트에서 필터링한다. 데이터량이 적어 서버 필터링/페이지네이션 없이도 충분하다는 점은 이미 `VerdictScreen`(ADM-200)이 증명한 패턴이다.
- **ADM-111**의 "현재 T = … → 예상 판정"은 실제로 판정을 확정하지 않고 `VerdictComputation.run()`(순수 함수, `domain-core`)을 **드라이런**으로 호출해서 얻는다. `VerdictAdminService.applySupersede()`가 `SubmissionSignal`/`SubmissionRef` 리스트를 조립하는 것과 동일한 방식을 그대로 따르되, 결과를 저장하지 않고 반환만 한다. 이미 판정이 확정된 항목(RESOLVED/VOID)은 미리보기 대신 확정 결과를 보여준다.
- **VOID·유예 연장 액션은 신규 백엔드가 필요 없다** — 이미 `VerdictController`(`/admin/verdicts/{id}/void`, `/admin/verdicts/{id}/extend-grace`)에 구현돼 있으므로 프론트에서 그대로 호출한다.
- **제보자 TI 표시**는 `UserGradeRepository.findTopByUserIdOrderByComputedAtDesc()`(주간 스냅샷)를 재사용한다. ADM-500의 시딩 적중률과 달리 여기서는 운영 의사결정에 직접 쓰이는 값이 아니라 참고용 표시일 뿐이므로, 이미 ADM-100 병합 미리보기가 쓰는 것과 같은 신선도(주간 스냅샷)로 충분하다 — 실시간 계산을 새로 만들지 않는다.

## 백엔드 설계

### 신규 컨트롤러 (`TrendItemController`, 신규 패키지 `kr.trendstage.apiadmin.trend`)

```java
@RestController
@RequestMapping("/admin/trend-items")
public class TrendItemController {

    private final TrendItemAdminService service;

    public TrendItemController(TrendItemAdminService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN', 'AUDITOR')")
    public List<TrendItemAdminService.TrendItemSummary> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN', 'AUDITOR')")
    public TrendItemAdminService.TrendItemDetail detail(@PathVariable UUID id) {
        return service.detail(id);
    }
}
```

조회 전용 화면이라 `MergeQueueController.list()`/`VerdictController.list()`와 동일하게 4개 역할 전부 허용.

### DTO (`TrendItemAdminService` 내부 record)

```java
public record TrendItemSummary(String id, String canonicalName, String category, String state,
                                String firstSeenAt, int submitterCount, String currentResult) {}

public record SubmissionRow(String submissionId, String userHandle, int orderRank,
                             int confidence, Double submitterTi, String createdAt) {}

public record TrendItemDetail(
        String id, String canonicalName, String category, String state,
        String firstSeenAt, String deadline, long daysLeft, boolean graceExtended,
        int distinctSubmitters, int distinctPlatforms, long endorseCount,
        String currentResult, String currentReachLevel, String currentScoreT, String currentJudgedAt,
        String previewResult, String previewReachLevel, String previewScoreT,
        List<SubmissionRow> submissions) {}
```

`currentResult` 계열은 이미 판정이 확정된 경우(RESOLVED/VOID)만 채워지고, `previewResult` 계열은 미판정(그 외 상태)일 때만 채워진다 — 화면은 둘 중 존재하는 쪽만 보여준다.

### `TrendItemAdminService` (`api-admin` 모듈, 신규 패키지 `kr.trendstage.apiadmin.trend`)

```java
@Service
public class TrendItemAdminService {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final TrendItemRepository trendItems;
    private final SubmissionRepository submissions;
    private final SubmissionOrderRankRepository orderRanks;
    private final VerdictRepository verdicts;
    private final UserRepository users;
    private final UserGradeRepository userGrades;
    private final EndorsementRepository endorsements;
    private final Clock clock;

    // 생성자 주입

    @Transactional(readOnly = true)
    public List<TrendItemSummary> list() {
        return trendItems.findAll().stream()
                .sorted(Comparator.comparing(TrendItem::getFirstSeenAt).reversed())
                .map(item -> {
                    long submitterCount = submissions.countByTrendItemIdAndResultNot(item.getId(), SubmissionResult.VOID);
                    Verdict current = verdicts.findCurrentByTrendItemId(item.getId()).orElse(null);
                    return new TrendItemSummary(
                            item.getId().toString(), item.getCanonicalName(), item.getCategory().name(), item.getState().name(),
                            DISPLAY_FORMAT.format(item.getFirstSeenAt()), (int) submitterCount,
                            current == null ? null : current.getResult().name());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public TrendItemDetail detail(UUID trendItemId) {
        TrendItem item = trendItems.findById(trendItemId)
                .orElseThrow(() -> new AdminValidationException("존재하지 않는 항목입니다"));

        List<SubmissionOrderRank> ranks = orderRanks.findByTrendItemId(trendItemId);
        Map<UUID, Integer> rankById = ranks.stream()
                .collect(Collectors.toMap(SubmissionOrderRank::getSubmissionId, SubmissionOrderRank::getOrderRank));
        List<Submission> subs = submissions.findByTrendItemIdAndResultNot(trendItemId, SubmissionResult.VOID);

        Instant now = clock.instant();
        List<SubmissionRef> refs = subs.stream().map(s -> new SubmissionRef(
                s.getId(), s.getUserId(), s.getConfidence(),
                rankById.getOrDefault(s.getId(), Integer.MAX_VALUE),
                Duration.between(s.getCreatedAt(), now).toDays())).toList();
        long distinctSubmitters = subs.stream().map(Submission::getUserId).distinct().count();
        long distinctPlatforms = subs.stream().map(Submission::getSourcePlatform).filter(Objects::nonNull).distinct().count();
        SubmissionSignal signal = new SubmissionSignal((int) distinctSubmitters, (int) distinctPlatforms);

        Verdict current = verdicts.findCurrentByTrendItemId(trendItemId).orElse(null);
        VerdictPlan preview = current == null
                ? VerdictComputation.run(signal, false, refs, ParameterSet.defaults())
                : null;

        List<SubmissionRow> rows = subs.stream()
                .sorted(Comparator.comparingInt(s -> rankById.getOrDefault(s.getId(), Integer.MAX_VALUE)))
                .map(s -> new SubmissionRow(
                        s.getId().toString(),
                        users.findById(s.getUserId()).map(UserAccount::getHandle).orElse("-"),
                        rankById.getOrDefault(s.getId(), -1), s.getConfidence(),
                        userGrades.findTopByUserIdOrderByComputedAtDesc(s.getUserId())
                                .map(g -> g.getTrustIndex().doubleValue()).orElse(null),
                        DISPLAY_FORMAT.format(s.getCreatedAt())))
                .toList();

        Instant deadline = DeadlineWindow.effectiveDeadline(item.getFirstSeenAt(), item.getJudgmentDeadlineOverride());

        return new TrendItemDetail(
                item.getId().toString(), item.getCanonicalName(), item.getCategory().name(), item.getState().name(),
                DISPLAY_FORMAT.format(item.getFirstSeenAt()), DISPLAY_FORMAT.format(deadline),
                Duration.between(now, deadline).toDays(), item.getJudgmentDeadlineOverride() != null,
                (int) distinctSubmitters, (int) distinctPlatforms, endorsements.countByTrendItemId(trendItemId),
                current == null ? null : current.getResult().name(),
                current == null || current.getReachLevel() == null ? null : current.getReachLevel().name(),
                current == null || current.getScoreT() == null ? null : current.getScoreT().toPlainString(),
                current == null ? null : DISPLAY_FORMAT.format(current.getJudgedAt()),
                preview == null ? null : preview.result().name(),
                preview == null || preview.reach() == null ? null : preview.reach().name(),
                preview == null ? null : BigDecimal.valueOf(preview.t()).setScale(4, RoundingMode.HALF_UP).toPlainString(),
                rows);
    }
}
```

`DeadlineWindow.effectiveDeadline()`은 ADM-010에서 이미 `domain-core`에 추출된 순수 함수를 그대로 재사용한다(신규 로직 없음).

## 프론트엔드 설계

기존 화면 스캐폴딩이 없으므로 처음부터 만든다. 목록→상세 이동은 `TodayScreen`의 `goMerge` 콜백 패턴을 확장해, `App.tsx`가 선택된 `trendItemId`를 상태로 들고 상세 화면에 넘긴다 — 이 코드베이스에 아직 없던 "목록→상세" 네비게이션 패턴을 이번에 도입한다.

### `admin/src/api/types.ts` 추가

```ts
export interface TrendItemSummary {
  id: string; canonicalName: string; category: string; state: string;
  firstSeenAt: string; submitterCount: number; currentResult: string | null;
}
export interface SubmissionRow {
  submissionId: string; userHandle: string; orderRank: number;
  confidence: number; submitterTi: number | null; createdAt: string;
}
export interface TrendItemDetail {
  id: string; canonicalName: string; category: string; state: string;
  firstSeenAt: string; deadline: string; daysLeft: number; graceExtended: boolean;
  distinctSubmitters: number; distinctPlatforms: number; endorseCount: number;
  currentResult: string | null; currentReachLevel: string | null; currentScoreT: string | null; currentJudgedAt: string | null;
  previewResult: string | null; previewReachLevel: string | null; previewScoreT: string | null;
  submissions: SubmissionRow[];
}
```

### `admin/src/fixtures.ts` 추가

- `fxTrendItems: TrendItemSummary[]` — 5~6건, 상태(PENDING/JUDGING/RESOLVED/VOID/DRAFT)를 섞어서.
- `fxTrendItemDetail: TrendItemDetail` — 제보 3~4건, `previewResult`가 채워진 PENDING 예시로 고정(목록의 어떤 행을 눌러도 동일 픽스처가 뜨는 것은 `MergeQueueScreen`의 기존 미리보기 픽스처 패턴과 동일한 절충).

### `admin/src/api/hooks.ts` 추가

```ts
export const useTrendItems = () =>
  useData<TrendItemSummary[]>(["admin", "trend-items"], "/admin/trend-items", fx.fxTrendItems);

export const fetchTrendItemDetail = (id: string) =>
  api.get<TrendItemDetail>(`/admin/trend-items/${id}`);
```

### `admin/src/screens/TrendListScreen.tsx` (신규)

`props: { onSelect: (id: string) => void }`. 상단에 상태 필터 탭(전체/PENDING·JUDGING/RESOLVED/VOID)과 텍스트 검색(이름 부분일치), 카테고리 드롭다운 — 전부 클라이언트 필터링(`VerdictScreen`처럼 서버 페이지네이션 없음). 행 클릭 시 `onSelect(item.id)`.

### `admin/src/screens/TrendDetailScreen.tsx` (신규)

`props: { trendItemId: string | null; onBack: () => void }`.

- `trendItemId`가 없으면 API 호출 없이 "목록에서 항목을 선택하세요" + 목록으로 버튼만 표시.
- 있으면 `useEffect`에서 `USE_FIXTURES ? fx.fxTrendItemDetail : await fetchTrendItemDetail(id)`로 로드(`MergeQueueScreen`의 미리보기 로딩과 동일 패턴).
- 표시 구성: 헤더(이름/상태/D+N 뱃지) → 제보 시계열 요약(`distinctSubmitters`/`distinctPlatforms`/`endorseCount`) → `previewResult`가 있으면 "현재 T = … → 예상 판정: …(확정 아님, D+14 재계산)" 배너, `currentResult`가 있으면 대신 확정 판정 표시 → 제보 이력 테이블(order/handle/confidence/TI/시각) → 액션 버튼.
- 액션: `CAN.void(role)` 게이트로 [VOID 처리]/[유예 연장] — `VerdictScreen`의 다이얼로그(사유 필수, 유예는 일수 입력)와 `voidVerdict`/`extendVerdictGrace` 훅을 그대로 재사용(신규 API 함수 없음).

### 라우팅

`Layout.tsx`의 `ScreenId`에 `"ADM-110"` `"ADM-111"` 추가, NAV의 "트렌드" 그룹의 `stub`("항목 목록") 항목을 실제 화면으로 교체(ADM-111은 목록에서만 진입하므로 NAV에는 노출하지 않음). `App.tsx`에 상태 추가:

```tsx
const [selectedTrendItemId, setSelectedTrendItemId] = useState<string | null>(null);
...
{screen === "ADM-110" && <TrendListScreen onSelect={(id) => { setSelectedTrendItemId(id); setScreen("ADM-111"); }} />}
{screen === "ADM-111" && <TrendDetailScreen trendItemId={selectedTrendItemId} onBack={() => setScreen("ADM-110")} />}
```

## 에러 처리

| 상황 | 처리 |
|---|---|
| 존재하지 않는 `trendItemId` 상세 조회 | `AdminValidationException("존재하지 않는 항목입니다")` → 기존 핸들러가 422로 매핑(신규 매핑 불필요) |
| 상세 화면에 `trendItemId`가 없는 상태로 진입 | API 호출 없이 "목록에서 항목을 선택하세요" + 목록으로 버튼만 표시 |
| VOID·유예 연장 실패(사유 누락, 이미 VOID 등) | `VerdictScreen`이 이미 쓰는 `catch (e) { setError(e instanceof ApiError ? e.message : ...) }` 패턴 재사용 |
| 목록 필터 결과 0건 | "조건에 맞는 항목이 없습니다" 안내 문구 |

## 테스트 계획

- **백엔드**: `TrendItemAdminService`는 api-admin의 기존 서비스들(ParamStudioService, VerdictAdminService, AdminSeedService)과 동일하게 자동 통합 테스트 없이 수동 E2E로 검증(이 모듈에 테스트 인프라가 없다는 기존 전례를 따름). `DeadlineWindow` 재사용 부분은 이미 `DeadlineWindowTest`로 커버돼 있어 추가 테스트 불필요.
- **브라우저(fixture 모드)**: 목록 필터(상태 탭·카테고리·검색) 동작, 행 클릭 시 상세로 이동, 상세에서 `previewResult`/`currentResult` 중 하나만 표시되는지, VOID/유예 연장 다이얼로그가 뜨는지 확인.
- **브라우저(실 DB)**: 미판정 항목 하나를 골라 상세 조회 → `previewResult`/`previewScoreT`가 채워지고 직접 SQL로 계산한 기대 T값과 일치하는지 검산. RESOLVED 항목은 `currentResult`만 채워지고 `previewResult`가 null인지 확인. 상세에서 VOID 처리 후 목록에 반영되는지 확인(쿼리 무효화).

## 비범위 (Out of scope)

- 항목 분리(병합 취소) — 별도 사이클로 분리
- ADM-110 서버사이드 페이지네이션/검색
- 제보 이력 무한스크롤
