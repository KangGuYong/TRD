# 트렌드 레이더 — 개발 설계서 (v0.1)

> **문서 성격**: 구현 착수용 개발 설계. 도메인 규칙·수치는 `01-system-design.md` / `02-admin-console.md` / `03-merge-clustering.md` / `CLAUDE.md`를 상위 근거로 삼는다.
> **작성 기준일**: 2026-08-06 · **개정**: 2026-09-17 (외부 수집기 제거, `sla_watch`·`JudgeService`·`ApprovalGate` 반영. 근거: `docs/superpowers/specs/2026-09-17-design-review-design.md`)
> **확정 스택**: 앱 = React Native(Expo) + Java 백엔드 / 관리자 = React(웹) + Java 백엔드
> **디자인 근거**: `유행수명 앱.dc.html`(앱 7화면), `관리자 콘솔.dc.html`(콘솔 15화면)

---

## 0. 이 문서가 정하는 것 / 정하지 않는 것

| 정한다 | 정하지 않는다 (미결로 이관) |
|---|---|
| 기술 스택, 리포 구조, 모듈 경계 | submitterTarget 상대값·임계값 (O1 — SP4 백테스트) |
| 백엔드 레이어링과 도메인 모듈 | 임베딩 모델 선정 (M2), 다축 신호 가중치 (O8) |
| DB 스키마 이관 방침, 배치 실행 방식 | 독립서비스 vs 커머스 부가모듈 (O6) — 인증 모듈에만 영향 |
| API 표면, 인증·권한·감사 방식 | 클라우드/온프레미스 최종 배포처 |
| Phase별 개발 순서와 마일스톤 | SLA 시간·인력 규모 (A1~A2) |

핵심 설계 판단 3가지는 §2에서 근거와 함께 명시한다.

---

## 1. 절대 원칙 → 구현 가드 매핑

`CLAUDE.md`의 R1~R5는 "코드로 강제"되어야 하며, 리뷰 체크리스트가 아니다.

| 원칙 | 구현 가드 (코드 레벨) |
|---|---|
| **R1** 유저 투표로 유행 결정 금지 | `verdicts` INSERT는 `JudgeService`(배치 판정·재판정·항목 VOID, 재판정·VOID는 supersedes 행)뿐이며 모두 `VerdictComputation` 계산 결과만 쓴다. 투표(`votes`)는 별도 테이블, 판정 입력에서 물리적으로 분리. 관리자 API에 "판정 결과 직접 변경" 엔드포인트 자체가 없음(§7). |
| **R2** 원장 불변(append-only) | `score_ledger`·`verdicts`에 DB 트리거로 `UPDATE/DELETE` 거부(`V7`, 구현됨). 정정은 `ADJ`/`supersedes` INSERT만. ORM 레벨에서도 엔티티에 setter 미노출 + `@Immutable`(`ScoreLedgerEntry`·`Verdict` 둘 다). |
| **R3** 등급 = 적중률 주축(AS AND TI) | 승급 판정은 순수함수 `GradePolicy.evaluate()` 하나로만. 제보 건수 단독 승급 경로 없음(타입상 불가하게 설계). |
| **R4** 자동은 플래그와 가역적 보전 조치까지 | 제재·비공개 **확정**은 사람. 병합은 유사도 ≥0.85만 자동 확정(되돌리려면 분리), 0.75~0.85는 사람(관리자 큐). `abuse_scan`(`abuse_flags` INSERT만)·`sanctions` 2인 승인 상태머신은 **미구현(Phase 2)**. `sla_watch`가 적용할 `TEMP_HIDDEN`(가역, ADM-410)은 **미구현(SP3)** — 현재 자동 임시비공개 자체가 일어나지 않는다. |
| **R5** 판정 입력은 제보뿐 | `VerdictEngine`은 `TrendSignal`(관측 마감 전 비VOID 제보의 제보자·시각·플랫폼·시딩 여부·가입일)만 입력받는다. 외부 지표 테이블·수집기는 존재하지 않는다. VOID·시딩은 제보자 수와 선점 순위에서 제외(구현됨). 시간 분포·플랫폼·독립성 축은 SP4. |

> 점수·등급·판정 계산은 전부 **파라미터 주입형 순수 함수**(`ScoreEngine`, `VerdictEngine`, `GradePolicy`)로 분리한다. 동일 함수를 `verdict_runner`(운영)와 ADM-600 파라미터 스튜디오(시뮬레이션)가 재사용해야 하기 때문(`CLAUDE.md` 규약).

---

## 2. 기술 스택과 3대 아키텍처 판단

