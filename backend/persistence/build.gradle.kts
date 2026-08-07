plugins {
    `java-library`
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports { mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.5") }
}

dependencies {
    // 소비 모듈(api-public/scheduler 등)이 엔티티·리포지토리 타입을 쓰므로 api로 노출.
    api(project(":domain-core"))
    api("org.springframework.boot:spring-boot-starter-data-jpa")

    // 구현 세부(마이그레이션·드라이버·벡터)는 전파 불필요.
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation("com.pgvector:pgvector:0.1.6")
}
