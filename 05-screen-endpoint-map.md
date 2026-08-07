# 화면 ↔ 엔드포인트 매핑 (v0.1)

> **목적**: 앱 7화면·콘솔 15화면을 API·권한과 1:1로 잇는다. 프런트 착수 순서와 **누락 엔드포인트 발견**에 쓴다.
> **근거**: `유행수명 앱.dc.html`, `관리자 콘솔.dc.html`, `backend/api-spec/openapi.yaml`, `02-admin-console.md §1.1`(RBAC)
> 표기: `✅` OpenAPI v0.1에 정의됨 · `🔶` 스펙 확장 대상(§C에 모음)

---

## A. 앱 (React Native / Expo) — JWT

### A-1. 온보딩 (3스텝)
| 상호작용 | API | 상태 | 비고 |
|---|---|:--:|---|
| 관심분야 3개 선택 → 알림시간 선택 → 완료 | `PUT /v1/me/preferences` | ✅ | 가입 직후. 워치가 첫날부터 채워지는 근거 |
| "오늘의 5개 보기" 진입 | `GET /v1/trends?daily=true` | ✅ | 홈 프리페치 |

### A-2. 홈 · 오늘의 5개
| 상호작용 | API | 상태 | 비고 |
|---|---|:--:|---|
| 카드 5개 로드 (단계·D-값·전파 미리보기) | `GET /v1/trends?daily=true` | ✅ | 정렬 = "지금 행동해야 하는 순"(서버) |
| 상단 스트릭·읽음 진행 | `GET /v1/me/summary` | 🔶 | streak·연속완독. 읽음(read)은 로컬 + `POST /v1/me/reads` 🔶 |
| 카드 탭 → 상세 | (클라 네비) | — | 진입 시 읽음 표시 |

### A-3. 상세 · 전파 경로
| 상호작용 | API | 상태 | 권한 |
|---|---|:--:|---|
| 상세 로드 (판정·전파경로·연령대·뜻/유래/예문) | `GET /v1/trends/{id}` | ✅ | 전체 |
| 지표 시계열(원시 그래프) | `GET /v1/trends/{id}/metrics` | ✅ | **L2 이상**(403 처리) |
| 전파경로 3뷰 토글(지도/타임라인/볼륨) | (클라, `propagationPath` 재사용) | — | |
| "뜬다 / 안 뜬다" 투표 | `POST /v1/trends/{id}/vote` | ✅ | 판정과 무관(R1) |
| 워치 추가/해제 | `POST /v1/me/watch` · `DELETE /v1/me/watch/{keyword}` | ✅ | |

### A-4. 검색 판정
| 상호작용 | API | 상태 | 비고 |
|---|---|:--:|---|
| 단어 입력 → 판정 결과 | `GET /v1/trends?q=…` | ✅ | 정규화 매칭 |
| 결과 없음 → 워치 추가 | `POST /v1/me/watch` | ✅ | |
| "많이 물어본 단어" 추천 | `GET /v1/trends?sort=asked` | 🔶 | 인기 질의 정렬 파라미터 확장 |
| 근거 자세히 보기 → 상세 | `GET /v1/trends/{id}` | ✅ | |

### A-5. 제보 (새 제보 / 내 제보)
| 상호작용 | API | 상태 | 비고 |
|---|---|:--:|---|
| 항목명 입력 시 중복 감지 | `GET /v1/trends?q=…`(디바운스) | ✅ | 확정 방어는 `POST` 409 |
| 제보권 잔량(도트) | `GET /v1/me/summary` | 🔶 | quota 주간·이월없음 |
| 제보하기(카테고리·플랫폼·URL·확신도·고지) | `POST /v1/submissions` | ✅ | 201 / 409(중복) / 422(권한·누락) |
| "동의로 올리기" | `POST /v1/trends/{id}/endorse` | ✅ | 제보권 미차감 |
| 내 제보 탭 (판정 상태·Δ) | `GET /v1/submissions/me` | ✅ | PENDING/HIT/MISS/VOID |

### A-6. 워치
| 상호작용 | API | 상태 |
|---|---|:--:|
| 감시 목록(단계·노트) | `GET /v1/me/watch` | ✅ |

### A-7. 나 · 등급 · 원장
| 상호작용 | API | 상태 | 비고 |
|---|---|:--:|---|
| 등급·다음 승급 부족분 | `GET /v1/me/grade` | ✅ | `requirements[].basis` 산정근거 동봉(R3) |
| 점수 원장 전건 | `GET /v1/me/ledger` | ✅ | append-only |
| 통계(적중률·읽은 트렌드) | `GET /v1/me/summary` | 🔶 | |
| 설정 행(알림시간·관심분야) | `GET /v1/me/preferences` | ✅ | |
| 이의 제기(원장/판정 상세에서) | `POST /v1/appeals` | ✅ | 처리기한 5영업일 |

---

## B. 관리자 콘솔 (React) — 세션+2FA · RBAC

역할 표기: R=REVIEWER, O=OPERATOR, A=ADMIN, Au=AUDITOR(읽기전용). 근거 02 §1.1.