### 2.1 스택

| 영역 | 선택 | 근거 |
|---|---|---|
| 백엔드 | **Java 21 + Spring Boot 3.3**, Gradle 멀티모듈 | 지정 스택. 배치·트랜잭션·스케줄 성숙도 |
| DB | **PostgreSQL 16 + pgvector** | 임베딩 유사도(0.85/0.75)를 별도 벡터DB 없이 처리(§5.4). 트랜잭션·행잠금·`RANK()` 윈도우(order_rank) 요구 충족 |
| 락/멱등/캐시 | **Phase 0~1: PostgreSQL로 처리** (advisory lock·유니크 제약·JDBC ShedLock) / **Phase 2: Redis 7 도입** | 소프트락·멱등키·단일실행은 초기 동시성에선 Postgres로 충분. Redis는 검수자 락 경합·레이트리밋·SLA 디바운스가 실제 부하가 되는 공개 이후에 추가(§2.5) |
| 마이그레이션 | **Flyway**(마이그레이션 도구는 필수) | 스키마 이력 = 감사 대상. **append-only 트리거(R2 강제)를 버전관리된 DDL로** 관리. `ddl-auto` 자동 스키마 금지 |
| 배치 | **Spring Scheduler + ShedLock(JDBC 백엔드)** → 규모 커지면 Spring Batch | 잡 4종(§6: `cluster_merge`·`verdict_runner`·`grade_recalc`·`sla_watch`) + Phase 2/3 `abuse_scan`·`l4_quota`. 멱등·재실행 필수. ShedLock JDBC로 다중 인스턴스 단일 실행 |
| 앱 | **React Native + Expo (SDK 최신), TypeScript** | 지정 스택. OTA 업데이트·푸시(expo-notifications) — "아침 8시 알림 하나"가 앱의 핵심 |
| 관리자 | **React 18 + Vite + TypeScript**, TanStack Query + Table | 지정 스택. 데스크톱 전용(콘솔 §4), 큐 대량 처리·서버 페이징에 적합 |
| 인증 | 앱: OIDC/JWT(액세스+리프레시) · 콘솔: 세션+2FA, 30분 타임아웃 | 콘솔 보안요건(ADM-800)이 앱과 달라 분리 |

### 2.2 판단 ①: 백엔드는 **단일 모듈러 모놀리스**, API 표면만 2개

앱과 콘솔이 **같은 도메인/원장/판정 엔진**을 공유한다. 별도 백엔드 2개로 나누면 점수 계산 로직이 이원화되어 R2/R3가 깨질 위험이 크다. 따라서 하나의 Spring Boot 애플리케이션 안에서 `api-public`과 `api-admin`을 **별도 컨트롤러 패키지 + 별도 시큐리티 필터체인**으로 분리한다. 물리 분리는 트래픽/보안 요구가 확정된 뒤(부록 A 공공환경 등) 모듈 경계 그대로 서비스 분리.

### 2.3 판단 ②: **모노레포**

```
trend-radar/
├─ backend/            # Gradle 멀티모듈 (Java)
├─ app/                # Expo (React Native)
├─ admin/              # Vite + React
├─ docs/               # 설계문서 (본 문서 포함)
└─ infra/              # docker-compose, flyway, CI 파이프라인
```
근거: 도메인 규칙(점수 공식·등급 임계값·enum)이 세 곳에서 동일해야 한다. 공유 스펙(OpenAPI)에서 앱/콘솔 타입을 코드생성해 드리프트를 막는다.

### 2.4 판단 ③: **API 우선(OpenAPI) + 타입 코드생성**

백엔드가 OpenAPI 3.1 스펙을 산출 → 앱/콘솔은 `openapi-typescript`로 타입·클라이언트 생성. 등급/판정 응답의 "산정 근거 동봉" 규약(§7)을 스펙에 강제한다.

### 2.5 Redis는 Phase 2로 지연 (선행 결정 반영)

초기에 Redis에 맡기려던 4가지 역할은 Phase 0~1 동시성 수준에서 **전부 PostgreSQL로 대체 가능**하다. 도구를 늘리기 전에 실제 부하를 관측하는 편이 낫다.

| 용도 | Phase 0~1 대체 | Redis 도입 트리거 |
|---|---|---|
| 병합 큐 소프트락(15분 클레임) | **미구현(SP2)** — 설계는 `merge_queue.assigned_to`·`claimed_at`(03 §6 M5), 현재 스키마엔 클레임 컬럼이 없다 | 다중 검수자 락 경합 관측 |
| 멱등성 키 | DB 유니크 제약 | — |
| 배치 단일 실행 | ShedLock **JDBC** 백엔드 | — |
| 레이트리밋 / SLA 알림 디바운스 | 초기 불필요 | 공개(Phase 2) 후 트래픽·알림 폭주 |

