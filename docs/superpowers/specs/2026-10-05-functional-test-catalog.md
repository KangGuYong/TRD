# 기능 테스트케이스 카탈로그

> 설계: [`2026-10-05-functional-test-suite-design.md`](2026-10-05-functional-test-suite-design.md) · 상태: `TODO` / `TODO(BUG 예상)` / `PASS` / `BUG-n`
>
> 공통 전제(따로 적지 않음): 시계는 고정 시각 `T0` = 2026-09-21(월) 10:00 KST에서 시작, 유저는 `fx.user()`(등급 L0, 주 2장), 관리자는 `fx.admin(role)` + `asAdmin`. "Problem"은 `{type,status,detail}` 바디. 기본 파라미터: submitterTarget 20, hitThreshold 0.20 → **서로 다른 비시딩 제보자 4명 이상이면 HIT**(7·11·15명부터 L2·L3·L4).

---

## USR — 유저 API `/v1`

### USR-AUTH 인증 공통 — `UserAuthTest`

| ID | 케이스 | 기대 | 근거·비고 | 상태 |
|---|---|---|---|---|
| USR-AUTH-01 | 인증 없이 보호 엔드포인트 18개(GET/POST/PUT/DELETE 각각) 호출 | 모두 401, 바디 없음 | `PublicSecurityConfig` — 파라미터화 테스트 1개 | PASS |
| USR-AUTH-02 | 인증 없이 `GET /v1/trends`, `GET /v1/trends/{id}` | 200 | 공개 경로. OpenAPI의 401은 D7 | PASS |
| USR-AUTH-03 | 잘못된 Bearer 토큰(Firebase 미설정)으로 보호 경로 | 401 (500 아님) | 필터가 미설정 예외를 삼킴 | PASS |
| USR-AUTH-04 | `/v1` POST에 CSRF 토큰 없음 | 403이 아님(201/200) | `/v1`은 STATELESS·CSRF 없음. 기존 `AdminCsrfTest#publicApiChainIsUnaffected`는 401만 봄 | PASS |

### USR-SUB 제보 — `UserSubmissionTest`

| ID | 케이스 | 기대 | 근거·비고 | 상태 |
|---|---|---|---|---|
| USR-SUB-01 | 새 이름 제보 | 201, `status=PENDING`, `orderRank=1`, `judgeInDays=14`, `word`=입력 이름. DB: `trend_items` 1행(PENDING, PUBLIC, first_seen=T0), `submissions.is_seed=false` | — | PASS |
| USR-SUB-02 | 다른 유저가 공백·대소문자만 다른 이름(`"  Foo   BAR "` vs `"foo bar"`) 제보 | 201, 같은 `trend_items.id`에 합류, `orderRank=2` | 완전일치 병합(`NameNormalizer`) — 단위 테스트 없음 | PASS |
| USR-SUB-03 | 같은 유저가 같은 항목에 다시 제보 | 409, 바디 `{trendItemId, dupeRank, message}`(Problem 아님), 제보권 차감 없음 | 기존 서비스 수준: `SubmissionQuotaIntegrationTest#duplicatesAndSeedsDoNotConsumeQuota` | PASS |
| USR-SUB-04 | `confidence=20` | 422 | {10,30,50}만 | PASS |
| USR-SUB-05 | 필수 필드 누락·길이 초과(name 121자, oneLine 201자), `category` 잘못된 enum | 400 | bean validation | PASS |
| USR-SUB-06 | L0 유저 3번째 제보 | 422 `type=quota-exhausted` | 기존: `#quotaExhaustedIsProblem422WithType` — **링크만**, 재작성 안 함 | PASS(기존) |
| USR-SUB-07 | 마감(D+14) 지난 항목 / JUDGING 항목에 제보 | 422 `type=item-closed`, 제보권 차감 없음 | 경계값은 기존 `#closedItemsRejectWithoutCharge`. HTTP 매핑만 확인 | PASS |
| USR-SUB-08 | 병합된 이름(MERGED 툼스톤)으로 제보 | 201, 생존 항목 id | 기존: `TombstoneJoinTest#submissionOnMergedNameJoinsSurvivor` — 링크 | PASS(기존) |
| USR-SUB-09 | `GET /v1/submissions/me` | 200, 최신순, 다른 유저 제보 없음, PENDING이면 `judgeInDays` 있음 | — | PASS |
| USR-SUB-10 | 판정 끝난 제보의 `GET /v1/submissions/me` | `status`=HIT/MISS, `delta`·`reachLevel`·`note`(산정 근거) 채워짐, `judgeInDays=null` | OpenAPI SubmissionMine — 현재 세 필드 항상 null. `JourneyTest`에 둠 | PASS(BUG-10 수정) |
| USR-SUB-11 | NFD로 분해된 한글 이름(macOS 입력) 제보 → NFC 이름 항목 | 같은 항목에 합류 | `NameNormalizer` NFC | PASS |
| USR-SUB-12 | 같은 유저가 같은 새 이름을 동시에 두 번 제보(네트워크 재시도) | 201 1건 + 409 1건, 500 없음, 제보 1행 | — | PASS |
| USR-SUB-13 | 이름 정확히 120자 | 201 (121자는 400 — USR-SUB-05) | 경계값 | PASS |
| USR-SUB-14 | 생성 응답의 `orderRank` | 1위, 이어서 2위 | OpenAPI "판정 전에는 파생 잠정값" — 현재 항상 null(flush 전 뷰 조회). USR-SUB-01·02는 순위를 `GET /v1/submissions/me`로 확인 | PASS(BUG-9 수정) |

