# 트렌드 레이더 (Trend Radar)

유저 제보 = 조기경보 신호 / 자동 지표 = 최종 판정. 제보를 2주간 외부 지표로 관측해 유행 여부를
자동 판정하고, 그 적중률로 유저 등급을 매기는 서비스.

## 모노레포 구조

```
trend-radar/
├─ backend/     Java 17 · Spring Boot 3.3 (Gradle 멀티모듈, 모듈러 모놀리스)
│   ├─ domain-core/   점수·판정·등급 순수 엔진 (골든 테스트)
│   ├─ persistence/   JPA + Flyway 마이그레이션 V1~V11
│   ├─ scheduler/     verdict_runner · grade_recalc 배치
│   ├─ api-public/    앱 API /v1 · api-admin/ 콘솔 API /admin
│   ├─ audit/ collectors/ app/(부트스트랩)
│   └─ api-spec/openapi.yaml   OpenAPI 3.1 — 프런트 타입 원천
├─ app/         유행수명 앱 (React Native · Expo)
├─ admin/       관리자 콘솔 (React · Vite, 데스크톱 전용)
├─ infra/       docker-compose (Postgres + pgvector)
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
./gradlew :app:bootRun                 # Flyway가 V1~V11 적용 후 :8080 기동
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

| 영역                                   | 상태                                                                  |
| -------------------------------------- | --------------------------------------------------------------------- |
| 백엔드 전체 빌드                       | ✅`./gradlew` 전 모듈 컴파일 + 테스트 성공                          |
| DB 스키마 V1~V11                       | ✅ pgvector 컨테이너 검증 (append-only 트리거·2인승인 CHECK 등 실증) |
| 도메인 엔진(점수·판정·등급·정규화)  | ✅ 골든 테스트 통과 (순수 함수, 시뮬레이션 재사용)                    |
| persistence 엔티티·리포지토리         | ✅ 컴파일 (append-only @Immutable, order_rank 뷰)                     |
| 배치 verdict_runner · grade_recalc    | ✅ 컴파일 (멱등·order_rank 동결·시딩 제외)                          |
| api-public`GET /v1/trends`           | ✅ 수직 슬라이스 구현                                                 |
| OpenAPI 3.1 계약 (앱7·콘솔15 화면)    | ✅ validator 통과                                                     |
| 앱 7화면 + API 연결                    | ✅ tsc 통과 · Metro 번들 성공                                        |
| 그 외`/v1`·`/admin` 엔드포인트    | 🔶 계약만(백엔드 구현 예정) — 05 §C                                 |
| 콘솔 P0 화면 (010·100·600·311·700) | ✅ tsc·vite 빌드 + 브라우저 렌더 (dev 픽스처, /admin 연동 대기)      |
| 그 외 콘솔 화면                        | 🔶 사이드바 스텁                                                      |

## 설계 문서

- `01-system-design.md` · `02-admin-console.md` · `03-merge-clustering.md`
- `04-development-plan.md` — 개발 설계서(스택·아키텍처·로드맵)
- `05-screen-endpoint-map.md` — 화면 ↔ 엔드포인트 매핑
- `CLAUDE.md` — 프로젝트 컨텍스트·절대 원칙

## 절대 원칙 (구현에서 강제됨)

R1 유저 투표로 판정하지 않음 · R2 원장 불변(DB 트리거+@Immutable) · R3 등급=적중률(AS AND TI) ·
R4 자동은 플래그까지 · R5 지표는 증가분.

## 다음 착수 순서

`04 §14`(엔진·감사로그 우선) → `05 §E`(앱: 홈→상세→제보 / 콘솔: 010→100→600→311→700).
백엔드 나머지 엔드포인트(제보·나·워치·콘솔 큐)를 채우면 앱/콘솔이 실데이터로 붙는다.

## 미결 (진행 차단 요소)

O1 가중치·임계값(Phase 0 백테스트) · O3 디시 수집(법무) · O6 독립/커머스(인증 모듈) ·
M2 임베딩 모델 · 배포처(클라우드/온프레).