### ADM-010 · 오늘의 작업
| 상호작용 | API | 상태 | 권한 |
|---|---|:--:|---|
| 큐 카운트·SLA초과·시스템알림·시딩비중 | `GET /admin/queues/summary` | ✅ | R/O/A/Au |

### ADM-100 · 병합 검수 큐 ★
| 상호작용 | API | 상태 | 권한 |
|---|---|:--:|---|
| 큐 로드(유사도·양쪽 비교·선점 미리보기) | `GET /admin/merge-queue` | ✅ | R/O/A/Au(조회) |
| 병합 / 분리 | `POST /admin/merge-queue/{id}/action` (MERGE·SPLIT) | ✅ | R/O/A · `Idempotency-Key` 필수 |
| VOID(허위/규정위반) | `POST …/action` (VOID) | ✅ | **O/A** (R은 403) |
| 보류(HOLD) | `POST …/action` (HOLD) | ✅ | R/O/A |

### ADM-110 / 111 · 트렌드 목록 / 상세
| 상호작용 | API | 상태 | 권한 |
|---|---|:--:|---|
| 항목 목록·필터 | `GET /admin/trends` | 🔶 | R/O/A/Au |
| 항목 상세(지표·예상판정 잠정) | `GET /admin/trends/{id}` | ✅ | R/O/A/Au |

### ADM-200 · 판정 관리 (예외 전용)
| 상호작용 | API | 상태 | 권한 |
|---|---|:--:|---|
| VOID / 유예연장 / 재판정 | `POST /admin/trends/{id}/exceptions` | ✅ | **O/A** · 사유코드 필수 |
| ~~HIT/MISS 변경·T 수동입력·verdict 삭제~~ | **엔드포인트 없음** | — | 금지기능(R1) |

### ADM-210 · 지표 수집 모니터
| 상호작용 | API | 상태 | 권한 |
|---|---|:--:|---|
| 소스별 상태·성공률·한도 | `GET /admin/collectors` | ✅ | R/O/A/Au |
| 영향 항목 일괄 유예 연장 | `POST /admin/collectors/extend-affected` | ✅ | **O/A** |

### ADM-300 · 어뷰징 플래그 큐
| 상호작용 | API | 상태 | 권한 |
|---|---|:--:|---|
| 플래그 목록·근거·연결그래프 | `GET /admin/abuse-flags` | 🔶 | O/A/Au |
| 오탐 처리 / 경고 발송 | `POST /admin/abuse-flags/{id}/resolve` | 🔶 | O/A |
| 제재 상신 → ADMIN | `POST /admin/sanctions` | 🔶 | O 상신 |

### ADM-310 / 311 · 유저 목록 / 상세·원장
| 상호작용 | API | 상태 | 권한 |
|---|---|:--:|---|
| 유저 목록 | `GET /admin/users` | 🔶 | R/O/A/Au |
| 유저 상세·원장(마스킹) | `GET /admin/users/{id}` | ✅ | 마스킹 R/O · 사유후 A/Au |
| 상쇄원장(ADJ) 추가 | `POST /admin/users/{id}/ledger-adjust` | ✅ | **A**(100점↑ 2인) |
| 제재 상신 | `POST /admin/sanctions` | 🔶 | O 상신 |
| 등급 수동 조정 | `POST /admin/users/{id}/grade-adjust` | 🔶 | **A(2인)** |

### ADM-320 · 제재 관리
| 상호작용 | API | 상태 | 권한 |
|---|---|:--:|---|
| 제재 목록·상태 | `GET /admin/sanctions` | 🔶 | O/A/Au |
| 확정(2인 승인) | `POST /admin/approvals/{id}/approve` | ✅ | **A(2인)** |

### ADM-400 · 이의 제기 큐
| 상호작용 | API | 상태 | 권한 |
|---|---|:--:|---|
| 큐·판정근거 자동첨부 | `GET /admin/appeals` | 🔶 | O/A/Au |
| 인용/부분/기각(근거수치 통보) | `POST /admin/appeals/{id}/resolve` | 🔶 | O/A · 인용 시 ADJ 발생 |

### ADM-410 · 신고 콘텐츠 큐 (공개 전 필수)
| 상호작용 | API | 상태 | 권한 |
|---|---|:--:|---|
| 신고 목록(신고자 비식별) | `GET /admin/reports` | 🔶 | R/O/A |
| 임시비공개/복원/영구비공개 | `POST /admin/reports/{id}/decide` | 🔶 | O/A · 4h 미처리 자동 비공개 |

### ADM-500 · 시딩 관리 (Phase 1)
| 상호작용 | API | 상태 | 권한 |
|---|---|:--:|---|
| 시딩 등록(`is_seed=true`, 원장 미반영) | `POST /admin/seeding` | 🔶 | O/A |
| 시딩 비중·담당자 적중률 | `GET /admin/seeding/stats` | 🔶 | O/A/Au |

