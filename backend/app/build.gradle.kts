plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":api-public"))
    implementation(project(":api-admin"))
    implementation(project(":scheduler"))
    implementation(project(":audit"))
    implementation(project(":merge"))
    implementation(project(":persistence"))
    implementation(project(":domain-core"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // 로컬 개발 편의: app/.env(gitignore 대상)를 자동으로 읽어 환경변수처럼 주입한다(FIREBASE_CREDENTIALS_PATH 등).
    implementation("me.paulschwarz:spring-dotenv:4.0.0")

    // 통합 테스트: 실제 PostgreSQL(pgvector)을 Testcontainers로 띄워 Flyway 전체 적용 + 보안 필터체인까지 검증.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}

// 단일 실행 파일(모듈러 모놀리스). api-public/api-admin은 필터체인으로만 분리(04 §2.2).
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("trend-radar.jar")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Docker Engine 29+는 최소 API 1.44를 요구한다. Boot 3.3.5가 관리하는 Testcontainers 1.19.8은
// 구버전 API로 요청해 400이 나므로, API 버전 협상이 고쳐진 버전으로 올린다.
extra["testcontainers.version"] = "1.21.4"
