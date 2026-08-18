# ADM-010 오늘의 작업 — 백엔드 연결

## 배경

`TodayScreen.tsx`(진입 기본 화면)는 병합 검수(ADM-100)·어뷰징(ADM-300)·이의 제기(ADM-400)·신고 콘텐츠(ADM-410) 4개 큐의 대기 건수·SLA, 시스템 알림, 오늘 판정 예정·D+14 임박 건수, 시딩 비중을 보여주는 대시보드다. 대응하는 `GET /admin/queues/summary` 백엔드가 없어 지금은 fixture(`fxQueueSummary`)로만 렌더링된다.

4개 큐 중 실제 백엔드가 있는 건 병합 검수(ADM-100, `merge_queue` 테이블)뿐이다. 어뷰징·이의 제기·신고 콘텐츠는 `abuse_scan` 배치조차 아직 없어(스케줄러에 `ClusterMergeJob`/`GradeRecalcJob`/`VerdictRunner`만 존재) 이 화면만으로 새로 만들 수 없다.

## 목표

1. 병합 검수 큐의 실제 대기 건수·SLA 초과 여부를 반영한다.
2. 오늘 판정 예정·D+14 임박(24h)·시딩 비중을 실제 데이터로 계산한다.
3. 미구현 큐(어뷰징·이의 제기·신고)는 화면 구조를 유지한 채 0건 고정으로 표시해, 해당 큐가 실제로 구현되면 연결만 바꾸면 되게 한다.

## 범위

- 신규 DB 마이그레이션 없음 — 기존 `merge_queue`/`trend_items`/`submissions` 테이블의 기존 컬럼만 사용.
- 프론트엔드 변경 없음 — `admin/src/api/types.ts`의 `QueueSummary`, `admin/src/api/hooks.ts`의 `useQueueSummary()`, `TodayScreen.tsx`가 이미 이 스펙 그대로 구현돼 있다. 백엔드 응답 필드명만 기존 타입과 정확히 맞추면 끝.
- 어뷰징/이의 제기/신고 콘텐츠 실제 카운트는 범위 밖(해당 서브시스템 자체가 미구현).

## 아키텍처

### 데이터 소스 매핑

| 화면 항목 | 백엔드 소스 | 방식 |
|---|---|---|
| 병합 검수 건수·최장대기 | `MergeQueueRepository` | `PENDING` 카운트 + 가장 오래된 `created_at` |
| 어뷰징/이의제기/신고 카드 | 없음 | `count=0, oldest="-", slaExceeded=false` 고정 |
| SLA 초과 배너(`slaBreaches`) | 병합 검수만 | 24시간 초과 PENDING 건수 |
| 시스템 알림 | 병합 검수 SLA 초과분에서 파생 | 별도 테이블 없이 응답 조립 시 즉석 생성 |
| 오늘 판정 예정 | `TrendItemRepository` | 유효 판정 기한(`judgment_deadline_override ?? first_seen_at+14일`)이 오늘(KST 00:00~다음날 00:00) 안에 들어오는 PENDING/JUDGING 항목 수 |
| D+14 임박 24h | `TrendItemRepository` | 같은 유효 판정 기한이 지금부터 24시간 이내(롤링 윈도우)인 PENDING/JUDGING 항목 수 |
| 시딩 비중 | `SubmissionRepository` | 최근 7일 제보 중 `is_seed=true` 비율 |

유효 판정 기한 계산은 `VerdictRunner.isDue()`가 이미 쓰는 것과 동일한 규칙(`judgment_deadline_override ?? first_seen_at+14일`)이다. `VerdictRunner`를 직접 수정하지 않고 별도 계산을 두는 이유는 배치 코드(판정에 직접 영향)를 건드리지 않기 위해서다 — `VerdictAdminService`가 이미 같은 이유로 판정 로직을 부분 중복해 쓰고 있다(기존 코드 주석 참고).

## 백엔드 설계

### 순수 함수 (`domain-core` 모듈, 단위 테스트 대상)

```java
package kr.trendstage.domain.verdict;

/** 유효 판정 기한이 특정 시간창 안에 드는지 판정 — ADM-010 집계·VerdictRunner가 공유하는 순수 규칙. */
public final class DeadlineWindow {
    private DeadlineWindow() {}

    public static Instant effectiveDeadline(Instant firstSeenAt, Instant override) {
        return override != null ? override : firstSeenAt.plus(Duration.ofDays(14));
    }

    public static boolean fallsWithin(Instant deadline, Instant windowStart, Instant windowEndExclusive) {
        return !deadline.isBefore(windowStart) && deadline.isBefore(windowEndExclusive);
    }
}
```

### 신규 리포지토리 메서드

```java
// SubmissionRepository
long countByCreatedAtAfter(Instant since);
long countByCreatedAtAfterAndSeedTrue(Instant since);
```

병합 검수·판정 임박 조회는 기존 `MergeQueueRepository.findByStatusOrderByCreatedAtAsc`/`TrendItemRepository.findByStateIn`을 재사용한다(신규 JPQL 없음). 관리자 대시보드는 데이터량이 적어 인메모리 필터링 비용이 무시할 만하다 — 배치 코드(`VerdictRunner`)와 같은 판단 기준.

### `QueueSummaryService` (`api-admin` 모듈, 신규 패키지 `kr.trendstage.apiadmin.queues`)

