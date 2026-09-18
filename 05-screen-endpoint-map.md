# 화면 ↔ 엔드포인트 매핑 (v0.1)

> **목적**: 앱 7화면·콘솔 15화면을 API·권한과 1:1로 잇는다. 프런트 착수 순서와 **누락 엔드포인트 발견**에 쓴다.
> **근거**: `유행수명 앱.dc.html`, `관리자 콘솔.dc.html`, `backend/api-spec/openapi.yaml`, `02-admin-console.md §1.1`(RBAC), `backend/api-admin/**`, `backend/api-public/**`
> 표기: **스펙**과 **구현**은 서로 다른 질문이다 — 스펙은 "`openapi.yaml`에 정의돼 있는가", 구현은 "컨트롤러 메서드가 실제로 있는가". 스펙 `✅` OpenAPI v0.1에 정의됨 · `🔶` 미정의(§C에 모음). 구현 `✅` 컨트롤러 존재 · `**미구현(SPn)**` 컨트롤러 없음(SP 번호가 정해지지 않은 Phase 2+ 기능은 SP 번호 없이 `**미구현**`).

---

## A. 앱 (React Native / Expo) — JWT

### A-1. 온보딩 (3스텝)
| 상호작용 | API | 스펙 | 구현 | 비고 |
|---|---|:--:|:--:|---|
| 관심분야 3개 선택 → 알림시간 선택 → 완료 | `PUT /v1/me/preferences` | ✅ | ✅ | 가입 직후. 워치가 첫날부터 채워지는 근거 |
| "오늘의 5개 보기" 진입 | `GET /v1/trends?daily=true` | ✅ | ✅ | 홈 프리페치 |

### A-2. 홈 · 오늘의 5개
| 상호작용 | API | 스펙 | 구현 | 비고 |
|---|---|:--:|:--:|---|
| 카드 5개 로드 (단계·D-값·전파 미리보기) | `GET /v1/trends?daily=true` | ✅ | ✅ | 정렬 = "지금 행동해야 하는 순"(서버) |
| 상단 스트릭·읽음 진행 | `GET /v1/me/summary` | ✅ | ✅ | streak·연속완독. 읽음(read)은 로컬 상태 + `POST /v1/me/reads`(스펙 ✅ · 구현 ✅)로 서버 동기화 |
| 카드 탭 → 상세 | (클라 네비) | — | — | 진입 시 읽음 표시 |

### A-3. 상세 · 전파 경로
| 상호작용 | API | 스펙 | 구현 | 권한 |
|---|---|:--:|:--:|---|
| 상세 로드 (판정·전파경로·연령대·뜻/유래/예문) | `GET /v1/trends/{id}` | ✅ | ✅ | 전체 |
| 전파경로 3뷰 토글(지도/타임라인/볼륨) | (클라, `propagationPath` 재사용) | — | — | |
| "뜬다 / 안 뜬다" 투표 | `POST /v1/trends/{id}/vote` | ✅ | ✅ | 판정과 무관(R1) |
| 워치 추가/해제 | `POST /v1/me/watch` · `DELETE /v1/me/watch/{keyword}` | ✅ | ✅ | |
| 신고하기 / 내 신고·소명요청 확인 / 소명 제출 | `POST /v1/reports` · `GET /v1/reports/me` · `GET /v1/reports/received` · `POST /v1/reports/{id}/explanation` | ✅ | ✅ | 전체 · ADM-410의 유저측 짝 |

### A-4. 검색 판정
| 상호작용 | API | 스펙 | 구현 | 비고 |
|---|---|:--:|:--:|---|
| 단어 입력 → 판정 결과 | `GET /v1/trends?q=…` | ✅ | 🔶 | **미구현** — 컨트롤러(`TrendController.list`)는 `daily` 파라미터만 읽고 `q`는 무시한다. 검색 자체가 동작하지 않음 |
| 결과 없음 → 워치 추가 | `POST /v1/me/watch` | ✅ | ✅ | |
| "많이 물어본 단어" 추천 | `GET /v1/trends?sort=asked` | ✅ | 🔶 | `sort` enum(action/asked)은 스펙에 있으나 위와 동일한 이유로 **미구현**(파라미터 무시) |
| 근거 자세히 보기 → 상세 | `GET /v1/trends/{id}` | ✅ | ✅ | |

