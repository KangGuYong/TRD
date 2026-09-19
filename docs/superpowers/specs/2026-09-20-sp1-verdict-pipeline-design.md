# SP1 판정 파이프라인 정합성 — 판정 저장·시딩 제외·VOID 사건화·제보권·원장 원값

- 작성일: 2026-09-20
- 상태: 검토 대기 (O11 = (b), 개발 DB 초기화는 2026-09-20 사용자 결정)
- 상위 스펙: `2026-09-17-design-review-design.md` §3 SP1 (§2.2 P2·P3·P5·P6, §2.3 판정 파이프라인)
- 브랜치: `sp1-verdict-pipeline` (base `main` 2e5bd9c)

## 0. 범위

판정이 DB에 남고, 시딩·VOID·제보권이 설계대로 동작하게 만든다. 판정 **공식**(T = 제보자 수 / 목표치)은 바꾸지 않는다 — 공식은 SP4.

| # | 항목 | 지금의 문제 (근거) |
|---|---|---|
| 1 | 판정 저장 — `JudgeService` 신설, `JUDGING` 전이 | `VerdictRunner.java:70`이 같은 빈의 `@Transactional judgeOne()`(`:87-88`)을 직접 호출 → 트랜잭션 없음. `submissions.result`·`trend_items.state`가 flush되지 않아 TI 0.4·판정완료 0에 고정, 승급 불가 |
| 2 | 시딩 제외 (P2) | `VerdictRunner.java:108`이 시딩을 제보자 수에 포함 — 운영진 시딩만으로 HIT. 선점 순위 뷰(`V11`)도 시딩을 셈 — 시딩이 먼저 들어가면 실유저 선점 가중치가 깎인다 |
| 3 | VOID 사건화 (P5) | `VerdictRunner.java:114` `voidByRule=false` 고정 — 유효 제보 0건도 MISS. ADM-100 VOID(`MergeService.java:82`)는 판정된 항목이어도 원장을 그대로 둔 채 제보만 VOID |
| 4 | 제보권 집행·반환·리필 (P3) | `SubmissionService.java:27` TODO — 한도 표시(`/v1/me/summary`)만 있고 막지 않는다. VOID 반환·리필 없음 |
| 5 | 원장 원값 + 조회 감쇠 (O11 = b) | `ScoreEngine.java:24`가 Δ에 감쇠를 곱해 기록하고 `ActiveScore.java:22`가 다시 곱한다(이중 적용) |
| 6 | 재판정 정합 | `VerdictAdminService.java:137` `ParameterSet.defaults()`. **신규 발견 둘**: ADJ(`:185`)에 verdict·submission이 없어 두 번째 재판정부터 이전 ADJ를 못 보고 전액을 다시 싣는다(HIT +50 → MISS 재판정 −65, 한 번 더 재판정하면 −15가 또 붙어 합계 −30). ADJ 대상에 시딩 유저가 들어간다(`:176`) |
| 7 | 멱등·일관성 DB 제약 | `score_ledger(verdict_id, submission_id)` UNIQUE 없음. 재판정 체인이 분기할 수 있다(같은 판정을 두 판정이 supersede) |
| 8 | `TrendSignal` 확장 + 판정 근거 동결 | SP4가 파이프라인을 다시 열지 않게 원자료를 지금 담는다 |
| 9 | 등급·TI 정합 | TI가 전 기간(`GradeRecalcJob.java:73`, 01 §5.2는 180일). `/v1/me`는 매번 `defaults()`로 등급을 실시간 계산(`MeService.java:147`) — 주간 스냅샷, 그리고 제보권 한도가 따라야 할 등급과 다르다 |
| 10 | 관측 마감 후 제보 차단 (**신규 발견**) | `SubmissionService.java:53`이 항목 상태·마감을 보지 않는다 — 판정이 끝난 항목에 붙은 제보는 영원히 PENDING이고, 제보권이 생기면 한 장을 헛되이 쓴다 |

**비범위**
- 판정 신호 공식·상대 목표치(P9)·플랫폼 enum·동의(endorse) 반영 → SP4
- 병합 잠금·RESOLVED 병합 409·정규화·tombstone 추종 → SP2
- `ApprovalGate`(재판정 ADJ 100점 초과 2인 승인)·`sla_watch`·ADM-311 수동 ADJ → SP3
- 강등(Phase 2), L4 정원(Phase 3)
- 가입 7일 + 본인인증 전 제보 제한(01 §3.3 L0 비고) — 본인인증 기능이 없어 별도 과제
- 파라미터 스튜디오 편집 항목 확대(현재 `submitterTarget`·`hitThreshold`만) — 반감기 편집은 J1 덕분에 나중에 붙여도 과거 AS가 흔들리지 않는다