### ADM-600 · 파라미터 스튜디오 ★
| 상호작용 | API | 상태 | 권한 |
|---|---|:--:|---|
| 드래프트 목록/생성(합계 1.0 강제) | `GET·POST /admin/parameter-drafts` | ✅ | O/A |
| 시뮬레이션(180일 재판정) | `POST /admin/parameter-drafts/{id}/simulate` | ✅ | O/A · 승인요청 선행 |
| 승인 요청(기본 예약·비소급) | `POST …/request-approval` | ✅ | O/A |
| 적용 승인 | `POST /admin/approvals/{id}/approve` | ✅ | **A(2인)** |

### ADM-610 · 등급 정책
| 상호작용 | API | 상태 | 권한 |
|---|---|:--:|---|
| 승급요구치·제보권·L4정원 드래프트 | `GET·POST /admin/grade-policy` | 🔶 | O/A(적용 2인) |

### ADM-700 · 감사 로그
| 상호작용 | API | 상태 | 권한 |
|---|---|:--:|---|
| 로그 검색(조회행위 포함) | `GET /admin/audit-log` | ✅ | **Au 전건 · A 본인외 열람 시 기록** |

### ADM-800 · 관리자 계정
| 상호작용 | API | 상태 | 권한 |
|---|---|:--:|---|
| 계정 목록·2FA·세션 | `GET /admin/accounts` | 🔶 | A |
| 권한 부여/회수 | `POST /admin/accounts/{id}/role` | 🔶 | **A(2인)** |

---

## C. 스펙 확장 대상 (OpenAPI v0.1 미포함 · 🔶)

프런트가 참조하지만 아직 `openapi.yaml`에 없는 엔드포인트. 다음 스펙 개정에서 추가.

| 제안 경로 | 용도 | 화면 |
|---|---|---|
| `GET /v1/me/summary` | 스트릭·제보권·적중률·읽은 수 | 홈·제보·나 |
| `POST /v1/me/reads` | 읽음 표시 서버 기록(선택) | 홈 |
| `GET /v1/trends?sort=asked` | 인기 질의 추천 | 검색 |
| `GET /admin/trends` | 트렌드 목록/필터 | ADM-110 |
| `GET /admin/abuse-flags`, `POST …/{id}/resolve` | 어뷰징 큐 | ADM-300 |
| `POST /admin/sanctions`, `GET /admin/sanctions` | 제재 상신/목록 | ADM-300/311/320 |
| `GET /admin/users`, `POST …/{id}/grade-adjust` | 유저 목록·등급조정 | ADM-310/311 |
| `GET /admin/appeals`, `POST …/{id}/resolve` | 이의 제기 큐 | ADM-400 |
| `GET /admin/reports`, `POST …/{id}/decide` | 신고 콘텐츠 큐 | ADM-410 |
| `POST /admin/seeding`, `GET /admin/seeding/stats` | 시딩 | ADM-500 |
| `GET·POST /admin/grade-policy` | 등급 정책 | ADM-610 |
| `GET /admin/accounts`, `POST …/{id}/role` | 관리자 계정 | ADM-800 |

---

## D. 콘솔 액션 → 역할 매트릭스 (요약, 02 §1.1)

| 액션(엔드포인트) | R | O | A | Au |
|---|:--:|:--:|:--:|:--:|
| 병합/분리 `POST …/action` | ✔ | ✔ | ✔ | 조회 |
| 판정 VOID/유예 `…/exceptions` | — | ✔ | ✔ | 조회 |
| 상쇄원장 `…/ledger-adjust` | — | 상신 | ✔ | 조회 |
| 유저 제재 `/admin/sanctions` | — | 상신 | ✔ | 조회 |
| 등급 수동 조정 `…/grade-adjust` | — | — | ✔(2인) | 조회 |
| 파라미터 드래프트 `/parameter-drafts` | — | ✔ | ✔ | 조회 |
| 파라미터 적용 `/approvals/*/approve` | — | — | ✔(2인) | 조회 |
| 개인정보 열람 `/admin/users/{id}?unmask` | 마스킹 | 마스킹 | 사유후 | 사유후 |
| 감사 로그 `/admin/audit-log` | — | 본인분 | ✔ | ✔ |

> **2인 승인(4-eyes)**: 유저제재 확정 · 등급 수동조정 · 파라미터 적용 · 상쇄원장 100점 초과. 요청자≠승인자·승인자1≠승인자2는 `approval_requests` CHECK로 이미 DB 강제(V6).

---

## E. 프런트 착수 순서 (본 매핑 기준)

1. **앱**: 온보딩 → 홈(`/trends?daily`) → 상세 → 제보 → 나. Query 캐시 키를 엔드포인트 단위로.
2. **콘솔**: ADM-010 → ADM-100(병합, 단축키) → ADM-600(시뮬) → ADM-311(원장) → ADM-700. 권한 가드는 서버 응답 기반(클라 가드는 UX용).
3. `🔶` 엔드포인트는 해당 화면 착수 전에 `openapi.yaml`에 먼저 추가 → 타입 재생성.
