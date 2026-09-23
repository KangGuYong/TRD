# Trend Radar 전체 설계 재검토 — 진단·결정·실행 분해

- 작성일: 2026-09-17
- 상태: 승인 대기 (브레인스토밍 4개 섹션 구두 승인 완료)
- 범위: 도메인·게임 설계 / 기술 아키텍처·구현 / 관리자 콘솔·운영 프로세스. 법무는 제외.
- 목적: "설계 자체를 재검토" — 원칙 R1~R5를 포함해 근본 재설계를 허용한다.

이 문서는 세 가지를 담는다.
1. 현재 `main`에 대한 **진단** (근거 파일·라인 포함)
2. 진단으로부터 내린 **결정** — 원칙 개정 문구, 정책 확정, 구조 선택
3. 결정을 실행하기 위한 **서브프로젝트 분해와 순서**

이 문서 자체가 SP0(원칙·문서 정비)의 스펙이다. SP0.5~SP4는 각각 별도 스펙 → 계획 → 구현 사이클을 돈다.

---

## 0. 검증 방법

- 핵심 문서(CLAUDE.md, 01~05)와 핵심 코드(`domain-core`, `scheduler`, `merge`, `api-admin`, `api-public`, `admin/`)를 직접 읽음.
- 도메인·아키텍처·운영 세 관점으로 독립 분석 에이전트 3개를 돌리고, 각 보고서의 가장 강한 주장은 코드로 재확인함.
- DB 실증(개발 DB 조회)은 Docker가 내려가 있어 수행하지 못함. 아래 "치명" 항목은 코드 구조상 예외 경로가 없어 정적 분석만으로 확정한 것들이다.

---

## 1. 진단

### 1.1 전체 그림

| 층 | 치명 (서비스가 성립하지 않음) | 중요 (베타 전 필수) |
|---|---|---|
| 도메인·게임 | ① T = 제보자 수/20 단일 축 ② 제보권(쿼터) 미구현 ③ 시딩이 T에 포함 | 콜드스타트 디플레 루프, 감쇠 이중 적용, `distinctPlatforms`·동의(endorse) 계산만 하고 미사용, 플랫폼 자유 텍스트 |
| 아키텍처·구현 | ④ `VerdictRunner` self-invocation → 판정 결과 미저장 ⑤ 판정 배치에서 VOID 절대 미발생 ⑥ 병합 행 잠금·멱등키 없음 + RESOLVED 항목 병합 → 이중 점수 | `normalized_key` UNIQUE 없음, CSRF off + 쿠키 세션, 재판정이 `defaults()` 사용, `JUDGING` 상태 미사용, DB 기본 호스트 사설 IP |
| 운영·콘솔 | ⑦ 진입 화면이 상수 ⑧ ADM-311(P0) 백엔드 부재 ⑨ 조회 감사 없음인데 UI는 "기록됩니다" ⑩ 4-eyes가 계정 수준에서만 성립 | rejudge→ADJ 무제한(승인 우회), 병합 큐 클레임/보류 없음, SLA 자동 조치 0개, 감사 API가 `detail` 버림, 배치 actor null = "(미인증)" |
| 문서 | ⑪ 01/03/04·CLAUDE.md R5·baseline 경고가 피벗 이전(외부 지표) 상태 | ⑫ "4h 자동 비공개 vs R4" — 문서 4곳과 코드가 반대 정책 |

### 1.2 도메인·게임 설계

**D1. 판정 신호가 머릿수 하나뿐이다.**
`VerdictEngine.computeT = clip(distinctSubmitters / submitterTarget)`, 기본 목표 20, HIT 임계 0.20 → **서로 다른 계정 4개면 HIT, 15개면 L4**. 어뷰징 탐지 잡(`abuse_scan`)은 존재하지 않는다.
- 카르텔: 비용 0.
- 콜드스타트: 절대값 목표 20은 유저 규모와 무관. Phase 1 유저 50~100명이 카테고리별로 흩어지면 도달 불가 → 거의 전부 MISS → TI 하락 → 등급 정체 → 이탈. 자체 교정 장치 없음.
- 조기경보가 아니라 인기투표: 제보 *시각*을 보지 않는다. 이미 뜬 것을 2주 안에 20명이 적으면 HIT. CLAUDE.md 요약("제보 시계열")과 코드(카운트)가 다르다.
- 이미 계산되는데 버려지는 값: `TrendSignal.distinctPlatforms`(미사용), 동의(endorse)·투표(저장만).

