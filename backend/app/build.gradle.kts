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
}

// 단일 실행 파일(모듈러 모놀리스). api-public/api-admin은 필터체인으로만 분리(04 §2.2).
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("trend-radar.jar")
}