### USR-TRD 트렌드 조회·투표·인정 — `UserTrendTest`

| ID | 케이스 | 기대 | 근거·비고 | 상태 |
|---|---|---|---|---|
| USR-TRD-01 | `GET /v1/trends` | 200, PENDING·JUDGING이고 PUBLIC인 내 항목이 포함되고, MERGED·RESOLVED·TEMP_HIDDEN 항목은 빠짐 | 전역 목록 — 내 id 포함·제외만 단언 | PASS |
| USR-TRD-02 | 단계(stage) 계산: 플랫폼 1·2·4·6종 제보 | SEED·RISING·PEAK·FADING | 기존 단위 `ReadModelTest` — HTTP는 1개 대표값만 | PASS |
| USR-TRD-03 | 로그인 + `daily=true` | 200, 최대 5개. 두 번 호출하면 같은 목록. `daily_selections`에 (user, 오늘) 행 | 선호 카테고리 우선은 기존 `DailySelectionPickerTest` | PASS |
| USR-TRD-04 | 오늘의 5개 선정 후 그중 한 항목이 TEMP_HIDDEN 또는 MERGED가 됨 → 다시 조회 | 그 항목이 빠짐 | 설계 §5 — 지금은 계속 노출 | PASS(BUG-1 수정) |
| USR-TRD-05 | `GET /v1/trends/{id}` 정상 | 200, `meaning`=가장 이른 제보의 oneLine, `pathText`, `reachedCount`, 투표 0이면 `voteCount=null` | — | PASS |
| USR-TRD-06 | 상세: 없는 id / MERGED / TEMP_HIDDEN / PERMANENT_HIDDEN | 404 | — | PASS |
| USR-TRD-07 | 상세: 판정된 항목 | `verdict`="적중했어요"/"빗나갔어요", `reachLevel`(HIT) — `verdictWhy`는 미검증 | JRN-01·02에서 확인 | PASS |
| USR-TRD-08 | 상세: 내가 워치한 항목 | `watched=true`(다른 유저 시점에서는 false) | — | PASS |
| USR-TRD-09 | 투표 `{willTrend:true}` → 다시 `{willTrend:false}` | 둘 다 200, `votes` 1행 유지·값 토글, `voteCount` 문구 갱신 | — | PASS |
| USR-TRD-10 | 투표: `willTrend` 누락 → 400 / 없는 항목·MERGED → 404 | — | — | PASS |
| USR-TRD-11 | 투표·인정: TEMP_HIDDEN·PERMANENT_HIDDEN 항목 | 404 | D1(결정: 상세와 일치) — 현재 200/201 | BUG-3 |
| USR-TRD-12 | 인정 첫 요청 → 201(바디 없음), 두 번째 → 409 | `endorsements` 1행 | — | PASS |
| USR-TRD-13 | 인정: 없는 항목·MERGED → 404 | — | — | PASS |
| USR-TRD-14 | 인정: 그 항목에 제보한 유저 | 409 | D2(결정: OpenAPI대로) — 현재 201 | BUG-4 |
| USR-TRD-15 | 같은 유저의 투표 2건·인정 2건 동시 첫 요청 | 500 없음. 투표는 1행, 인정은 201 1건 + 409 1건 | 설계 §5 | PASS(BUG-2 수정) |