**D2. 베팅 구조에 비용이 없다.**
`SubmissionService` 쿼터 TODO, `GradeRecalcJob` 리필 TODO, `VerdictRunner` VOID 제보권 반환 TODO. 무제한 제보가 가능하므로 "MISS 페널티 = HIT의 절반"이라는 의도된 비대칭이 역효과를 낸다: 적중률 ~30%만 넘으면 난사가 이득.

**D3. 시딩이 판정 입력에 들어간다.**
`is_seed`는 원장에서만 제외되고 `VerdictRunner`의 `distinctSubmitters` 집계에는 포함된다. 운영진 주 20건 시딩이 곧 운영진이 HIT를 만드는 경로 — R1을 운영진 스스로 어긴다.

**D4. 감쇠 이중 적용.**
`ScoreEngine`이 Δ에 `d = 0.5^(경과일/90)`을 곱해 원장에 기록하고, `ActiveScore`가 원장 행 나이로 다시 `0.5^(age/90)`을 곱한다.

**D5. 기타.** `TrustIndex`가 "최근 180일"이 아니라 전 기간(TODO), 강등 규칙 없음, `GradePolicy`에 L4 없음, `ParameterSet`에 밴드 순서 검증 없음, 플랫폼이 자유 텍스트라 다양성 축을 만들 수 없음.

### 1.3 아키텍처·구현

**A1. 판정 배치가 결과를 저장하지 않는다 (④).**
`backend/scheduler/.../VerdictRunner.java:70` — `run()`(`@Scheduled`, `@SchedulerLock`, `@Transactional` 없음)이 같은 빈의 `@Transactional protected judgeOne()`(`:87-88`)을 직접 호출한다. 프록시 기반 트랜잭션에서 self-invocation은 어드바이스를 타지 않으며 `open-in-view: false`라 엔티티는 detached다. `verdictRepo.save()`·`ledgerRepo.save()`는 명시 호출이라 저장되지만 `sub.markResult()`·`item.transitionTo(RESOLVED)`는 dirty-check로만 반영되므로 flush되지 않는다.
→ 제보는 영원히 PENDING, 항목도 PENDING, TI는 0.4 고정. 멱등 가드(`existsByTrendItemIdAndSupersedesIsNull`)가 재판정을 막아 겉으로는 "판정됨"처럼 보인다.

**A2. VOID가 존재하지 않는다 (⑤).** `VerdictComputation.run(signal, false, …)` 하드코딩. 동일 유저 중복 제보 VOID·제보권 반환·쿼터 반환 모두 TODO. `MergeService.dedupSameUserSubmissions`는 표시만 한다.

**A3. 병합 무결성 (⑥).** `MergeService.merge()`는 `findById`(잠금 없음), 상태 가드는 `MERGED`뿐 → **RESOLVED 항목도 병합**되어 흡수된 제보가 다음 판정에서 원장에 다시 실린다. ~~`trend_items.normalized_key`에 UNIQUE 없음~~ → **정정(2026-09-17, SP0 중 발견)**: `V24__watches_trend_item_fk.sql:15`가 이미 **전체 UNIQUE**(`trend_items_normalized_key_key`)를 걸었고 `watches.normalized_key`가 `:19`에서 이를 **FK로 참조**한다(`ON UPDATE CASCADE`). 따라서 중복 항목 레이스는 DB가 막고 있다. 남은 실제 문제는 다른 것이다 — `TrendItem.mergeInto()`가 패자의 `normalized_key`를 그대로 두므로 tombstone(MERGED) 항목이 키를 영구 점유하고, `SubmissionService`의 완전일치 조회가 MERGED를 거르지 않아 **신규 제보가 이미 병합된 죽은 클러스터에 붙는다.** `Idempotency-Key`는 05 문서에만 있고 컨트롤러는 받지 않는다. `MergeService.preview()`의 `baselineShifted`는 피벗 잔재.

**A4. 재판정이 승인된 파라미터를 무시한다.** `VerdictAdminService.java:137 ParameterSet p = ParameterSet.defaults()` — 배치는 `ParameterSetProvider`를 쓰므로 같은 항목이 배치와 재판정에서 다른 결과를 낼 수 있다.

**A5. 보안.** `AdminSecurityConfig.java:45`·`PublicSecurityConfig.java:32` `csrf.disable()` — 관리자 SPA는 쿠키 세션(`credentials:"include"`). 로그인 실패 lockout 없음(`AdminAccountService`는 기록만). `AdminAccount.twofaEnabled` 기본값 `false`가 DB `DEFAULT TRUE`와 반대. `application.yml` DB 기본 호스트 `192.168.0.56`.