### A-5. 제보 (새 제보 / 내 제보)
| 상호작용 | API | 스펙 | 구현 | 비고 |
|---|---|:--:|:--:|---|
| 항목명 입력 시 중복 감지 | `GET /v1/trends?q=…`(디바운스) | ✅ | 🔶 | **미구현**(A-4 참조) — 확정 방어는 `POST` 409(구현됨)로 이뤄지므로, 현재 클라이언트 쪽 사전 중복 감지는 사실상 없고 서버 409 응답에만 의존한다 |
| 제보권 잔량(도트) | `GET /v1/me/summary` | ✅ | ✅ | 표시만(`quotaUsed`/`quotaMax`는 매 요청마다 그 주 제보 수를 세어 계산) — 차감·리필은 `QuotaService` 자체가 없어 **미구현(SP1)** |
| 제보하기(카테고리·플랫폼·URL·확신도·고지) | `POST /v1/submissions` | ✅ | ✅ | 201 / 409(중복) / 422(권한·누락) |
| "동의로 올리기" | `POST /v1/trends/{id}/endorse` | ✅ | ✅ | 제보권 미차감 |
| 내 제보 탭 (판정 상태·Δ) | `GET /v1/submissions/me` | ✅ | ✅ | PENDING/HIT/MISS/VOID |

### A-6. 워치
| 상호작용 | API | 스펙 | 구현 |
|---|---|:--:|:--:|
| 감시 목록(단계·노트) | `GET /v1/me/watch` | ✅ | ✅ |

### A-7. 나 · 등급 · 원장
| 상호작용 | API | 스펙 | 구현 | 비고 |
|---|---|:--:|:--:|---|
| 등급·다음 승급 부족분 | `GET /v1/me/grade` | ✅ | ✅ | `requirements[].basis` 산정근거 동봉(R3) |
| 점수 원장 전건 | `GET /v1/me/ledger` | ✅ | ✅ | append-only |
| 통계(적중률·읽은 트렌드) | `GET /v1/me/summary` | ✅ | ✅ | |
| 명예의 전당(상위 10, 동의자 한정 노출) | `GET /v1/leaderboard` | ✅ | 🔶 | **미구현** — 컨트롤러 없음(전 소스 검색 결과 `leaderboard` 관련 구현체 없음). 스펙만 정의돼 있고 화면·백엔드 모두 미착수 |
| 설정 행(알림시간·관심분야) | `GET /v1/me/preferences` | 🔶 | ✅ | `MeController`엔 GET·PUT 모두 구현돼 있으나 스펙은 `PUT`만 정의 — GET을 스펙에 추가해야 함 |
| 이의 제기(원장/판정 상세에서) | `POST /v1/appeals` | ✅ | 🔶 | **미구현** — 스펙엔 있으나 컨트롤러(`AppealController`) 자체가 없어 호출 시 404. 처리기한 5영업일은 설계값(ADM-400도 동일 사유로 전 구간 미구현) |

---

## B. 관리자 콘솔 (React) — 세션+2FA · RBAC

역할 표기: R=REVIEWER, O=OPERATOR, A=ADMIN, Au=AUDITOR(읽기전용). 근거 02 §1.1. 아래 권한/비고는 각 컨트롤러의 실제 `@PreAuthorize`를 확인해 표기했다(컨트롤러 자체가 없는 항목은 스펙 설명 기반 목표 권한).