**실행 순서**: 도메인(§2) → 스키마 V28(§3) → `JudgeService`(§4) → 제보권·등급(§5) → 통합 테스트(§7) → 프론트(§6) → 문서(§8). 백엔드를 먼저 끝내고 프론트는 실제 응답에 맞춘다(상위 스펙 §4 규칙 1).

**개발 DB 초기화 (2026-09-20 결정)**: 기존 원장은 감쇠가 곱해진 값이고 판정 근거 JSON 형식도 바뀌므로 비우고 시작한다. Docker는 `docker compose down` 후 `docker volume rm infra_trd-db`(임베딩 모델 캐시는 유지). V28은 기존 판정·원장 행이 있으면 기동을 멈추고 이 안내를 출력한다(J7).

## 1. 이 스펙에서 정한 것

J1은 사용자 결정, J2~J7은 이 스펙의 설계 결정이다. 검토 때 특히 봐 줄 것.

| # | 결정 | 이유 |
|---|---|---|
| J1 | **O11 = (b)**: 원장 행마다 `halflife_days`와 `decay_anchor_at`을 기록하고, AS는 행별 값으로 감쇠한다 | 파라미터를 바꿔도 과거 AS가 흔들리지 않는다(ADM-600 비소급). 반감기 변경으로 등급이 바뀌면 이의 제기 대응이 어렵다 |
| J2 | **재판정은 원 판정 때 동결한 파라미터로** 다시 계산한다. 새 파라미터의 소급 적용은 ADM-600 RETROACTIVE 경로(2인 승인 + 사유서)만 | 재판정의 목적은 입력 변화(VOID된 제보 등) 반영이다. 현재 값으로 재판정하면 "파라미터 변경 → 골라서 재판정"으로 과거 결과를 선택적으로 뒤집을 수 있다(R1). 04 §7.2의 "승인된 파라미터로 재실행"을 이 뜻으로 고친다 — 동결값도 판정 당시 승인·적용된 값이다 |
| J3 | 시딩은 **T와 선점 순위 모두에서 제외**한다. `first_seen_at`(관측 시계)에는 계속 반영한다 | P2 "시딩은 클러스터 앵커 역할만". 시딩이 1위를 차지하면 실유저의 `w_order`가 깎인다 — 운영진이 점수에 개입하는 경로다 |
| J4 | 제보권은 **저장 카운터 없이 제보 행에서 파생**한다: 이번 주 사용 = 이번 주 제출 − 이번 주 VOID 반환. 리필은 주 경계(월 00:00 KST) 자체. 동시성은 유저 행 잠금 | 차감·반환·리필 배치가 따로 없으니 서로 어긋날 수 없다. VOID와 반환이 같은 트랜잭션(`voided_at` 기록)이라 01 §3.3의 "판정과 같은 트랜잭션 경계"를 자연히 만족한다 |
| J5 | **공식 등급 = 주간 스냅샷**(`user_grade_current`). 제보권 한도와 앱의 현재 등급이 같은 값을 쓰고, 다음 등급까지의 진행 상황만 실시간으로 계산한다 | 01 §3.3 "표시와 집행을 같은 소스로". 실시간 등급과 스냅샷이 다르면 "L1인데 제보권은 L0"이 생긴다 |
| J6 | **관측 마감**(기본 D+14, 유예 연장 반영) 이후와 `JUDGING`·`RESOLVED`·`VOID` 항목에는 새 제보를 받지 않는다(422, 제보권 차감 없음). 판정 신호도 마감 전 제보만 센다 | "2주간 후속 제보"가 판정 창이다. 지금은 마감 뒤~배치 실행 전(최대 하루) 제보도 T에 들어가고, 판정 뒤 제보는 영원히 PENDING이 된다 |
| J7 | V28은 기존 `verdicts`·`score_ledger` 행이 있으면 **예외로 중단**한다 | 초기화 결정을 코드로 강제 — 감쇠된 옛 원장과 원값 원장이 섞이는 것을 막는다 |

## 2. 도메인 (domain-core, 순수 함수)

### 2.1 `TrendSignal` (`SubmissionSignal` 대체)

```java
public record TrendSignal(Instant deadline, List<Entry> entries) {
    public record Entry(UUID submissionId, UUID userId, boolean seed,
                        Instant submittedAt, String platform, Instant userJoinedAt) {}
    public int validCount()          // entries.size() — VOID는 애초에 넣지 않는다
    public int distinctSubmitters()  // 시딩 제외 distinct userId → T의 입력(P2, J3)
    public int distinctPlatforms()   // 시딩 제외 distinct platform(현행 자유 텍스트) — SP4 전까지 판정에 안 씀
}
```