**A6. 기타.** `JUDGING` 상태 전이 없음, `SubmissionService` 완전일치 조회가 MERGED 항목을 거르지 않음, 클러스터 병합 후보에 RESOLVED 포함.

### 1.4 운영 콘솔·프로세스

**O1. 진입 화면이 상수다 (⑦).** `QueueSummaryService.java:81-83` 어뷰징·이의제기·신고 타일이 `0, "-", false` 고정 — 신고 서브시스템(`Report`, `ReportAdminController`)은 **존재**하므로 실재하는 미처리 신고를 0으로 오표시. `admin/src/components/Layout.tsx:11-14` 사이드바 뱃지 24/6/3/2 하드코딩. `admin/src/api/client.ts:37` `USE_FIXTURES` 기본 `true` → `.env` 없는 빌드는 읽기=픽스처·쓰기=실서버 혼합.

**O2. 결정의 근거 화면이 없다 (⑧).** `/admin/users/**` 컨트롤러 부재. ADM-311은 `useAdminUser("user_4410")` 하드코딩 픽스처. `AuditLogEntryResponse`에 `detail` 없음(DB에는 저장) — 병합 사유·파라미터 전후값·ADJ 금액을 감사자가 볼 수 없다. 검색·필터 없음, 최신 200건 고정.

**O3. 통제가 UI에만 있다 (⑨⑩).**
- `PII_VIEW`를 기록하는 서버 코드 없음. `UserLedgerScreen.tsx:46` "이 열람은 감사 로그에 기록됩니다", `Layout.tsx:106` 전역 문구 — 거짓.
- `POST /admin/verdicts/{id}/rejudge`(O/A 단독) → `reconcileLedger()`가 상한 없이 ADJ 기록, `ApprovalService` 미경유. "100점 초과 상쇄 원장 2인 승인" 무력화.
- `ApprovalExecutor` 구현체는 `ParamApplyExecutor` 하나. V6가 선언한 SANCTION/GRADE_ADJUST/LEDGER_ADJ_OVER100은 executor·엔드포인트 없음.
- 관리자 계정 생성·권한 변경 ADMIN 단독 → 계정 하나 더 만들면 자기 요청 자기 승인. 요청자≠승인자 CHECK는 계정 수준.
- 역할 매트릭스 불일치: AUDITOR가 신고 큐·파라미터 드래프트 조회 불가(문서는 전 영역 읽기), REVIEWER가 임시 비공개 단독 실행(문서는 O/A).
- `CAN.ledgerAdj`, `CAN.sanctionRequest`는 프론트 게이트만 있고 서버 없음.

**O4. SLA — 감지·통보·조치 전부 없음.** 스케줄러 잡 3개(cluster_merge/verdict_runner/grade_recalc). SLA 타이머 잡 0개. 병합 24h는 계산만 하고 "판정 유예 자동 연장"은 배너 문구뿐 → 병합 미완료 상태로 D+14에 그대로 판정. 신고 4h 자동 임시 비공개는 `ReportAdminService.java:27-28`이 "의도적으로 없음"이라 선언 — CLAUDE.md·02·05 문서와 정반대(⑫).

**O5. 병합 큐 워크플로.** `requirePending()`은 잠금 없는 `findById`. `assigned_to`·`@Version` 없음 → 검수자 A "병합" + B "분리"가 서로 다른 행을 건드려 충돌 미감지. `hold` 없음, `separate`가 `SKIPPED`로 종결되어 보류와 분리가 같은 상태. VOID 사유 null 허용.

**O6. 계정 위생.** 부트스트랩 ADMIN 비밀번호 영구·평문 env, 변경 엔드포인트 없음, 90일 미접속 비활성화 없음.

### 1.4.1 API 표면 — 스펙과 구현의 삼중 괴리 (SP0 Task 6에서 발견)

`05-screen-endpoint-map.md`를 컨트롤러와 한 줄씩 대조한 결과, **세 가지 어긋남이 동시에** 존재했다.

| 유형 | 예 |
|---|---|
| (a) 문서가 "스펙 미포함(🔶)"이라 했으나 `openapi.yaml`에 이미 있음 | `/v1/me/summary`, `/v1/me/reads`, `/admin/users/**`, `/admin/sanctions`, `/admin/appeals`, `/admin/abuse-flags`, `/admin/grade-policy` |
| (b) 스펙에 있고 문서가 ✅라 했으나 **컨트롤러가 없음** | `POST /v1/appeals`, `/admin/users/**`, `/admin/sanctions`, `/admin/appeals`, `/admin/abuse-flags`, `/admin/grade-policy`, `POST /admin/accounts/{id}/role` |
| (c) 문서·스펙의 경로가 실제 컨트롤러와 다름 | `/admin/trends` → 실제 `/admin/trend-items` · `/admin/seeding` → `/admin/seed/submissions`·`/admin/seed/accuracy` · `/admin/parameter-drafts`(POST, 다중) → `/admin/params/draft`(PUT, 단일 활성) · `/admin/merge-queue/{id}/action` → `/merge`·`/separate`·`/void` · `/admin/trends/{id}/exceptions` → `/admin/verdicts/{id}/...` |