### ADM-010 · 오늘의 작업
| 상호작용 | API | 스펙 | 구현 | 권한 | 비고 |
|---|---|:--:|:--:|:--:|---|
| 큐 카운트·SLA초과·시스템알림·시딩비중 | `GET /admin/queues/summary` | ✅ | ✅ | R/O/A/Au | ADM-410은 실데이터(OPEN+EXPLAINING, OPEN 4h SLA), ADM-300/400은 `available=false`로 '미구현' 표시. 사이드바 뱃지도 같은 API |

### 콘솔 인증
| 상호작용 | API | 스펙 | 구현 | 권한 | 비고 |
|---|---|:--:|:--:|:--:|---|
| CSRF 쿠키 발급(SPA 부팅용) | `GET /admin/auth/csrf` | ✅ | ✅ | 전체 | 응답에 XSRF-TOKEN 쿠키 |
| 로그인 | `POST /admin/auth/login` | ✅ | ✅ | 전체 | X-XSRF-TOKEN 헤더 필수, 5회 실패 15분 잠금(423) |

### ADM-100 · 병합 검수 큐 ★
| 상호작용 | API | 스펙 | 구현 | 권한 |
|---|---|:--:|:--:|---|
| 큐 로드(유사도·양쪽 비교·선점 미리보기) | `GET /admin/merge-queue` | ✅ | ✅ | R/O/A/Au(조회) |
| 병합 결정 미리보기(dry-run) | `GET /admin/merge-queue/{id}/preview` | 🔶 | ✅ | R/O/A/Au · 실제 병합과 같은 계산(`MergeService.preview`)을 공유하므로 결과가 실제 병합과 일치한다 |
| 병합 / 분리 | `POST /admin/merge-queue/{id}/merge` · `…/separate` | 🔶 | ✅ | R/O/A · `openapi.yaml`은 구식 단일 `POST …/action`(action enum)만 정의 — 실제 경로 3종 미반영, 스펙 갱신(치환) 필요. `Idempotency-Key` 헤더·사유 필수 검증은 컨트롤러에 없음 — **미구현(SP2)** |
| VOID(허위/규정위반) | `POST /admin/merge-queue/{id}/void` | 🔶 | ✅ | **O/A**(R은 403) · 위와 동일한 스펙 불일치. 사유 필수 검증은 서버에 없음(`@RequestBody(required=false)`) — **미구현(SP2)** |
| 클레임 / 해제 | `POST /admin/merge-queue/{id}/claim` · `…/release` | 🔶 | 🔶 | **미구현(SP2)** — R/O/A · 15분 만료 설계값, 컨트롤러 없음 |
| 보류(HOLD) | `POST /admin/merge-queue/{id}/hold` | 🔶 | 🔶 | **미구현(SP2)** — R/O/A · 3회 → ESCALATED 설계값, 컨트롤러 없음 |

### ADM-110 / 111 · 트렌드 목록 / 상세
| 상호작용 | API | 스펙 | 구현 | 비고 |
|---|---|:--:|:--:|---|
| 항목 목록·필터 | `GET /admin/trend-items` | 🔶 | ✅ | R/O/A/Au · 실제 컨트롤러 경로는 `/admin/trend-items`. `openapi.yaml`은 여전히 구경로 `/admin/trends` 정의 — 스펙 갱신(치환) 필요 |
| 항목 상세(제보 시계열·예상판정 잠정) | `GET /admin/trend-items/{id}` | 🔶 | ✅ | R/O/A/Au · 위와 동일한 경로 불일치(스펙은 `/admin/trends/{id}`) |