### USR-RPT 신고·소명 — `UserReportTest`

| ID | 케이스 | 기대 | 근거·비고 | 상태 |
|---|---|---|---|---|
| USR-RPT-01 | 신고 접수 | 201, `status=OPEN`, `decision=null`. `reports` 1행 | — | PASS |
| USR-RPT-02 | 신고: `reason` 누락·잘못된 값, `detail` 1001자 → 400 / 없는 항목·MERGED → 404 | — | — | PASS |
| USR-RPT-03 | 비공개 항목 신고 | 201 | 비공개 항목도 신고 가능(현재 동작 고정) | PASS |
| USR-RPT-04 | `GET /v1/reports/me` | 내 신고만, 최신순 | — | PASS |
| USR-RPT-05 | `GET /v1/reports/received` — 관리자가 내 제보를 지정해 소명 요청한 신고 | 그 신고가 보임, 신고자 id는 응답에 없음. 제보 없는 유저는 빈 목록 | — | PASS |
| USR-RPT-06 | 소명 제출: 지정된 제보자 + `EXPLAINING` | 200, `explanation_text` 저장 | — | PASS |
| USR-RPT-07 | 소명: 없는 신고 → 404 / 지정된 제보자가 아님·`submission_id` 없음 → 403 / `DECIDED` → 409(OPEN은 지정 제보가 없어 403이 먼저) / `text` 빈값·2001자 → 400 | — | — | PASS |
| USR-RPT-08 | 소명: 마감(요청 + 48h) 지난 제출 / 마감 전 재제출 | 409 / 200(덮어씀) | D3 — 현재 마감 후도 200 | BUG-5 |

### USR-RD / USR-WCH 읽음·워치 — `UserReadWatchTest`

| ID | 케이스 | 기대 | 근거·비고 | 상태 |
|---|---|---|---|---|
| USR-RD-01 | 읽음 기록 2번 → 목록 | 204 두 번(멱등), `GET /v1/me/reads`에 id 1개 | — | PASS |
| USR-RD-02 | 없는 항목 읽음 → 404 / `trendId` 누락 → 400 | — | — | PASS |
| USR-WCH-01 | 존재하는 항목 이름으로 워치 → 목록 | 201, 목록에 `{keyword, stage}` | — | PASS |
| USR-WCH-02 | 같은 키워드(대소문자만 다름) 다시 워치 | 201, 목록 1개, 처음 입력한 `keyword` 유지 | — | PASS |
| USR-WCH-03 | 존재하지 않는 키워드 워치 → 404 / `keyword` 빈값 → 400 | — | FK `watches.normalized_key` | PASS |
| USR-WCH-04 | 워치 해제(있는 것·없는 것) | 둘 다 204, 목록에서 빠짐 | — | PASS |
| USR-WCH-05 | 병합된 키워드 워치 | 생존 항목 단계 표시 | 기존: `TombstoneJoinTest#watchOnMergedChainShowsSurvivorStage` — 링크 | PASS(기존) |

### USR-ME 내 정보 — `UserMeTest`