**운영·법무에 직결되는 세 건(직접 확인함):**

1. **`POST /v1/appeals`는 404다.** `api-public`에 appeals 컨트롤러가 아예 없다. 이의 제기는 CLAUDE.md 법무 체크리스트 항목이고 약관에 처리 기한(5영업일)을 명시해야 하는 절차인데, **유저가 이의를 제기할 수단 자체가 없다.** 등급·판정을 공개하기 전에 반드시 필요 → SP3(ADM-400과 짝).
2. **관리자 권한 부여·회수가 불가능하다.** `AdminAccountController`에 있는 것은 create·disable·enable뿐이고 `POST /admin/accounts/{id}/role`은 스펙에만 있다. 역할을 바꾸려면 계정을 새로 만드는 수밖에 없는데, 이는 P8(신규 계정 7일 승인권 유예)과 정면으로 부딪친다 → SP3.
3. **검색이 동작하지 않는다.** `TrendController.list()`는 `daily`만 읽는다. 스펙이 선언한 `q`·`category`·`sort`·`cursor`는 전부 **조용히 무시**된다 — 앱의 "검색 판정" 화면과 제보 시 중복 감지(`GET /v1/trends?q=`)가 성립하지 않는다. 03 §1이 말하는 "중복 감지 누락"의 실제 원인 → SP2(정규화와 함께).

`/v1/reports` 계열(유저 측 신고·소명)은 구현·스펙 모두 있으나 화면-엔드포인트 맵에서 통째로 빠져 있었다 — 문서가 기능의 존재 자체를 놓친 사례.

### 1.5 문서

- CLAUDE.md: R5(누적치→증가분)가 외부 지표 전제. "⚠ first_seen_at이 바뀌면 baseline 재계산" 경고는 존재하지 않는 baseline을 가리킴. 상세 문서 경로가 `docs/`로 적혀 있으나 실제는 저장소 루트.
- `01-system-design.md` §4(외부 지표 판정), §10(`metric_collector`), §3.2 동의, §3.3 쿼터, §6 등급·강등, §7 어뷰징 — 판정 절은 피벗 이전, 나머지는 미구현.
- `03-merge-clustering.md` first_seen_at→baseline 재계산 절, Instagram 손실 근거의 24h SLA.
- `04-development-plan.md` 197·274행 `metric_collector`.
- ADM-900(배치 관리)·`POST /admin/batch-jobs/**`는 02·05 어디에도 없음.

---

## 2. 결정

### 2.1 원칙 개정 (CLAUDE.md)

**R4 (개정)** — *자동은 플래그와 가역적 보전 조치까지, 결정은 사람이*
> 어뷰징 탐지·병합 후보 추출은 자동. 제재 확정·병합 확정·비공개 **확정**은 사람.
> 임시 비공개(`TEMP_HIDDEN`)는 되돌릴 수 있는 보전 조치이므로 신고 큐 SLA(4h) 초과 시 **자동 적용을 허용**한다. 사람이 확인해 해제하거나 확정한다.
> 오탐으로 인한 자동 강등·자동 제재 확정은 절대 구현하지 않는다.

**R5 (대체)** — *판정 입력은 제보 데이터뿐이며, 제보는 누적이 아니라 시간 분포로 본다*
> 외부 사이트 지표(해시태그 게시물 수, 갤러리 글 수, 검색량 등)는 어떤 형태로도 판정에 들어가지 않는다.
> "2주 안에 N명이 제보했다"는 단순 누적 카운트는 유행의 증거로 불충분하다. 서로 다른 제보자 수에 더해 **제보의 시간 분포(지속성)**, **플랫폼 다양성**, **제보자 독립성**을 신호로 쓴다.
> (현행 코드는 제보자 수 축만 구현. 나머지 축은 SP4에서 Phase 0 백테스트와 함께 도입.)

