rootProject.name = "trend-radar"

include(
    "domain-core",   // 순수 계산 엔진·정책 (프레임워크 의존 없음)
    "persistence",   // JPA/JDBC + Flyway (V1~V9)
    "audit",         // 감사 로그(조회행위 포함) · 해시체인
    "collectors",    // 지표 수집 어댑터 (X/네이버DL/인스타/디시)
    "scheduler",     // 배치 잡 6종 + ShedLock(JDBC)
    "api-public",    // 앱 API /v1 (JWT)
    "api-admin",     // 콘솔 API /admin (세션+RBAC)
    "app"            // 부트스트랩(단일 실행 파일)
)