```java
@Service
public class QueueSummaryService {
    private static final int MERGE_SLA_HOURS = 24;
    private static final int SEED_RATIO_WINDOW_DAYS = 7;

    private final MergeQueueRepository mergeQueue;
    private final TrendItemRepository trendItems;
    private final SubmissionRepository submissions;
    private final Clock clock;

    // 생성자 주입

    public QueueSummaryResponse summarize() {
        Instant now = clock.instant();
        List<MergeQueueEntry> pending = mergeQueue.findByStatusOrderByCreatedAtAsc(MergeQueueStatus.PENDING);
        long mergeOverdue = pending.stream()
                .filter(e -> Duration.between(e.getCreatedAt(), now).toHours() >= MERGE_SLA_HOURS)
                .count();
        String oldest = pending.stream().map(MergeQueueEntry::getCreatedAt)
                .min(Comparator.naturalOrder())
                .map(t -> formatAgo(t, now)).orElse("-");

        List<TrendItem> active = trendItems.findByStateIn(List.of(TrendState.PENDING, TrendState.JUDGING));
        ZoneId kst = ZoneId.of("Asia/Seoul");
        Instant todayStartKst = now.atZone(kst).toLocalDate().atStartOfDay(kst).toInstant();
        Instant todayEndKst = todayStartKst.plus(Duration.ofDays(1));
        long judgedToday = active.stream()
                .filter(i -> DeadlineWindow.fallsWithin(
                        DeadlineWindow.effectiveDeadline(i.getFirstSeenAt(), i.getJudgmentDeadlineOverride()),
                        todayStartKst, todayEndKst))
                .count();
        long imminent24h = active.stream()
                .filter(i -> DeadlineWindow.fallsWithin(
                        DeadlineWindow.effectiveDeadline(i.getFirstSeenAt(), i.getJudgmentDeadlineOverride()),
                        now, now.plus(Duration.ofHours(24))))
                .count();

        Instant seedWindowStart = now.minus(Duration.ofDays(SEED_RATIO_WINDOW_DAYS));
        long seedCount = submissions.countByCreatedAtAfterAndSeedTrue(seedWindowStart);
        long totalCount = submissions.countByCreatedAtAfter(seedWindowStart);
        double seedRatio = totalCount == 0 ? 0.0 : (double) seedCount / totalCount;

        List<QueueTile> queues = List.of(
                new QueueTile("ADM-100", "병합 검수", pending.size(), oldest, mergeOverdue > 0),
                new QueueTile("ADM-300", "어뷰징", 0, "-", false),
                new QueueTile("ADM-400", "이의 제기", 0, "-", false),
                new QueueTile("ADM-410", "신고 콘텐츠", 0, "-", false)
        );
        List<Alert> alerts = mergeOverdue == 0 ? List.of() : List.of(
                new Alert("병합 검수 큐 SLA 초과 " + mergeOverdue + "건", "24시간 기준 초과, 미처리 시 판정 유예 자동 연장")
        );

        return new QueueSummaryResponse((int) mergeOverdue, queues, alerts,
                seedRatio, (int) judgedToday, (int) imminent24h);
    }

    private static String formatAgo(Instant t, Instant now) {
        Duration d = Duration.between(t, now);
        if (d.toHours() < 1) return d.toMinutes() + "분";
        return d.toHours() + "h";
    }
}
```

### DTO (`QueueSummaryController` 내부 record, `MergeQueueController` 패턴과 동일)

```java
public record QueueTile(String id, String name, int count, String oldest, boolean slaExceeded) {}
public record Alert(String title, String detail) {}
public record QueueSummaryResponse(int slaBreaches, List<QueueTile> queues, List<Alert> alerts,
                                    double seedRatio, int judgedToday, int imminent24h) {}
```

필드명은 프론트 `QueueSummary` 타입(`admin/src/api/types.ts`)과 정확히 일치시킨다(Jackson 기본 직렬화가 record 컴포넌트명을 그대로 camelCase JSON 키로 씀 — 별도 매핑 불필요).

### 엔드포인트

```java
@RestController
@RequestMapping("/admin/queues")
public class QueueSummaryController {
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN', 'AUDITOR')")
    public QueueSummaryResponse summary() { return service.summarize(); }
}
```

`MergeQueueController.list()`와 동일 권한(대시보드 조회이므로 4개 역할 전부 허용).

## 예외 처리

이 엔드포인트는 순수 집계 조회라 실패할 비즈니스 로직이 없다 — DB 연결 실패 등은 Spring 기본 500 처리로 충분, 별도 커스텀 예외 불필요. `totalCount == 0`일 때 0으로 나누기는 삼항 연산자로 가드한다.

## 테스트 계획

- **백엔드 단위**: `DeadlineWindow`를 `DeadlineWindowTest`로 검증 — override 있는/없는 항목, 윈도우 경계값(정확히 시작·끝 시각), override가 firstSeenAt+14일보다 이르거나 늦은 경우.
- **서비스**: 시딩 비율 0건일 때 0.0 반환(0으로 나누기 방지) 케이스는 `totalCount == 0` 가드 로직만 있어 별도 Spring 컨텍스트 테스트 불필요(순수 삼항식이라 코드 리뷰로 갈음).
- **브라우저**: 병합 검수 큐에 PENDING이 있을 때 카운트 반영 확인, 24시간 넘은 항목이 있을 때 SLA 배너·알림 표시 확인, 어뷰징/이의제기/신고 카드가 0건으로 고정 표시되는지 확인.

## 비범위 (Out of scope)

- 어뷰징/이의 제기/신고 콘텐츠 실제 카운트 (해당 서브시스템 미구현)
- `VerdictRunner.isDue()`를 `DeadlineWindow`로 리팩터링하는 것 (배치 코드는 그대로 두고, 새 계산만 공유 순수 함수를 씀 — 기존 배치 로직 변경은 이번 스코프 밖)
- 알림(alerts)을 별도 테이블로 영속화하는 것 (매 요청마다 즉석 계산)