### ADM-200 · 판정 관리 (예외 전용)
| 상호작용 | API | 스펙 | 구현 | 비고 |
|---|---|:--:|:--:|---|
| 큐 로드(판정 완료 목록 + D+14 임박 30건) | `GET /admin/verdicts` | 🔶 | ✅ | R 불가 · O/A/Au · 스펙엔 이 목록 조회 자체가 없음(추가 필요) |
| VOID / 재판정 | `POST /admin/verdicts/{id}/void` · `…/rejudge` | 🔶 | ✅ | **O/A** · 사유 **서버 필수**(`VerdictAdminService.requireReason`, 없으면 예외) · 재판정 ADJ 합계 >100 → `ApprovalGate` **미구현(SP3)** · 스펙은 여전히 구경로 `POST /admin/trends/{id}/exceptions`(type 파라미터로 VOID/EXTEND/REJUDGE 분기) 하나만 정의 — 치환 필요 |
| 유예연장 | `POST /admin/verdicts/{id}/extend-grace` | 🔶 | ✅ | **O/A** · 사유는 **서버가 요구하지 않는다** — `VerdictAdminService.extendGrace`는 `requireReason`을 호출하지 않고, `reason`이 null이면 빈 문자열로 감사 로그에만 남긴다. 1~90일(`MAX_GRACE_DAYS`) 범위·최대 유예 도달 여부만 검증. 위와 동일한 스펙 불일치 |
| ~~HIT/MISS 변경·T 수동입력·verdict 삭제~~ | **엔드포인트 없음** | — | — | 금지기능(R1) |

### ADM-300 · 어뷰징 플래그 큐
| 상호작용 | API | 스펙 | 구현 | 권한 |
|---|---|:--:|:--:|---|
| 플래그 목록·근거·연결그래프 | `GET /admin/abuse-flags` | ✅ | 🔶 | O/A/Au · **미구현** — 컨트롤러 없음(Phase 2). 화면은 전부 픽스처 |
| 오탐 처리 / 경고 발송 | `POST /admin/abuse-flags/{id}/resolve` | ✅ | 🔶 | O/A · **미구현** — 컨트롤러 없음 |
| 제재 상신 → ADMIN | `POST /admin/sanctions` | ✅ | 🔶 | O 상신 · **미구현** — 컨트롤러 없음. 상신 자체가 안 되므로 이 큐로 흘러들어올 제재가 없다 |

### ADM-310 / 311 · 유저 목록 / 상세·원장
| 상호작용 | API | 스펙 | 구현 | 권한 |
|---|---|:--:|:--:|---|
| 유저 목록 | `GET /admin/users` | ✅ | 🔶 | R/O/A/Au · **미구현(SP3)** — 컨트롤러 없음 |
| 유저 상세·원장(마스킹) | `GET /admin/users/{id}` | ✅ | 🔶 | 마스킹 R/O · 사유후 A/Au · **미구현(SP3)** — 컨트롤러 없음, 현재 화면은 픽스처 |
| 마스킹 해제(사유 → `PII_VIEW` 기록) | `POST /admin/users/{id}/unmask` | 🔶 | 🔶 | A/Au · **미구현(SP3)** — 이 경로는 스펙에도 없음(스펙은 `?unmask=true` 쿼리파라미터 방식, §C 참조) |
| 상쇄원장(ADJ) 추가 | `POST /admin/users/{id}/ledger-adjust` | ✅ | 🔶 | **A**(100점↑ 2인) · **미구현(SP3)** — `/admin/users/**`는 전부 컨트롤러가 없다 |
| 제재 상신 | `POST /admin/sanctions` | ✅ | 🔶 | O 상신 · **미구현** — 컨트롤러 없음 |
| 등급 수동 조정 | `POST /admin/users/{id}/grade-adjust` | ✅ | 🔶 | **A(2인)** · **미구현(SP3)** — 컨트롤러 없음. 2인 승인 executor도 없음(`ApprovalService`에 등록된 executor는 `ParamApplyExecutor` 하나뿐) |

### ADM-320 · 제재 관리
| 상호작용 | API | 스펙 | 구현 | 권한 |
|---|---|:--:|:--:|---|
| 제재 목록·상태 | `GET /admin/sanctions` | ✅ | 🔶 | O/A/Au · **미구현** — 컨트롤러 없음(ADM-300/311과 동일) |
| 확정(2인 승인) | `POST /admin/approvals/{id}/approve` | ✅ | ✅ | **A(2인)** · `ApprovalController`는 실재하고 동작하지만, 승인할 대상(제재 상신)이 만들어질 방법이 없어 이 화면은 현재 도달 불가 |