| ID | 케이스 | 기대 | 근거·비고 | 상태 |
|---|---|---|---|---|
| USR-ME-01 | 신규 유저 `GET /v1/me/grade` | 200, `grade=L0`, `trustIndex=0.4`, `judgedCount=0`, `nextGrade`="제보자"(L1 표시명 — OpenAPI는 string만 정함, 코드값 아님), `requirements` 3개(판정 수·TI·AS)에 `current`·`required`·`met`·`basis` | 신규 TI 0.4(CLAUDE.md) | PASS |
| USR-ME-02 | 신규 유저 `GET /v1/me/ledger` | 200, `items=[]` | — | PASS |
| USR-ME-03 | 원장: 관리자 수동 ADJ(제보 없음) 1행 | `word="계정 조정"`, `kind=ADJ` | — | PASS |
| USR-ME-04 | `GET /v1/me/summary` — 제보 1건 후 | `quotaUsed=1`, `quotaMax=2` | — | PASS |
| USR-ME-05 | 요약: 투표 정확도 — 판정 HIT 항목에 `true` 투표 1, MISS 항목에 `true` 투표 1, 미판정 항목 투표 1 | `votesTotal=2`, `votesCorrect=1` | 미판정 투표는 집계 제외 | PASS |
| USR-ME-06 | 선호 조회: 온보딩 전 | 404 | — | PASS |
| USR-ME-07 | 선호 저장 → 조회 | PUT 200(요청 그대로), GET 동일 | — | PASS |
| USR-ME-08 | 선호 저장: `categories=[]` / `notifyHour=24` / `-1` / 잘못된 카테고리 | 400 | — | PASS |

---

## ADM — 관리자 API (HTTP 테스트가 없던 엔드포인트)

공통: 각 엔드포인트의 **역할 매트릭스**는 메서드 1개로 허용 역할 2xx·거부 역할 403을 함께 확인한다. 미인증 401·CSRF 403은 기존 `AdminCsrfTest`·`SessionRevalidationTest`가 다루므로 재작성하지 않는다.

### ADM-ME / ADM-ACC 내 정보·계정 — `AdminMeAccountTest`

| ID | 케이스 | 기대 | 근거·비고 | 상태 |
|---|---|---|---|---|
| ADM-ME-01 | `GET /admin/me` (4개 역할 각각) | 200, `{id, loginId, displayName, role}`이 세션 값 | — | PASS |
| ADM-ACC-01 | `GET /admin/accounts` 역할: ADMIN·AUDITOR 200 / REVIEWER·OPERATOR 403 | — | 02 §1.1 | PASS |
| ADM-ACC-02 | 비활성화: ADMIN이 다른 계정을 비활성화 | 200, `disabledAt` 채워짐, 감사 `ACCOUNT_DISABLE` 1행, 그 계정의 다음 요청 401 `session-revoked` | — | PASS |
| ADM-ACC-03 | 비활성화: 자기 자신 → 403 / 없는 계정 → 422 / OPERATOR가 호출 → 403 | — | — | PASS |

### ADM-BAT 배치 수동 실행 — `AdminBatchTest`

| ID | 케이스 | 기대 | 근거·비고 | 상태 |
|---|---|---|---|---|
| ADM-BAT-01 | OPERATOR가 `cluster-merge/run` (임베딩 서비스 없음) | 200, `autoMerged=queued=separated=0`, `failed=candidates`. 감사 `CLUSTER_MERGE_MANUAL_TRIGGER` 1행 증가. 내 후보 항목의 `merge_checked_at`은 NULL 유지 | 설계 §2.1의 `embedding.service.url` 고정 | PASS |
| ADM-BAT-02 | `shedlock`의 `cluster_merge` 잠금을 미래로 설정 후 실행 | 409, 감사 행 증가 없음. 끝나면 잠금 해제 | ADM-900 "실행 중이면 409" | PASS |
| ADM-BAT-03 | REVIEWER·AUDITOR → 403 | — | — | PASS |

### ADM-MQ 병합 큐 — `AdminMergeQueueTest`