→ **Flyway(마이그레이션 도구)는 착수부터 필수**: append-only 트리거(R2)·제약이 버전관리된 DDL로 존재해야 환경 간 재현·감사·롤백이 된다.

---

## 3. 시스템 아키텍처

```
                 ┌──────────────┐        ┌──────────────┐
 [모바일 유저] ── │  App (Expo)  │        │ Admin (React)│ ── [운영자/관리자]
                 └──────┬───────┘        └──────┬───────┘
                        │ JWT/HTTPS             │ Session+2FA/HTTPS
                        ▼                       ▼
        ┌───────────────────────────────────────────────────┐
        │        Spring Boot (모듈러 모놀리스)                │
        │  api-public │ api-admin │ scheduler(배치) │ webhook │
        │  ───────────────────────────────────────────────  │
        │  domain-core : ScoreEngine · VerdictEngine ·       │
        │                GradePolicy (순수)                   │
        └───────┬───────────────┬───────────────┬───────────┘
                ▼               ▼               ▼
        [PostgreSQL+pgvector] [Redis(Phase 2)]   [Embedding 서비스 (TEI, KURE-v1)]
```

배치 실행: 스케줄러가 `merge`·`JudgeService` 등 서비스 빈을 호출한다. 잡은 전부 멱등(§6). **배치 클래스 안에서 같은 빈의 `@Transactional` 메서드를 직접 호출하지 않는다** — 프록시를 타지 않아 트랜잭션이 무효가 된다. 판정·병합처럼 트랜잭션이 필요한 작업은 별도 서비스 빈으로 두고 배치는 목록 순회만 한다.

---

## 4. 백엔드 모듈 구조 (Gradle 멀티모듈)

```
backend/
├─ domain-core/        # 엔티티, 순수 계산 엔진, 정책. 프레임워크 의존 최소
│   ├─ score/          ScoreEngine (HIT/MISS/VOID Δ, TI, AS)
│   ├─ verdict/        VerdictEngine (TrendSignal → T, reach_level 판정. 시딩 제외)
│   ├─ grade/          GradePolicy (승급 AND 조건, L3까지. 강등 규칙은 **미구현**)
│   ├─ trend/          NameNormalizer (NFC·공백 정리·소문자화. 조사 탈락·반복 축약·영한 혼용은 **미구현(SP2)**)
│   ├─ vote/           투표 집계(판정 입력 아님 · R1)
│   └─ params/         ParameterSet (가중치·임계값·반감기 주입 객체)
├─ persistence/        # JPA/JDBC, Flyway, append-only 트리거. order_rank는 저장 컬럼이 아니라 뷰 매핑(`SubmissionOrderRank`, `@Immutable`, V11)
├─ api-public/         # /v1/** 앱 API + JWT 필터체인
├─ api-admin/          # /admin/** 콘솔 API + 세션·RBAC · 2FA(**미구현(SP3)**) · ApprovalGate(**미구현(SP3)** — 현행 `ApprovalService`+`ParamApplyExecutor`)
├─ merge/              # 클러스터 병합·임베딩: `MergeService`·`ClusterMergeCandidateService`·`MergeComputation`·`EmbeddingClient` (배치·관리자 공용)
├─ judge/              # 판정 실행: `JudgeService`(배치 판정·재판정·항목 VOID·ADM-111 미리보기의 유일한 경로) · `VerdictEvidence`(evidence_json 형식) — scheduler·api-admin 공용
├─ scheduler/          # 배치 잡 + ShedLock (목록 순회만, 트랜잭션은 서비스 빈)
├─ api-spec/           # OpenAPI (앱/콘솔 타입 코드생성 원본)
├─ audit/              # admin_audit_log (조회행위 포함), 해시체인
└─ app/                # 부트스트랩(단일 실행 파일), 프로파일 분리
```

**설계 규칙**
- `domain-core`는 `ParameterSet`을 **주입받는다**. 하드코딩된 가중치 없음 → ADM-600 시뮬레이션이 임의 파라미터로 같은 엔진 호출.
- 점수 산정 결과는 `ScoreResult{ delta, breakdown }` 형태로 **산정 근거(breakdown)를 항상 동봉**(§7 응답 규약).
- 시간 값은 **전부 UTC 저장**, 표시 변환(KST)은 클라이언트/DTO 직렬화 계층에서만.

---

## 5. 데이터 모델 & 핵심 알고리즘 구현