### ADM-400 · 이의 제기 큐
| 상호작용 | API | 스펙 | 구현 | 권한 |
|---|---|:--:|:--:|---|
| 큐·판정근거 자동첨부 | `GET /admin/appeals` | ✅ | 🔶 | O/A/Au · **미구현** — 컨트롤러 없음. 앱 쪽 `POST /v1/appeals`도 미구현(A-7) — 이의 제기 기능은 접수부터 처리까지 전 구간 미구현 |
| 인용/부분/기각(근거수치 통보) | `POST /admin/appeals/{id}/resolve` | ✅ | 🔶 | O/A · 인용 시 ADJ 발생 · **미구현** — 컨트롤러 없음 |

### ADM-410 · 신고 콘텐츠 큐 (공개 전 필수)
| 상호작용 | API | 스펙 | 구현 | 권한 |
|---|---|:--:|:--:|---|
| 신고 목록(신고자 비식별) / 제보 원문 후보 | `GET /admin/reports` · `GET /admin/reports/{id}/submissions` | ✅ | ✅ | **R/O/A**(Au 없음 — 컨트롤러 `@PreAuthorize`에 AUDITOR 미포함) |
| 임시비공개 / 소명 요청(48h) | `POST /admin/reports/{id}/hide` · `…/request-explanation` | ✅ | ✅ | **R/O/A**(Au 없음) · 두 엔드포인트 모두 REVIEWER 허용(**미구현(SP3)**에서 축소 예정) · 4h 미처리 자동 비공개는 `sla_watch`가 담당하며 **미구현(SP3)** |
| 결정(복원·영구비공개) | `POST /admin/reports/{id}/decide` | ✅ | ✅ | **O/A**(R·Au 불가) |

### ADM-500 · 시딩 관리 (Phase 1)
| 상호작용 | API | 스펙 | 구현 | 권한 |
|---|---|:--:|:--:|---|
| 시딩 등록(`is_seed=true`, 원장 미반영) | `POST /admin/seed/submissions` | 🔶 | ✅ | O/A · 실제 경로는 `/admin/seed/submissions`. `openapi.yaml`은 여전히 구경로 `/admin/seeding` 정의 — 스펙 갱신(치환) 필요 |
| 시딩 비중·담당자 적중률 | `GET /admin/seed/accuracy` | 🔶 | ✅ | **R/O/A/Au** · 실제 경로는 `/admin/seed/accuracy`. 스펙은 구경로 `/admin/seeding/stats` 정의 — 치환 필요 |

### ADM-600 · 파라미터 스튜디오 ★
| 상호작용 | API | 스펙 | 구현 | 권한 |
|---|---|:--:|:--:|---|
| 드래프트 조회/수정(`hitThreshold`·`submitterTarget` 등 단일값 필드만 있음) | `GET·PUT /admin/params/draft` | 🔶 | ✅ | O/A · 실제는 액터당 활성 드래프트 1개(ID 없이 `/draft`), 스펙은 여전히 구모델 `GET·POST /admin/parameter-drafts`(다건·ID기반) — 경로·모델 모두 갱신 필요 |
| 시뮬레이션(180일 재판정) | `POST /admin/params/draft/simulate` | 🔶 | ✅ | O/A · 승인요청 선행 · 스펙은 구경로 `POST /admin/parameter-drafts/{id}/simulate` |
| 승인 요청(기본 예약·비소급) | `POST /admin/params/draft/request-approval` | 🔶 | ✅ | O/A · 스펙은 구경로 `POST /admin/parameter-drafts/{id}/request-approval` |
| 적용 승인 | `POST /admin/approvals/{id}/approve` | ✅ | ✅ | **A(2인)** |

