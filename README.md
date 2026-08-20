# 트렌드 레이더 (Trend Radar)

## 개발 배경

이제 유행은 특정 세대만의 이야기가 아니다. 10대·20대·30대를 가리지 않고, 대한민국 전역에서
유행이 순식간에 번지고 순식간에 식는다. 문제는 속도다 — 유행을 남들보다 늦게 알면 그만큼
대화에서, 소비에서, 콘텐츠에서 뒤처진다. "유행을 모르면 뒤처지는 시대"가 됐는데, 정작 그
유행이 진짜인지 며칠 반짝하고 사라질 것인지 미리 가늠할 방법은 마땅치 않다.

## 개발 목표

**목표: 유행을 가장 빠르게, 가장 믿을 수 있게 접할 수 있는 앱을 만든다.**

이 프로젝트가 만들고자 하는 것은 두 가지다.

1. **조기 포착** — 유저가 "이거 뜰 것 같다"고 제보하면 그게 곧 조기경보 신호가 된다. 아직
   포털·SNS 지표에 잡히기 전, 현장에서 가장 먼저 감지된 유행을 앱에서 볼 수 있게 한다.
2. **신뢰할 수 있는 판정** — 조기 포착이 소문에 그치지 않도록, 이후 2주간 쌓이는 서로 다른
   제보자 수(제보 시계열)만으로 실제 유행했는지를 자동 판정한다. 외부 사이트 지표나 운영자의
   주관적 판단에 기대지 않고, 그 판정 적중률로 제보자에게 등급을 매겨 신뢰도 높은 제보를
   가려낸다.

즉, "누가 먼저 알아챘고, 그 감이 실제로 맞았는가"를 데이터로 증명하는 구조 위에서, 유저는
남들보다 빠르게 유행을 접하고 서비스는 그 정보의 신뢰도를 계속 검증해 나간다.

**한 줄 요약: 유저 제보 = 조기경보 신호이자 판정 데이터 / 배치 집계 = 최종 판정.**
2주간 쌓이는 후속 제보 시계열(서로 다른 제보자 수)만으로 유행 여부를 자동 판정하고, 그
적중률로 유저 등급을 매기는 서비스 — 외부 사이트 지표(X·네이버·인스타 등)는 쓰지 않는다.

## 모노레포 구조

```
trend-radar/
├─ backend/     Java 17 · Spring Boot 3.3 (Gradle 멀티모듈, 모듈러 모놀리스)
│   ├─ domain-core/   점수·판정·등급·정규화 순수 엔진 (골든 테스트)
│   ├─ persistence/   JPA + Flyway 마이그레이션 V1~V25
│   ├─ merge/         임베딩 유사도 클러스터링 (KURE-v1 연동)
│   ├─ scheduler/     verdict_runner · grade_recalc · cluster_merge 배치
│   ├─ api-public/    앱 API /v1 · api-admin/ 콘솔 API /admin
│   ├─ audit/         해시체인 감사로그 · app/(부트스트랩)
│   └─ api-spec/openapi.yaml   OpenAPI 3.1 — 프런트 타입 원천
├─ app/         유행수명 앱 (React Native · Expo)
├─ admin/       관리자 콘솔 (React · Vite, 데스크톱 전용)
├─ infra/       docker-compose (Postgres+pgvector, 임베딩 서비스)
└─ 01~05-*.md   설계문서 (루트) + CLAUDE.md
```

## 사전 준비물

| 도구           | 버전          | 용도                                     |
| -------------- | ------------- | ---------------------------------------- |
| Docker Desktop | 최신          | Postgres+pgvector (DB)                   |
| JDK            | 17+           | 백엔드 (Gradle 설치 불필요 — 래퍼 포함) |
| Node.js        | 20+ / npm 10+ | 앱·콘솔                                 |

> DB 접속 기본값: `jdbc:postgresql://localhost:5433/trd`, user `postgres`, pw `pw`
> (환경변수 `DB_URL`/`DB_USER`/`DB_PASSWORD`로 재정의).
> 컨테이너는 호스트 **5433** 포트를 쓴다 — 로컬에 설치된 PostgreSQL(5432)과 충돌을 피하기 위함.

## 최초 실행

### 1) 데이터베이스

```bash
cd infra
docker compose up -d db
```

`pgvector/pgvector:pg16` 컨테이너가 호스트 **5433** 포트로 뜬다. 스키마는 백엔드 기동 시 Flyway가 자동 적용한다.

> **접속 오류(password authentication failed) 대처**
>
> - 로컬에 PostgreSQL이 설치돼 5432를 쓰고 있으면 컨테이너 대신 그쪽에 붙어 실패한다 → 본 프로젝트는 컨테이너를 5433으로 분리해 회피.
> - 볼륨(`trd-db`)이 예전에 다른 비밀번호로 초기화됐다면 env 변경이 반영되지 않는다. 비번만 맞추려면:
>   `docker exec trd-db psql -U postgres -c "ALTER USER postgres WITH PASSWORD 'pw';"`
>   (완전 초기화는 `docker compose down -v` 후 다시 `up` — 데이터 삭제됨, 스키마는 Flyway가 재적용).

### 2) 백엔드 (앱·콘솔 API + 배치)

Gradle 래퍼가 포함돼 있어 별도 설치가 필요 없다(최초 실행 시 Gradle 8.10을 자동 내려받음, 인터넷 필요).

```bash
cd backend
./gradlew :domain-core:test            # 점수·판정·등급 골든 테스트   (Windows: .\gradlew.bat)
./gradlew :app:bootRun                 # Flyway가 V1~V25 적용 후 :8080 기동
```