### 5.1 스키마 이관 (01 §2.1 기준)

| 테이블 | 구현 주의 |
|---|---|
| `users` | status enum. 앱/콘솔 공용 |
| `trend_items` | `canonical_name`, `aliases text[]`, `first_seen_at`, `state`, `version`(낙관적 락), `merged_into`(tombstone). `normalized_key` 전체 UNIQUE(`V24`, `watches`가 FK 참조) — MERGED 항목이 키를 점유하므로 완전일치 조회가 `merged_into`를 따라가야 한다(**미구현(SP2)**) |
| `submissions` | `confidence`(10/30/50), `order_rank`는 **저장 안 함**(파생, §5.3 — 뷰에서 VOID·시딩 제외), `disclosure`, `voided_at`(VOID 시각 = 제보권 반환 기준, result=VOID와 함께만 — CHECK), `resolved_at`(처음 HIT/MISS 판정 시각 = TI 180일 창 기준) — V28 |
| `endorsements` | 유저 단위 dedup. 현행 저장만(점수·판정 미반영) |
| `merge_queue` | **미구현(SP2)**: `assigned_to`·`claimed_at`(15분 클레임, 03 §6 M5), `hold_count`, `decision_key UNIQUE`(멱등키) — 현재 스키마(`V15`)는 `status`·`similarity`·`resolved_by`뿐이고 클레임 컬럼 자체가 없다 |
| `verdicts` | **append-only**. `supersedes`, `evidence_json`(`VerdictEvidence` — 선점 순위·`TrendSignal`·판정 파라미터 동결). `(trend_item_id) WHERE supersedes IS NULL` 부분 UNIQUE(`V4` — `verdict_one_original_per_item`), `(supersedes) WHERE supersedes IS NOT NULL` 부분 UNIQUE(`V28` — `verdict_superseded_once`, 재판정 체인 분기 방지) |
| `score_ledger` | **append-only**. `reason`, `kind`(HIT/MISS/VOID/ADJ). `delta`는 감쇠 없는 원값(§5.2). `halflife_days`·`decay_anchor_at`(행별 감쇠 기준, O11 = (b), V28). `(verdict_id, submission_id)` UNIQUE(`V28`, 판정 멱등). `approved_by`는 `users(id)` FK뿐이라 관리자를 못 넣는다 — **미구현(SP3)**(승인자는 감사 로그에만 남음) |
| `user_grades` | 스냅샷(파생). 주간 재계산 |
| `abuse_flags` | 플래그만. 제재 아님 |
| `appeals`, `sanctions`, `votes`, `admin_audit_log`, `parameter_drafts` | §7·§8 참조 |

**append-only 강제**: `score_ledger`, `verdicts`에 `BEFORE UPDATE OR DELETE` 트리거 → `RAISE EXCEPTION`. Flyway 마이그레이션으로 관리. R2를 DB가 물리적으로 보장.

### 5.2 점수 엔진 (01 §5 → 순수 함수)

```
HIT :  Δ = + c × w_order × (1 + m)
MISS:  Δ = − c × 0.5              (실패=이득의 절반, 의도된 비대칭 — 변경 금지)
VOID:  Δ = 0
AS   = Σ Δ_i × 0.5^(decay_anchor_at_i 로부터 경과일 / halflife_days_i)   ← 감쇠는 조회 시점에만, 원장은 원값
TI   = (HIT+2)/(HIT+MISS+5)  (최근 180일 — resolved_at 기준, 시딩 제외, α2 β3, 초기 0.4)
```
`ScoreEngine.compute(submission, verdict, params)` → `ScoreResult`. `w_order`·`m` 테이블(01 §5.1, §4.3)은 `ParameterSet`에서 주입. 단위 테스트에 docs의 예시값(짭쪼롬 +60 등)을 골든 케이스로 고정.

> 원장에는 `ScoreEngine`의 원값 Δ를 소수 4자리로 기록하고, 감쇠는 `ActiveScore`가 원장 행마다 기록된 반감기·기준 시각으로 조회 때만 적용한다(O11 = (b)) — 반감기를 바꿔도 과거 AS는 그대로다. 재판정·VOID 차액(ADJ)은 원 판정과 같은 기준으로 기록해 정확히 상쇄된다. `grade_recalc`와 `/v1/me/grade`는 같은 입력(`GradeInputsReader`)을 읽는다.

### 5.3 order_rank — 저장 컬럼 아님 (03 §3.1)