### ADM-610 · 등급 정책
| 상호작용 | API | 스펙 | 구현 | 권한 |
|---|---|:--:|:--:|---|
| 승급요구치·제보권·L4정원 드래프트 | `GET·POST /admin/grade-policy` | ✅ | 🔶 | O/A(적용 2인) · **미구현** — 컨트롤러 없음 |

### ADM-620 · 승인 대기함
승인이 필요한 모든 흐름(현재는 파라미터 적용뿐, 향후 제재 확정·등급 수동조정·계정 권한 변경도 여기로 모인다)의 공용 큐. `ApprovalController` 코드 주석에 화면 ID가 명시돼 있다.

| 상호작용 | API | 스펙 | 구현 | 권한 |
|---|---|:--:|:--:|---|
| 승인 대기 목록 | `GET /admin/approvals` | ✅ | ✅ | A/Au(조회) |
| 승인(요청자·직전 승인자와 달라야 함) | `POST /admin/approvals/{id}/approve` | ✅ | ✅ | **A**(2/2면 즉시 실행) |
| 반려(사유 필수, 대상은 재수정 가능 상태로 복귀) | `POST /admin/approvals/{id}/reject` | ✅ | ✅ | **A** |

### ADM-700 · 감사 로그
| 상호작용 | API | 스펙 | 구현 | 권한 |
|---|---|:--:|:--:|---|
| 로그 검색(최신 200건, 필터·페이지네이션 없음) | `GET /admin/audit-log` | ✅ | ✅ | **O 본인분 · A/Au 전건**(최신 200, 검색 없음) — REVIEWER는 403. 열람 행위 자체를 감사 로그에 남기는 기능(조회 기록)은 컨트롤러에 없다 — **미구현(SP3)** |

### ADM-800 · 관리자 계정
| 상호작용 | API | 스펙 | 구현 | 권한 |
|---|---|:--:|:--:|---|
| 계정 목록·2FA·세션 | `GET /admin/accounts` | ✅ | ✅ | **A/Au** · 응답에 2FA·세션 필드가 없다 — 화면 문구를 실제 응답 필드에 맞게 수정 필요 |
| 계정 생성 | `POST /admin/accounts` | 🔶 | ✅ | **A**(2인 승인은 **미구현(SP3)**, 현행 ADMIN 단독) · 신규 계정 7일 승인권 유예 · `openapi.yaml`은 `/admin/accounts`에 GET만 정의, POST 없음 — 스펙 갱신 필요 |
| 계정 비활성화 / 재활성화 | `POST /admin/accounts/{id}/disable` · `…/enable` | 🔶 | ✅ | **A**(본인 계정 비활성화는 409) · `openapi.yaml`엔 없음 — 스펙 추가 필요. 실제 구현은 이것뿐이며 "권한 부여/회수"는 이 컨트롤러에 없다 |
| 권한 부여/회수 | `POST /admin/accounts/{id}/role` | ✅ | 🔶 | **A(2인)** · **미구현** — `openapi.yaml`엔 있으나 컨트롤러가 없다. role 변경 엔드포인트 자체가 존재하지 않는다(disable/enable만 존재) |
| 비밀번호 변경(부트스트랩 강제) | `POST /admin/accounts/me/password` | 🔶 | 🔶 | 본인 · **미구현(SP3)** |

### ADM-900 · 배치 관리
| 상호작용 | API | 스펙 | 구현 | 권한 |
|---|---|:--:|:--:|---|
| cluster_merge 수동 실행 | `POST /admin/batch-jobs/cluster-merge/run` | ✅ | ✅ | **O/A** · 실행 중 409 · 감사 `CLUSTER_MERGE_MANUAL_TRIGGER` |

---

## C. 스펙 확장 대상 (OpenAPI v0.1 미포함 · 🔶)

프런트가 참조하지만 아직 `openapi.yaml`에 없는 엔드포인트, 그리고 스펙의 옛 경로가 실제 컨트롤러 경로로 바뀌면서 남은 치환 대상을 모은다. 대부분은 §B 각 표의 스펙 열에서 `🔶`로 이미 표시했다 — 여기서는 그 사유와 제안 경로만 정리한다.

