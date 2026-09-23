rootProject.name = "trend-radar"

include(
    "domain-core",   // 순수 계산 엔진·정책 (프레임워크 의존 없음)
    "persistence",   // JPA/JDBC + Flyway (V1~V12)
    "audit",         // 감사 로그(조회행위 포함) · 해시체인
    "merge",         // 임베딩 유사도 병합 실행(MergeService) · TEI 임베딩 클라이언트 — scheduler·api-admin 공용
    "judge",         // 판정 실행(JudgeService) — scheduler·api-admin 공용. 배치·재판정·VOID·미리보기의 유일한 경로(P6)
    "scheduler",     // 배치 잡 + ShedLock(JDBC)
    "api-public",    // 앱 API /v1 (JWT)
    "api-admin",     // 콘솔 API /admin (세션+RBAC)
    "app"            // 부트스트랩(단일 실행 파일)
)