병합 검수가 최대 24시간 지연되므로 "최대값+1" 부여는 선점자를 뒤로 민다. `created_at` 기준 파생:
```sql
SELECT id, RANK() OVER (ORDER BY created_at ASC) AS order_rank
FROM submissions WHERE trend_item_id = :target AND result <> 'VOID'
```
판정 시점(D+14) `verdicts.evidence_json`에 **스냅샷 동결**. 이후 병합돼도 확정 점수 불변.

### 5.4 병합·클러스터링 (03)

4단계: 정규화(NFC·공백 정리·소문자화 — 구현됨. 조사 탈락·반복 축약·영한 혼용은 **미구현(SP2)**) → 완전일치 즉시병합 → 임베딩 코사인 → 회색지대(0.75~0.85) ADM-100 큐. pgvector로 `≥0.85` 자동/그 미만 큐 분기.

**반드시 테스트로 거는 회귀 케이스**(에러 없이 조용히 틀리는 유형):
1. `first_seen_at` 변경 → **order_rank 재계산**이 같은 트랜잭션에서 일어남(03 §3.2).
2. 같은 유저 A·B 제보 병합 → 늦은 쪽 **VOID + 제보권 반환**(03 §4.4).
3. RESOLVED 상태 병합 시도 → **409**(03 §4.3, P4). ADJ 상쇄 경로는 Phase 2(O9).
4. 판정 배치 1회 실행 후 `submissions.result`·`trend_items.state`가 **DB에 반영**되어 있음(self-invocation 회귀 — `JudgePipelineTest`).

병합 트랜잭션(목표 설계, 03 §6): 단일 트랜잭션 + 두 항목 id 오름차순 `FOR UPDATE` + `version` 낙관적 락. `Idempotency-Key`→`decision_key UNIQUE`·큐 클레임은 **미구현(SP2)**(§5.1 `merge_queue` 참조).

---

## 6. 배치 잡 (01 §10 · CLAUDE.md)

| 주기 | 잡 | 멱등 보장 방식 |
|---|---|---|
| 일 1회 | `cluster_merge` | `trend_items.merge_checked_at`로 처리 완료 마킹 + `merge_queue_one_open_per_new` UNIQUE로 중복 적재 방지(구현됨). 회색지대는 큐 적재만. ADM-900 수동 실행(같은 ShedLock 이름, 실행 중 409) |
| 일 1회 | `verdict_runner` | 관측 마감 지난 PENDING → JUDGING, 이어서 JUDGING 항목마다 `JudgeService.judge()`(별도 빈, 항목 행 잠금 + 항목별 트랜잭션). 실패한 항목은 JUDGING으로 남아 다음 실행에서 재시도. 원본 판정 재생성 방지는 `verdicts` 부분 UNIQUE(`V4`), 원장 중복 방지는 `score_ledger(verdict_id, submission_id)` UNIQUE(`V28`) |
| 1시간 | `sla_watch` | **미구현(SP3)**. 신고 4h→`TEMP_HIDDEN`+에스컬레이션 / 병합 24h→판정 유예 자동 연장 / 90일 미접속 관리자 비활성화. 유예 연장은 실제로는 `trend_items.judgment_deadline_override`(수동 `VerdictAdminService.extendGrace`, 1~7일 단위·D+21 상한)이며 `grace_until` 컬럼은 존재하지 않는다. 통보는 `SlaNotifier`(Phase 1 로그) |
| 주 1회(월 00:00 KST) | `grade_recalc` | 공식 등급 스냅샷 — AS(행별 감쇠)·TI(180일)·판정완료 건수(전 기간)로 `GradePolicy` 평가. 강등 규칙(Phase 2) 전까지 직전 등급 아래로 내리지 않음(J8). `@Scheduled`에 `zone = Asia/Seoul` 명시(컨테이너 JVM은 UTC). 제보권 리필(이월 없음)은 주 경계 자체라 배치가 없다 |
| 일 1회 | `abuse_scan` | **미구현(Phase 2)**. 플래그 INSERT만(R4) |
| 월 1회 | `l4_quota` | **미구현(Phase 3)**. 정원 재산정, 초과분 L3 이동(페널티 아님 표기) |

전 잡 ShedLock으로 단일 실행. `verdict_runner`·`grade_recalc`는 실행ID로 재실행 안전성 단위테스트 필수.

---

## 7. API 표면