**점수 공식 절 수정** — `d`(시간감쇠)를 Δ에서 분리해 AS 정의로 옮긴다.
```
HIT :  Δ = + c × w_order × (1 + m)
MISS:  Δ = − c × 0.5
VOID:  Δ = 0
AS   = Σ Δ_i × 0.5^(원장 기록일로부터 경과일 / 90)
```
원장에는 감쇠 없는 원값을 기록한다(R2 — "그때 얼마를 받았나"가 불변). 감쇠는 조회 관심사.

> **반대급부**: 조회 시점 감쇠는 반감기를 바꾸면 과거 AS가 전부 재계산된다. 반대로 원장에 감쇠를 미리 곱해 두면 값이 박제되어 반감기 변경이 과거에 닿지 않는 대신, 바꾸려면 원장을 UPDATE해야 한다(R2 위반). 전자를 택하되 ADM-600 "비소급 예약 적용"과의 충돌은 O11에서 정한다 — 조회에 전역 현재값을 쓸지, 원장 행 기록 시점의 파라미터 버전을 쓸지.

**"⚠ first_seen_at이 바뀌면 baseline 재계산 필수" 삭제** → 대체:
> ⚠ first_seen_at이 앞당겨지면 `order_rank`와 (SP4 이후) 시간 분포 신호가 바뀐다. 판정 전이면 병합 시 재계산, 판정 후(RESOLVED)면 병합 자체를 금지한다.

**배치 잡 표** — `sla_watch`(시간당) 추가, `abuse_scan`·`l4_quota`는 "미구현(Phase 2/3)" 표기. `cluster_merge`에 수동 트리거(ADM-900) 명시.

**상세 문서 경로** `docs/` → 저장소 루트로 수정.

### 2.2 정책 확정

| # | 결정 | 근거 |
|---|---|---|
| P1 | 신고 4h 초과 시 **자동 임시 비공개** (문서 쪽 채택, 코드 주석 폐기) | 당직 인력 계획 없이 금요일 밤 명예훼손 신고가 월요일까지 노출되는 리스크가 더 큼. R4 개정으로 원칙과 양립 |
| P2 | 시딩(`is_seed`)은 `distinctSubmitters`에서 제외. 클러스터 앵커 역할만 | R1 — 운영진이 HIT를 만들 수 없어야 함 |
| P3 | 제보권 쿼터·리필·VOID 반환은 Phase 1 **선행 조건** | 쿼터 없는 베팅 구조는 난사를 보상 |
| P4 | RESOLVED 항목 병합 **금지**(409). 03 문서의 "판정 후 병합 = ADJ 원장"은 Phase 2 이후 | 이중 점수 차단이 우선 |
| P5 | VOID는 판정 공식의 출력이 아니라 **사건**(항목 VOID·중복 제보)의 결과. `voidByRule` 플래그 제거 | 판정 시점 VOID는 "유효 제보 0건"뿐 |
| P6 | 판정 로직은 `JudgeService` 하나. 배치·관리자 재판정·파라미터 시뮬레이션이 공유 | R1 + 코드 규약 "같은 함수 재사용" |
| P7 | 통제는 서버 계층 한 곳(`ApprovalGate`). 프론트 `CAN`은 표시용 | UI 게이트는 통제가 아님 |
| P8 | 관리자 계정 생성·권한 변경 2인 승인 + 신규 계정 승인권 유예(생성 후 7일) | 4-eyes를 계정 수준 → 사람 수준으로 |
| P9 | 목표 제보자 수는 **상대값** `max(하한, 최근 N일 활성 제보자 × 비율)` 방향. 값은 SP4 백테스트로 | 절대값 20은 유저 규모와 무관 |
| P10 | 콘솔 진입 화면·뱃지는 단일 `QueueSummary` 쿼리. 미구현 큐는 "미구현" 표시, 0 금지 | 상수 표시는 오표시 |

### 2.3 구조 선택

**판정 파이프라인 (SP1)**
```
VerdictRunner (배치, 목록 순회만)
VerdictAdminService.rejudge (관리자)          ─┐
ParamStudioService.simulate (시뮬)            ─┼→ JudgeService.judge(itemId, params, now)  [@Transactional, 별도 빈]
                                                │     1. item.transitionTo(JUDGING)
                                                │     2. TrendSignal 집계 (VOID·seed 제외, 제보 시각·플랫폼·제보자 메타 포함)
                                                │     3. VerdictComputation.run(signal, params)  [순수 함수]
                                                │     4. verdict + score_ledger 저장 (원값)
                                                │     5. submissions.result, item.RESOLVED
```
멱등성은 DB 제약: `verdicts(trend_item_id) WHERE supersedes IS NULL` 부분 UNIQUE는 **이미 있다**(`V4:26` `verdict_one_original_per_item` — SP0 최종 리뷰에서 정정). SP1이 신설할 것은 `score_ledger(verdict_id, submission_id)` UNIQUE뿐이다.
`TrendSignal`은 SP4가 파이프라인을 다시 열지 않도록 지금부터 확장 형태(제보 시각 목록·플랫폼 enum·제보자 가입일/디바이스 해시)로 정의한다.