- 기동되면 `GET http://localhost:8080/v1/trends?daily=true` 로 확인.

### 3) 앱 (Expo)

```bash
cd app
npm install
# 기기/에뮬레이터에서 백엔드에 붙이려면 접속 주소 지정:
#   iOS 시뮬레이터: http://localhost:8080
#   Android 에뮬레이터: http://10.0.2.2:8080
EXPO_PUBLIC_API_URL=http://localhost:8080 npm start
```

Expo Go 앱 또는 시뮬레이터로 QR/키를 눌러 실행. 백엔드가 없어도 앱은 로딩/에러 상태로 뜬다.

### 4) 관리자 콘솔 (Vite)

```bash
cd admin
npm install
npm run dev            # http://localhost:5173 (/admin 요청은 :8080으로 프록시)
```

### (선택) OpenAPI → 타입 생성

앱/콘솔 각각에서 계약 기반 타입을 재생성:

```bash
npm run gen:api        # openapi.yaml → src/api/schema.ts
```

## 현재 구현 상태

| 영역                                                   | 상태                                                                                  |
| ------------------------------------------------------ | -------------------------------------------------------------------------------------- |
| 백엔드 전체 빌드                                       | ✅ `./gradlew build` 전 모듈 컴파일 + 테스트 성공                                      |
| DB 스키마 V1~V25                                       | ✅ append-only 트리거·2인승인 CHECK·컬럼 코멘트까지 반영                              |
| 도메인 엔진(점수·판정·등급·정규화·시딩·병합계산)    | ✅ 골든 테스트 9종 통과 (순수 함수, ADM-600 시뮬레이션과 동일 코드 재사용)              |
| 배치 — verdict_runner · grade_recalc · cluster_merge    | ✅ 구현 (멱등·order_rank 동결·시딩 제외, cluster_merge는 KURE-v1 임베딩 유사도 연동) |
| 배치 — abuse_scan · l4_quota                            | 🔴 미착수 (엔티티·서비스 없음)                                                      |
| api-public `/v1` — 트렌드·제보·투표·나(등급/원장/워치) | ✅ 대부분 구현 (trends, submissions, vote, endorse, me/\*, reads)                     |
| api-public `/v1/appeals` · `/v1/leaderboard`            | 🔶 OpenAPI 계약만 (엔티티부터 없음)                                                  |
| **푸시 알림 발송**                                | 🔴 미구현 (`expo-notifications` 의존성만 설치, 발송 코드 0줄 — 워치해도 알림 안 감) |
| api-admin — 큐 요약·병합검수·판정관리·시딩·계정·감사로그·파라미터스튜디오 | ✅ 구현 + 콘솔 연동                                                    |
| api-admin — **2인 승인 실행 경로**(approve/reject)  | ✅ 신규 구현 — `ApprovalExecutor` 레지스트리, 파라미터 적용 실행기 포함             |
| api-admin — 신고 큐 · 이의제기 · 어뷰징/제재 · 유저관리·등급정책 | 🔴 미구현 (DB 테이블만 있거나 그마저 없음 — `reports` 테이블 자체가 없음)     |
| 앱 9화면(홈·상세·검색·워치·제보·로그인·설정·나·온보딩) | ✅ tsc 통과 · Metro 번들 성공                                                          |
| 콘솔 메뉴 15개 중 10개(010·100·110·111·200·311·500·600·620·700·800) | ✅ tsc·vite 빌드 + 브라우저 렌더 (일부 dev 픽스처 병행, 111은 110 하위 화면) |
| 콘솔 그 외 화면(300·310·400·410·610)                     | 🔶 사이드바 스텁                                                                    |
| 판정 임계값·목표 제보자 수(O1)                          | 🔴 미검증 — 근거 없는 초기 추정치 그대로 운영 중 (Phase 0 백테스트 미착수)          |

**목표 대비:** "신뢰할 수 있는 판정"의 핵심 엔진(점수·적중률·등급)은 구현·검증됐지만, 그 임계값 자체는
아직 실증 전이다. "빠르게 접한다"의 직접적 전달 채널인 푸시 알림은 아직 없다. 최근 작업은 대부분
Phase 2(공개) 전 필요한 운영/거버넌스 인프라(2인 승인, 감사로그, 신고 처리)에 쏠려 있다.

## 설계 문서

- `01-system-design.md` · `02-admin-console.md` · `03-merge-clustering.md`
- `04-development-plan.md` — 개발 설계서(스택·아키텍처·로드맵)
- `05-screen-endpoint-map.md` — 화면 ↔ 엔드포인트 매핑
- `CLAUDE.md` — 프로젝트 컨텍스트·절대 원칙

## 절대 원칙 (구현에서 강제됨)

R1 유저 투표로 판정하지 않음 · R2 원장 불변(DB 트리거+@Immutable) · R3 등급=적중률(AS AND TI) ·
R4 자동은 플래그까지 · R5 지표는 증가분.

## 다음 착수 순서

거버넌스 track(공개 전 필수, 순서 고정): 신고 큐(`reports` 테이블부터 신규) → 이의 제기 →
어뷰징 탐지/제재(2인 승인 경로 재사용) → 유저 관리·등급 정책.

목표 직결 track(위와 병렬 가능): 푸시 알림 발송 배선(워치 → 판정/HIT 시 알림) ·
Phase 0 백테스트(O1 — 과거 사례로 판정 임계값 보정, 실유저 등급 시작 전에 끝내야 함).

## 미결 (진행 차단 요소)

O1 판정 임계값·목표 제보자 수(Phase 0 백테스트 미착수) · O3 디시 수집(법무) ·
O6 독립/커머스(인증 모듈) · 배포처(클라우드/온프레) 미정.