- `entries` = 관측 마감 전에 들어온 비VOID 제보 전부(J6). **시딩도 넣는다** — "유효 제보 0건" 판정과 시딩 정확도(ADM-500) 결과 기록에 필요하고, 계산 메서드가 시딩을 뺀다.
- SP4가 쓸 원자료(제보 시각·플랫폼·제보자 가입일)를 지금 담는다. 디바이스 해시는 수집 경로가 없어 SP4가 필드를 더한다(레코드 확장이라 호출부 영향 없음).

### 2.2 `VerdictEngine`·`VerdictComputation`

- `VerdictEngine.evaluate(TrendSignal, ParameterSet)` — 공식은 그대로 `T = clip(n / submitterTarget)`, n만 시딩 제외.
- `VerdictComputation.run(TrendSignal signal, List<SubmissionRef> refs, ParameterSet p)` — **`voidByRule` 삭제**(P5).
  - `signal.validCount() == 0` → VOID, 원장 라인 없음. 판정 시점의 VOID는 이것뿐이다.
  - 그 외 HIT/MISS. 원장 라인은 **시딩이 아닌 제보만** 만든다 — "시딩은 원장 미반영"을 영속 코드(`VerdictRunner.java:129`)가 아니라 순수 함수가 보장한다.
- `SubmissionRef(submissionId, userId, confidence, orderRank)` — `elapsedDays` 삭제(감쇠는 조회 관심사), `orderRank`는 시딩 제외 순위(J3).
- `VerdictPlan(result, reach, t, ledgerLines)`. 제보 `result`는 `plan.result`를 유효 제보 전부(시딩 포함)에 적용한다.

### 2.3 `ScoreEngine` — 감쇠 제거

```
HIT : Δ = c × w_order × (1 + m)        MISS: Δ = −c × 0.5        VOID: 0
```

산정 근거 문자열에서 "× 감쇠 d" 항을 뺀다 — 예 `HIT L3 · 확신도 30 × 선점 1위(1.0) × 확산 ×2.0 = +60.0`. MISS가 HIT 기준의 절반인 비대칭은 그대로.

### 2.4 `ActiveScore` — 행별 감쇠 (J1)

```java
public record Aged(double delta, long ageDays, int halflifeDays) {}
// AS = Σ delta_i × 0.5^(ageDays_i / halflifeDays_i),   ageDays = now − decay_anchor_at
```

현재 파라미터의 반감기는 **새 원장 행을 쓸 때만** 읽는다. `ParameterSet`을 인자로 받지 않는다.

### 2.5 `SubmissionQuota` — 파생 계산 (J4)

```java
static Instant weekStart(Instant now)        // 그 주 월요일 00:00 KST (MeService.java:97-99의 계산을 옮김)
static int used(int submittedThisWeek, int refundedThisWeek)   // max(0, s − r)
static int remaining(Grade grade, int used)  // max(0, weeklyLimit(grade) − used)
```

- 이번 주 제출 = `created_at`이 이번 주인 시딩 아닌 제보(지금 VOID인지와 무관). 이번 주 반환 = `voided_at`이 이번 주인 시딩 아닌 제보(지난주에 낸 것 포함).
- 이번 주에 내고 이번 주에 VOID되면 양쪽에 한 번씩 잡혀 0 → 반환. 지난주 제보가 이번 주에 VOID되면 이번 주에 한 장 더 쓸 수 있다. 주가 바뀌면 둘 다 0부터 → 리필. `used ≥ 0`이라 남은 제보권이 한도를 넘지 않는다(이월 없음).
- 한도 표(L0 2 / L1 3 / L2 5 / L3 8 / L4 12)는 그대로.

### 2.6 `ParamSimulation`·`GradePolicy`

- 시뮬레이션은 판정 근거의 `signal`에서 `TrendSignal`을 복원해 `VerdictEngine.evaluate`를 돌린다(`VerdictSnapshot`의 두 정수 필드 대체). VOID 판정 제외는 현행 유지.
- `GradePolicy.progressToward(Grade next, judged, ti, as)` 추가 — 스냅샷 등급 기준 다음 등급 요구 항목(J5). `evaluate`는 `grade_recalc`가 계속 쓴다.

## 3. 스키마 — `V28__sp1_verdict_pipeline.sql`