**병합 (SP2)**
- 두 항목 id 오름차순 `SELECT … FOR UPDATE`. 상태 가드: `PENDING`만 병합 가능, `JUDGING` 409 재시도, `RESOLVED` 409.

  > ⚠ **상태만 보는 가드는 SP1 전까지 동작하지 않는다.** A1(self-invocation) 때문에 배치가 판정한 항목도 `trend_items.state`는 `PENDING`으로 남는다 — `state == PENDING` 가드가 이미 판정된 항목을 통과시켜 이중 점수를 낸다. 가드는 `verdicts.existsByTrendItemIdAndSupersedesIsNull(id)`를 **함께** 봐야 한다(배치·관리자 코드가 이미 쓰는 술어). 이것이 SP1이 SP2에 선행해야 하는 진짜 이유다. 게다가 `ClusterMergeCandidateService:72`의 후보 스캔이 RESOLVED를 포함하므로 ≥0.85 자동 병합 경로에서 **지금도 실제로 발생 가능**하다.
- `Idempotency-Key` → `merge_queue.decision_key UNIQUE`. 재요청은 이전 결과 반환.
- **완전일치 조회가 tombstone을 따라가게 한다.** UNIQUE는 이미 존재하므로(A3 정정) 추가하지 않는다. ~~부분 UNIQUE~~는 **채택 불가** — `watches.normalized_key`가 이 컬럼을 FK로 참조하는데(`V24:19`) PostgreSQL에서 부분 UNIQUE 인덱스는 FK 대상이 될 수 없다. 대신 `SubmissionService`의 완전일치 조회가 `state = 'MERGED'` 항목을 만나면 `merged_into`를 따라 승자에 합류하도록 고친다. UNIQUE 충돌 시 재조회 후 합류(현재는 조회-후-삽입이라 레이스).
- 큐 상태 모델: `PENDING → CLAIMED → (MERGED | SEPARATED | VOIDED | HELD)`, `HELD` 3회 → `ESCALATED`. 클레임 15분 만료. VOID 사유 필수. (현행 `MergeQueueStatus`는 `{PENDING, MERGED, VOIDED, SKIPPED}` — `SKIPPED`가 오늘의 "분리"이므로 개명 포함.)

**SP2에 추가로 편입 — SP0 중 발견**

- **정규화가 사실상 없다.** `NameNormalizer.normalize()`는 NFC → `strip()` → 공백 축약 → 소문자화가 전부다. 03 문서가 표로 정리한 특수문자 제거·조사 탈락·반복 문자 축약·영한 혼용·외래어 표기 흔들림은 **전혀 구현돼 있지 않다.** 그래서 03 §1의 전제 예시부터 깨진다 — `두바이초콜릿`과 `두바이 초콜릿`은 오늘 완전일치로 병합되지 않는다. "완전일치에서 대부분이 걸러진다"는 서술은 성립하지 않으며, 임베딩 단계가 그만큼 과부하를 받는다.
- **`aliases[]`는 쓰기 전용.** `MergeService:154-157`만 기록하고 어떤 조회도 읽지 않는다. 표기 변형 흡수에 기여하지 않는다.
- **분리(split)는 존재하지 않는다.** `MergeService.recordSeparateDecision()`은 감사 로그만 남기는 *후보 기각*이며 클러스터를 쪼개지 않는다. 03 §5의 "분리 시 최소 7일 관측"은 전부 미래 설계다.
- **병합 큐에 테스트가 하나도 없다.** `src/test` 어디에도 `MergeQueue` 참조가 없다. 잠금·멱등·상태 가드를 넣기 전에 회귀 테스트부터 필요하다.
- 유예 연장은 `grace_until`이 아니라 **`trend_items.judgment_deadline_override`**(`V16`)이며, API는 **일 단위 1~7일**에 상한 **D+21**(`MAX_GRACE_DAYS=7`)이다. 시간당 잡이 "+24h"를 무한 연장하는 설계는 이 API로 표현되지 않는다 — 상한 도달 시 에스컬레이션으로 바꾼다.