| ID | 케이스 | 기대 | 근거·비고 | 상태 |
|---|---|---|---|---|
| ADM-MQ-01 | `GET /admin/merge-queue` (4개 역할) | 200, 내 PENDING 행이 `similarity`·`newName`·`oldName`·`orderPreview`와 함께 있음. 시딩은 "시딩 handle"로 표시 | — | PASS |
| ADM-MQ-02 | `GET …/{id}/preview` | 200, 같은 유저 중복이 `dedupVoidedHandles`·`quotaRefundHandles`(시딩 제외)에, `orderRank`에서 시딩은 순위 없음·다른 유저는 병합 후 2위 — 생존 항목·`firstSeenAt*`·`deadline*` 계산은 기존 `MergeRecomputeTest` | — | PASS |
| ADM-MQ-03 | 미리보기: 없는 id·이미 처리된 항목 → 422 | — | — | PASS |
| ADM-MQ-04 | 병합 역할: REVIEWER·OPERATOR 200 / AUDITOR 403 | — | 기존은 ADMIN만 | PASS |
| ADM-MQ-05 | 같은 `Idempotency-Key`로 병합 두 번 | 두 번째 200 `replayed=true`, 감사 `MERGE` 1행 | 기존 서비스 수준 `MergeIdempotencyTest` — HTTP 매핑만 | PASS |
| ADM-MQ-06 | 같은 키로 다른 결정(분리) → 422 `idempotency-key-mismatch` / 처리된 항목에 새 키 → 409 `merge-queue-decided` | — | — | PASS |
| ADM-MQ-07 | 판정된 항목(RESOLVED)과 병합 → 409 `merge-resolved`, 큐는 PENDING 유지 | — | CLAUDE.md "판정 후면 병합 금지" | PASS |
| ADM-MQ-08 | 분리 | 200 `status=SKIPPED`, 감사 `MERGE_SEPARATE`, 두 항목·제보 변화 없음 | — | PASS |
| ADM-MQ-09 | 분리: 키 없음 → 400 `idempotency-key-required` / 판정된 항목 → 200(가드 없음, 데이터 변화 없음) | 현재 동작 고정 | PASS |

### ADM-PRM 파라미터 스튜디오 — `AdminParamStudioTest`

전역 드래프트 1개 — 클래스가 `@BeforeEach`·`@AfterEach`에서 DRAFT·REVIEW 드래프트를 지우고 딸린 PENDING 승인 요청을 REJECTED로 닫는다.

| ID | 케이스 | 기대 | 근거·비고 | 상태 |
|---|---|---|---|---|
| ADM-PRM-01 | OPERATOR `GET /admin/params/draft` (드래프트 없음) | 200, 드래프트 생성(DRAFT), `current*` 값 = 현재 파라미터 | AUDITOR는 기존 `RoleMatrixTest`(생성 안 함) | PASS |
| ADM-PRM-02 | PUT `{submitterTarget:25, hitThreshold:0.25}` | 200, 값 반영, `simResult` 비워짐, 감사 `PARAM_DRAFT_UPDATE` | — | PASS |
| ADM-PRM-03 | PUT `submitterTarget=0` → 422 | — | — | PASS |
| ADM-PRM-04 | PUT `hitThreshold=-0.1` / `1.5` / `0` | 422 (`0 < hitThreshold ≤ 1`만 허용) | D5 — 현재 200 | BUG-7 |
| ADM-PRM-05 | simulate | 200, `simResult{changed,total,missToHit,hitToMiss,reachChanged}`, 감사 `PARAM_SIMULATE` | — | PASS |
| ADM-PRM-06 | 시뮬레이션 없이 승인 요청 → 422 / PUT 후(시뮬레이션 지워짐) 승인 요청 → 422 | CLAUDE.md "시뮬레이션 없이 승인 요청 불가" | PASS |
| ADM-PRM-07 | 시뮬레이션 후 승인 요청(사유 있음) | 200 `status=REVIEW`, `approval_requests` PARAM_APPLY 1행, 감사 `APPROVAL_REQUEST` | 승인 이후는 기존 `ApprovalFlowTest` | PASS |
| ADM-PRM-08 | 승인 요청: 사유 빈값 → 422 / REVIEW 중 다시 요청 → 409 / REVIEW 중 PUT → 409 | — | — | PASS |
| ADM-PRM-09 | REVIEW 중 simulate | 409, `sim_result` 그대로 | D4 — 현재 200·덮어씀 | BUG-6 |
| ADM-PRM-10 | 역할: REVIEWER는 GET·PUT·simulate·request-approval 모두 403 / AUDITOR는 PUT·simulate·request-approval 403 | — | — | PASS |

### ADM-RPT 신고 대상 제보 목록 — `AdminReportSubmissionsTest`