```sql
-- 0) 초기화 확인(J7). 감쇠가 곱해진 옛 원장·옛 판정 근거 형식과 섞이지 않게 한다.
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM verdicts) OR EXISTS (SELECT 1 FROM score_ledger) THEN
    RAISE EXCEPTION 'SP1(V28): 기존 판정·원장이 남아 있습니다. 개발 DB를 초기화하세요 (docs/superpowers/specs/2026-09-20-sp1-verdict-pipeline-design.md §0)';
  END IF;
END $$;

-- 1) 원장 행별 감쇠 기준(J1). 0)에서 비어 있음을 확인했으므로 기본값 없이 NOT NULL.
ALTER TABLE score_ledger
    ADD COLUMN halflife_days   SMALLINT    NOT NULL CHECK (halflife_days > 0),
    ADD COLUMN decay_anchor_at TIMESTAMPTZ NOT NULL;

-- 2) 멱등: 한 판정이 한 제보에 원장을 두 번 남기지 않는다(상위 스펙 §2.3). 수동 ADJ(verdict NULL)는 영향 없음.
ALTER TABLE score_ledger
    ADD CONSTRAINT ledger_one_row_per_verdict_submission UNIQUE (verdict_id, submission_id);

-- 3) 재판정 체인은 한 줄 — 같은 판정을 두 판정이 대체하지 못한다(동시 재판정 한쪽은 409).
CREATE UNIQUE INDEX verdict_superseded_once ON verdicts (supersedes) WHERE supersedes IS NOT NULL;

-- 4) 제보: VOID 시각(제보권 반환 기준)·판정 시각(TI 180일 창 기준)
ALTER TABLE submissions
    ADD COLUMN voided_at   TIMESTAMPTZ,
    ADD COLUMN resolved_at TIMESTAMPTZ;
UPDATE submissions SET voided_at   = created_at WHERE result = 'VOID';
UPDATE submissions SET resolved_at = created_at WHERE result IN ('HIT', 'MISS');
ALTER TABLE submissions
    ADD CONSTRAINT submission_void_has_time   CHECK ((result = 'VOID') = (voided_at IS NOT NULL)),
    ADD CONSTRAINT submission_judged_has_time CHECK (result NOT IN ('HIT', 'MISS') OR resolved_at IS NOT NULL);
CREATE INDEX idx_submissions_user_voided   ON submissions (user_id, voided_at)   WHERE voided_at IS NOT NULL;
CREATE INDEX idx_submissions_user_resolved ON submissions (user_id, resolved_at) WHERE resolved_at IS NOT NULL;
-- 이번 주 제출 수는 기존 idx_submissions_user (user_id, created_at DESC)를 쓴다.

-- 5) 선점 순위에서 시딩 제외(J3)
CREATE OR REPLACE VIEW submission_order_rank AS
SELECT id, trend_item_id,
       CAST(RANK() OVER (PARTITION BY trend_item_id ORDER BY created_at ASC) AS integer) AS order_rank
FROM submissions
WHERE result <> 'VOID' AND NOT is_seed;
```

`score_ledger.created_at`은 기록 시각으로 남고, 감쇠는 `decay_anchor_at`을 쓴다. 엔티티의 `createdAt = Instant.now()`(`ScoreLedgerEntry.java:51`)는 그대로 두되, 감쇠 기준은 서비스가 Clock으로 명시한다(테스트에서 시간을 조작할 수 있게).

## 4. `JudgeService` (신규 모듈 `judge`)

### 4.1 모듈·트랜잭션 경계

- 새 Gradle 모듈 `judge`(패키지 `kr.trendstage.judge`) → `domain-core`·`persistence`·`audit`에 의존. `scheduler`·`api-admin`이 의존한다. `merge`는 의존하지 않는다(항목 VOID를 여기로 옮기므로).
- 공개 메서드마다 `@Transactional`. 배치·컨트롤러가 다른 빈에서 호출하므로 프록시를 탄다(04 §3 — 배치 클래스 안에서 같은 빈의 `@Transactional` 메서드를 직접 호출하지 않는다).
- 파라미터 소스는 `CurrentParameterSetResolver` 하나. `scheduler.ParameterSetProvider`(얇은 래퍼)는 삭제한다.
- 대상 항목은 `SELECT … FOR UPDATE`로 잠근다. 배치·재판정·VOID가 같은 항목을 동시에 건드려도 순서대로 처리되고, SP2의 병합 잠금이 같은 행을 잠그므로 그때 병합과도 직렬화된다.

### 4.2 배치 판정 — `closeDue` + `judge`