| 제안 경로 | 용도 | 화면 | 비고 |
|---|---|---|---|
| `POST /admin/users/{id}/unmask` | PII 마스킹 해제(사유 기록) | ADM-311 | 스펙은 `GET …?unmask=true`+사유 헤더로 모델링 — 별도 POST 경로 자체가 스펙에도 없고 컨트롤러도 없음(SP3) |
| `GET /admin/merge-queue/{id}/preview` | 병합 미리보기(dry-run) | ADM-100 | 구현은 이미 있음 — 스펙에만 없음 |
| `POST /admin/merge-queue/{id}/merge`·`separate`·`void`, `…/claim`·`release`·`hold` | 병합/분리/VOID/클레임/보류 | ADM-100 | merge·separate·void는 구현 있음(스펙은 구형 `POST …/action` 하나로만 정의, 치환 필요). claim·release·hold는 구현도 없음(SP2) |
| `GET /admin/trend-items`, `GET /admin/trend-items/{id}` | 트렌드 목록/상세 | ADM-110/111 | 구현은 이미 있음 — 스펙 옛 경로 `/admin/trends`를 치환 |
| `GET /admin/verdicts`, `POST …/{id}/void`·`rejudge`·`extend-grace` | 판정 관리 목록/예외처리 | ADM-200 | 구현은 이미 있음 — 스펙 옛 경로 `/admin/trends/{id}` + `/admin/trends/{id}/exceptions`(단일 엔드포인트, type 파라미터 분기)를 치환 |
| `GET·PUT /admin/params/draft`, `POST …/draft/simulate`, `…/draft/request-approval` | 파라미터 드래프트(액터당 1개, ID 없음) | ADM-600 | 구현은 이미 있음 — 스펙 옛 모델 `/admin/parameter-drafts`(다건·ID기반)를 치환. 스펙 요약문의 "가중치 합계 1.0"(~`GET·POST /admin/parameter-drafts` summary/422)은 폐기된 외부지표 다축 가중치(S1~S5) 모델의 잔재 — 삭제 대상(스펙 정리는 SP0.5). 현재 구현은 `submitterTarget`+`hitThreshold` 2필드뿐이고 합계 제약 자체가 없다 |
| `POST /admin/seed/submissions`, `GET /admin/seed/accuracy` | 시딩 등록/적중률 | ADM-500 | 구현은 이미 있음 — 스펙 옛 경로 `/admin/seeding`, `/admin/seeding/stats`를 치환 |
| `POST /admin/accounts/{id}/disable`, `…/enable` | 관리자 계정 비활성화/재활성화 | ADM-800 | 구현은 이미 있음 — 스펙엔 대신 미구현 상태인 `POST …/{id}/role`만 있음. role 변경 자체가 필요하면 이 스펙 항목은 구현부터 필요(SP3), disable/enable은 스펙 추가만 필요 |
| `POST /admin/accounts/me/password` | 비밀번호 변경(부트스트랩 강제) | ADM-800 | 구현·스펙 둘 다 없음(SP3) |
| `GET /v1/me/preferences`, `GET /v1/me/reads` | 설정 조회, 읽음 목록(재로그인 시 복원) | A-7, A-2 | 구현은 이미 있음 — 스펙은 각각 PUT/POST만 정의(GET 없음) |
| `GET /v1/trends?q=`, `?category=`, `?sort=`, `?cursor=` | 검색·필터·정렬·페이지네이션 | A-4, A-5 | 스펙엔 이미 있으나 컨트롤러가 `daily` 외 파라미터를 전혀 읽지 않는다 — 이건 "스펙 확장 대상"이 아니라 "스펙은 있는데 구현이 없는" 역방향 문제. 스펙을 늘릴 게 아니라 `TrendController.list()`를 구현해야 한다 |
| `POST /v1/appeals`, `GET /v1/leaderboard` | 이의 제기, 명예의 전당 | A-7 | 둘 다 스펙엔 있으나 컨트롤러 자체가 없다(`AppealController`·leaderboard 컨트롤러 부재) — 위와 동일한 역방향 문제 |

