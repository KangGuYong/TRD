# 트렌드 레이더 (Trend Radar)

유저 제보 = 조기경보 신호 / 자동 지표 = 최종 판정. 2주 관측 후 지표로 유행 여부를 판정하고,
적중률로 유저 등급을 매기는 서비스.

## 모노레포 구조

```
trend-radar/
├─ backend/     Java 17 · Spring Boot 3.3 (Gradle 멀티모듈, 모듈러 모놀리스)
│   ├─ domain-core/   점수·판정·등급 순수 엔진 (✅ 골든 테스트 통과)
│   ├─ persistence/   JPA + Flyway 마이그레이션 V1~V9 (✅ pgvector로 검증)
│   ├─ audit/ collectors/ scheduler/ api-public/ api-admin/ app/
│   └─ api-spec/openapi.yaml   OpenAPI 3.1 (✅ validator 통과) — 프런트 타입 원천
├─ app/         유행수명 앱 (React Native · Expo)
├─ admin/       관리자 콘솔 (React · Vite, 데스크톱 전용)
├─ infra/       docker-compose (Postgres + pgvector)
└─ 01~05-*.md   설계문서 (루트) + CLAUDE.md
```

## 설계 문서
- `01-system-design.md` · `02-admin-console.md` · `03-merge-clustering.md`
- `04-development-plan.md` — 개발 설계서(스택·아키텍처·로드맵)
- `05-screen-endpoint-map.md` — 화면 ↔ 엔드포인트 매핑

## 절대 원칙 (구현에서 강제됨)
R1 유저 투표로 판정하지 않음 · R2 원장 불변(DB 트리거) · R3 등급=적중률(AS AND TI) ·
R4 자동은 플래그까지 · R5 지표는 증가분. 상세는 `CLAUDE.md`.

## 로컬 실행 (개발)

```bash
# 1) DB
cd infra && docker compose up -d db

# 2) 백엔드 (최초 1회 래퍼 생성)
cd ../backend && gradle wrapper --gradle-version 8.10
./gradlew :domain-core:test        # 골든 테스트
./gradlew :app:bootRun             # Flyway가 V1~V9 적용 후 기동 (:8080)

# 3) 앱 / 콘솔
cd ../app && npm install && npm run gen:api && npm start
cd ../admin && npm install && npm run gen:api && npm run dev   # :5173
```

## 착수 순서
`04 §14`(엔진·감사로그 우선) → `05 §E`(앱: 홈→상세→제보 / 콘솔: 010→100→600→311→700).

## 미결 (진행 차단 요소)
O1 가중치·임계값(Phase 0 백테스트) · O3 디시 수집(법무) · O6 독립/커머스 · M2 임베딩 모델 · 배포처.
