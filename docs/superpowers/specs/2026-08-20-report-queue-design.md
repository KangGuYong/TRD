# B1 · 신고 콘텐츠 큐 (ADM-410)

## 배경

`02-admin-console.md`는 ADM-410을 "공개 전 필수, 법적 리스크 대응"으로 못박고 있지만, DB에 `reports` 테이블조차 없다. `backend/api-spec/openapi.yaml`에는 `/admin/reports`, `/admin/reports/{id}/decide`가 정의만 돼 있고 구현은 전무하다. `/v1` 쪽에는 유저가 신고를 접수할 방법 자체가 명세에도 없다.

CLAUDE.md의 법무 체크리스트("강등·제재 사유 열거주의", "이의 제기 절차 및 처리 기한 명시")와 마찬가지로, 신고 처리도 공개(Phase 2) 전 반드시 있어야 하는 법적 방어선이다.

## 목표

1. 유저가 트렌드 카드를 신고할 수 있게 한다.
2. 검수자(REVIEWER+)가 신고를 검토해 "즉시 비공개" 또는 "공개 유지한 채 소명 요청" 중 하나를 선택하게 한다.
3. 그 판단의 근거가 되는 실제 작성자(제보자)를 관리자가 직접 지목해 소명을 요구하게 한다 — 자동 추정하지 않는다.
4. 운영자(OPERATOR+)가 최종 결정(복원/영구비공개/수정후복원)을 내리게 한다.

## 범위 — 브레인스토밍으로 원안에서 의도적으로 바꾼 부분

`02-admin-console.md` 원안과 이번 설계는 두 지점에서 다르다. **의도된 변경**이며 이유를 남긴다.

1. **4시간 미처리 시 자동 임시비공개를 완전히 제거한다.** 원안의 취지는 "야간·주말 공백을 자동으로 메운다"였지만, 이 메커니즘은 그대로 악용 벡터가 된다 — 오신고(허위 신고) 한 건만으로 정상 콘텐츠가 사람 개입 없이 비공개될 수 있다. 이번 설계에서는 **사람이 명시적으로 판단하기 전까지는 절대 비공개되지 않는다.** 대신 큐 화면에 경과시간을 표시해(기존 `QueueSummaryController`/`MergeQueueController` 패턴 재사용) 신속 처리를 유도한다.
2. **소명 요청 대상은 "최초 제보자"를 자동 추정하지 않는다.** 트렌드 항목은 병합을 거치며 `first_seen_at`이 앞당겨질 수 있는 것처럼, "누가 대표 문구를 썼는가"도 병합 이력에 따라 달라진다. 자동 추정은 엉뚱한 사람에게 소명을 요구하는 결과를 낳을 수 있다. 대신 **관리자가 그 트렌드의 전체 제보 원문 목록(병합검수 화면과 동일한 뷰)을 보고 실제 문제 제보를 직접 지목**하게 한다.

신고 대상은 `TrendItem`(트렌드 카드)이다 — 일반 유저는 카드만 볼 수 있고 개별 제보 원문에 접근할 수 없으므로, 유저가 신고할 수 있는 유일한 단위이기 때문이다. 다만 그 신고를 처리하는 과정에서 관리자가 특정 `Submission`(그리고 그 작성자)을 지목함으로써, 실질적으로는 "사람을 신고하는" 효과를 낸다.

**스코프 밖(명시):**
- 동일 항목 3회 이상 신고 시 자동 상위 역할 배정
- 실제 푸시 알림 발송(앱·백엔드 어디에도 발송 인프라가 없다 — 별도 트랙)
- 소명 마감(48h) 경과 시 자동 처리 — 마감은 관리자 큐에 표시되는 정보일 뿐, 시스템이 강제하지 않는다(제출은 `DECIDED` 전이면 언제든 허용)
- 하나의 트렌드에 여러 신고가 동시에 열려 있을 때의 조정 로직(각 신고는 독립적으로 처리되고, `TrendItem.visibility`는 마지막 처리 결과를 반영)
- 신고/소명 접수 시 판정(verdict)·점수 파이프라인에 미치는 영향 — **없다.** `visibility`는 순수 표시 계층이고 판정 엔진과 완전히 분리된다(R2 원장 불변 유지: 비공개돼도 채점은 그대로 진행).

## 아키텍처

### 상태 머신

```
Report:  OPEN ──관리자 1차 처리(hide 또는 request-explanation, submissionId 지목)──▶ EXPLAINING ──관리자 최종결정──▶ DECIDED
```