| ID | 케이스 | 기대 | 근거·비고 | 상태 |
|---|---|---|---|---|
| ADM-RPT-01 | `GET /admin/reports/{id}/submissions` | 200, 그 항목의 비VOID 제보(시딩 포함) 생성순, 신고자 id 없음 | — | PASS |
| ADM-RPT-02 | 없는 신고 → 422 | — | — | PASS |

### ADM-SEED 시딩 — `AdminSeedTest`

| ID | 케이스 | 기대 | 근거·비고 | 상태 |
|---|---|---|---|---|
| ADM-SEED-01 | OPERATOR 첫 시딩 | 200 `{submissionId, canonicalName, trendItemId}`. `users`에 `seed_<loginId>` 생성·`admin_accounts.seed_user_id` 연결, `submissions.is_seed=true`, 감사 `SEED_SUBMISSION_CREATE` | 200 vs OpenAPI 201은 D7 | PASS |
| ADM-SEED-02 | 같은 관리자가 같은 항목 재시딩 → 422 | — | — | PASS |
| ADM-SEED-03 | `confidence=20`·잘못된 category → 422 / 필수 필드 누락 → 400 | — | — | PASS |
| ADM-SEED-04 | 시딩은 유저 제보권을 쓰지 않고, 같은 이름으로 유저 제보 시 그 항목에 합류 | 유저 `quotaUsed` 그대로 반영, 같은 `trendItemId` | Phase 1 시딩 = 클러스터 앵커 | PASS |
| ADM-SEED-05 | JUDGING·RESOLVED 항목 시딩 → 200 | D9: 현재 동작 고정 | PASS |
| ADM-SEED-06 | `GET /admin/seed/accuracy` — 시딩 HIT 1·MISS 1인 관리자 | 그 관리자 행 `hit=1, miss=1, judged=2, trustIndex=(1+2)/(2+5)` | — | PASS |
| ADM-SEED-07 | 역할: REVIEWER·AUDITOR 시딩 → 403, 정확도 조회는 4개 역할 200 | — | — | PASS |

### ADM-ITM 항목 — `AdminTrendItemTest`

| ID | 케이스 | 기대 | 근거·비고 | 상태 |
|---|---|---|---|---|
| ADM-ITM-01 | `GET /admin/trend-items` | 내 항목(MERGED 포함)이 `submitterCount`(시딩·VOID 제외)와 함께 있음(MERGED는 `state`) | 개수 규칙은 기존 `TrendItemListCountTest` | PASS |
| ADM-ITM-02 | 상세: 미판정 항목 | 200, `deadline`·`daysLeft`·`distinctSubmitters`·`distinctPlatforms`(시딩 제외)·`endorseCount`, `preview*` 채워짐, 첫 제보자 `submissions[].orderRank`=1 | — | PASS |
| ADM-ITM-03 | 상세: 판정된 항목 | `current*` 채워짐, `preview*` 없음 | — | PASS |
| ADM-ITM-04 | 상세: 없는 id → 422 | — | — | PASS |

### ADM-VRD 판정 목록·유예 연장 — `AdminVerdictTest`

| ID | 케이스 | 기대 | 근거·비고 | 상태 |
|---|---|---|---|---|
| ADM-VRD-01 | `GET /admin/verdicts` | 판정된 내 항목은 `judged`에, 마감 임박 내 항목은 `imminent`에(`daysLeft` 오름차순) | `imminent`는 최대 30개 — 마감을 가장 가깝게 잡아 포함시킴 | PASS |
| ADM-VRD-02 | 역할: OPERATOR·ADMIN·AUDITOR 200 / REVIEWER 403 | — | — | PASS |
| ADM-VRD-03 | 유예 연장 3일 (PENDING) | 200, 마감 = D+14+3일, 감사 `VERDICT_GRACE_EXTEND` | 02 ADM-200 | PASS |
| ADM-VRD-04 | 연장 누적이 D+21을 넘음(예: D+20에서 7일) | 200, 마감 = D+21(잘림). 이미 D+21에서 연장 → 422 | 상한 D+21 | PASS |
| ADM-VRD-05 | `days=0`·`8` → 422 / 판정된 항목 → 422 / 없는 항목 → 422 | 1~7일(02와 코드. 05 문서의 "1~90일"은 오류) | PASS |
| ADM-VRD-06 | JUDGING 항목을 연장해 새 마감이 미래 | 200, 항목 PENDING 복귀 | — | PASS |
| ADM-VRD-07 | 사유 없이 연장 | 200(서버가 사유 요구 안 함 — 02 §사유 입력) | 현재 동작 고정 | PASS |
| ADM-VRD-08 | MERGED·VOID 항목 연장 | 422, override 변화 없음 | D8(PENDING·JUDGING만) — 현재 200 | BUG-8 |
| ADM-VRD-09 | 역할: REVIEWER·AUDITOR 연장 → 403 | — | — | PASS |