**콘솔 통제·SLA (SP3)**
- `ApprovalGate.require(kind, amount)` — rejudge(ADJ 합계 > 100), 계정 생성/권한 변경, 향후 제재·등급 조정이 공통 호출. executor: SANCTION·GRADE_ADJUST·LEDGER_ADJ_OVER100.
- PII 마스킹 해제는 `POST /admin/users/{id}/unmask` 경유, 서버에서 `PII_VIEW` 기록. 클라이언트 토글 제거.
- `sla_watch` 잡(시간당): 신고 4h → `TEMP_HIDDEN` + 에스컬레이션 / 병합 24h → 대상 항목 `grace_until` +24h / 90일 미접속 관리자 비활성화. 통보는 `SlaNotifier` 인터페이스, Phase 1은 로그 구현체.
- 역할 정합: AUDITOR 전 영역 읽기, 임시 비공개는 O/A.
- `score_ledger.approved_by_admin_id` 추가(ADJ 승인자 표시).

---

## 3. 서브프로젝트 분해와 순서

기준: 닫힌 베타(Phase 1)를 시작하려면 무엇이 먼저 사실이어야 하는가.

| # | 이름 | 범위 | 비범위 | 완료 기준 |
|---|---|---|---|---|
| **SP0** | 원칙·문서 정비 | §2.1 전부 적용. 01 §4·§10·03 baseline 절·04 `metric_collector` 삭제/개정. §2.2 정책을 02·05에 반영(ADM-900·배치 API 추가, 자동 임시 비공개 명시). `ReportAdminService` 주석 정정 | 코드 변경 없음(주석 제외) | 문서에 외부 지표 판정 서술 0건. CLAUDE.md R4/R5가 §2.1과 일치 |
| **SP0.5** | 즉시 수정 묶음 | CSRF 활성화 + 로그인 lockout, `USE_FIXTURES` 기본 `false`, 뱃지 하드코딩 제거(요약 API 연결 전까지 숨김), DB 기본 호스트 `localhost`, `twofaEnabled` 기본값 일치 → `2026-09-18-sp05-hardening-design.md` | 설계 논쟁이 있는 것 전부 | PR 1개. 기존 테스트 통과 + CSRF 토큰 없는 POST 403 테스트 |
| **SP1** | 판정 파이프라인 정합성 | `JudgeService` 단일화(P6), `JUDGING` 전이, 시딩 제외(P2), VOID 사건화 + 제보권 반환(P5), **쿼터·리필 구현(P3)**, 원장 원값 + AS 조회 감쇠, DB 멱등 제약, 재판정 파라미터 정합, `TrendSignal` 확장 형태 | 신호 공식 변경(SP4) | 통합 테스트: 배치 1회 실행 후 `submissions.result`·`trend_items.state`가 DB에 반영, 2회 실행 시 원장 행 불변, 시딩 4건+실유저 3건은 MISS |
| **SP2** | 병합 무결성 | §2.3 병합 항목 전부 + ADM-100 UI(클레임·보류·에스컬레이션·멱등키 헤더) | 판정 후 병합(ADJ) | 동시 병합/분리 테스트에서 하나만 성공. RESOLVED 병합 409. 동일 키 재요청 동일 응답 → 무결성 묶음은 `2026-09-21-sp2-merge-integrity-design.md`, 큐 워크플로(클레임·보류·에스컬레이션·ADM-100 UI)·정규화는 SP2b |
| **SP3** | 콘솔 통제·SLA | 실데이터 `QueueSummary`(P10), `AdminUserController`(ADM-311) + 감사 `detail`·필터, `ApprovalGate`(P7) + executor 3종, `PII_VIEW`, 역할 정합, `sla_watch`(P1), 계정 위생(P8 포함) | 어뷰징·이의제기 큐 본체(Phase 2) | 신고 4h 초과 시 자동 `TEMP_HIDDEN` 통합 테스트. rejudge 101점 ADJ가 승인 대기로 전환. UI에 "감사 기록" 문구는 서버 기록이 있는 곳만 |
| **SP4** | 판정 신호 재설계 | 상대 목표치(P9), 시간 분포 축, 플랫폼 다양성(enum으로 값 목록 확정·기존 자유 텍스트 이관·앱·관리자 폼·API·DB 동시 변경), 제보자 독립성 압축, `VerdictComputation` 확장 + 파라미터 스튜디오 시뮬 + **Phase 0 백테스트 30건** | 어뷰징 제재(별도) | 백테스트 30건에서 기존 공식 대비 정밀도·재현율 보고서. 새 파라미터가 스튜디오 승인 경로로 적용 |