`hide`와 `request-explanation`은 검수자가 고르는 두 가지 "1차 처리" 액션이다. 어느 쪽이든 `Report`를 `EXPLAINING`으로 옮기고 48h 소명기한을 설정하며 `submissionId`를 지목한다 — 차이는 `hide`만 `TrendItem.visibility`를 즉시 `TEMP_HIDDEN`으로 바꾼다는 점이다(`request-explanation`은 공개 상태를 유지한 채로 소명만 요구한다).

최종 결정(`decide`)은 그 시점의 `visibility`가 무엇이었든 상관없이 결정 유형대로 확정한다 — `RESTORE`→`PUBLIC`, `HIDE_PERMANENT`→`PERMANENT_HIDDEN`, `EDIT_RESTORE`→`canonicalName` 수정 후 `PUBLIC`. 즉 `request-explanation`(공개 유지)으로 시작했더라도 최종적으로 `HIDE_PERMANENT`가 나올 수 있고, `hide`(즉시비공개)로 시작했더라도 소명이 납득되면 `RESTORE`로 다시 공개될 수 있다.

### 공개 API 필터링

`api-public`의 트렌드 목록/상세 조회는 `visibility = PUBLIC`인 항목만 반환한다. `TEMP_HIDDEN`/`PERMANENT_HIDDEN`은 둘 다 공개 화면에서 동일하게 제외된다(둘의 차이는 관리자 쪽 복원 가능 여부일 뿐). 판정 배치(`verdict_runner`)·점수 원장(`score_ledger`)은 `visibility`를 전혀 참조하지 않는다 — 비공개된 트렌드도 판정·점수는 정상 진행된다.

## 백엔드 설계

### DB 마이그레이션 V26

```sql
CREATE TYPE trend_visibility AS ENUM ('PUBLIC', 'TEMP_HIDDEN', 'PERMANENT_HIDDEN');
ALTER TABLE trend_items ADD COLUMN visibility trend_visibility NOT NULL DEFAULT 'PUBLIC';

CREATE TYPE report_status AS ENUM ('OPEN', 'EXPLAINING', 'DECIDED');
CREATE TYPE report_reason AS ENUM ('DEFAMATION', 'BUSINESS_INTERFERENCE', 'OTHER');
CREATE TYPE report_decision AS ENUM ('RESTORE', 'HIDE_PERMANENT', 'EDIT_RESTORE');

CREATE TABLE reports (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trend_item_id            UUID NOT NULL REFERENCES trend_items(id),
    reporter_id              UUID NOT NULL REFERENCES users(id),
    reason                   report_reason NOT NULL,
    detail                   TEXT,
    status                   report_status NOT NULL DEFAULT 'OPEN',
    submission_id            UUID REFERENCES submissions(id),   -- 1차 처리 시 관리자가 지목(그 전엔 NULL)
    explanation_deadline     TIMESTAMPTZ,
    explanation_text         TEXT,
    explanation_submitted_at TIMESTAMPTZ,
    decision                 report_decision,
    decision_note            TEXT,
    decided_by                UUID REFERENCES admin_accounts(id),
    decided_at                TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reports_status ON reports(status);
CREATE INDEX idx_reports_trend_item_id ON reports(trend_item_id);
CREATE INDEX idx_reports_submission_id ON reports(submission_id);
```

신고자 비식별 원칙(`reporter_id`는 저장하되 관리자 API 응답에 절대 노출 안 함)은 컨트롤러 레이어에서 강제한다 — DTO에 필드 자체를 넣지 않는다.

### persistence 모듈

- `TrendVisibility`, `ReportStatus`, `ReportReason`, `ReportDecision` enum (기존 `TrendState` 등과 동일 패턴)
- `TrendItem`에 `visibility` 필드 + `applyVisibility(TrendVisibility)` 메서드, 생성자는 `PUBLIC` 기본값 유지
- `Report` 엔티티: `moveToExplaining(UUID submissionId, boolean hide, Instant deadline)`, `submitExplanation(String text, Instant now)`, `decide(ReportDecision decision, String note, UUID decidedBy, Instant now)` — 각 전이는 현재 상태를 검증(예: `OPEN`이 아니면 1차 처리 재실행 불가)
- `ReportRepository`: `findByStatusInOrderByCreatedAtAsc(List<ReportStatus>)`(큐), `findById`