### 7.1 앱 (api-public, 01 §9)
```
POST /v1/submissions            제보 등록·중복검사·제보권 집행(QuotaService, 유저 행 잠금)·관측 마감 후 거부(구현). 422 type = quota-exhausted | item-closed
GET  /v1/submissions/me         내 제보+판정상태
POST /v1/trends/{id}/endorse    동의(중복→endorsement)
GET  /v1/trends                 목록 — 현재는 daily 파라미터만 처리. 필터·정렬·q 검색은 미구현(SP2)
GET  /v1/trends/{id}            상세(뜻/유래/전파경로/연령대)
POST /v1/trends/{id}/vote       "더 뜰까요" 투표 (판정과 분리·R1)
GET  /v1/me/grade               등급·AS·TI·다음 승급 부족분
GET  /v1/me/ledger              점수 원장 전건
POST /v1/appeals                이의 제기 — 미구현(SP3): 컨트롤러 없음, 404
GET  /v1/leaderboard            상위 10(동의자 한정) — 미구현: 컨트롤러 없음, 404
```
디자인 매핑: 온보딩 → 프로필/관심분야/알림시간, 홈(오늘의 5개) → `GET /v1/trends?daily`, 검색 판정 → `GET /v1/trends?q=`(**미구현(SP2)**), 제보 폼 → `POST /v1/submissions`.

### 7.2 콘솔 (api-admin)
큐(병합/어뷰징/이의/신고), 트렌드 상세·판정관리(예외만), 유저원장(ADJ 추가), 파라미터 스튜디오(드래프트→시뮬→2인승인), 배치 관리(ADM-900), 감사로그. **판정결과 직접변경·T 수동입력·verdict 삭제 엔드포인트는 존재하지 않음**(ADM-200 금지기능). **재판정은 `JudgeService`가 원 판정 때 동결한 파라미터로 재실행한다(J2)** — 새 파라미터를 소급하지 않고, 원장은 제보 단위 차액만 ADJ로 남긴다. VOID(ADM-100·ADM-200)도 같은 서비스의 항목 VOID 경로다. **미구현(SP3)**: 재판정 ADJ 차액에는 승인·100점 상한 검사가 없다 — `ApprovalGate` 자체가 없다(§8 참조).

### 7.3 공통 응답 규약
등급·판정 관련 응답은 **산정 근거를 동봉**: `"TI 0.42 / 요구치 0.45 / 부족분 0.03"`. OpenAPI 스키마에 `breakdown` 필수 필드로 강제.

---

## 8. 횡단 관심사

| 항목 | 방침 |
|---|---|
| 인증 | 앱=JWT(리프레시 회전), 콘솔=서버세션+2FA+30분 타임아웃(ADM-800) |
| 권한(RBAC) | REVIEWER/OPERATOR/ADMIN/AUDITOR. 메서드 시큐리티 + 권한 매트릭스(02 §1.1). 판정검수자가 파라미터 못 만짐(02 C6) |
| 2인 승인(4-eyes) | 유저제재·등급수동조정·파라미터적용·상쇄원장 100점 초과·**관리자 계정 생성/권한 변경**. 서버 한 곳 `ApprovalGate.require(kind, amount)`가 강제(**미구현(SP3)** — 현행은 `PARAM_APPLY` executor 하나뿐이고 재판정이 승인 경로를 우회), `approval_requests` 상태머신(요청→1인→2인→실행), 요청자≠승인자 검증, 신규 계정 7일 승인권 유예 |
| 감사 로그 | **Phase 0부터 필수**(ADM-700, 소급 불가). 상태변경·원장추가·정책변경·**조회행위**(`PII_VIEW`는 서버 엔드포인트에서 기록, 클라 토글 금지 — 기록 자체는 **미구현(SP3)**)·인증 기록. `detail` 포함 응답은 **미구현(SP3)** — 현행 `AuditLogController`는 actor/role/action/target/시각만 반환. 해시체인·append-only 트리거(WORM)는 구현됨 |
| 개인정보 | 기본 마스킹, 열람 시 사유입력→접속기록. **미구현(SP3)** — `/admin/users/**` 언마스크 엔드포인트·`PII_VIEW` 기록이 없어 현재 ADM-311의 열람 토글은 서버에 아무것도 남기지 않는다(02 참조). 디바이스지문·IP는 동의 항목 별도 명시 |
| 시간 | UTC 저장, KST 표시. 배치 경계(월 00:00)는 타임존 명시 |
| 멱등·동시성 | 병합 큐 항목 중복 적재는 `merge_queue_one_open_per_new` UNIQUE로 방지(구현됨). 판정 재실행 방지는 `verdicts` 부분 UNIQUE(구현됨, `score_ledger` 쪽은 미구현·§6 참조). 큐 소프트락(15분 클레임, `assigned_to`·`claimed_at`)은 **미구현(SP2)** — 현재 `merge_queue`에 클레임 컬럼 자체가 없다(→ Phase 2 Redis 전환은 §2.5) |
| 알림 | 앱: expo-notifications(아침 정시 1건 + 급상승 진입). 콘솔: SLA초과 `SlaNotifier`(Phase 1 로그, Phase 2 사내 웹훅 — O10) |