**순서와 근거**
- SP0 → SP0.5 → SP1 → SP2 → SP3 → SP4.
- SP1이 첫 코드 작업인 이유: 판정이 저장되지 않는 상태(④)는 나머지 전부의 전제. 쿼터는 판정과 같은 트랜잭션 경계를 쓰므로 함께.
- SP2가 SP1 직후인 이유: SP1이 RESOLVED 항목을 만들기 시작하는 순간 이중 점수(⑥)가 실제로 터진다.
- SP4가 마지막인 이유: SP1 없이는 새 공식도 저장이 안 되고, 백테스트 없이는 새 공식의 정당성이 없다(O1과 같은 "근거 없는 추정치"가 하나 더 생길 뿐). SP1의 `TrendSignal` 확장 형태가 SP4의 진입점.

---

## 4. 실행 규칙

1. **각 서브프로젝트 안에서 API(백엔드) 먼저, 프론트 로직은 그다음.** 백엔드 작업이 커밋·리뷰를 통과한 뒤 프론트 작업을 시작한다. 프론트는 픽스처가 아니라 실제 응답 스키마에 맞춘다.
2. 서브프로젝트마다 별도 스펙(`docs/superpowers/specs/`) → 계획(`docs/superpowers/plans/`) → 워크트리 → 서브에이전트 구현 → PR. `main` 직접 커밋 없음.
3. 문서(01~05, CLAUDE.md)는 저장소 루트. SP0 결과가 병합되기 전에는 SP1 스펙을 시작하지 않는다(에이전트가 옛 문서를 읽으면 안 됨).
4. 점수·판정 로직은 순수 함수 + 파라미터 주입(기존 규약). `JudgeService`는 오케스트레이션만.
5. 마이그레이션은 서브프로젝트당 V-번호 연속. 제약(UNIQUE·CHECK)은 코드 가드 대신 DB에.

---

## 5. 열린 결정 사항 갱신

| # | 항목 | 상태 |
|---|---|---|
| O1 | 목표 제보자 수·임계값 | **상대값 방향 확정(P9)**. 값은 SP4 백테스트 |
| O2 | 판정 유예 14일 | 유지. `sla_watch`가 병합 지연 시 연장 |
| O4 | 확신도 3단계 | 유지 |
| O5 | 반감기 90일 | 유지. 카테고리별 차등은 미검토 |
| O6 | 독립 서비스/커머스 부가모듈 | 미정(변동 없음) |
| O7 | 임베딩 임계값 | 유지 |
| **O8 (신규)** | 시간 분포·플랫폼 다양성·독립성 축의 가중치 | SP4 |
| **O9 (신규)** | 판정 후 병합(ADJ 경로) 도입 시점 | Phase 2 |
| **O10 (신규)** | SLA 통보 채널(메신저 웹훅) | Phase 2. Phase 1은 로그 |
| **O11 (신규)** | AS 조회 감쇠에 쓸 반감기 버전 — 전역 현재값 vs 원장 기록 시점의 파라미터 버전 | SP1. §2.3 참조 |

---

## 6. SP0 실행 체크리스트 (이 스펙의 구현)

- [x] CLAUDE.md: R4 개정, R5 대체, 점수 공식 절(d → AS), baseline 경고 대체, 배치 잡 표(`sla_watch`·미구현 표기·ADM-900), 상세 문서 경로 수정, "열려 있는 결정" 표에 O8~O10
- [x] `01-system-design.md`: §4 판정 알고리즘을 제보 신호 기반으로 재서술(현행 = 제보자 수, SP4 = 다축), §10 `metric_collector`·S1–S5·baseline 삭제, §3.3 쿼터를 "Phase 1 선행"으로, §6 등급에 L4 없음 명시
- [x] `03-merge-clustering.md`: baseline 재계산 절 → `order_rank`·시간 분포 재계산 + RESOLVED 병합 금지, 24h SLA 근거를 "판정 유예 연장"으로, 클레임·보류·멱등키 절 추가
- [x] `04-development-plan.md`: 197·274행 `metric_collector` 삭제, `sla_watch` 추가, Phase 1 선행 조건에 쿼터
- [x] `02-admin-console.md`·`05-screen-endpoint-map.md`: ADM-900 + `POST /admin/batch-jobs/**` 추가, ADM-410 자동 임시 비공개 확정 표기, ADM-100 클레임/보류/에스컬레이션, ADM-800 2인 승인·승인권 유예
- [x] `ReportAdminService.java:27-28` 주석 → "4h 자동 임시 비공개는 SP3 `sla_watch`에서 구현" 로 정정
- [x] PR: `docs/design-review` → `main`