### api-public — `ReportController`, `ReportService`

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/v1/reports` | 신고 접수. `{ trendItemId, reason, detail? }`. 인증 필요(Firebase). |
| GET | `/v1/reports/me` | 내가 접수한 신고 목록 + 처리결과(신고자 비식별 원칙과 무관 — 본인 것이므로) |
| GET | `/v1/reports/received` | 나를 지목해 소명을 요구한 신고 목록 (로그인 유저 = `submission.userId`인 것들) |
| POST | `/v1/reports/{id}/explanation` | 소명 제출. `{ text }`. 호출자가 `report.submission.userId`와 일치하고 `status = EXPLAINING`이어야 함(`AdminValidationException` 계열 아님 — `api-public`의 기존 `ApiExceptionHandler` 패턴을 따르는 새 예외로 403/409 매핑) |

같은 트렌드에 같은 유저가 중복 신고하는 것은 막지 않는다(스코프 밖 — 3회+ 에스컬레이션과 마찬가지로 어뷰징 대응은 B3 몫).

### api-admin — `ReportController`(신규 web 패키지), `ReportAdminService`

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/admin/reports` | REVIEWER/OPERATOR/ADMIN | 큐 — `OPEN`/`EXPLAINING`만, `reporterId` 없이 |
| GET | `/admin/reports/{id}/submissions` | REVIEWER/OPERATOR/ADMIN | 그 트렌드의 제보 원문 목록(병합검수 `SubmissionDetail`과 동일 패턴) — 관리자가 지목할 후보 |
| POST | `/admin/reports/{id}/hide` | REVIEWER/OPERATOR/ADMIN | `{ submissionId, note? }` — 즉시비공개 + 소명요청 |
| POST | `/admin/reports/{id}/request-explanation` | REVIEWER/OPERATOR/ADMIN | `{ submissionId, note? }` — 공개 유지 + 소명요청만 |
| POST | `/admin/reports/{id}/decide` | OPERATOR/ADMIN | `{ decision, note, newCanonicalName? }` — `EDIT_RESTORE`면 `newCanonicalName` 필수 |

`05-screen-endpoint-map.md`의 권한 표(R/O/A 큐 조회, O/A 결정)와 일치한다. 감사 로그는 `REPORT_HIDE` / `REPORT_REQUEST_EXPLANATION` / `REPORT_DECIDE` 액션으로 기존 `AuditLogService`에 기록한다(요청자 정보는 감사로그에는 남되, 이건 처리자를 위한 비식별과 무관한 내부 추적 목적이므로 상충하지 않는다).

## 프론트엔드 설계

### admin (ADM-410 신고 콘텐츠 큐)

- `ReportQueueScreen.tsx` — `MergeQueueScreen.tsx`와 동일한 확장 카드 패턴: 신고 사유·경과시간 목록 → 펼치면 그 트렌드의 제보 원문 목록(라디오 선택으로 지목) → "즉시비공개" / "소명요청(공개유지)" 버튼. `EXPLAINING` 상태 행은 소명 텍스트(제출됐다면)와 함께 "결정" 폼(복원/영구비공개/수정후복원 + 사유) 노출 — 결정 버튼은 `CAN.reportDecide = (r) => r === "OPERATOR" || r === "ADMIN"`으로 게이트.
- Layout.tsx `NAV`의 기존 `{ id: "stub", label: "신고 콘텐츠", code: "ADM-410" }`를 실 화면으로 교체.

### app

- `DetailScreen.tsx`에 "신고하기" 버튼 → 사유 선택(명예훼손/영업방해/기타) + 상세 텍스트 입력 모달 → `POST /v1/reports`.
- `MeScreen.tsx`에 "받은 소명요청" 섹션 — `GET /v1/reports/received` 목록, 항목 선택 시 소명 텍스트 입력 후 `POST /v1/reports/{id}/explanation`. 신고 내가 접수한 것의 처리결과(`GET /v1/reports/me`)도 같은 화면 하단에 노출.

## 테스트 관점

- 관리자가 `submissionId` 없이 `hide`/`request-explanation` 호출 → 검증 실패.
- `request-explanation`은 `TrendItem.visibility`를 바꾸지 않는지, `hide`는 바꾸는지.
- `EXPLAINING`이 아닌 상태에서 소명 제출 시도 → 거부.
- 소명을 요구받은 사람이 아닌 다른 유저가 `POST /v1/reports/{id}/explanation` 호출 → 403.
- `decide(HIDE_PERMANENT)` 후 `/v1/trends`, `/v1/trends/{id}` 양쪽에서 사라지는지.
- `decide(RESTORE)` 후 다시 공개 목록에 나타나는지, 판정/점수(`score_ledger`)는 신고 처리 전후로 전혀 변하지 않는지(순수 표시 계층 분리 검증).
- `GET /admin/reports` 응답 JSON에 `reporterId`/`reporter` 관련 필드가 전혀 없는지(직렬화 결과 확인).

## 스코프 밖 (명시, 재정리)

- 4h 자동 임시비공개(완전 제거 — 위 "범위" 섹션 참고)
- 동일 항목 3회+ 자동 상위 역할 배정
- 실제 푸시 알림 발송
- 소명 마감 시간 시스템 강제(정보 표시만)
- 여러 신고가 동시에 열려 있을 때의 조정/우선순위 로직
