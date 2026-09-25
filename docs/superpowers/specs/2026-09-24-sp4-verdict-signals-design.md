# SP4 · 판정 신호 재설계 + 백테스트 도구 — 설계

- 상위 스펙: `docs/superpowers/specs/2026-09-17-design-review-design.md` §3 SP4 행, P9, O1·O8
- 선행: SP1(#6)·SP2(#7) main 병합, SP3(#8, 승인 게이트) — 이 브랜치는 `sp3-console-control`에서 땄다. SP3 병합 후 main 기준으로 올린다
- 작성: 2026-09-24. 브레인스토밍에서 사용자가 정한 것: 백테스트는 도구 + 합성 시나리오 먼저(실제 30건은 이후 운영진이 파일로), 독립성 데이터는 지금 수집·기본 꺼짐, 축 결합은 T에 곱하는 감점 계수, 플랫폼은 근거 링크로 판별, 백테스트는 파라미터 스튜디오 탭

## 0. 범위

R5("제보는 머릿수가 아니라 여러 축으로 본다")를 코드로 옮기고, 그 값을 근거 있게 정할 도구를 만든다. **기본값에서는 판정이 지금과 한 건도 달라지지 않는다** — 값은 백테스트를 거쳐 스튜디오 승인 경로로 바꾼다.

| # | 항목 | 지금의 문제 (근거) |
|---|---|---|
| 1 | 목표치 | `ParameterSet.submitterTarget = 20` 절대값. 유저 규모와 무관하다(P9) |
| 2 | 시간 분포 | 판정이 제보 시각을 보지 않는다. 하루에 몰린 15명과 14일에 걸친 15명이 같다 |
| 3 | 플랫폼 다양성 | `submissions.source_platform`은 자유 텍스트(`VARCHAR(60)`), 앱 칩은 유저가 고르는 값이라 몰아주기 무리가 칩만 나눠 골라도 다양해 보인다. 판정에 쓰이지 않는다(`TrendSignal.distinctPlatforms` 주석) |
| 4 | 제보자 독립성 | 기기·IP·초대 관계 컬럼이 없다. 가입일은 클로즈드 베타에서 초대 유저가 한꺼번에 가입하므로 독립성 신호가 못 된다 |
| 5 | 스튜디오 | 편집 가능한 값이 목표치·HIT 임계 2개. 시뮬레이션은 최근 180일 판정 재생뿐이라 판정이 거의 없는 Phase 0에서는 비어 있다 |
| 6 | 백테스트 | 도구가 없다. O1(목표치·임계값)이 "근거 없는 초기 추정치"로 남아 있다 |

**비범위**
- 실제 과거 사례 30건 수집과 값 보정 — SP4 완료 후 운영진이 사례 파일을 만들어 백테스트 탭에서 돌리고 스튜디오 승인으로 적용한다(사용자 결정)
- 같은 기기 다계정 **제재** — Phase 2 `abuse_scan`. SP4의 독립성 압축은 T 계산에만 쓴다
- 카테고리별 파라미터(O2·O5), L1~L4 구간·확산배수·선점 가중·반감기·TI 편집, 예약 적용(`apply_mode`)
- 개인정보 수집·이용 동의 화면, 해시 보존 기간 — 법무 체크리스트 항목. **클로즈드 베타 시작 전 필수**(§11)
- 01 §4.1의 "첫 48시간 집중도" — 쓰지 않는다. 지속성은 제보일수 하나로 본다(S3)

**개발 DB**: 초기화 불필요. V31은 기존 행을 채우는 방식이다(§7).

## 1. 결정

| # | 결정 | 이유 |
|---|---|---|
| S1 | **유효 T = 제보자 비율 × 지속성 계수 × 다양성 계수.** 각 0~1, 두 계수는 감점만. HIT 임계·L1~L4 구간은 그대로 유효 T에 적용 | 경계에서 HIT↔MISS가 통째로 뒤집히는 절벽이 없다(최소 조건 방식 기각). 기존 구간 구조를 그대로 쓴다(사용자 결정) |
| S2 | **목표치 = `max(targetFloor, ⌈activeSubmitters × targetRatio⌉)`.** `activeSubmitters` = 관측 마감 직전 `activeWindowDays`일 동안 비VOID·비시딩 제보를 1건 이상 한 서로 다른 유저 수. 판정 시점에 세어 판정 근거에 동결. 재판정·VOID는 **원 판정의 동결값**을 쓴다 | P9. 동결해야 시뮬레이션이 비율만 바꿔 재계산할 수 있고, 재판정이 원 판정과 같은 기준에 선다(J2) |
| S3 | **지속성 = 시딩을 뺀 제보가 있었던 서로 다른 날(KST 달력일) 수.** 계수 = `pFloor + (1 − pFloor) × min(1, 제보일수 / pFullDays)` | 하루 몰림(단톡방 몰아주기·반짝)을 가른다. 48h 집중도보다 설명하기 쉽고 파라미터가 하나 적다 |
| S4 | **플랫폼 = 근거 링크 도메인으로 서버가 판별**(§3). 목록 밖은 `ETC` 한 종. 계수 = `dFloor + (1 − dFloor) × min(1, (플랫폼 수 − 1) / (dFullPlatforms − 1))` | 링크는 실제로 그 플랫폼 글이어야 해서 칩보다 속이기 어렵다(사용자 결정) |
| S5 | **독립성 = 기기·IP 해시를 지금부터 수집, 압축 모드 기본 `OFF`.** 모드 `DEVICE`는 같은 기기 그룹, `DEVICE_OR_IP`는 기기 또는 IP 그룹을 공유하는 제보자를 1명으로 센다(전이적으로 — A–B 기기 공유, B–C IP 공유면 A·B·C가 1명) | 수집하지 않은 데이터는 소급할 수 없다. 근거 없는 압축은 공유 IP(통신사 NAT·카페)의 정상 유저를 뭉갠다(사용자 결정) |
| S6 | **기본값은 중립** — `targetFloor 20`, `targetRatio 0`, `pFloor 1.0`, `dFloor 1.0`, `independenceMode OFF`. 이 값에서 새 엔진 결과 = 옛 엔진 결과 | 값 변경은 백테스트 근거와 2인 승인을 거친다. SP4 배포 자체가 판정을 바꾸지 않는다 |
| S7 | 판정 근거(`evidence_json`)에는 **해시가 아니라 그 판정 안에서만 의미 있는 그룹 번호**(1, 2, …)를 동결한다 | 판정 근거는 지울 수 없다(R2). 그룹 번호로도 시뮬레이션이 모드를 바꿔 재계산할 수 있다 |
| S8 | **SP4 이전 판정 근거는 새 축을 중립으로 읽는다** — 새 파라미터 없음 → 중립 기본값, `activeSubmitters` 없음 → 목표치 = `targetFloor`, 플랫폼 코드 없음 → 다양성 1, 그룹 없음 → 압축 없음. 빠진 값을 0으로 채우지 않는다(`pFloor = 0`은 "전부 감점"이다) | 재판정·시뮬레이션이 SP4 전 판정의 결과를 바꾸지 않는다 |
| S9 | **백테스트는 파라미터 스튜디오(ADM-600) 탭.** 사례 파일을 올리면 현재 운영값과 초안값으로 같은 판정 함수를 돌려 정답 대비 정밀도·재현율을 비교한다. 승인 요청 조건에 **백테스트 실행**을 추가한다 | 값을 바꾸는 사람이 개발자가 아니어도 되고, 결과가 승인 근거로 남는다(사용자 결정). Phase 0에는 180일 시뮬레이션이 비어 있다 |
| S10 | 사례 파일의 **정답 라벨(`label`)은 평가에만 쓴다.** 판정 입력은 사례의 제보 시계열뿐 | R5. 라벨은 사람이 사후에 외부 사정까지 보고 붙이지만 판정에 들어가지 않는다 |
| S11 | 업로드한 사례 파일은 **바꿀 수 없게 보관**(`backtest_datasets`)하고 결과는 파일 해시와 함께 남긴다 | 승인자·감사자가 "어떤 데이터로 돌린 결과인가"를 확인할 수 있어야 한다 |
| S12 | **앱·시딩 폼의 플랫폼 입력을 없앤다.** API의 `platform` 필드는 받되 판정·표시에 쓰지 않고 `source_platform`(보관용, NULL 허용)에 둔다 | 입력을 받으면서 안 쓰는 것은 혼란이다. 옛 앱 빌드가 보내는 값은 거부하지 않는다 |

## 2. 판정 공식 (`domain-core`, 순수 함수)

### 2.1 계산

```
independent = 독립 제보자 수          # 시딩 제외, independenceMode로 압축(S5)
target      = max(targetFloor, ceil(activeSubmitters × targetRatio − 1e-9))   # activeSubmitters 없으면 targetFloor. 1e-9는 부동소수 잡음(60 × 0.1) 제거
ratio       = clip(independent / target, 0, 1)

activeDays  = 시딩 제외 제보의 서로 다른 KST 날짜 수
persistence = pFloor + (1 − pFloor) × min(1, activeDays / pFullDays)

platforms   = 시딩 제외 제보의 서로 다른 플랫폼 코드 수          # 코드 없는 제보(SP4 이전)가 있으면 diversity = 1
diversity   = dFloor + (1 − dFloor) × min(1, (platforms − 1) / (dFullPlatforms − 1))

T = ratio × persistence × diversity
```

- 분류(`classify`)는 그대로: `T < hitThreshold → MISS`, 이후 `bandL2/L3/L4`로 L1~L4.
- 유효 제보가 시딩뿐이면 `independent = 0` → `T = 0` → MISS(현행 J3 그대로). 유효 제보 0건 → VOID(P5, 엔진 밖).
- 압축(S5): 노드 = 시딩 아닌 제보자(`userId`). 같은 `deviceGroup`(모드 `DEVICE`·`DEVICE_OR_IP`) 또는 같은 `ipGroup`(모드 `DEVICE_OR_IP`)을 가진 제보가 있는 두 제보자를 잇는다. 그룹이 `null`인 제보는 잇지 않는다. `independent` = 연결 요소 수. `OFF`면 서로 다른 `userId` 수(현행 `distinctSubmitters`).
- 점수 공식·선점 순위·원장은 바꾸지 않는다. 같은 기기의 두 계정도 각자 자기 제보로 점수를 받는다.

### 2.2 타입

- `TrendSignal`: `Integer activeSubmitters` 추가(nullable). `Entry`에 `String platformCode`(nullable, `Platform` enum 이름), `Integer deviceGroup`, `Integer ipGroup`(nullable) 추가. 기존 `platform`(자유 텍스트)은 옛 근거 호환용으로 남기되 새 판정에서는 `null`.
- `ParameterSet`: `submitterTarget` → **`targetFloor`로 이름을 바꾸고**, 새 축은 레코드 `SignalAxes(targetRatio, activeWindowDays, persistenceFloor, persistenceFullDays, diversityFloor, diversityFullPlatforms, independenceMode)` 하나로 묶어 `axes` 필드로 둔다(기본 `SignalAxes.NEUTRAL`). 범위 검증은 `SignalAxes` 생성자(§5.1 표).
- `VerdictEngine.evaluate`는 `VerdictOutcome`에 **`TBreakdown`**(`independent, target, activeSubmitters, ratio, activeDays, persistence, platforms, diversity, t`)을 담아 돌려준다. 판정 근거·ADM-200·스튜디오·백테스트가 같은 분해를 표시한다.
- `ParamsSnapshot`: `axes`(SignalAxes) 필드 추가, JSON 컴포넌트 이름 `submitterTarget`은 옛 근거 호환으로 유지. **역직렬화 시 `axes`가 없으면 `NEUTRAL`**(S8) — 새 축을 원시 필드로 풀어 두면 빠진 값이 0으로 채워지므로 객체 하나로 묶는다. 테스트로 고정.
- `VerdictEvidence`: `tBreakdown` 추가. `distinctPlatforms`는 플랫폼 코드 기준 값.

### 2.3 산정 근거 표시 (CLAUDE.md "관리자 API는 산정 근거를 함께 반환")

`T 0.48 = 제보자 15/20 (0.75) × 지속성 0.80 (2일 · 기준 5일) × 다양성 0.80 (2곳 · 기준 3곳)` — 압축이 켜져 있으면 `제보자 13/20 (계정 15 → 독립 13)`.

## 3. 플랫폼 판별 (`domain-core` `Platform`, `PlatformResolver`)

| 코드 | 표시 | 도메인(자신 + 하위 도메인) |
|---|---|---|
| `DCINSIDE` | 디시 | dcinside.com |
| `THEQOO` | 더쿠 | theqoo.net |
| `FMKOREA` | 에펨코리아 | fmkorea.com |
| `INSTIZ` | 인스티즈 | instiz.net |
| `X` | X | x.com, twitter.com |
| `INSTAGRAM` | 인스타 | instagram.com |
| `THREADS` | 스레드 | threads.net, threads.com |
| `YOUTUBE` | 유튜브 | youtube.com, youtu.be |
| `TIKTOK` | 틱톡 | tiktok.com |
| `NAVER` | 네이버 | naver.com |
| `ETC` | 기타 | 그 외 전부 |

- 호스트는 소문자화, 포트 제거 후 **도메인과 같거나 `.` + 도메인으로 끝날 때만** 일치(`evil-dcinside.com`은 `ETC`).
- 파싱 실패·스킴 없음·단축 링크(bit.ly, t.co 등) → `ETC`.
- 목록 변경은 코드 + DB CHECK 제약 변경(마이그레이션)이다.
- 앱 미리보기용 복사본(`app/src/platform.ts`)을 둔다. 서버 판별이 권위이고, 불일치는 미리보기 문구만 틀린다.

## 4. 기기·IP 수집

- **앱**: `expo-application` 추가. 안드로이드 `getAndroidId()`, iOS `getIosIdForVendorAsync()`, 웹은 최초 실행 시 만든 무작위 ID(`expo-secure-store`가 아니라 웹 저장소). 모든 공개 API 요청에 `X-Device-Id` 헤더(최대 128자).
- **서버**(`api-public` `SubmissionService`): 제보 생성 시
  - `device_hash = HMAC-SHA256(SIGNAL_HASH_SECRET, "device:" + X-Device-Id)` — 헤더 없거나 비면 `NULL`, 길이 초과(128자)면 422
  - `ip_hash = HMAC-SHA256(SIGNAL_HASH_SECRET, "ip:" + 키)` — IPv4는 주소 전체, IPv6는 앞 /64. 주소는 `request.getRemoteAddr()`. 프록시 뒤 배포 시 `SERVER_FORWARD_HEADERS_STRATEGY`로 신뢰 프록시 설정(기본 `none`)
  - 원문은 저장·로그·예외 메시지 어디에도 남기지 않는다
- 시딩(`AdminSeedService`)은 두 해시 모두 `NULL`.
- `SIGNAL_HASH_SECRET`이 없으면 기동 실패. 테스트는 테스트 속성으로, `infra/docker-compose.yml`은 `.env`로 주입. 비밀값을 바꾸면 이전 해시와 그룹이 이어지지 않는다(운영 안내).
- **판정 시 그룹 번호(S7)**: `JudgeService.collect`가 그 항목의 제보를 제출 시각 순으로 훑으며 처음 본 `device_hash`에 1, 2, …를 매기고 `ip_hash`도 따로 매긴다. 근거에는 번호만 남는다.

## 5. 파라미터 스튜디오 (ADM-600)

### 5.1 편집 항목 (2개 → 9개)

| 묶음 | 필드 | 기본 | 허용 범위 |
|---|---|---|---|
| 목표치 | `targetFloor` | 20 | 정수 ≥ 1 |
| | `targetRatio` | 0 | 0 ~ 1 |
| | `activeWindowDays` | 28 | 정수 7 ~ 90 |
| 판정 | `hitThreshold` | 0.20 | 0 ~ 1 |
| 지속성 | `persistenceFloor` | 1.0 | 0 ~ 1 |
| | `persistenceFullDays` | 5 | 정수 1 ~ 14 |
| 다양성 | `diversityFloor` | 1.0 | 0 ~ 1 |
| | `diversityFullPlatforms` | 3 | 정수 2 ~ 11 |
| 독립성 | `independenceMode` | `OFF` | `OFF` · `DEVICE` · `DEVICE_OR_IP` |

- 드래프트 `payload`(JSONB)에 9개를 저장하고 나머지는 지금처럼 `defaults()`. 옛 드래프트 payload(`submitterTarget`·`hitThreshold`만)는 `submitterTarget → targetFloor`, 나머지 중립으로 읽는다.
- `PUT /admin/params/draft`는 9개 필드를 받는다. 범위 위반·누락은 422(메시지가 필드명으로 시작). 수정하면 시뮬레이션·백테스트 결과를 모두 지운다.
- 시뮬레이션(180일 재생)은 그대로 두되 결과에 축별 변화 건수를 더하지 않는다(YAGNI) — 사례별 분해는 백테스트가 보여준다.

### 5.2 사례 파일 형식 (`formatVersion: 1`)

```json
{
  "formatVersion": 1,
  "name": "과거 사례 1차",
  "cases": [{
    "caseId": "c01",
    "title": "새싹 챌린지",
    "label": "HIT",
    "labelReach": "L2",
    "note": "선택",
    "deadline": "2026-05-15T00:00:00Z",
    "activeSubmitters": 150,
    "submissions": [
      { "submitter": "u1", "at": "2026-05-01T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/…",
        "device": "d1", "ip": "i1", "seed": false }
    ]
  }]
}
```

- `label`: `HIT` | `MISS`. `labelReach`: `HIT`일 때만, 선택. `activeSubmitters`: 선택(없으면 목표치 = `targetFloor`).
- `submitter`·`device`·`ip`는 아무 이름표 — 같은 이름표 = 같은 사람·기기·IP. `seed` 기본 `false`.
- 검증(위반 시 422, 메시지에 `cases[3].submissions[5].at` 같은 위치): `formatVersion == 1`, `caseId` 중복 없음, 사례 1~500건, 사례당 제보 ≥ 1, `at < deadline`, 필수 필드, 파일 ≤ 2MB.
- 사례 → `TrendSignal` 변환: 이름표를 결정적 UUID로, `evidenceUrl`을 `PlatformResolver`로, `device`·`ip`를 §4와 같은 방식으로 그룹 번호로. 운영 판정과 **같은 엔진**을 탄다.

### 5.3 API

| 메서드·경로 | 역할 | 동작 |
|---|---|---|
| `POST /admin/params/backtest-datasets` (JSON 본문) | O/A | 검증 후 보관. 같은 SHA-256이 있으면 기존 행 반환(200), 새로 만들면 201. 감사 `BACKTEST_DATASET_UPLOAD` |
| `GET /admin/params/backtest-datasets` | O/A/Au | 목록(이름·건수·해시·올린 사람·시각). 본문은 빼고 |
| `GET /admin/params/backtest-datasets/example` | O/A/Au | 저장소의 합성 시나리오 파일(§5.5) 그대로 |
| `POST /admin/params/draft/backtest {datasetId}` | O/A | 현재 운영값·초안값으로 실행, 결과를 드래프트에 저장. 감사 `PARAM_BACKTEST` |
| `GET /admin/params/draft` | O/A/Au | 기존 응답 + 9개 값 + `backtestResult` |

- 승인 요청(`POST /admin/params/draft/request-approval`) 조건: **시뮬레이션 결과와 백테스트 결과가 둘 다 있을 것**(둘 다 마지막 수정 이후 — 수정 시 지워지므로 자동). 없으면 422 "백테스트를 먼저 실행해야 승인 요청을 보낼 수 있습니다".
- 승인 대기함(`ParamApplyExecutor.describe`) 요약: `데이터셋 '과거 사례 1차'(30건) — 정밀도 0.62→0.78, 재현율 0.80→0.75, 판정 변경 4건`.
- 실행기(`PARAM_APPLY`)는 바꾸지 않는다 — 승인 즉시 운영값, 이후 판정부터(비소급).

### 5.4 백테스트 결과 (`parameter_drafts.backtest_result`)

- 머리: `datasetId`, `datasetName`, `sha256`, `caseCount`, `ranAt`
- 요약(현재·초안 각각): 혼동표(TP·FP·FN·TN, HIT = 양성), 정밀도 `TP/(TP+FP)`, 재현율 `TP/(TP+FN)`(분모 0이면 `null` → 화면 "—"), 확산 등급 일치율(정답 `labelReach`가 있고 양쪽 다 HIT인 사례 중), 판정이 바뀐 사례 수
- 사례별: `caseId`, `title`, `label`, `labelReach`, 현재 `{result, reach, TBreakdown}`, 초안 `{…}`, `changed`

### 5.5 합성 시나리오 (`backend/api-admin/src/main/resources/backtest/synthetic-v1.json`)

약 12건. 각 사례는 한 가지 축을 드러내도록 만든다: 자연 확산(여러 날·여러 플랫폼, HIT), 하루 몰림(MISS), 한 플랫폼 몰림(MISS), 같은 기기 무리(MISS), 공유 IP 정상 유저(HIT — `DEVICE_OR_IP` 오탐을 보여 줌), 소규모지만 며칠·여러 곳(HIT), 대규모 커뮤니티 활성 유저 대비 소수(MISS — 상대 목표치), 시딩만(MISS), 경계 사례 2~3건. 정확한 수치와 "예시 보정값"은 구현 계획에서 정한다.
- 이 파일은 형식 견본이자 통합 테스트 입력이다. 라벨은 "이 설계가 의도한 판정"이지 실측이 아니다 — 화면·파일 머리에 그렇게 적는다.

## 6. 판정 파이프라인 (`judge`)

- `JudgeService.collect`: 제보에서 `platform`(코드)·그룹 번호를 채우고, `activeSubmitters`를 센다 — `submissions`에서 `created_at ∈ [deadline − activeWindowDays, deadline)`, `result <> 'VOID'`, `NOT is_seed`인 서로 다른 `user_id` 수. 창 크기는 그 판정에 쓰는 파라미터의 값.
- 재판정·VOID(`rejudge`·`voidItem`): 파라미터는 지금처럼 원 판정의 동결값(J2), `activeSubmitters`도 **원 판정 근거의 값**(S2, 없으면 `null`). 제보·그룹·플랫폼은 지금 데이터로 다시 모은다(VOID 반영 — 현행과 같다).
- 미리보기(`preview`)도 같은 경로.

## 7. 스키마 — `V31__sp4_verdict_signals.sql` (+ Java 마이그레이션)

```
submissions
  + platform     VARCHAR(20) NULL → (V31_1 채움) → NOT NULL + CHECK (platform IN (…11개…))
  + device_hash  VARCHAR(64) NULL CHECK (소문자 hex 64자)
  + ip_hash      VARCHAR(64) NULL CHECK (소문자 hex 64자)
  ~ source_platform  DROP NOT NULL        -- 보관용(S12)
  + 인덱스 (created_at) WHERE result <> 'VOID' AND NOT is_seed   -- activeSubmitters 계산

backtest_datasets (신설)
  id UUID PK, name VARCHAR(120) NOT NULL, sha256 VARCHAR(64) NOT NULL UNIQUE CHECK (소문자 hex 64자),
  case_count INT NOT NULL, payload JSONB NOT NULL,
  uploaded_by UUID NOT NULL REFERENCES admin_accounts, created_at TIMESTAMPTZ NOT NULL
  -- 불변: 애플리케이션은 INSERT만. UPDATE/DELETE 경로 없음

parameter_drafts
  + backtest_result JSONB NULL
```

- 순서: `V31__sp4_verdict_signals.sql`(컬럼 추가) → `V31_1__backfill_submission_platform.java`(`persistence` 모듈 `db.migration` 패키지, `PlatformResolver`로 기존 행의 `evidence_url`을 판별해 채움 — **판별 규칙이 SQL·Java 두 벌이 되지 않게**) → `V31_2__submission_platform_constraints.sql`(NOT NULL + CHECK).
- `db/seed/app_flow_test_data.sql`의 제보 INSERT에 `platform` 추가.
- 컬럼 주석(V23 방식)도 갱신.

## 8. 표시 쪽 변경

- 공개 API: `TrendItemDetail.distinctPlatforms`, `TrendQueryService`의 플랫폼별 최초 목격, `StageEvaluator`(급상승 등 표시 단계)를 **`platform` 코드 기준**으로. 표시는 한글 라벨(§3 표).
- `SubmissionCreate.platform`: 선택 필드로 바꾸고 설명에 "무시됨, 보관용" 명시(S12). `X-Device-Id` 헤더를 openapi에 문서화.
- 관리자 API: 제보 목록(`TrendItemAdminService`, `MergeQueueController`)이 `platform` 라벨을 반환. ADM-200 판정 상세가 `TBreakdown`을 반환.

## 9. 프론트 (백엔드가 커밋·리뷰를 통과한 뒤)

- **앱** `SubmitScreen`: 플랫폼 칩 삭제, 링크 입력 아래 "디시 제보로 기록돼요"(목록 밖이면 "기타 사이트로 기록돼요"). `client.ts`가 `X-Device-Id` 헤더.
- **콘솔** `ParamStudioScreen`: 9개 필드(묶음별), 백테스트 탭 — 데이터셋 업로드·목록·예시 받기, 실행, 요약 카드(현재 vs 초안), 사례별 표(바뀐 사례 강조, T 분해 펼침). 승인 요청 버튼은 두 결과가 있을 때만 활성.
- `VerdictScreen`: T 분해 표시(§2.3).
- 시딩 화면: 플랫폼 입력 삭제, 판별 결과 미리보기.
- `ApprovalsScreen`: `PARAM_APPLY` 요약에 백테스트 수치(서버 `describe` 그대로).
- 제보 목록 표시 화면들: 플랫폼 라벨.

## 10. 테스트

**순수 함수 (`domain-core`)**
1. 기본값에서 새 `VerdictEngine` = 옛 공식 — 무작위 신호 200건(시드 고정) 비교
2. 목표치: `activeSubmitters` 없음 → floor, `ceil` 경계, floor가 이기는 경우
3. 지속성: KST 자정 경계(UTC 14:59/15:00), 시딩 날짜는 안 셈, `min(1, …)` 상한
4. 다양성: 1곳 = floor, 기준 이상 = 1, `ETC` 1종, 코드 없는 제보 섞이면 1
5. 압축: `DEVICE`, `DEVICE_OR_IP`, 전이 연결(A–B 기기, B–C IP), `null` 그룹은 안 이음, 같은 유저 여러 제보, 시딩 제외
6. S8: SP4 이전 `ParamsSnapshot`·`TrendSignal` JSON을 읽으면 중립 — 0 아님
7. `ParameterSet` 범위 검증
8. `PlatformResolver`: 하위 도메인, `youtu.be`, 대소문자, 포트, `evil-dcinside.com` → ETC, 단축 링크, 파싱 실패

**통합 (`app`)**
9. V31: 기존 제보 행이 판별 코드로 채워지고 CHECK가 목록 밖 값을 거부
10. 제보 API: 해시만 저장(원문과 다름, 64자), 같은 기기 ID → 같은 해시, 헤더 없음 → 성공·`NULL`, 129자 → 422
11. 판정: 근거 JSON에 해시 문자열이 없고 그룹 번호·`activeSubmitters`·`tBreakdown`이 있음
12. 재판정: SP4 이전 형식 근거의 판정을 재판정해도 결과·원장 불변
13. 스튜디오: 9개 저장·범위 422, 수정 시 두 결과 삭제, 백테스트 없이 승인 요청 422, AUDITOR 쓰기 403
14. 데이터셋: 형식 오류 422(위치 포함), 같은 파일 재업로드 → 같은 id, 2MB·500건 초과 422
15. 합성 시나리오: 예시 보정값으로 전 사례가 라벨과 일치, 기본값으로는 옛 공식 결과와 같음
16. **완료 기준 경로**: 업로드 → 백테스트 → 시뮬레이션 → 승인 요청 → 다른 ADMIN 승인 → 다음 `verdict_runner` 판정이 새 파라미터(근거의 `params`·`tBreakdown`)로 나옴

## 11. 문서·설정

- CLAUDE.md: R5 "(현행 코드는 제보자 수 축만 구현…)" 문구 → 4축 구현·기본 중립, O1·O8 상태("도구 완료, 값은 실사례 백테스트"), 핵심 개념 표의 T 정의
- `01-system-design.md` §4: 공식(§2), 48h 집중도 삭제, 플랫폼 = 링크 판별
- `02-admin-console.md` ADM-600: 9개 항목·백테스트 탭·승인 조건
- `04-development-plan.md`: V31, `SIGNAL_HASH_SECRET`
- `05-screen-endpoint-map.md`: 새 엔드포인트
- `backend/api-spec/openapi.yaml`, `infra/docker-compose.yml`·`.env.example`(`SIGNAL_HASH_SECRET`, `SERVER_FORWARD_HEADERS_STRATEGY`)
- 법무 체크리스트(CLAUDE.md): "디바이스 지문·IP 수집 동의" 항목에 **"SP4부터 수집 — 클로즈드 베타 전 필수, 해시 보존 기간 명시"** 추가

## 12. 완료 기준

1. 백테스트 탭에서 합성 데이터셋으로 **현재 공식 vs 새 공식의 정밀도·재현율 보고서**가 나온다(상위 스펙 SP4 행)
2. 테스트 16이 통과한다 — 새 파라미터가 스튜디오 승인 경로로 적용된다(상위 스펙 SP4 행)
3. 기본값 배포에서 판정이 한 건도 바뀌지 않는다(테스트 1·12·15)

## 13. 리스크·후속

- **값은 여전히 추정치다.** 합성 시나리오는 로직을 검증할 뿐 값을 정당화하지 않는다. 실사례 30건 파일이 오기 전까지 기본 중립을 유지하는 것이 이 설계의 전제다
- **IP 오탐**: 통신사 NAT로 무관한 유저가 같은 IP를 쓴다. `DEVICE_OR_IP`는 실사례 백테스트 전에는 켜지 않는다(합성 시나리오에 오탐 사례를 넣어 보여 준다)
- **기기 ID 회피**: 공장 초기화·에뮬레이터·여러 기기로 우회된다. 독립성 압축은 비용을 올리는 장치이지 보장이 아니다
- **해시 비밀값 교체** 시 이전 제보와 그룹이 끊긴다 — 교체는 판정 대기 항목이 적을 때
- **동의 전 수집**: 베타 유저에게서 수집하기 전에 동의 문구가 있어야 한다. Phase 0은 유저가 없어 문제없지만 베타 시작 조건에 건다
- **옛 앱 빌드**: `platform`을 계속 보내도 무시하고 `X-Device-Id`가 없으면 `NULL` — 독립성 신호가 비는 것뿐 제보는 된다
- 후속: 실사례 30건 파일 작성(운영진) → 백테스트 → 값 승인, 같은 기기 다계정 제재(Phase 2), 카테고리별 파라미터(O2·O5)