---

## JRN — 여정 — `JourneyTest`

모든 여정은 제보·조회를 **HTTP로**, 판정·등급은 배치 직접 호출(`releaseBatchLock` 후 `run()`)로 한다. 점수 기대값은 CLAUDE.md 공식에서 계산한 값이다.

| ID | 시나리오 | 기대 | 근거 | 상태 |
|---|---|---|---|---|
| JRN-01 | **HIT.** 유저 A(c50)·B(c30)·C(c10)·D(c10)가 T0부터 1분 간격으로 같은 항목에 제보(B는 공백·대소문자만 다른 이름) → 시계 D+14+1s → `verdict_runner` | 항목 HIT·L1(T=4/20=0.2). A `GET /v1/me/ledger`: HIT +60(=50×1.0×1.2), B +21.6, C +4.8, D +2.4. A `GET /v1/submissions/me`: HIT·delta 60·orderRank 1. `GET /v1/trends/{id}`: "적중했어요"·L1. A `GET /v1/me/grade`: judgedCount 1, TI 0.5(=(1+2)/(1+5)), activeScore 60 | 점수 공식, 선점 가중, T | PASS |
| JRN-02 | **MISS.** A(c50)·B(c30)·C(c10) 3명 → 판정 | MISS. 원장 −25·−15·−5(=c×0.5). A의 TI 0.333(=2/6). 상세 "빗나갔어요" | 실패 페널티 = 이득의 절반 | PASS |
| JRN-03 | **시딩 제외.** (a) 시딩 1건(가장 먼저) + 실유저 4명 → HIT, A(첫 실유저, c50) +60(순위 1), 시딩 원장 행 없음, `evidence_json.orderRanks`에 시딩 없음. (b) 다른 관리자 3명 시딩 + 실유저 3명 → **MISS** | 시딩은 T·선점 순위·원장에서 제외. 시딩만으로 HIT 안 됨 | R5 | PASS |
| JRN-04 | **병합 후 같은 유저 중복 → VOID → 제보권 반환.** U가 "alpha"(항목 X, T0)와 "alpha 2"(항목 Y, T0+1h) 제보 → `quotaUsed=2` → 큐(Y→X) → REVIEWER가 병합(Idempotency-Key) | 생존 X. U의 늦은 제보(Y 쪽) VOID(`/v1/submissions/me`에서 VOID). `GET /v1/me/summary` `quotaUsed=1`. U의 새 제보 201 | 중복 제보 VOID, `voided_at` 주 반환 | PASS |
| JRN-05 | **재판정 + 승인 게이트 + 원장 불변.** JRN-01 상태에서 D의 제보를 VOID(fixture) → OPERATOR가 재판정 | 차액 합 >100이라 202(아무것도 안 씀) → 다른 ADMIN이 승인 → MISS(T=3/20), A의 원장 = 원래 HIT +60 행 그대로 + ADJ −85(합 −25). 원장 행 수는 늘기만 함, 기존 행 `delta` 불변 | R2, ApprovalGate 100점 | PASS |
| JRN-06 | **판정된 항목 VOID.** JRN-01 상태에서 OPERATOR가 항목 VOID(차액 합 88.8 ≤100 → 200 즉시) | 각 유저 원장 합 0(ADJ 상쇄), 원래 행 불변. `/v1/submissions/me` VOID. A `GET /v1/me/grade` judgedCount 0·activeScore 0. 상세 `verdict=null` | R2, VOID = 점수 변동 없음 | PASS |
| JRN-07 | **투표·인정은 판정에 안 들어감.** 제보자 3명 + 다른 유저 30명이 `willTrend=true` 투표·인정 → 판정 | MISS, `evidence_json.distinctSubmitters=3`, T=0.15. 투표자 `GET /v1/me/summary` `votesTotal=1, votesCorrect=0` | R1 | PASS |
| JRN-08 | **주간 등급 재계산.** A에게 과거 판정 HIT 4건 + 원장 행(fixture) → HTTP로 1건 더 제보해 HIT(+60) → 다음 월요일 00:00 KST → `grade_recalc` | A `GET /v1/me/grade` `grade=L1`(판정 5·TI≥0.35·AS≥30 AND). `GET /v1/me/summary` `quotaMax=3`. 조건을 하나만 못 채운 유저 B(TI 미달)는 L0 유지 | R3 AND | PASS |
| JRN-09 | **신고 → 소명 → 결정.** 신고자 R이 `POST /v1/reports` → REVIEWER가 소명 요청(대상 = 제보자 S의 제보) → S `GET /v1/reports/received`에 보임 → S 소명 제출 → ADMIN이 `HIDE_PERMANENT` 결정 | R `GET /v1/reports/me`에 결정 표시. `GET /v1/trends/{id}` 404, `GET /v1/trends`에서 빠짐 | ADM-410, 02 §신고 | PASS |