```
VerdictRunner.run()   @Scheduled · @SchedulerLock, 트랜잭션 없음 — 목록 순회만
  judgeService.closeDue(now)                 마감 지난 PENDING → JUDGING (한 트랜잭션)
  for id in (JUDGING 항목 전부):              이전 실행에서 실패해 남은 것 포함
      judgeService.judge(id, now)            항목마다 별도 트랜잭션. 실패하면 로그만 — 항목은 JUDGING으로 남아 다음 실행에서 재시도
```

`judge(itemId, now)` — 한 트랜잭션:
1. 항목 잠금. JUDGING이 아니거나 원본 판정이 이미 있으면 아무것도 하지 않는다(멱등. 최종 방어는 `verdict_one_original_per_item`).
2. 마감 = `judgment_deadline_override ?? first_seen_at + 14일`. 마감 전에 들어온 비VOID 제보로 `TrendSignal`을 만든다(제보자 `joined_at` 포함). 선점 순위는 뷰(시딩 제외)에서 읽어 동결한다.
3. 파라미터 = 현재 적용값.
4. `VerdictComputation.run` (순수).
5. `verdicts` 저장. 근거 JSON은 아래 형식.
6. 원장: `ledgerLines`마다 원값 Δ, `halflife_days = p.halflifeDays`, `decay_anchor_at = judgedAt`, `verdict_id`, `submission_id`.
7. 유효 제보 전부 `result = HIT|MISS`, `resolved_at = judgedAt`.
8. 항목 → `RESOLVED`(VOID 판정이면 `VOID`).

판정 근거 `evidence_json` — 재판정(J2)·시뮬레이션·감사의 입력. 지금처럼 문자열을 손으로 잇지 않고 `ObjectMapper`로 만든다.

```json
{ "result": "HIT", "reach": "L1", "t": 0.2000,
  "deadline": "2026-10-04T01:00:00Z",
  "params": { "submitterTarget": 20, "hitThreshold": 0.2, "halflifeDays": 90, "...": "ParameterSet 전체" },
  "signal": { "entries": [ { "submissionId": "…", "userId": "…", "seed": false,
                             "submittedAt": "…", "platform": "X", "userJoinedAt": "…" } ] },
  "distinctSubmitters": 4, "distinctPlatforms": 2,
  "orderRanks": { "<submissionId>": 1 } }
```

### 4.3 재판정 — `rejudge(itemId, actor, reason, now)`

1. 항목 잠금. 현재 판정이 있고 VOID가 아니어야 한다.
2. `TrendSignal`은 `judge`와 같은 방식(마감 전·비VOID). 그사이 VOID된 제보가 빠지는 것이 재판정의 실질이다.
3. 파라미터 = 원본 판정(체인의 첫 판정) 근거의 `params` (J2).
4. 새 판정(`supersedes` = 현재 판정). 체인 분기는 `verdict_superseded_once`가 막는다.
5. **원장 차액은 제보 단위**로 계산한다. 제보 s마다 `old` = 이 항목 판정 체인에 귀속된 s의 원장 합, `new` = 새 플랜의 s 라인(없으면 0). 차이가 0이 아니면 ADJ를 남긴다(`verdict_id` = 새 판정, `submission_id` = s, `halflife_days`·`decay_anchor_at` = 원본 판정 값). 원본과 같은 기준으로 감쇠하므로 AS에서 정확히 상쇄된다. 시딩 제보는 원장 라인이 없으므로 ADJ도 없다.
   - §0 표 6번의 두 버그(두 번째 재판정 이중 반영, 시딩 유저 ADJ)가 여기서 없어진다.
6. 제보 `result` 갱신(`resolved_at`은 원래 값 유지). 유효 제보가 0건이 됐으면 VOID 판정 → 항목 `VOID`, 원장은 제보마다 −old.
7. 감사 로그 `VERDICT_REJUDGE`(현행 유지). ADJ 합계 상한·2인 승인은 SP3(`ApprovalGate`).

### 4.4 항목 VOID — `voidItem(itemId, actor, reason, now)`

ADM-100 큐의 VOID(`MergeService.voidTrendItem`, `MergeService.java:82`)와 ADM-200의 VOID(`VerdictAdminService.voidVerdict`)를 하나로 합친다(P5 "항목이 VOID됨" 사건).

1. 항목 잠금. `MERGED`·`VOID`면 409.
2. 비VOID 제보 전부 `result = VOID`, `voided_at = now` — 제보권 반환은 §2.5 계산이 자동으로 잡는다.
3. 판정이 있었다면 VOID 판정(`supersedes` = 현재 판정)과 제보 단위 −old ADJ(원본 감쇠 기준).
4. 항목 → `VOID`. 감사 로그 action은 호출 경로를 유지(`MERGE_VOID` / `VERDICT_VOID`).