---

## 9. 프런트엔드 설계

### 9.1 앱 (Expo) — 디자인 `유행수명 앱.dc.html`
- 네비게이션: 하단 탭 5개(홈/검색/제보/워치/나) + 홈 스택(상세). React Navigation.
- 상태: 서버상태 TanStack Query, 로컬 UI는 최소 useState/Context(온보딩·읽음표시).
- 화면: 온보딩(3스텝: 관심분야 3개·알림시간) / 홈(5개 카드, "더보기 없음") / 상세(전파경로 3뷰: 경로지도·타임라인·채널볼륨) / 검색 판정 / 제보 폼(확신도 베팅·이해관계 고지·중복→동의) / 워치 / 나(등급·원장·완독 스트릭).
- 원칙 준수: 색은 단계(씨앗/급상승/정점/식는중)에만, 나머지 무채색. "숫자보다 문장" 카피는 서버 `verdict/lifeSentence` 필드로 내려줌.
- 폰트 Pretendard(expo-font 번들).

### 9.2 관리자 콘솔 (React) — 디자인 `관리자 콘솔.dc.html`
- 레이아웃: 좌측 다크 사이드바(네비 그룹+큐 배지) + 상단바(화면ID·역할) + 본문. **데스크톱 전용**. 큐 배지와 ADM-010 타일은 단일 `GET /admin/queues/summary`에서 그린다(P10) — 하드코딩·픽스처 금지, 미구현 큐는 "미구현" 표시.
- 진입 = **ADM-010 오늘의 작업**(큐 카운트·SLA초과), 대시보드 아님(02 C1).
- "감사 로그에 기록됩니다" 문구는 서버가 실제로 기록하는 곳에만 둔다(SP3 전까지 표시 금지).
- 큐 화면 공통: 키보드 단축키(J/K/M/S/V)·서버 커서 페이징(현재 목록은 전량 반환)·5초 Undo 토스트는 **미구현**(02 참조). 되돌릴 수 없는 액션의 확인모달+사유 입력은 화면마다 다르다.
- 병합검수(ADM-100): 좌우 비교 + **선점순위 미리보기** + 권한없으면 액션 비활성(역할전환 데모처럼).
- 파라미터 스튜디오(ADM-600): **시뮬 없이는 승인요청 버튼 비활성**, 기본=예약(비소급).
- 유저 원장(ADM-311): **미구현** — `/admin/users/**` 컨트롤러 자체가 없어 [상쇄 원장 추가]·[제재 상신]·[등급 수동 조정] 버튼이 서버를 호출하지 않고 성공 토스트만 띄운다(02 참조). 목표 설계는 원장에 수정·삭제 UI 부재, ADJ 추가만, 개인정보 마스킹 토글→사유.
- 상태/데이터: TanStack Query + Table, 권한은 서버 응답 기반 렌더 가드(클라 가드는 UX용, 실제 통제는 서버).

---

## 10. 개발 로드맵 (Phase 매핑)

`CLAUDE.md`·01 §8·02 §5의 Phase/화면 우선순위를 개발 순서로 변환.

### Phase 0 (~4주) — 유저 노출 없음, 판정 엔진 보정
- **백엔드 우선**: domain-core(ScoreEngine·VerdictEngine) + merge(MergeService) + persistence + append-only 트리거 + 배치(verdict_runner→`JudgeService`) + audit(ADM-700).
- 콘솔 최소: ADM-010, 200, **600(파라미터 스튜디오)**, 700, 800, 900.
- 산출물: 과거 사례 30건 백테스트(SP4 판정 신호 재설계와 함께 진행 — CLAUDE.md R5·SP4 참조)로 O1(목표 제보자 수·임계값) 보정.
- 앱: 미개발(스켈레톤만).

### Phase 1 (4~12주) — 클로즈드 베타
- 백엔드: 제보 파이프라인·cluster_merge·endorsement·**제보권 쿼터(선행 조건, SP1에서 구현)**·`sla_watch`(SP3).
- 선행 서브프로젝트: SP0.5(보안 즉시 수정) → SP1(판정 파이프라인) → SP2(병합 무결성) → SP3(콘솔 통제·SLA). 상세는 `docs/superpowers/specs/2026-09-17-design-review-design.md` §3.
- 콘솔 추가: ADM-100(병합검수), 110/111, 311(원장 — 이의 대응 근거), 410(신고 콘텐츠 큐, 구현됨), 500(시딩, `is_seed=true` 원장 미반영).
- 앱: 온보딩·홈·상세·제보·검색 (등급 노출은 최소).
- 운영진 시딩 주 20건 + 초대 유저.