---

## BUG 목록

테스트가 드러낸 설계·계약과 다른 동작. 각 테스트는 `@Disabled("BUG-n: …")`로 남아 있다 — 수정 시 `@Disabled`를 지우면 그대로 회귀 테스트가 된다. 수정은 별도 승인 후.

| BUG | TC | 현상 | 관련 코드 |
|---|---|---|---|
| BUG-1 | USR-TRD-04 | 오늘의 5개가 선정 후 비공개·병합된 항목을 그날 계속 노출(명예훼손 대응 비공개 무력화) | `TrendQueryService` 오늘의 5개 재조회 — 저장된 선정을 상태·공개 여부 재확인 없이 반환 — **수정됨**(읽을 때 PUBLIC·비병합만) |
| BUG-2 | USR-TRD-15 | 같은 유저의 투표·인정 동시 첫 요청이 UNIQUE 위반으로 500 | `TrendInteractionService.vote/endorse` — **수정됨**(INSERT … ON CONFLICT) |
| BUG-3 | USR-TRD-11 | 비공개(TEMP_HIDDEN·PERMANENT_HIDDEN) 항목에도 투표·인정이 된다(D1: 404) | `TrendInteractionService` 항목 조회 — MERGED만 거름 |
| BUG-4 | USR-TRD-14 | 제보자 본인이 자기 항목을 인정할 수 있다(D2: 409) | `TrendInteractionService.endorse` |
| BUG-5 | USR-RPT-08 | 소명 기한(요청+48h) 미검사 — 마감 후 제출도 200(D3: 409) | `ReportService` 소명 제출 |
| BUG-6 | ADM-PRM-09 | REVIEW(승인 대기) 드래프트도 simulate가 `sim_result`를 덮어씀(D4: 409) | `ParamStudioService.simulate` |
| BUG-7 | ADM-PRM-04 | `hitThreshold` 범위 미검증(D5: `0 < t ≤ 1` 밖이면 422) | `ParamStudioService` 드래프트 수정 |
| BUG-8 | ADM-VRD-08 | 유예 연장이 MERGED·VOID 항목에도 된다(D8: PENDING·JUDGING만) | `VerdictAdminService.extendGrace` |
| BUG-9 | USR-SUB-14 | 제보 생성 응답의 `orderRank`가 항상 null — `save()` 후 flush 없이 순위 뷰 조회 | `SubmissionService.create/toResponse` — **수정됨**(뷰 엔티티에 @Synchronize("submissions") — 조회 전 자동 flush) |
| BUG-10 | USR-SUB-10 | 판정 끝난 제보의 `delta`·`reachLevel`·`note`(산정 근거)가 항상 null | `SubmissionService.toResponse` — **수정됨**(원장 합·현재 판정 reach·T+원 산정식+조정 합계) |