판정 전 항목도 VOID할 수 있다(ADM-100이 이미 그렇게 쓴다). ADM-200은 지금처럼 판정된 항목에서만 노출한다.

같은 유저 중복 제보 VOID(`MergeService.java:150`)는 병합 트랜잭션 안에 남기고, `Submission.voidOut(Instant)`가 `voided_at`을 함께 기록한다. 판정된 항목으로의 병합은 SP2가 409로 막는다 — 그 전까지는 판정된 제보가 원장 상쇄 없이 VOID될 수 있는 공백이 남는다(§10).

### 4.5 미리보기 — `preview(itemId, now)` (읽기 전용)

ADM-111의 예상 판정(`TrendItemAdminService.java:126`). 현재 파라미터 + 지금까지의 신호로 계산만 하고 저장하지 않는다.

### 4.6 호출부 변경

| 위치 | 지금 | SP1 |
|---|---|---|
| `VerdictRunner` | 판정 로직 자체 보유 | `closeDue` + `JudgeService.judge` |
| `VerdictAdminService` | `applySupersede`·`reconcileLedger` 중복 보유, `defaults()` | `rejudge`·`voidItem` 위임. `extendGrace`는 JUDGING 항목도 허용하고, 새 마감이 미래면 PENDING으로 되돌린다 |
| `MergeQueueController` `/void` | `MergeService.voidTrendItem` | `JudgeService.voidItem`. `MergeService.voidTrendItem` 삭제 |
| `TrendItemAdminService:126` | `VerdictComputation` + `defaults()` | `JudgeService.preview` |
| `ParamStudioService.toSnapshot` | 근거의 두 정수 | 근거의 `signal` → `TrendSignal` |
| `AdminSeedService:115`, `MeService:77·149·173` | `defaults()` | `CurrentParameterSetResolver` |

`ParamStudioService:149`의 `defaults()`는 새 드래프트의 초기값이라 그대로 둔다.

## 5. 제보권·등급

### 5.1 제보 등록 — `SubmissionService.create` (J4·J6)

1. 확신도 검증 → 정규화.
2. **유저 행 잠금** `SELECT … FROM users WHERE id = ? FOR UPDATE` — 같은 유저의 동시 제보를 직렬화한다(한도 초과 레이스 방지).
3. 항목 조회. 있으면 상태가 PENDING이 아니거나 `now ≥ 마감`이면 422 `item-closed`. 같은 유저 중복이면 지금처럼 409(동의 전환 안내). 둘 다 제보권을 쓰지 않는다.
4. `QuotaService.remaining(userId, now) == 0`이면 422 `quota-exhausted`.
5. 저장. 시각은 Clock(`SubmissionService.java:51`의 `Instant.now()` 교체).

- 한도의 등급 = 최신 스냅샷, 없으면 L0(J5).
- `QuotaService`(api-public)가 used·remaining 계산을 한 곳에서 하고, 집행(`SubmissionService`)과 표시(`MeService.summary`)가 같은 메서드를 부른다. 쿼리는 이번 주 제출 수·반환 수 두 개(둘 다 시딩 제외).
- 시딩(`AdminSeedService`)은 경로가 달라 제보권과 무관 — 변경 없음.

### 5.2 `GradeRecalcJob`

- `@Scheduled(cron = "${jobs.grade-recalc.cron:0 0 0 * * MON}", zone = "${jobs.grade-recalc.zone:Asia/Seoul}")` — 주 경계를 코드에서 KST로 명시한다(04 §8 "배치 경계는 타임존 명시"). 제보권 주 경계(§2.5)와 같은 순간이다.
  - `infra/docker-compose.yml`·`infra/.env.example`의 UTC 보정 `JOBS_GRADE_RECALC_CRON=0 0 15 * * SUN`은 **삭제**한다. 남기면 KST로 해석돼 일요일 15:00에 돈다.
- TI = 최근 180일(`resolved_at` 기준) 시딩 아닌 HIT/MISS(01 §5.2). `GradePolicy`의 판정완료 건수는 지금처럼 전 기간.
- AS = §2.4. 파라미터 = `CurrentParameterSetResolver`.
- "제보권 리필" TODO(`:94`)는 삭제 — 리필은 주 경계 자체다(J4).

### 5.3 `MeService` (J5)