---

## D. 콘솔 액션 → 역할 매트릭스 (요약, 02 §1.1)

> 아래는 **목표 권한**이다(02 §1.1). 현행 코드와 다른 칸은 각 화면 표의 권한 열을 따른다 — 차이는 **미구현(SP3)**. 예: 임시 비공개(`hide`)는 코드상 REVIEWER도 허용되지만 이 표는 목표대로 O만 표시하고, AUDITOR는 신고 큐·파라미터 드래프트 조회 권한이 코드엔 전혀 없는데도 이 표는 목표값인 "조회"로 표시한다. 실제 동작은 ADM-410·ADM-600 표를 참조.

| 액션(엔드포인트) | R | O | A | Au |
|---|:--:|:--:|:--:|:--:|
| 병합/분리 `POST /admin/merge-queue/{id}/merge`·`separate` | ✔ | ✔ | ✔ | 조회 |
| 병합 VOID `POST /admin/merge-queue/{id}/void` | — | ✔ | ✔ | 조회 |
| 판정 VOID/유예연장/재판정 `POST /admin/verdicts/{id}/void`·`extend-grace`·`rejudge` | — | ✔ | ✔ | 조회 |
| 상쇄원장 `…/ledger-adjust` | — | 상신 | ✔ | 조회 |
| 유저 제재 `/admin/sanctions` | — | 상신 | ✔ | 조회 |
| 등급 수동 조정 `…/grade-adjust` | — | — | ✔(2인) | 조회 |
| 파라미터 드래프트 `/admin/params/draft` | — | ✔ | ✔ | 조회 |
| 파라미터 적용 `/approvals/*/approve` | — | — | ✔(2인) | 조회 |
| 개인정보 열람 `POST /admin/users/{id}/unmask` | 마스킹 | 마스킹 | 사유후(기록) | 사유후(기록) |
| 임시 비공개 `/admin/reports/{id}/hide` | — | ✔ | ✔ | 조회 |
| 배치 수동 실행 `/admin/batch-jobs/*/run` | — | ✔ | ✔ | 조회 |
| 관리자 계정 생성·권한 `/admin/accounts` | — | — | ✔(2인) | 조회 |
| 감사 로그 `/admin/audit-log` | — | 본인분 | ✔ | ✔ (Au 전 영역 읽기 — 신고·드래프트 포함) |

> **2인 승인(4-eyes)**: 유저제재 확정 · 등급 수동조정 · 파라미터 적용 · 상쇄원장 100점 초과(재판정 ADJ 포함) · 관리자 계정 생성·권한 변경. 요청자≠승인자·승인자1≠승인자2는 `approval_requests` CHECK로 DB 강제(V6) — 단 이는 *계정* 수준이라 신규 계정 7일 승인권 유예(P8)로 *사람* 수준을 보강한다. 서버 게이트 `ApprovalGate` 하나가 전 대상을 강제(SP3). 현행 executor는 `PARAM_APPLY`만 존재.

---

## E. 프런트 착수 순서 (본 매핑 기준)

1. **앱**: 온보딩 → 홈(`/trends?daily`) → 상세 → 제보 → 나. Query 캐시 키를 엔드포인트 단위로.
2. **콘솔**: ADM-010 → ADM-100(병합, 단축키) → ADM-600(시뮬) → ADM-311(원장) → ADM-700. 권한 가드는 서버 응답 기반(클라 가드는 UX용).
3. 구현 열이 `🔶`(미구현)인 화면은 해당 컨트롤러부터 만들고, 스펙 열이 `🔶`인 엔드포인트는 착수 전에 `openapi.yaml`에 먼저 추가 → 타입 재생성.