### Phase 2 (12~24주) — 공개 + 등급 활성화
- 백엔드: grade_recalc·abuse_scan·appeals·sanctions·2인승인.
- 콘솔 추가: ADM-300/310/320/400/610.
- 앱: 워치·나(등급/원장)·투표 완성.

### Phase 3 (24주~) — 리워드·L4 정원제
- l4_quota, 리워드(세무처리), L4 자문단.

---

## 11. 테스트 전략

| 유형 | 필수 케이스 |
|---|---|
| 골든 단위테스트 | ScoreEngine: docs 예시값(HIT +60, MISS −15, VOID 0) 고정. GradePolicy: AND 조건 경계값 |
| 회귀(조용한 버그) | first_seen_at 변경→order_rank 재계산. RESOLVED 병합→409. 같은유저 dedup→VOID+제보권 반환. **판정 배치 후 submissions.result·trend_items.state DB 반영**(self-invocation) |
| 멱등 | verdict_runner·grade_recalc 재실행 시 점수 불변 — DB UNIQUE 제약 위반으로 증명 |
| 동시성(큐) | 검수자 A 병합 + B 분리 동시 → 클레임으로 하나만 진행. 같은 `Idempotency-Key` 재요청 동일 응답 |
| 불변성 | score_ledger/verdicts UPDATE/DELETE 시도 → 트리거 예외 |
| 권한 | 매트릭스(02 §1.1) 전 조합. 2인 승인 요청자=승인자 거부 |
| 동시성 | 두 검수자 동시 병합 → 낙관적 락 충돌 처리 |

---

## 12. 환경 · DevOps

- 로컬: `infra/docker-compose`(Postgres+pgvector, 임베딩 TEI). Redis는 Phase 2에 추가(§2.5). DB 기본 호스트는 `localhost:5433` (다른 호스트는 `DB_URL` 환경변수).
- CI: 백엔드(Gradle test), 앱/콘솔(typecheck·lint·build), OpenAPI 계약 검증.
- 환경 분리: local/staging/prod 프로파일. Firebase 서비스 계정 키는 시크릿 매니저. 로컬 오버라이드는 `application-local.yml`(gitignore).
- 배포: 백엔드 컨테이너, 앱 EAS Build+OTA, 콘솔 정적 호스팅(내부망/VPN).

---

## 13. 미결 · 선행 결정

| # | 항목 | 차단 대상 | 결정 주체 |
|---|---|---|---|
| O1 | submitterTarget 상대값(하한·비율)·임계값 | 판정 정확도 | SP4 백테스트 |
| O8 | 다축 신호 가중치 | SP4 | SP4 백테스트 |
| O9 | 판정 후 병합(ADJ 경로) | RESOLVED 병합 허용 | Phase 2 |
| O10 | SLA 통보 채널 | 야간 대응 | Phase 2 |
| O6 | 독립/커머스 부가 | 인증·회원 모듈 | 사업 |
| O7/M1 | 임베딩 임계값 0.85/0.75 | cluster_merge | Phase 0 한국어 데이터 |
| M2 | 임베딩 모델 | 병합 품질·비용·온프레미스 | 기술검토 |
| A1 | SLA 시간 | 콘솔 알림·자동조치 | 운영 인력 확정 후 |
| — | 배포처(클라우드/공공온프레) | 인프라·암호화(부록 A) | 사업 |

---

## 14. 착수 스프린트 백로그 (Sprint 0~1)

1. 모노레포 스캐폴딩(backend 멀티모듈, app Expo, admin Vite) + docker-compose.
2. Flyway 초기 스키마 + append-only 트리거 + 골든 단위테스트 틀.
3. `domain-core` ScoreEngine/VerdictEngine + `ParameterSet` 주입 구조 + 30건 백테스트 하네스.
4. audit(ADM-700) 골격 — 이후 모든 기능이 여기에 훅.
5. OpenAPI 스펙 초안 + 앱/콘솔 타입 코드생성 파이프라인.
6. 콘솔 ADM-600 시뮬레이션(같은 엔진 재사용 검증) — O1 보정 도구.

> 순서의 핵심: **엔진과 감사 로그가 먼저**다. 둘 다 나중에 얹으면 소급이 불가능하거나(감사) 이원화된다(엔진).