- `grade()`: 현재 등급 = 최신 스냅샷(없으면 L0). 다음 등급 요구 항목은 지금 값(TI 180일·AS 행별 감쇠·현재 파라미터)으로 `GradePolicy.progressToward`를 계산한다. 요구치를 모두 채웠으면 `note`에 "월요일 00:00 등급 재계산 때 반영"을 넣는다.
- `summary()`: `quotaUsed`·`quotaMax` = `QuotaService`(집행과 같은 값).

### 5.4 API 계약 (`openapi.yaml`)

- `POST /v1/submissions` 422: `Problem.type`으로 구분한다 — `quota-exhausted`(detail 예 "이번 주 제보권 2/2 사용 · 월요일 00:00 리필"), `item-closed`(관측 마감·판정 완료·VOID 항목), 필드 검증은 기존대로 `about:blank`.
- `/v1/me/summary`: `quotaUsed` = 이번 주 제출 − 이번 주 VOID 반환.
- `/v1/me/grade`: `current`는 주간 스냅샷, `requirements`는 실시간이라고 명시.

## 6. 프론트 (백엔드가 커밋·리뷰를 통과한 뒤)

### 6.1 앱

- `SubmitScreen`: 제출 전 남은 제보권 표시(`/v1/me/summary`), 0이면 제출 버튼 비활성 + "월요일 00:00 리필" 안내. 422는 `type`별 문구. 409(동의 전환) 흐름은 유지.
- `MeScreen`: 등급 `note` 표시(기존 필드).

### 6.2 관리자 콘솔

- ADM-200 재판정 확인 모달에 "원 판정 때의 파라미터로 다시 계산합니다" 문구(J2).
- `JUDGING` 상태는 목록 필터·라벨에 이미 있다(`TrendListScreen.tsx:9`) — 변경 없음.

## 7. 테스트

### 7.1 도메인 골든 (domain-core)

- `ScoreEngine`: 원값 Δ. `EngineGoldenTest`의 90일 감쇠 케이스는 `ActiveScore`로 옮긴다.
- `VerdictComputation`: 유효 0건 → VOID(라인 없음), 시딩만 있는 항목 → MISS·라인 없음, 시딩 4 + 실유저 3 → MISS·라인 3. `voidByRule` 케이스(`VerdictComputationTest`)는 삭제.
- `ActiveScore`: 반감기가 다른 행 혼합(90·60), 재판정 ADJ가 원본 기준으로 정확히 상쇄.
- `SubmissionQuota`: 주 경계(일요일 23:59:59 KST / 월요일 00:00 KST), 같은 주 VOID 반환, 지난주 제보의 이번 주 반환, `used` 하한 0.
- `TrendSignal`: 시딩 제외 distinct 계산. `ParamSimulation`: 근거 신호 복원.

### 7.2 통합 (Testcontainers, `app` 모듈)

상위 스펙의 완료 기준(1·2·3번)을 포함한다. 시간은 `MutableClock`으로 조작한다.

| # | 케이스 | 확인 |
|---|---|---|
| 1 | 배치 저장: 실유저 3명, 마감 지남 → `run()` | 제보 MISS·`resolved_at`, 항목 RESOLVED, 판정 1건, 원장 3행(원값 −c×0.5, `halflife_days` 90, `decay_anchor_at` = 판정 시각) |
| 2 | 멱등: `run()` 두 번 | 판정·원장 행 수 불변 |
| 3 | 시딩: 시딩 4(먼저 제출) + 실유저 3 | MISS. 시딩 result MISS·원장 없음. 실유저 선점 1·2·3위 |
| 4 | HIT: 실유저 4 | HIT L1, Δ = c × w_order × 1.2 |
| 5 | 유효 0건: 전부 VOID 후 마감 | 항목 VOID, 원장 없음 |
| 6 | 마감 경계: 마감 직후 제보 시도 / 마감 직전 제보 | 직후는 422 `item-closed`, 직전 것은 판정 신호에 포함 |
| 7 | JUDGING 재시도: 판정 중 예외 주입 | 항목 JUDGING 유지, 다음 `run()`에서 판정 |
| 8 | 재판정 차액: HIT 뒤 제보 2건 VOID → 재판정(MISS) → 한 번 더 재판정 | 제보별 ADJ = new − old. 두 번째 재판정은 ADJ 0건(이중 반영 회귀) |
| 9 | 재판정 파라미터: 판정 뒤 `hitThreshold` 변경 적용 → 입력 변화 없이 재판정 | 결과·원장 변화 없음(J2) |
| 10 | 항목 VOID: 판정된 항목 VOID | 유저별 원장 합 0, 제보 VOID·`voided_at`, 항목 VOID |
| 11 | 제약: 같은 판정을 두 번 supersede / 같은 (verdict, submission) 원장 두 번 | 둘 다 제약 위반(재판정은 409) |
| 12 | 제보권: L0 세 번째 제보 | 422 `quota-exhausted` → 한 건 VOID 후 성공 → 다음 월요일 00:00 KST에 리셋. 중복(409)·시딩은 차감 없음 |
| 13 | 동시 제보: 남은 제보권 1에서 동시 2건 | 1건만 성공 |
| 14 | TI 창: 181일 전 판정 | TI 계산에서 제외 |
| 15 | 등급 소스: `grade_recalc` 뒤 `/v1/me/summary`·`/v1/me/grade` | `quotaMax`와 현재 등급이 스냅샷과 같다 |

### 7.3 실행

`./gradlew test` — Docker가 실행 중이어야 한다(Testcontainers). 앱 `tsc`, 콘솔 `npm run build`.

## 8. 문서·설정 갱신

- **CLAUDE.md**: 점수 공식 절의 "현행 이중 적용" 문구 → 원값 기록·행별 감쇠(J1)로. O11 → 결정됨. R5·Phase 1의 "시딩 T 제외 미구현" → 구현. 배치 표(`verdict_runner` = JudgeService, `grade_recalc` = KST·리필은 주 경계). "중복 제보 VOID" 문단의 제보권 반환 → 구현. `order_rank` SQL에 `AND NOT is_seed`.
- **01**: §2.2(JUDGING 설정됨), §3.3(집행·반환·리필 방식, 관측 마감 후 제보 불가), §4.1·4.3·4.4·5.1~5.3의 미구현 표기, §6 note.
- **02**: ADM-200 재판정 = 원 판정 파라미터(J2), ADM-100 VOID = 항목 VOID 사건.
- **03**: 선점 순위 시딩 제외(J3).
- **04**: 모듈 트리에 `judge`, 스키마 V28, 배치 표, §7.2 재판정 서술(J2).
- **05**: `POST /v1/submissions` 422 type, 구현 상태 열.
- **openapi.yaml**: §5.4.
- **infra**: `docker-compose.yml`·`.env.example`의 `JOBS_GRADE_RECALC_CRON` 보정 삭제. README Docker 절의 배치 시각 문구는 유지.
- **README** "현재 구현 상태" 표: SP1 해당 행.

## 9. 완료 기준

- 상위 스펙 SP1 기준 통과 — 배치 1회 후 `submissions.result`·`trend_items.state`가 DB에 반영되고, 2회 실행해도 원장이 그대로이며, 시딩 4 + 실유저 3은 MISS(§7.2 1~3번).
- §7.1·7.2 전 케이스와 기존 테스트 통과, 앱 `tsc`·콘솔 빌드 통과.
- 코드에 `voidByRule`, 판정·등급 경로의 `ParameterSet.defaults()`(초기 드래프트 값 제외), 원장을 쓰는 경로의 감쇠 계산이 남아 있지 않다.
- 빈 DB에서 docker compose로 한 바퀴 수동 확인: 제보 → (`first_seen_at`을 과거로 조정) → 배치 판정 → 등급 재계산 → 앱 `/v1/me` 수치.

## 10. 리스크·후속

- **J2가 기존 문서와 다르다**: 04 §7.2·01 §4.3은 "재판정도 같은 파라미터 소스(현재값)"로 읽힌다. 이 스펙이 대체하고 §8에서 문서를 고친다. 소급이 필요하면 ADM-600 RETROACTIVE 경로(미구현, SP3 이후).
- **J5로 앱의 현재 등급은 월요일에만 바뀐다** — 조건을 채운 뒤 최대 7일 늦게 반영된다. `note`로 안내한다.
- **J6으로 판정이 끝난 트렌드는 다시 제보할 수 없다**. 동의(`TrendInteractionService`)는 이 스펙에서 막지 않는다 — 반영 방식과 함께 SP4에서 정한다.
- **판정된 항목으로의 병합(SP2 전)**: 흡수된 제보가 판정 밖에 남고, 같은 유저 중복 VOID가 원장 상쇄 없이 일어날 수 있다. SP2의 RESOLVED 병합 409가 해소한다.
- 제보 단위 ADJ로 재판정 1회당 원장 행이 영향받은 제보 수만큼 늘어난다. 판정 근거에 제보 목록 전체가 들어가 판정당 크기가 제보 수에 비례한다. 항목당 제보 수백 건까지는 문제없다.
- V28(J7)이 기존 판정이 있는 DB에서 기동을 막는다 — 공용 개발 DB를 쓰는 사람은 초기화해야 한다.
